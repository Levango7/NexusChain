package org.nexus.signing.mpc.crypto;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *     use-plaintext: true       # 开发环境；生产环境应设为 false 启用 mTLS
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>gRPC {@code ManagedChannel} 与 blocking stub 均线程安全，
 * 本类可在多线程下并发调用。{@code deadline} 通过
 * {@code stub.withDeadlineAfter(...)} 在每次调用上独立应用。</p>
 *
 * <h2>异常处理</h2>
 * <p>传输层失败（{@link StatusRuntimeException}）时返回 {@code success=false}
 * 的响应对象，编排层据此做熔断 / 重试。仅在 channel 未初始化时抛出
 * {@link IllegalStateException}。</p>
 *
 * <h2>生命周期</h2>
 * <p>由 Spring 容器管理：{@link PostConstruct} 建立 channel，
 * {@link PreDestroy} 优雅关闭。</p>
 *
 * @see MpcCryptoEngine
 */
@Component
public class GrpcMpcCryptoEngine implements MpcCryptoEngine {

    private static final Logger log = LoggerFactory.getLogger(GrpcMpcCryptoEngine.class);

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
     * <p>生产环境应设为 {@code false} 并配置 mTLS（设计文档 §7.1 R10）。</p>
     */
    @Value("${mpc.engine.use-plaintext:true}")
    private boolean usePlaintext;

    /** gRPC channel，由 {@link PostConstruct} 初始化。 */
    private ManagedChannel channel;

    /** gRPC blocking stub，由 {@link PostConstruct} 初始化。 */
    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub blockingStub;

    /**
     * 初始化 gRPC channel 与 stub。
     *
     * <p>由 Spring 容器在属性注入后调用。失败时记录错误日志但不抛异常，
     * 允许 signing-service 在引擎不可达时仍能启动（healthCheck 会返回 false）。</p>
     */
    @PostConstruct
    public void init() {
        try {
            ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                    .forAddress(host, port)
                    .enableRetry()
                    .maxRetryAttempts(3);
            if (usePlaintext) {
                builder.usePlaintext();
                log.warn("GrpcMpcCryptoEngine: using PLAINTEXT gRPC to {}:{} — "
                        + "NOT for production; set mpc.engine.use-plaintext=false "
                        + "and configure mTLS (design §7.1 R10)", host, port);
            }
            // 生产环境：builder.useTransportSecurity() + SslContext with mTLS
            this.channel = builder.build();
            this.blockingStub = MpcCryptoServiceGrpc.newBlockingStub(channel);
            log.info("GrpcMpcCryptoEngine initialized: {}:{}, deadline={}ms, plaintext={}",
                    host, port, deadlineTimeoutMillis, usePlaintext);
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
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStub();
        try {
            MpcCryptoProto.DkgRequest proto = toProto(request);
            MpcCryptoProto.DkgResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .dkg(proto);
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("DKG gRPC call failed: session={}, status={}",
                    request.getSessionId(), e.getStatus(), e);
            return new DkgResponse(null, null, null, false,
                    "gRPC DKG failed: " + e.getStatus());
        }
    }

    @Override
    public SignResponse sign(SignRequest request) {
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStub();
        try {
            MpcCryptoProto.SignRequest proto = toProto(request);
            MpcCryptoProto.SignResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .sign(proto);
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("Sign gRPC call failed: session={}, status={}",
                    request.getSessionId(), e.getStatus(), e);
            return new SignResponse(null, null, false,
                    "gRPC Sign failed: " + e.getStatus());
        }
    }

    @Override
    public AggregateResponse aggregate(AggregateRequest request) {
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub = requireStub();
        try {
            MpcCryptoProto.AggregateRequest proto = toProto(request);
            MpcCryptoProto.AggregateResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .aggregate(proto);
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("Aggregate gRPC call failed: session={}, status={}",
                    request.getSessionId(), e.getStatus(), e);
            return new AggregateResponse(null, null, null, 0, false,
                    "gRPC Aggregate failed: " + e.getStatus());
        }
    }

    @Override
    public boolean healthCheck() {
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

    // === protobuf → DTO 转换 ===

    private static DkgResponse fromProto(MpcCryptoProto.DkgResponse proto) {
        return new DkgResponse(
                proto.getPublicKey(),
                proto.getKeyShare(),
                proto.getProof(),
                proto.getSuccess(),
                proto.getError());
    }

    private static SignResponse fromProto(MpcCryptoProto.SignResponse proto) {
        return new SignResponse(
                proto.getPartialSignature(),
                proto.getProof(),
                proto.getSuccess(),
                proto.getError());
    }

    private static AggregateResponse fromProto(MpcCryptoProto.AggregateResponse proto) {
        return new AggregateResponse(
                proto.getSignature(),
                proto.getR(),
                proto.getS(),
                proto.getRecoveryId(),
                proto.getSuccess(),
                proto.getError());
    }

    /**
     * 获取已初始化的 blocking stub，未初始化时抛异常。
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
}
