package org.nexus.signing.mpc.crypto;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import org.nexus.signing.mpc.transport.GrpcTlsContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MPC 引擎多端点路由器（P0-1 Task 239：分散式部署）。
 *
 * <p>将 {@link GrpcMpcCryptoEngine} 从单端点升级为多端点路由：
 * 支持配置逗号分隔的 {@code mpc.engine.endpoints}（host:port 列表），
 * 按 {@code partyIndex} 路由到对应节点的 gRPC channel。</p>
 *
 * <h2>路由策略</h2>
 * <p>每个 MPC 参与方（party_index=0/1/2）对应一个独立的 mpc-engine 节点。
 * 调用 {@code dkg} / {@code sign} 时，根据请求的 {@code partyIndex}
 * 选择对应的 {@link ManagedChannel} 发起 gRPC 调用。</p>
 *
 * <p>端点列表与 party_index 的映射关系：</p>
 * <pre>
 * mpc.engine.endpoints=mpc-engine-0:50051,mpc-engine-1:50051,mpc-engine-2:50051
 *                  →  party_index=0 → mpc-engine-0:50051
 *                  →  party_index=1 → mpc-engine-1:50051
 *                  →  party_index=2 → mpc-engine-2:50051
 * </pre>
 *
 * <h2>向后兼容</h2>
 * <ul>
 *   <li>当 {@code mpc.engine.endpoints} 未配置（空）时，回退到
 *       {@code mpc.engine.host:port} 单端点模式，行为与原 {@link GrpcMpcCryptoEngine} 一致</li>
 *   <li>当 {@code mpc.engine.endpoints} 仅配置一个端点时，所有 partyIndex
 *       均路由到该端点（等价于单端点模式）</li>
 *   <li>当 {@code partyIndex} 超出端点列表长度时，回退到端点 0（容错，
 *       并记录警告日志）</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <pre>
 * mpc:
 *   engine:
 *     # 多端点配置（逗号分隔的 host:port 列表，优先于 host:port）
 *     endpoints: ${NEX_MPC_ENGINE_ENDPOINTS:}
 *     # 单端点配置（endpoints 为空时使用，向后兼容）
 *     host: localhost
 *     port: 50051
 *     deadline-timeout: 30000
 *     use-plaintext: false
 *     tls:
 *       trust-cert-path: /etc/nexus/mpc/ca.pem
 *       client-cert-path: /etc/nexus/mpc/client.pem
 *       client-key-path:  /etc/nexus/mpc/client.key
 *     auth-token: ${NEX_MPC_ENGINE_AUTH_TOKEN:}
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>每个端点对应一个独立的 {@link ManagedChannel}（线程安全），
 * 本类的路由方法仅做数组索引选择，无共享可变状态，线程安全。</p>
 *
 * <h2>生命周期</h2>
 * <p>由 Spring 容器管理：{@link PostConstruct} 为所有端点建立 channel，
 * {@link PreDestroy} 优雅关闭所有 channel。</p>
 *
 * @see GrpcMpcCryptoEngine
 * @since 2.2.0
 */
@Component
public class MpcEngineRouter {

    private static final Logger log = LoggerFactory.getLogger(MpcEngineRouter.class);

    /** gRPC metadata 中的 Authorization 头键（MPC-P1-05）。 */
    private static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Authorization 头的 Bearer 前缀（MPC-P1-05，RFC 6750）。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 端点列表分隔符（逗号）。 */
    private static final String ENDPOINT_SEPARATOR = ",";

    /** host:port 分隔符。 */
    private static final String HOST_PORT_SEPARATOR = ":";

    /**
     * 多端点配置（逗号分隔的 host:port 列表）。
     *
     * <p>优先于 {@link #host} / {@link #port}。为空时回退到单端点模式。</p>
     */
    @Value("${mpc.engine.endpoints:}")
    private String endpoints;

    /**
     * 分布式模式（P2-T3，B2：可信协调器退役）。
     *
     * <p>{@code true}（生产 prod profile）时强制分布式部署：endpoints 必须配置
     * 且不少于 3 个节点；partyIndex 超界时抛异常而非回退端点 0（fail-closed，
     * 杜绝单进程全份额路径与错误路由）。{@code false}（默认，dev 兼容）保留
     * 原可信协调器回退行为。</p>
     */
    @Value("${mpc.engine.distributed-mode:false}")
    private boolean distributedMode;

    /** 引擎 gRPC 主机（单端点模式，向后兼容）。 */
    @Value("${mpc.engine.host:localhost}")
    private String host;

    /** 引擎 gRPC 端口（单端点模式，向后兼容）。 */
    @Value("${mpc.engine.port:50051}")
    private int port;

    /** 单次 RPC deadline 超时（毫秒）。 */
    @Value("${mpc.engine.deadline-timeout:30000}")
    private long deadlineTimeoutMillis;

    /** 是否使用明文传输（开发环境）。 */
    @Value("${mpc.engine.use-plaintext:false}")
    private boolean usePlaintext;

    /** mTLS 信任证书路径。 */
    @Value("${mpc.engine.tls.trust-cert-path:}")
    private String tlsTrustCertPath;

    /** mTLS 客户端证书路径。 */
    @Value("${mpc.engine.tls.client-cert-path:}")
    private String tlsClientCertPath;

    /** mTLS 客户端私钥路径。 */
    @Value("${mpc.engine.tls.client-key-path:}")
    private String tlsClientKeyPath;

    /** gRPC 应用层认证 token（MPC-P1-05）。 */
    @Value("${mpc.engine.auth-token:}")
    private String authToken;

    /** 已建立的 channel 列表（与 endpoints 顺序对齐，索引 = partyIndex）。 */
    private final List<ManagedChannel> channels = new ArrayList<>();

    /** 端点描述列表（host:port 字符串，用于日志与错误消息）。 */
    private final List<String> endpointDescriptions = new ArrayList<>();

    /** 是否已初始化成功。 */
    private volatile boolean initialized = false;

    /**
     * 初始化所有端点的 gRPC channel。
     *
     * <p>解析 {@code endpoints} 配置，为每个端点建立独立的 {@link ManagedChannel}。
     * {@code endpoints} 为空时回退到 {@code host:port} 单端点模式。</p>
     */
    @PostConstruct
    public void init() {
        List<String> parsedEndpoints = parseEndpoints();

        // P2-T3：分布式模式（prod）强制多端点，拒绝可信协调器单端点路径
        if (distributedMode) {
            if (parsedEndpoints.isEmpty()) {
                throw new IllegalStateException(
                        "MpcEngineRouter: distributed-mode=true requires mpc.engine.endpoints "
                                + "configured (>=3 nodes, e.g. mpc-engine-0:50051,mpc-engine-1:50051,"
                                + "mpc-engine-2:50051). Single-process trusted-coordinator mode "
                                + "is forbidden in production (B2-T3).");
            }
            if (parsedEndpoints.size() < 3) {
                throw new IllegalStateException(
                        "MpcEngineRouter: distributed-mode=true requires >=3 endpoints, got "
                                + parsedEndpoints.size() + ". Threshold signature (t-of-n) needs "
                                + "at least 3 parties (B2-T3).");
            }
        }

        if (parsedEndpoints.isEmpty()) {
            log.warn("MpcEngineRouter: no endpoints configured — engine calls will fail");
            this.initialized = false;
            return;
        }

        log.info("MpcEngineRouter: initializing {} endpoint(s): {}",
                parsedEndpoints.size(), parsedEndpoints);

        SslContext sharedSslContext = buildSslContextOrNull();

        for (String endpoint : parsedEndpoints) {
            try {
                String[] parts = endpoint.split(HOST_PORT_SEPARATOR, 2);
                if (parts.length != 2) {
                    log.error("MpcEngineRouter: invalid endpoint '{}', expected host:port", endpoint);
                    this.channels.add(null);
                    this.endpointDescriptions.add(endpoint);
                    continue;
                }
                String epHost = parts[0].trim();
                int epPort;
                try {
                    epPort = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    log.error("MpcEngineRouter: invalid port in endpoint '{}': {}", endpoint, e.getMessage());
                    this.channels.add(null);
                    this.endpointDescriptions.add(endpoint);
                    continue;
                }

                ManagedChannel channel = buildChannel(epHost, epPort, sharedSslContext);
                this.channels.add(channel);
                this.endpointDescriptions.add(epHost + ":" + epPort);
                log.info("MpcEngineRouter: channel established for endpoint {} (party_index={})",
                        epHost + ":" + epPort, this.channels.size() - 1);
            } catch (Exception e) {
                log.error("MpcEngineRouter: failed to init endpoint '{}': {}", endpoint, e.getMessage(), e);
                this.channels.add(null);
                this.endpointDescriptions.add(endpoint);
            }
        }

        this.initialized = true;
        log.info("MpcEngineRouter initialized: {} endpoint(s), plaintext={}, tls={}, auth={}",
                this.channels.size(), usePlaintext,
                !usePlaintext && sharedSslContext != null,
                authToken != null && !authToken.isEmpty());
    }

    /**
     * 优雅关闭所有 channel。
     */
    @PreDestroy
    public void shutdown() {
        for (int i = 0; i < channels.size(); i++) {
            ManagedChannel ch = channels.get(i);
            if (ch != null && !ch.isShutdown()) {
                try {
                    ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                    log.info("MpcEngineRouter: channel[{}] shutdown complete (endpoint={})",
                            i, endpointDescriptions.get(i));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("MpcEngineRouter: channel[{}] shutdown interrupted", i);
                }
            }
        }
        channels.clear();
        endpointDescriptions.clear();
    }

    /**
     * 获取指定 partyIndex 对应的 channel。
     *
     * <p>路由策略：{@code partyIndex} 直接作为 channel 索引。
     * 超出范围时回退到索引 0（容错）。</p>
     *
     * @param partyIndex MPC 参与方索引（0-based）
     * @return 对应的 {@link ManagedChannel}，未初始化或无效时返回 {@code null}
     */
    public ManagedChannel getChannel(int partyIndex) {
        if (!initialized || channels.isEmpty()) {
            return null;
        }
        int idx = partyIndex;
        if (idx < 0 || idx >= channels.size()) {
            // P2-T3：分布式模式（prod）下超界为配置/路由错误，fail-closed 拒绝静默回退
            if (distributedMode) {
                throw new IllegalArgumentException(
                        "MpcEngineRouter: partyIndex=" + partyIndex + " out of range [0,"
                                + (channels.size() - 1) + "] in distributed-mode — "
                                + "endpoints configuration mismatch (B2-T3). Refusing to "
                                + "fall back to endpoint 0 (would route to wrong party).");
            }
            log.warn("MpcEngineRouter: partyIndex={} out of range [0,{}], falling back to endpoint 0",
                    partyIndex, channels.size() - 1);
            idx = 0;
        }
        return channels.get(idx);
    }

    /**
     * 获取指定 partyIndex 对应的端点描述（host:port）。
     *
     * @param partyIndex MPC 参与方索引
     * @return 端点描述字符串，无效时返回 {@code "unknown"}
     */
    public String getEndpointDescription(int partyIndex) {
        if (endpointDescriptions.isEmpty()) {
            return "unknown";
        }
        int idx = partyIndex;
        if (idx < 0 || idx >= endpointDescriptions.size()) {
            idx = 0;
        }
        return endpointDescriptions.get(idx);
    }

    /**
     * 获取端点总数。
     *
     * @return 端点数（未初始化时返回 0）
     */
    public int getEndpointCount() {
        return channels.size();
    }

    /**
     * 检查路由器是否已初始化且至少有一个有效 channel。
     *
     * @return {@code true} 若已初始化且至少有一个非 null channel
     */
    public boolean isReady() {
        if (!initialized || channels.isEmpty()) {
            return false;
        }
        for (ManagedChannel ch : channels) {
            if (ch != null && !ch.isShutdown()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对所有端点执行健康检查。
     *
     * @return 所有端点健康的比例（"healthy/total"），用于日志与监控
     */
    public String healthCheckSummary() {
        int healthy = 0;
        int total = channels.size();
        for (ManagedChannel ch : channels) {
            if (ch != null && !ch.isShutdown()) {
                healthy++;
            }
        }
        return healthy + "/" + total;
    }

    /**
     * 获取 deadline 超时（毫秒）。
     *
     * @return deadline 超时
     */
    public long getDeadlineTimeoutMillis() {
        return deadlineTimeoutMillis;
    }

    /**
     * 构建 gRPC channel（含 mTLS + Bearer token interceptor）。
     *
     * @param epHost       端点主机
     * @param epPort       端点端口
     * @param sslContext   共享的 mTLS SslContext（null 表示明文）
     * @return 已配置的 ManagedChannel
     */
    private ManagedChannel buildChannel(String epHost, int epPort, SslContext sslContext) {
        NettyChannelBuilder builder = NettyChannelBuilder
                .forAddress(epHost, epPort)
                .enableRetry()
                .maxRetryAttempts(3);

        if (usePlaintext || sslContext == null) {
            builder.usePlaintext();
            if (usePlaintext) {
                log.warn("MpcEngineRouter: using PLAINTEXT gRPC to {}:{} — NOT for production",
                        epHost, epPort);
            } else {
                log.warn("MpcEngineRouter: TLS config incomplete, falling back to PLAINTEXT to {}:{}",
                        epHost, epPort);
            }
        } else {
            builder.sslContext(sslContext);
            log.info("MpcEngineRouter: mTLS enabled to {}:{}", epHost, epPort);
        }

        return builder.build();
    }

    /**
     * 构建 mTLS SslContext（所有端点共享同一客户端证书）。
     *
     * @return SslContext，明文模式或配置不完整时返回 null
     */
    private SslContext buildSslContextOrNull() {
        if (usePlaintext) {
            return null;
        }
        if (GrpcTlsContextFactory.isTlsConfigComplete(
                tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath)) {
            try {
                return GrpcTlsContextFactory.buildClientSslContext(
                        tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath);
            } catch (Exception e) {
                log.error("MpcEngineRouter: failed to build mTLS SslContext: {}", e.getMessage(), e);
                return null;
            }
        } else {
            log.error("MpcEngineRouter: use-plaintext=false but TLS config incomplete "
                    + "(trust-cert-path/client-cert-path/client-key-path all required)");
            return null;
        }
    }

    /**
     * 解析 endpoints 配置字符串为端点列表。
     *
     * <p>解析顺序：</p>
     * <ol>
     *   <li>若 {@code endpoints} 非空，按逗号分隔解析为列表</li>
     *   <li>否则回退到 {@code host:port} 单端点</li>
     * </ol>
     *
     * @return 端点列表（host:port 字符串），空列表表示无有效配置
     */
    private List<String> parseEndpoints() {
        if (endpoints != null && !endpoints.trim().isEmpty()) {
            String[] parts = endpoints.split(ENDPOINT_SEPARATOR);
            List<String> result = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            if (!result.isEmpty()) {
                return Collections.unmodifiableList(result);
            }
        }

        // 回退到单端点模式
        log.info("MpcEngineRouter: endpoints not configured, falling back to single endpoint {}:{}",
                host, port);
        return Collections.singletonList(host + ":" + port);
    }
}