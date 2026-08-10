package org.nexus.signing.mpc.transport;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import org.nexus.signing.mpc.transport.grpc.MpcTransportProto;
import org.nexus.signing.mpc.transport.grpc.MpcTransportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MPC P2P 传输层 gRPC server 端实现（P5-T3）。
 *
 * <p>每个 signing-service 实例启动一个 {@code MpcTransportGrpcServer}，接收其他
 * Java 参与方通过 gRPC 发来的 {@link MpcMessage}，将其投递到本地
 * {@link GrpcMpcTransportStub} 的邮箱，供 {@link GrpcMpcTransportStub#receive} 取出。</p>
 *
 * <h2>架构</h2>
 * <pre>
 *   Participant A                                Participant B (本节点)
 *   ┌──────────────────┐                         ┌──────────────────────────────┐
 *   │ GrpcMpcTransport │──gRPC SendMpcMessage──► │ MpcTransportGrpcServer       │
 *   │ Stub.send(msg)   │                         │  └─ SendMpcMessage(msg)      │
 *   └──────────────────┘                         │     └─ stub.deliverLocal(msg)│
 *                                                │        └─ localMailbox.offer  │
 *                                                │ GrpcMpcTransportStub         │
 *                                                │  └─ receive() ◄─ localMailbox │
 *                                                └──────────────────────────────┘
 * </pre>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link #start()}：绑定端口并启动 gRPC server（非阻塞，后台线程处理请求）。</li>
 *   <li>{@link #stop()}：优雅关闭，等待最多 5 秒让在途 RPC 完成。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>gRPC {@link Server} 线程安全，可在多线程下并发调用。
 * {@link GrpcMpcTransportStub#deliverLocal} 内部线程安全。</p>
 *
 * <h2>配置</h2>
 * <p>绑定端口从构造参数传入，通常对应 {@code mpc.transport.local-port} 配置。
 * 生产环境应配置 mTLS（设计文档 §7.1 R10），开发环境使用明文。</p>
 *
 * <h2>mTLS 行为（MPC-P0-02 修复）</h2>
 * <ul>
 *   <li>{@code usePlaintext=true}：使用明文 gRPC（仅开发环境，记录警告）</li>
 *   <li>{@code usePlaintext=false} + TLS 配置完整：使用 mTLS（服务端证书 + 强制客户端认证）</li>
 *   <li>{@code usePlaintext=false} + TLS 配置不完整：记录错误并回退到明文（容错启动）</li>
 * </ul>
 *
 * <h2>应用层 token 认证（MPC-P1-03 修复）</h2>
 * <p>构造时传入 {@code authToken}，非空时通过 {@link AuthTokenServerInterceptor}
 * 校验每个 RPC 请求的 {@code Authorization: Bearer <token>} metadata。校验失败
 * 返回 {@code UNAUTHENTICATED} 状态，拒绝请求。token 为空时跳过校验
 * （开发模式，记录 WARN）。</p>
 *
 * @see GrpcMpcTransportStub
 * @see MpcTransportServiceGrpc
 * @see AuthTokenServerInterceptor
 */
public class MpcTransportGrpcServer {

    private static final Logger log = LoggerFactory.getLogger(MpcTransportGrpcServer.class);

    /**
     * gRPC metadata 中的 Authorization 头键（MPC-P1-03）。
     *
     * <p>与 {@link GrpcMpcTransportStub#AUTHORIZATION_METADATA_KEY} 保持一致，
     * 客户端发送、服务端校验使用相同的 key。</p>
     */
    static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Authorization 头的 Bearer 前缀（MPC-P1-03）。
     *
     * <p>与 {@link GrpcMpcTransportStub#BEARER_PREFIX} 保持一致。</p>
     */
    static final String BEARER_PREFIX = "Bearer ";

    /** 本地 transport stub，用于投递收到的消息。 */
    private final GrpcMpcTransportStub transportStub;

    /** gRPC server 实例。 */
    private Server server;

    /** 绑定端口。 */
    private final int port;

    /** 是否使用明文传输（开发环境）。 */
    private final boolean usePlaintext;

    /** mTLS 信任证书路径（PEM，验证客户端证书）。 */
    private final String tlsTrustCertPath;

    /** mTLS 服务端证书路径（PEM）。 */
    private final String tlsServerCertPath;

    /** mTLS 服务端私钥路径（PEM，未加密）。 */
    private final String tlsServerKeyPath;

    /**
     * 应用层认证 token（MPC-P1-03）。
     *
     * <p>非空时，server 通过 {@link AuthTokenServerInterceptor} 校验每个请求的
     * {@code Authorization: Bearer <token>} 头。为空时跳过校验（开发模式）。</p>
     */
    private final String authToken;

    /**
     * 构造 gRPC server（含 mTLS 配置，MPC-P0-02 修复）。
     *
     * @param transportStub     本地 transport stub，收到的消息将投递到它的邮箱
     * @param port              绑定端口（&gt; 0）
     * @param usePlaintext      是否使用明文传输（开发环境）
     * @param tlsTrustCertPath  mTLS 信任证书路径（PEM，验证客户端证书），可为 null
     * @param tlsServerCertPath mTLS 服务端证书路径（PEM），可为 null
     * @param tlsServerKeyPath  mTLS 服务端私钥路径（PEM），可为 null
     */
    public MpcTransportGrpcServer(GrpcMpcTransportStub transportStub,
                                  int port,
                                  boolean usePlaintext,
                                  String tlsTrustCertPath,
                                  String tlsServerCertPath,
                                  String tlsServerKeyPath) {
        this(transportStub, port, usePlaintext, tlsTrustCertPath,
                tlsServerCertPath, tlsServerKeyPath, null);
    }

    /**
     * 构造 gRPC server（含 mTLS + 应用层 token 认证，MPC-P0-02 + MPC-P1-03 修复）。
     *
     * <p>MPC-P1-03 修复：添加 {@code authToken} 参数，非空时通过
     * {@link AuthTokenServerInterceptor} 校验每个 RPC 请求的
     * {@code Authorization: Bearer <token>} 头。token 为空且
     * {@code usePlaintext=false} 时记录 WARN 日志。</p>
     *
     * @param transportStub     本地 transport stub，收到的消息将投递到它的邮箱
     * @param port              绑定端口（&gt; 0）
     * @param usePlaintext      是否使用明文传输（开发环境）
     * @param tlsTrustCertPath  mTLS 信任证书路径（PEM，验证客户端证书），可为 null
     * @param tlsServerCertPath mTLS 服务端证书路径（PEM），可为 null
     * @param tlsServerKeyPath  mTLS 服务端私钥路径（PEM），可为 null
     * @param authToken         应用层认证 token（MPC-P1-03），可为 null/空（开发模式）
     */
    public MpcTransportGrpcServer(GrpcMpcTransportStub transportStub,
                                  int port,
                                  boolean usePlaintext,
                                  String tlsTrustCertPath,
                                  String tlsServerCertPath,
                                  String tlsServerKeyPath,
                                  String authToken) {
        this.transportStub = Objects.requireNonNull(transportStub, "transportStub");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        this.port = port;
        this.usePlaintext = usePlaintext;
        this.tlsTrustCertPath = tlsTrustCertPath;
        this.tlsServerCertPath = tlsServerCertPath;
        this.tlsServerKeyPath = tlsServerKeyPath;
        this.authToken = authToken;
        // MPC-P1-03: token 为空且 usePlaintext=false 时记录 WARN
        if (!usePlaintext && (authToken == null || authToken.isEmpty())) {
            log.warn("MpcTransportGrpcServer: authToken is empty but usePlaintext=false "
                    + "(MPC-P1-03: production should set mpc.transport.auth-token)");
        }
    }

    /**
     * 构造 gRPC server（向后兼容，无 mTLS）。
     *
     * <p>保留此构造函数以兼容现有测试代码。新代码应使用含 TLS 参数的构造函数。</p>
     *
     * @param transportStub 本地 transport stub，收到的消息将投递到它的邮箱
     * @param port          绑定端口（&gt; 0）
     * @param usePlaintext  是否使用明文传输（开发环境）
     */
    public MpcTransportGrpcServer(GrpcMpcTransportStub transportStub,
                                  int port,
                                  boolean usePlaintext) {
        this(transportStub, port, usePlaintext, null, null, null);
    }

    /**
     * 启动 gRPC server（非阻塞）。
     *
     * <p>绑定到 {@code 0.0.0.0:port}，后台线程处理请求。
     * 调用后立即返回，server 在后台运行直到 {@link #stop} 被调用。</p>
     *
     * <p>MPC-P0-02 修复：当 {@code usePlaintext=false} 且 TLS 配置完整时，
     * 使用 {@link NettyServerBuilder#sslContext(SslContext)} 配置服务端 mTLS
     * （服务端证书 + 强制客户端认证 {@code ClientAuth.REQUIRE}）。
     * 若 TLS 配置不完整，记录错误并回退到明文（容错启动）。</p>
     *
     * <p>MPC-P1-03 修复：当 {@code authToken} 非空时，添加
     * {@link AuthTokenServerInterceptor} 校验每个请求的 Authorization 头。</p>
     *
     * @throws IOException 若端口绑定失败（端口被占用等）
     * @throws IllegalStateException 若 server 已启动
     */
    public void start() throws IOException {
        if (server != null && !server.isShutdown()) {
            throw new IllegalStateException("server already started on port " + port);
        }

        NettyServerBuilder builder = NettyServerBuilder.forPort(port)
                .addService(new MpcTransportServiceImpl(transportStub));

        // MPC-P1-03: 若 authToken 非空，添加 token 校验 interceptor
        if (authToken != null && !authToken.isEmpty()) {
            builder.intercept(new AuthTokenServerInterceptor(authToken));
            log.info("MpcTransportGrpcServer: auth token verification enabled on port {} "
                    + "(MPC-P1-03)", port);
        }

        boolean tlsEnabled = false;
        if (!usePlaintext) {
            // MPC-P0-02: usePlaintext=false → 尝试配置服务端 mTLS
            if (GrpcTlsContextFactory.isTlsConfigComplete(
                    tlsTrustCertPath, tlsServerCertPath, tlsServerKeyPath)) {
                SslContext sslContext = GrpcTlsContextFactory.buildServerSslContext(
                        tlsTrustCertPath, tlsServerCertPath, tlsServerKeyPath);
                builder.sslContext(sslContext);
                tlsEnabled = true;
                log.info("MpcTransportGrpcServer: mTLS enabled on port {} "
                        + "(trustCert={}, serverCert={})",
                        port, tlsTrustCertPath, tlsServerCertPath);
            } else {
                log.error("MpcTransportGrpcServer: use-plaintext=false but TLS config "
                        + "incomplete (trust-cert-path/client-cert-path/client-key-path "
                        + "all required) — falling back to PLAINTEXT on port {} "
                        + "(MPC-P0-02: this is a security risk, fix TLS config)", port);
            }
        } else {
            log.warn("MpcTransportGrpcServer: PLAINTEXT on port {} (dev mode, MPC-P0-02)", port);
        }

        server = builder.build().start();

        log.info("MpcTransportGrpcServer started on port {} (plaintext={}, tls={}, authToken={})",
                port, usePlaintext, tlsEnabled,
                authToken != null && !authToken.isEmpty() ? "enabled" : "disabled");
    }

    /**
     * 优雅关闭 gRPC server。
     *
     * <p>等待最多 5 秒让在途 RPC 完成。</p>
     */
    public void stop() {
        if (server == null || server.isShutdown()) {
            return;
        }
        try {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("MpcTransportGrpcServer stopped on port {}", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MpcTransportGrpcServer stop interrupted on port {}", port);
        }
    }

    /**
     * @return server 是否正在运行
     */
    public boolean isRunning() {
        return server != null && !server.isShutdown() && !server.isTerminated();
    }

    /**
     * @return 绑定端口
     */
    public int getPort() {
        return port;
    }

    // =========================================================================
    // gRPC service 实现
    // =========================================================================

    /**
     * {@link MpcTransportServiceGrpc.MpcTransportService} 的实现。
     *
     * <p>收到 {@code SendMpcMessage} RPC 后，将 protobuf 消息转换为 {@link MpcMessage}，
     * 调用 {@link GrpcMpcTransportStub#deliverLocal} 投递到本地邮箱。</p>
     */
    private static final class MpcTransportServiceImpl
            extends MpcTransportServiceGrpc.MpcTransportServiceImplBase {

        private final GrpcMpcTransportStub transportStub;

        MpcTransportServiceImpl(GrpcMpcTransportStub transportStub) {
            this.transportStub = transportStub;
        }

        @Override
        public void sendMpcMessage(MpcTransportProto.MpcMessageProto request,
                                   StreamObserver<MpcTransportProto.Ack> responseObserver) {
            try {
                MpcMessage msg = GrpcMpcTransportStub.fromProto(request);
                boolean delivered = transportStub.deliverLocal(msg);
                MpcTransportProto.Ack ack = MpcTransportProto.Ack.newBuilder()
                        .setSuccess(true)
                        .setError(delivered ? "" : "duplicate message ignored")
                        .build();
                responseObserver.onNext(ack);
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("SendMpcMessage failed: {}", e.getMessage(), e);
                MpcTransportProto.Ack ack = MpcTransportProto.Ack.newBuilder()
                        .setSuccess(false)
                        .setError(e.getMessage() == null ? "unknown error" : e.getMessage())
                        .build();
                responseObserver.onNext(ack);
                responseObserver.onCompleted();
            }
        }

        @Override
        public void healthCheck(MpcTransportProto.HealthCheckRequest request,
                                StreamObserver<MpcTransportProto.HealthCheckResponse> responseObserver) {
            MpcTransportProto.HealthCheckResponse resp =
                    MpcTransportProto.HealthCheckResponse.newBuilder()
                            .setHealthy(true)
                            .setStatus("MpcTransportGrpcServer running")
                            .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        }
    }

    // =========================================================================
    // 应用层 token 认证 interceptor（MPC-P1-03）
    // =========================================================================

    /**
     * 校验 gRPC 请求 {@code Authorization: Bearer <token>} 头的 server interceptor
     * （MPC-P1-03 修复）。
     *
     * <p>对每个 RPC 请求：</p>
     * <ul>
     *   <li>从 gRPC metadata 读取 {@code Authorization} 头</li>
     *   <li>校验格式为 {@code Bearer <expectedToken>}，且 token 与期望值匹配</li>
     *   <li>校验通过：放行到下一个 handler</li>
     *   <li>校验失败：返回 {@code UNAUTHENTICATED} 状态，拒绝请求</li>
     * </ul>
     *
     * <p>线程安全：interceptor 无状态，可被多线程并发调用。</p>
     *
     * <h2>安全注意事项</h2>
     * <ul>
     *   <li>token 比较使用 {@link String#equals} 而非常量时间比较。
     *       由于 token 是Bearer token（非密码），且失败会立即拒绝连接，
     *       时序攻击收益有限。生产环境应配合 mTLS 使用。</li>
     *   <li>token 不记录到日志（仅记录校验成功/失败，不记录 token 值）。</li>
     * </ul>
     */
    static final class AuthTokenServerInterceptor implements ServerInterceptor {

        private static final Logger log = LoggerFactory.getLogger(AuthTokenServerInterceptor.class);

        /** 期望的 Bearer token 值（不含 "Bearer " 前缀）。 */
        private final String expectedToken;

        /**
         * 构造 token 校验 interceptor。
         *
         * @param expectedToken 期望的 token 值，非空
         */
        AuthTokenServerInterceptor(String expectedToken) {
            this.expectedToken = Objects.requireNonNull(expectedToken, "expectedToken");
            if (expectedToken.isEmpty()) {
                throw new IllegalArgumentException("expectedToken must not be empty");
            }
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            // 从 metadata 读取 Authorization 头
            String authHeader = headers.get(AUTHORIZATION_METADATA_KEY);

            if (authHeader == null || authHeader.isEmpty()) {
                // 缺少 Authorization 头
                log.warn("MPC-P1-03: gRPC request rejected — missing Authorization header "
                        + "(method={}, authority={})",
                        call.getMethodDescriptor().getFullMethodName(),
                        call.getAuthority());
                call.close(Status.UNAUTHENTICATED
                        .withDescription("Missing Authorization header"), new Metadata());
                return new ServerCall.Listener<ReqT>() {};
            }

            // 校验 Bearer 前缀
            if (!authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("MPC-P1-03: gRPC request rejected — Authorization header "
                        + "not Bearer format (method={})",
                        call.getMethodDescriptor().getFullMethodName());
                call.close(Status.UNAUTHENTICATED
                        .withDescription("Authorization header must be Bearer format"), new Metadata());
                return new ServerCall.Listener<ReqT>() {};
            }

            // 提取并校验 token
            String providedToken = authHeader.substring(BEARER_PREFIX.length());
            if (!providedToken.equals(expectedToken)) {
                // token 不匹配（不记录实际 token 值，仅记录失败）
                log.warn("MPC-P1-03: gRPC request rejected — auth token mismatch "
                        + "(method={})", call.getMethodDescriptor().getFullMethodName());
                call.close(Status.UNAUTHENTICATED
                        .withDescription("Invalid auth token"), new Metadata());
                return new ServerCall.Listener<ReqT>() {};
            }

            // 校验通过，放行到下一个 handler
            log.debug("MPC-P1-03: gRPC request authorized (method={})",
                    call.getMethodDescriptor().getFullMethodName());
            return next.startCall(call, headers);
        }
    }
}
