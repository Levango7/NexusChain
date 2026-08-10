package org.nexus.signing.mpc.transport;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MPC 传输层 Spring 配置（P5-T3）。
 *
 * <p>根据 {@code mpc.transport.real-grpc-enabled} 配置选择传输实现：</p>
 * <ul>
 *   <li>{@code false}（默认，开发/测试模式）：使用 {@link InMemoryMpcTransport}，
 *       所有参与者位于同一 JVM，不经过网络层。</li>
 *   <li>{@code true}（生产模式）：使用 {@link GrpcMpcTransportStub}（真实 gRPC），
 *       并启动 {@link MpcTransportGrpcServer} 接收其他参与方的消息。</li>
 * </ul>
 *
 * <h2>配置项</h2>
 * <pre>
 * mpc:
 *   transport:
 *     real-grpc-enabled: false      # 默认 false，生产环境设为 true
 *     deadline-timeout: 10000       # gRPC 调用超时（毫秒）
 *     use-plaintext: false          # MPC-P0-02: 默认 false（安全）；开发环境设为 true
 *     local-port: 50052             # 本节点 gRPC server 端口（接收其他参与方消息）
 *     auth-token:                   # MPC-P1-03: 应用层认证 token（可选，生产环境必填）
 *       # 非空时：客户端在 gRPC metadata 添加 Authorization: Bearer <token>，
 *       #         服务端校验该头，校验失败返回 UNAUTHENTICATED
 *       # 为空且 use-plaintext=false：记录 WARN（生产环境应配置）
 *       # 为空且 use-plaintext=true：跳过校验（开发模式）
 *     tls:                          # mTLS 配置（use-plaintext=false 时生效）
 *       trust-cert-path: /etc/nexus/mpc/ca.pem
 *       client-cert-path: /etc/nexus/mpc/client.pem
 *       client-key-path:  /etc/nexus/mpc/client.key
 * </pre>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link #initTransport}（{@link PostConstruct}）：若 real-grpc-enabled=true，
 *       启动 {@link MpcTransportGrpcServer}。</li>
 *   <li>{@link #destroyTransport}（{@link PreDestroy}）：关闭 gRPC server。</li>
 * </ul>
 *
 * @see GrpcMpcTransportStub
 * @see InMemoryMpcTransport
 * @see MpcTransportGrpcServer
 */
@Configuration
public class MpcTransportConfig {

    private static final Logger log = LoggerFactory.getLogger(MpcTransportConfig.class);

    /** 是否启用真实 gRPC 传输。 */
    @Value("${mpc.transport.real-grpc-enabled:false}")
    private boolean realGrpcEnabled;

    /** gRPC 调用超时（毫秒）。 */
    @Value("${mpc.transport.deadline-timeout:10000}")
    private long deadlineTimeoutMillis;

    /** 是否使用明文传输（开发环境）。MPC-P0-02: 默认改为 false。 */
    @Value("${mpc.transport.use-plaintext:false}")
    private boolean usePlaintext;

    /** 本节点 gRPC server 端口（接收其他参与方消息）。 */
    @Value("${mpc.transport.local-port:50052}")
    private int localPort;

    /** mTLS 信任证书路径（PEM）。use-plaintext=false 时生效。 */
    @Value("${mpc.transport.tls.trust-cert-path:}")
    private String tlsTrustCertPath;

    /** mTLS 客户端证书路径（PEM）。use-plaintext=false 时生效。 */
    @Value("${mpc.transport.tls.client-cert-path:}")
    private String tlsClientCertPath;

    /** mTLS 客户端私钥路径（PEM，未加密）。use-plaintext=false 时生效。 */
    @Value("${mpc.transport.tls.client-key-path:}")
    private String tlsClientKeyPath;

    /**
     * 应用层认证 token（MPC-P1-03）。
     *
     * <p>可选配置，生产环境（{@code use-plaintext=false}）必填。
     * 非空时：客户端在 gRPC metadata 添加 {@code Authorization: Bearer <token>}，
     * 服务端校验该头。为空且 {@code use-plaintext=false} 时记录 WARN。</p>
     */
    @Value("${mpc.transport.auth-token:}")
    private String authToken;

    /** gRPC server 实例（real-grpc-enabled=true 时创建）。 */
    private MpcTransportGrpcServer grpcServer;

    /**
     * 创建 {@link MpcTransport} Bean。
     *
     * <p>根据 {@code real-grpc-enabled} 配置返回 {@link GrpcMpcTransportStub}
     * 或 {@link InMemoryMpcTransport}。</p>
     *
     * <p>MPC-P1-03 修复：{@code real-grpc-enabled=true} 时将 {@link #authToken}
     * 传入 {@link GrpcMpcTransportStub}，用于在 gRPC metadata 中添加
     * {@code Authorization: Bearer <token>} 头。</p>
     *
     * @return 传输层实现
     */
    @Bean
    public MpcTransport mpcTransport() {
        if (realGrpcEnabled) {
            log.info("Creating GrpcMpcTransportStub (real gRPC mode): deadline={}ms, plaintext={}, tls={}, authToken={}",
                    deadlineTimeoutMillis, usePlaintext,
                    !usePlaintext && GrpcTlsContextFactory.isTlsConfigComplete(
                            tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath),
                    authToken != null && !authToken.isEmpty() ? "(set)" : "(empty)");
            return new GrpcMpcTransportStub(true, deadlineTimeoutMillis, usePlaintext,
                    tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath, authToken);
        }
        log.info("Creating InMemoryMpcTransport (development mode)");
        return new InMemoryMpcTransport();
    }

    /**
     * 初始化传输层：若 real-grpc-enabled=true，启动 gRPC server。
     *
     * <p>MPC-P1-03 修复：将 {@link #authToken} 传入 {@link MpcTransportGrpcServer}，
     * 非空时 server 会校验每个请求的 {@code Authorization: Bearer <token>} 头。</p>
     *
     * @throws Exception 若 gRPC server 启动失败（端口绑定失败等）
     */
    @PostConstruct
    public void initTransport() throws Exception {
        if (!realGrpcEnabled) {
            return;
        }
        MpcTransport transport = mpcTransport();
        if (!(transport instanceof GrpcMpcTransportStub)) {
            return;
        }
        GrpcMpcTransportStub stub = (GrpcMpcTransportStub) transport;
        try {
            // MPC-P0-02: 服务端 mTLS 使用 client-cert/client-key 作为服务端证书
            // （P2P 场景下每个节点既是客户端也是服务端，证书复用）
            // MPC-P1-03: 传入 authToken 用于校验 Authorization 头
            grpcServer = new MpcTransportGrpcServer(stub, localPort, usePlaintext,
                    tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath, authToken);
            grpcServer.start();
            log.info("MpcTransport gRPC server started on port {} (authToken={})",
                    localPort, authToken != null && !authToken.isEmpty() ? "enabled" : "disabled");
        } catch (Exception e) {
            log.error("Failed to start MpcTransport gRPC server on port {}: {}",
                    localPort, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 销毁传输层：关闭 gRPC server。
     */
    @PreDestroy
    public void destroyTransport() {
        if (grpcServer != null) {
            grpcServer.stop();
            log.info("MpcTransport gRPC server stopped");
        }
    }
}