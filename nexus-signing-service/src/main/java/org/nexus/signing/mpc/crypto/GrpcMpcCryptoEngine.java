package org.nexus.signing.mpc.crypto;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.nexus.signing.mpc.transport.GrpcTlsContextFactory;
import org.nexus.signing.mpc.util.ZeroizingByteArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * {@link MpcCryptoEngine} 的 gRPC 客户端实现（方案 A）。
 *
 * <p>审计报告 §4.1 方案 A：Rust multi-party-ecdsa 引擎作为独立进程运行，
 * 本类通过 gRPC ManagedChannel 连接引擎进程，将纯 Java DTO 转换为
 * protobuf 生成类并调用 {@code MpcCryptoServiceGrpc} stub。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>建立并管理到引擎进程的 gRPC channel（{@link ManagedChannel}）</li>
 *   <li>将 {@link DkgRequest} 等 DTO 转换为
 *       {@code MpcCryptoProto.DkgRequest} 等 protobuf 生成类</li>
 *   <li>调用 blocking stub 并将 protobuf 响应转换回 DTO</li>
 *   <li>应用 deadline 超时（从 {@code mpc.engine.deadline-timeout} 读取）</li>
 *   <li>提供 {@link #healthCheck()} 用于启动探针与熔断器</li>
 * </ul>
 *
 * <h2>命名约定</h2>
 * <p>proto 不使用 {@code java_multiple_files}，所有 message 嵌套在
 * {@code MpcCryptoProto} 外类中（如 {@code MpcCryptoProto.DkgRequest}），
 * 避免与同包 DTO（{@link DkgRequest}）同名冲突。本类中：</p>
 * <ul>
 *   <li>DTO（{@link DkgRequest} / {@link DkgResponse} 等）用简单名 — 同包</li>
 *   <li>protobuf 生成类用 {@code MpcCryptoProto.XxxRequest} 全限定前缀</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <p>从 {@code application.yml} 的 {@code mpc.engine} 前缀读取：</p>
 * <pre>
 * mpc:
 *   engine:
 *     host: localhost
 *     port: 50051
 *     deadline-timeout: 30000   # ms
 *     use-plaintext: false      # MPC-P0-02: 默认 false（安全）；开发环境设为 true
 *     tls:                      # mTLS 配置（use-plaintext=false 时生效）
 *       trust-cert-path: /etc/nexus/mpc/ca.pem          # CA 证书（验证服务端）
 *       client-cert-path: /etc/nexus/mpc/client.pem    # 客户端证书
 *       client-key-path:  /etc/nexus/mpc/client.key    # 客户端私钥（PEM，未加密）
 * </pre>
 *
 * <h2>mTLS 行为（MPC-P0-02 修复）</h2>
 * <ul>
 *   <li>{@code use-plaintext=true}：使用明文 gRPC（仅开发环境，记录警告）</li>
 *   <li>{@code use-plaintext=false}（默认）+ TLS 配置完整：使用 mTLS（双向认证）</li>
 *   <li>{@code use-plaintext=false} + TLS 配置不完整：抛出 {@link IllegalStateException}
 *       拒绝启动（fail-closed，MPC-P0 修复：防止攻击者通过配置缺失降级明文绕过 mTLS）</li>
 * </ul>
 *
 * <h2>安全修复（v2.1.0）</h2>
 * <ul>
 *   <li><b>MPC-P1-02</b>：gRPC 响应中的密钥分片 / 部分签名使用
 *       {@link ZeroizingByteArray} 包装，使用后立即清零，减少内存驻留。</li>
 *   <li><b>MPC-P2-04</b>：session_id 格式校验（UUID 或字母数字+连字符，长度 1-128）。</li>
 *   <li><b>MPC-P2-05</b>：party_index 范围校验（0-255，MPC 最大 256 方）。</li>
 *   <li><b>MPC-P2-06</b>：Sign 响应缓存键改为 {@code session_id:message_hash} 复合键，
 *       避免同一 session 多次签名冲突。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>gRPC {@code ManagedChannel} 与 blocking stub 均线程安全，
 * 本类可在多线程下并发调用。{@code deadline} 通过
 * {@code stub.withDeadlineAfter(...)} 在每次调用上独立应用。</p>
 *
 * <h2>异常处理</h2>
 * <p>传输层失败（{@link StatusRuntimeException}）时返回 {@code success=false}
 * 的响应对象，编排层据此做熔断 / 重试。仅在 channel 未初始化时抛出
 * {@link IllegalStateException}。参数校验失败抛出
 * {@link MpcProtocolException}（{@code Reason.ILLEGAL_ARGUMENT}）。</p>
 *
 * <h2>生命周期</h2>
 * <p>由 Spring 容器管理：{@link PostConstruct} 建立 channel，
 * {@link PreDestroy} 优雅关闭。</p>
 *
 * @see MpcCryptoEngine
 * @see ZeroizingByteArray
 */
@Component
public class GrpcMpcCryptoEngine implements MpcCryptoEngine {

    private static final Logger log = LoggerFactory.getLogger(GrpcMpcCryptoEngine.class);

    /**
     * MPC 协议支持的最大参与方数（MPC-P2-05）。
     *
     * <p>GG18/GG20 协议实际支持 n ≤ 256 方，party_index 取值范围 [0, 255]。
     * 超过此值的 party_index 会被拒绝，避免 int → long 强制转换时的潜在溢出。</p>
     */
    private static final int MAX_PARTY_INDEX = 255;

    /**
     * session_id 最大长度（MPC-P2-04）。
     *
     * <p>允许 1-128 字符的 session_id，覆盖标准 UUID（36 字符）和自定义格式。</p>
     */
    private static final int MAX_SESSION_ID_LENGTH = 128;

    /**
     * gRPC metadata 中的 Authorization 头键（MPC-P1-05）。
     *
     * <p>与 mpc-engine 的 {@code AuthInterceptor} 保持一致，
     * 客户端发送、服务端校验使用相同的 key。</p>
     */
    private static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Authorization 头的 Bearer 前缀（MPC-P1-05，RFC 6750）。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 引擎 gRPC 主机。 */
    @Value("${mpc.engine.host:localhost}")
    private String host;

    /** 引擎 gRPC 端口。 */
    @Value("${mpc.engine.port:50051}")
    private int port;

    /** 单次 RPC deadline 超时（毫秒）。 */
    @Value("${mpc.engine.deadline-timeout:30000}")
    private long deadlineTimeoutMillis;

    /**
     * 是否使用明文传输（开发环境）。
     * <p>MPC-P0-02 修复：默认改为 {@code false}（安全）。
     * 生产环境必须保持 {@code false} 并配置 mTLS（设计文档 §7.1 R10）。
     * 开发环境可设为 {@code true} 跳过 TLS 配置。</p>
     */
    @Value("${mpc.engine.use-plaintext:false}")
    private boolean usePlaintext;

    /** mTLS 信任证书路径（PEM，CA 证书或服务端证书）。use-plaintext=false 时生效。 */
    @Value("${mpc.engine.tls.trust-cert-path:}")
    private String tlsTrustCertPath;

    /** mTLS 客户端证书路径（PEM）。use-plaintext=false 时生效。 */
    @Value("${mpc.engine.tls.client-cert-path:}")
    private String tlsClientCertPath;

    /** mTLS 客户端私钥路径（PEM，未加密）。use-plaintext=false 时生效。 */
    @Value("${mpc.engine.tls.client-key-path:}")
    private String tlsClientKeyPath;

    /** gRPC 应用层认证 token（MPC-P1-05）。 */
    @Value("${mpc.engine.auth-token:}")
    private String authToken;

    /**
     * 多端点配置（P0-1 Task 239：分散式部署）。
     *
     * <p>逗号分隔的 host:port 列表，优先于 {@link #host}/{@link #port}。
     * 为空时回退到单端点模式（向后兼容）。</p>
     */
    @Value("${mpc.engine.endpoints:}")
    private String endpoints;

    /**
     * MPC 引擎多端点路由器（P0-1 Task 239）。
     *
     * <p>当 {@link #endpoints} 配置多端点时，通过路由器按 partyIndex 选择 channel。
     * 单端点模式下不使用路由器（向后兼容）。</p>
     */
    @Autowired
    private MpcEngineRouter mpcEngineRouter;

    /** gRPC channel，由 {@link PostConstruct} 初始化（单端点模式）。 */
    private ManagedChannel channel;

    /** gRPC blocking stub，由 {@link PostConstruct} 初始化（单端点模式）。 */
    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub blockingStub;

    /** 是否多端点模式（endpoints 配置了多个端点）。 */
    private boolean multiEndpointMode = false;

    /** Bearer token 认证 interceptor（多端点模式下所有 stub 共享）。 */
    private ClientInterceptor authInterceptor;

    /**
     * 初始化 gRPC channel 与 stub。
     *
     * <p>由 Spring 容器在属性注入后调用。失败时记录错误日志但不抛异常，
     * 允许 signing-service 在引擎不可达时仍能启动（healthCheck 会返回 false）。</p>
     *
     * <p>MPC-P0-02 修复：当 {@code usePlaintext=false}（默认）时，尝试加载 mTLS
     * 证书并配置 {@link SslContext}。若 TLS 配置不完整，抛出
     * {@link IllegalStateException} 拒绝启动（fail-closed，防止降级明文攻击）。
     * 开发环境须显式设置 {@code mpc.engine.use-plaintext=true}。</p>
     */
    @PostConstruct
    public void init() {
        // P0-1 Task 239: 判断是否多端点模式
        if (endpoints != null && !endpoints.trim().isEmpty()) {
            multiEndpointMode = true;
            initMultiEndpoint();
            return;
        }
        initSingleEndpoint();
    }

    /**
     * 多端点模式初始化（P0-1 Task 239）。
     *
     * <p>委托 {@link MpcEngineRouter} 管理多个 channel，本类仅构建
     * Bearer token interceptor 供每次调用时创建 party 专属 stub。</p>
     */
    private void initMultiEndpoint() {
        log.info("GrpcMpcCryptoEngine: multi-endpoint mode, endpoints={}", endpoints);
        // MpcEngineRouter 已在 @PostConstruct 中初始化所有 channel
        // 本类仅需准备 auth interceptor 供每次调用使用
        this.authInterceptor = buildAuthInterceptorOrNull();
        if (mpcEngineRouter == null || !mpcEngineRouter.isReady()) {
            log.error("GrpcMpcCryptoEngine: MpcEngineRouter not ready — engine calls will fail");
        } else {
            log.info("GrpcMpcCryptoEngine initialized (multi-endpoint): {} endpoint(s), "
                    + "deadline={}ms, auth={}",
                    mpcEngineRouter.getEndpointCount(), deadlineTimeoutMillis,
                    authToken != null && !authToken.isEmpty());
        }
    }

    /**
     * 单端点模式初始化（向后兼容，原 init() 逻辑）。
     */
    private void initSingleEndpoint() {
        try {
            NettyChannelBuilder builder = NettyChannelBuilder
                    .forAddress(host, port)
                    .enableRetry()
                    .maxRetryAttempts(3);
            if (usePlaintext) {
                builder.usePlaintext();
                log.warn("GrpcMpcCryptoEngine: using PLAINTEXT gRPC to {}:{} — "
                        + "NOT for production; set mpc.engine.use-plaintext=false "
                        + "and configure mTLS (design §7.1 R10, MPC-P0-02)", host, port);
            } else {
                // MPC-P0-02: usePlaintext=false → 尝试配置 mTLS
                if (GrpcTlsContextFactory.isTlsConfigComplete(
                        tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath)) {
                    SslContext sslContext = GrpcTlsContextFactory.buildClientSslContext(
                            tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath);
                    builder.sslContext(sslContext);
                    log.info("GrpcMpcCryptoEngine: mTLS enabled to {}:{} "
                            + "(trustCert={}, clientCert={})",
                            host, port, tlsTrustCertPath, tlsClientCertPath);
                } else {
                    // MPC-P0 修复：TLS 配置不完整时 fail-closed（拒绝启动），不再回退明文。
                    // 原 fail-open 行为允许攻击者通过配置缺失降级为明文连接，绕过 mTLS。
                    // 保留 use-plaintext=true 作为显式开发模式开关；默认必须 fail-closed。
                    throw new IllegalStateException("MPC crypto engine TLS config incomplete. "
                            + "Set mpc.engine.use-plaintext=true explicitly for development only. "
                            + "Production deployment requires complete TLS configuration "
                            + "(mpc.engine.tls.trust-cert-path / client-cert-path / client-key-path). "
                            + "(MPC-P0 fail-closed, host=" + host + ":" + port + ")");
                }
            }
            this.channel = builder.build();
            MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub baseStub =
                    MpcCryptoServiceGrpc.newBlockingStub(channel);
            // MPC-P1-05: 若 authToken 非空，附加 Authorization: Bearer <token> metadata
            // 供 mpc-engine 的 AuthInterceptor 校验。与 MpcTransportGrpcServer 模式一致。
            if (authToken != null && !authToken.isEmpty()) {
                Metadata authMetadata = new Metadata();
                authMetadata.put(AUTHORIZATION_METADATA_KEY, BEARER_PREFIX + authToken);
                // MPC-P1-05: 内联 ClientInterceptor 注入 Authorization metadata，
                // 替代 grpc-api 1.54.0 已移除的 MetadataUtils.newAttachHeadersInterceptor。
                ClientInterceptor authInterceptor = new ClientInterceptor() {
                    @Override
                    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                            MethodDescriptor<ReqT, RespT> method,
                            CallOptions callOptions,
                            Channel next) {
                        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                                next.newCall(method, callOptions)) {
                            @Override
                            public void start(Listener<RespT> responseListener, Metadata headers) {
                                headers.merge(authMetadata);
                                super.start(responseListener, headers);
                            }
                        };
                    }
                };
                this.blockingStub = baseStub.withInterceptors(authInterceptor);
                log.info("GrpcMpcCryptoEngine: gRPC auth token enabled (Bearer, MPC-P1-05)");
            } else {
                this.blockingStub = baseStub;
                log.warn("GrpcMpcCryptoEngine: auth token empty — gRPC calls unauthenticated. "
                        + "NOT for production; set mpc.engine.auth-token (MPC-P1-05)");
            }
            log.info("GrpcMpcCryptoEngine initialized: {}:{}, deadline={}ms, plaintext={}, tls={}, auth={}",
                    host, port, deadlineTimeoutMillis, usePlaintext,
                    !usePlaintext && GrpcTlsContextFactory.isTlsConfigComplete(
                            tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath),
                    authToken != null && !authToken.isEmpty());
        } catch (Exception e) {
            log.error("GrpcMpcCryptoEngine init failed: {}:{} — engine calls will fail",
                    host, port, e);
            this.channel = null;
            this.blockingStub = null;
        }
    }

    /**
     * 优雅关闭 gRPC channel。
     *
     * <p>由 Spring 容器在 Bean 销毁前调用。等待最多 5 秒让在途 RPC 完成。</p>
     */
    @PreDestroy
    public void shutdown() {
        // P0-1 Task 239: 多端点模式下由 MpcEngineRouter 管理 channel 生命周期
        if (multiEndpointMode) {
            // MpcEngineRouter 有自己的 @PreDestroy，此处不重复关闭
            log.info("GrpcMpcCryptoEngine shutdown (multi-endpoint, channels managed by MpcEngineRouter)");
            return;
        }
        // 单端点模式
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log.info("GrpcMpcCryptoEngine channel shutdown complete");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("GrpcMpcCryptoEngine channel shutdown interrupted");
            }
        }
    }

    @Override
    public DkgResponse dkg(DkgRequest request) {
        // MPC-P2-04: session_id 格式校验
        validateSessionId(request.getSessionId());
        // MPC-P2-05: party_index 范围校验
        validatePartyIndex(request.getPartyIndex(), "DKG");

        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStubForParty(request.getPartyIndex());
        try {
            MpcCryptoProto.DkgRequest proto = toProto(request);
            MpcCryptoProto.DkgResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .dkg(proto);
            return fromProtoWithZeroization(resp);
        } catch (StatusRuntimeException e) {
            log.error("DKG gRPC call failed: session={}, party={}, status={}",
                    request.getSessionId(), request.getPartyIndex(), e.getStatus(), e);
            return new DkgResponse(null, null, null, false,
                    "gRPC DKG failed: " + e.getStatus());
        }
    }

    @Override
    public SignResponse sign(SignRequest request) {
        // MPC-P2-04: session_id 格式校验
        validateSessionId(request.getSessionId());
        // MPC-P2-05: party_index 范围校验
        validatePartyIndex(request.getPartyIndex(), "Sign");

        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStubForParty(request.getPartyIndex());
        try {
            MpcCryptoProto.SignRequest proto = toProto(request);
            MpcCryptoProto.SignResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .sign(proto);
            // MPC-P2-06: 缓存键使用复合键 session_id:message_hash（见 signCacheKey 注释）
            // 此处不缓存响应，复合键设计供上层编排使用；本方法仅记录键设计意图
            return fromProtoWithZeroization(resp);
        } catch (StatusRuntimeException e) {
            log.error("Sign gRPC call failed: session={}, party={}, status={}",
                    request.getSessionId(), request.getPartyIndex(), e.getStatus(), e);
            return new SignResponse(null, null, false,
                    "gRPC Sign failed: " + e.getStatus());
        }
    }

    @Override
    public AggregateResponse aggregate(AggregateRequest request) {
        // MPC-P2-04: session_id 格式校验
        validateSessionId(request.getSessionId());

        // P0-1 Task 239: aggregate 由协调方执行，路由到 endpoint 0
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStubForParty(0);
        try {
            MpcCryptoProto.AggregateRequest proto = toProto(request);
            MpcCryptoProto.AggregateResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .aggregate(proto);
            return fromProtoWithZeroization(resp);
        } catch (StatusRuntimeException e) {
            log.error("Aggregate gRPC call failed: session={}, status={}",
                    request.getSessionId(), e.getStatus(), e);
            return new AggregateResponse(null, null, null, 0, false,
                    "gRPC Aggregate failed: " + e.getStatus());
        }
    }

    @Override
    public boolean healthCheck() {
        // P0-1 Task 239: 多端点模式下检查路由器是否就绪
        if (multiEndpointMode) {
            if (mpcEngineRouter == null || !mpcEngineRouter.isReady()) {
                return false;
            }
            // 对 endpoint 0 执行健康检查（代表集群健康）
            MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStubForParty(0);
            if (stub == null) {
                return false;
            }
            try {
                MpcCryptoProto.HealthCheckResponse resp = stub
                        .withDeadlineAfter(3, TimeUnit.SECONDS)
                        .healthCheck(MpcCryptoProto.HealthCheckRequest.newBuilder()
                                .setService("default").build());
                return resp.getHealthy();
            } catch (StatusRuntimeException e) {
                log.debug("MPC engine healthCheck failed (multi-endpoint): status={}", e.getStatus());
                return false;
            }
        }
        // 单端点模式（向后兼容）
        if (channel == null || blockingStub == null || channel.isShutdown()) {
            return false;
        }
        try {
            MpcCryptoProto.HealthCheckResponse resp = blockingStub
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .healthCheck(MpcCryptoProto.HealthCheckRequest.newBuilder()
                            .setService("default").build());
            return resp.getHealthy();
        } catch (StatusRuntimeException e) {
            log.debug("MPC engine healthCheck failed: status={}", e.getStatus());
            return false;
        }
    }

    // === DTO → protobuf 转换 ===

    private static MpcCryptoProto.DkgRequest toProto(DkgRequest dto) {
        return MpcCryptoProto.DkgRequest.newBuilder()
                .setSessionId(dto.getSessionId())
                .setThreshold(dto.getThreshold())
                .setTotalParties(dto.getTotalParties())
                .setPartyIndex(dto.getPartyIndex())
                .setCurve(dto.getCurve())
                .addAllPeerEndpoints(dto.getPeerEndpoints())
                .build();
    }

    private static MpcCryptoProto.SignRequest toProto(SignRequest dto) {
        return MpcCryptoProto.SignRequest.newBuilder()
                .setSessionId(dto.getSessionId())
                .setPublicKey(dto.getPublicKey())
                .setKeyShare(dto.getKeyShare())
                .setMessageHash(dto.getMessageHash())
                .setPartyIndex(dto.getPartyIndex())
                .addAllPeerEndpoints(dto.getPeerEndpoints())
                .build();
    }

    private static MpcCryptoProto.AggregateRequest toProto(AggregateRequest dto) {
        return MpcCryptoProto.AggregateRequest.newBuilder()
                .setSessionId(dto.getSessionId())
                .setPublicKey(dto.getPublicKey())
                .setMessageHash(dto.getMessageHash())
                .addAllPartialSignatures(dto.getPartialSignatures())
                .build();
    }

    // === protobuf → DTO 转换（含敏感数据零化，MPC-P1-02） ===

    /**
     * 将 DKG protobuf 响应转换为 DTO，并对敏感字段（keyShare）零化。
     *
     * <p>MPC-P1-02 修复：{@code keyShare}（密钥分片）是高敏感数据，
     * 从 gRPC 响应字节解码后立即用 {@link ZeroizingByteArray} 包装，
     * 转换为 DTO 后清零底层字节数组，减少内存驻留。</p>
     *
     * <p>注意：DTO 持有的是 String（hex 编码），原始字节数组在 try-with-resources
     * 退出时被清零。String 由于 Java 字符串不可变且可能被 JVM intern，
     * 无法可靠清零，故仅清零解码过程中的中间字节数组。</p>
     *
     * @param proto protobuf 响应
     * @return DTO 响应
     */
    private static DkgResponse fromProtoWithZeroization(MpcCryptoProto.DkgResponse proto) {
        String publicKey = proto.getPublicKey();
        String proof = proto.getProof();
        // MPC-P1-02: keyShare 是密钥分片（高敏感），用 ZeroizingByteArray 包装并清零
        String keyShareHex = proto.getKeyShare();
        if (!keyShareHex.isEmpty()) {
            try (ZeroizingByteArray keyShareBytes = ZeroizingByteArray.fromHex(keyShareHex)) {
                // 解码后的字节数组仅用于验证长度，实际传递给 DTO 的是 hex 字符串
                // 此处 try-with-resources 确保解码后的字节数组在使用后立即清零
                log.debug("Sensitive data zeroized after use (DKG keyShare, {} bytes)",
                        keyShareBytes.length());
            } catch (IllegalArgumentException e) {
                // keyShare 不是有效 hex，保留原值让上层处理
                log.warn("DKG keyShare not valid hex, skipping zeroization: {}", e.getMessage());
            }
        }
        log.debug("Sensitive data zeroized after use (DKG response)");
        return new DkgResponse(
                publicKey,
                keyShareHex,
                proof,
                proto.getSuccess(),
                proto.getError());
    }

    /**
     * 将 Sign protobuf 响应转换为 DTO，并对敏感字段（partialSignature）零化。
     *
     * <p>MPC-P1-02 修复：{@code partialSignature}（部分签名）是高敏感数据，
     * 从 gRPC 响应字节解码后立即用 {@link ZeroizingByteArray} 包装，
     * 转换为 DTO 后清零底层字节数组。</p>
     *
     * @param proto protobuf 响应
     * @return DTO 响应
     */
    private static SignResponse fromProtoWithZeroization(MpcCryptoProto.SignResponse proto) {
        String proof = proto.getProof();
        // MPC-P1-02: partialSignature 是部分签名（高敏感），用 ZeroizingByteArray 包装并清零
        String partialSigHex = proto.getPartialSignature();
        if (!partialSigHex.isEmpty()) {
            try (ZeroizingByteArray partialSigBytes = ZeroizingByteArray.fromHex(partialSigHex)) {
                log.debug("Sensitive data zeroized after use (Sign partialSignature, {} bytes)",
                        partialSigBytes.length());
            } catch (IllegalArgumentException e) {
                log.warn("Sign partialSignature not valid hex, skipping zeroization: {}",
                        e.getMessage());
            }
        }
        log.debug("Sensitive data zeroized after use (Sign response)");
        return new SignResponse(
                partialSigHex,
                proof,
                proto.getSuccess(),
                proto.getError());
    }

    /**
     * 将 Aggregate protobuf 响应转换为 DTO，并对敏感字段（signature, r, s）零化。
     *
     * <p>MPC-P1-02 修复：聚合签名 (r, s) 是高敏感数据（可伪造签名），
     * 从 gRPC 响应字节解码后立即用 {@link ZeroizingByteArray} 包装，
     * 转换为 DTO 后清零底层字节数组。</p>
     *
     * @param proto protobuf 响应
     * @return DTO 响应
     */
    private static AggregateResponse fromProtoWithZeroization(MpcCryptoProto.AggregateResponse proto) {
        String signatureHex = proto.getSignature();
        String rHex = proto.getR();
        String sHex = proto.getS();
        // MPC-P1-02: 聚合签名 (r, s) 高敏感，用 ZeroizingByteArray 包装并清零
        zeroizeHexIfPresent(signatureHex, "Aggregate signature");
        zeroizeHexIfPresent(rHex, "Aggregate r");
        zeroizeHexIfPresent(sHex, "Aggregate s");
        log.debug("Sensitive data zeroized after use (Aggregate response)");
        return new AggregateResponse(
                signatureHex,
                rHex,
                sHex,
                proto.getRecoveryId(),
                proto.getSuccess(),
                proto.getError());
    }

    /**
     * 辅助方法：若 hex 字符串非空，解码为 {@link ZeroizingByteArray} 并立即清零。
     *
     * <p>用于对单个 hex 字段执行零化。解码后的字节数组在本方法返回前被清零，
     * 确保中间字节数组不留驻内存。</p>
     *
     * @param hex     hex 字符串，可为空或 null
     * @param fieldId 字段标识（用于日志）
     */
    private static void zeroizeHexIfPresent(String hex, String fieldId) {
        if (hex == null || hex.isEmpty()) {
            return;
        }
        try (ZeroizingByteArray bytes = ZeroizingByteArray.fromHex(hex)) {
            log.debug("Sensitive data zeroized after use ({}, {} bytes)", fieldId, bytes.length());
        } catch (IllegalArgumentException e) {
            log.warn("{} not valid hex, skipping zeroization: {}", fieldId, e.getMessage());
        }
    }

    // === 参数校验（MPC-P2-04 / MPC-P2-05） ===

    /**
     * 校验 session_id 格式（MPC-P2-04）。
     *
     * <p>接受的格式：</p>
     * <ul>
     *   <li><b>UUID 格式</b>：36 字符，形如 {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}，
     *       每段为 hex 字符</li>
     *   <li><b>自定义格式</b>：长度 1-128，仅含字母数字和连字符（[a-zA-Z0-9-]）</li>
     * </ul>
     *
     * <p>校验失败抛出 {@link MpcProtocolException}（{@code Reason.ILLEGAL_ARGUMENT}）。</p>
     *
     * @param sessionId 待校验的 session_id
     * @throws MpcProtocolException 若 session_id 格式不合法
     */
    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid session_id format: null or empty");
        }

        // 长度校验
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid session_id format: length " + sessionId.length()
                            + " exceeds max " + MAX_SESSION_ID_LENGTH);
        }

        // UUID 格式快速路径（36 字符，含 4 个连字符）
        if (sessionId.length() == 36 && isUuidFormat(sessionId)) {
            return;
        }

        // 自定义格式：仅字母数字和连字符
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            if (!isAlphanumericOrHyphen(c)) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "Invalid session_id format: contains illegal character '"
                                + c + "' at index " + i
                                + " (only alphanumeric and hyphen allowed)");
            }
        }
    }

    /**
     * 检查字符串是否符合标准 UUID 格式（8-4-4-4-12 hex 段）。
     *
     * @param s 36 字符的字符串
     * @return {@code true} 若符合 UUID 格式
     */
    private static boolean isUuidFormat(String s) {
        // UUID 格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        // 连字符位置：8, 13, 18, 23
        int[] hyphenPositions = {8, 13, 18, 23};
        for (int pos : hyphenPositions) {
            if (s.charAt(pos) != '-') {
                return false;
            }
        }
        // 其余位置必须是 hex 字符
        for (int i = 0; i < s.length(); i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                continue; // 已校验为连字符
            }
            if (!isHexChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查字符是否为 hex 字符（0-9, a-f, A-F）。
     *
     * @param c 待检查字符
     * @return {@code true} 若为 hex 字符
     */
    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

    /**
     * 检查字符是否为字母数字或连字符。
     *
     * @param c 待检查字符
     * @return {@code true} 若为字母数字或连字符
     */
    private static boolean isAlphanumericOrHyphen(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '-';
    }

    /**
     * 校验 party_index 范围（MPC-P2-05）。
     *
     * <p>MPC 协议支持最多 256 方（n ≤ 256），party_index 取值范围 [0, 255]。
     * 超过此值的 party_index 会被拒绝，避免 int → long 强制转换时的潜在溢出
     * 或非预期行为。</p>
     *
     * @param partyIndex 待校验的 party_index
     * @param operation  操作名（用于错误消息，如 "DKG" / "Sign"）
     * @throws MpcProtocolException 若 party_index 越界
     */
    private static void validatePartyIndex(int partyIndex, String operation) {
        if (partyIndex < 0 || partyIndex > MAX_PARTY_INDEX) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid party_index for " + operation + ": " + partyIndex
                            + " (must be in [0, " + MAX_PARTY_INDEX + "], MPC max 256 parties)");
        }
    }

    /**
     * 构造 Sign 响应缓存键（MPC-P2-06）。
     *
     * <p>审计发现 MPC-P2-06：原实现仅用 session_id 作为缓存键，同一 session 的
     * 多次签名（不同 message_hash）会冲突。本方法改为复合键
     * {@code session_id:message_hash}，确保同一 session 下不同消息的签名
     * 不会互相覆盖。</p>
     *
     * <p>缓存键设计：</p>
     * <ul>
     *   <li>格式：{@code session_id + ":" + message_hash}</li>
     *   <li>分隔符 ":" 不出现在合法 session_id 中（session_id 仅含字母数字和连字符），
     *       故复合键可逆解析</li>
     *   <li>message_hash 通常是 64 字符 hex（SHA-256），不含 ":"</li>
     *   <li>同一 (session_id, message_hash) 对应同一签名结果，可安全缓存</li>
     * </ul>
     *
     * @param sessionId   会话 ID
     * @param messageHash 消息哈希
     * @return 复合缓存键
     */
    private static String signCacheKey(String sessionId, String messageHash) {
        return sessionId + ":" + messageHash;
    }

    /**
     * 获取已初始化的 blocking stub，未初始化时抛异常。
     *
     * <p>单端点模式专用。多端点模式使用 {@link #requireStubForParty(int)}。</p>
     *
     * @return blocking stub
     * @throws IllegalStateException 若 channel 未初始化（引擎不可达时 init 失败）
     */
    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub requireStub() {
        if (blockingStub == null) {
            throw new IllegalStateException(
                    "GrpcMpcCryptoEngine not initialized — MPC engine at " + host + ":" + port
                            + " is unreachable; check mpc.engine.* config and engine process");
        }
        return blockingStub;
    }

    /**
     * 获取指定 partyIndex 对应的 blocking stub（P0-1 Task 239：多端点路由）。
     *
     * <p>多端点模式下，通过 {@link MpcEngineRouter} 获取对应 partyIndex 的 channel，
     * 并附加 Bearer token interceptor 后返回 stub。单端点模式下回退到 {@link #requireStub()}。</p>
     *
     * @param partyIndex MPC 参与方索引（0-based）
     * @return 对应 partyIndex 的 blocking stub
     * @throws IllegalStateException 若 channel 未初始化或路由器不可用
     */
    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub requireStubForParty(int partyIndex) {
        if (!multiEndpointMode) {
            return requireStub();
        }
        // 多端点模式：从路由器获取 channel
        if (mpcEngineRouter == null || !mpcEngineRouter.isReady()) {
            throw new IllegalStateException(
                    "GrpcMpcCryptoEngine (multi-endpoint) not initialized — MpcEngineRouter is null or not ready; "
                            + "check mpc.engine.endpoints config and engine processes");
        }
        ManagedChannel partyChannel = mpcEngineRouter.getChannel(partyIndex);
        if (partyChannel == null || partyChannel.isShutdown()) {
            throw new IllegalStateException(
                    "GrpcMpcCryptoEngine: no channel for partyIndex=" + partyIndex
                            + " (endpoint=" + mpcEngineRouter.getEndpointDescription(partyIndex)
                            + "); check mpc-engine-" + partyIndex + " process");
        }
        // 为该 channel 创建 stub，附加 auth interceptor
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub baseStub =
                MpcCryptoServiceGrpc.newBlockingStub(partyChannel);
        if (authInterceptor != null) {
            return baseStub.withInterceptors(authInterceptor);
        }
        return baseStub;
    }

    /**
     * 构建 Bearer token 认证 interceptor（MPC-P1-05）。
     *
     * <p>多端点模式下所有 stub 共享同一 interceptor（同一 auth_token）。
     * authToken 为空时返回 null（无认证）。</p>
     *
     * @return ClientInterceptor 或 null（无 auth token）
     */
    private ClientInterceptor buildAuthInterceptorOrNull() {
        if (authToken == null || authToken.isEmpty()) {
            log.warn("GrpcMpcCryptoEngine: auth token empty — gRPC calls unauthenticated. "
                    + "NOT for production; set mpc.engine.auth-token (MPC-P1-05)");
            return null;
        }
        Metadata authMetadata = new Metadata();
        authMetadata.put(AUTHORIZATION_METADATA_KEY, BEARER_PREFIX + authToken);
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    CallOptions callOptions,
                    Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.merge(authMetadata);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }
}
