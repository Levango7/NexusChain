package org.nexus.signing.mpc.crypto;

/**
 * MPC 密码学引擎 SPI 接口。
 *
 * <p>解耦 Java 编排层（{@code org.nexus.signing.mpc.*}：MpcSigner /
 * MpcSignatureAggregator / MpcKeyGeneration 等）与底层密码学实现。</p>
 *
 * <p>审计报告 §4.1 方案 A：Rust multi-party-ecdsa 引擎作为独立进程运行，
 * signing-service 通过 gRPC 调用。本接口的参考实现为
 * {@link GrpcMpcCryptoEngine}（gRPC stub 客户端）。</p>
 *
 * <p>未来可注入其他实现（如 in-process Java 引擎、mock 测试引擎），
 * 编排层无需修改。SPI 风格：编排层仅依赖本接口，实现通过 Spring
 * {@code @Component} 注入或 {@link java.util.ServiceLoader} 发现。</p>
 *
 * <h2>方法语义</h2>
 * <ul>
 *   <li>{@link #dkg} — 分布式密钥生成（GG18/GG20 第 1 阶段），产出聚合公钥 + 本节点密钥份额</li>
 *   <li>{@link #sign} — 部分签名（每个参与方本地执行签名轮次），产出部分签名 s_i</li>
 *   <li>{@link #aggregate} — 聚合 t 个部分签名为最终 ECDSA 签名 (r, s)</li>
 *   <li>{@link #healthCheck} — 引擎健康检查，用于启动探针与运行时熔断</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 实现必须是线程安全的。gRPC stub 本身线程安全，编排层可在多线程下并发调用。
 *
 * <h2>异常处理</h2>
 * 实现应在底层失败时返回 {@code success=false} 的响应对象，而非抛出异常；
 * 仅在不可恢复的传输层错误（如 channel 关闭）时抛出 {@link RuntimeException}。
 *
 * @see GrpcMpcCryptoEngine
 * @see DkgRequest
 * @see DkgResponse
 * @see SignRequest
 * @see SignResponse
 * @see AggregateRequest
 * @see AggregateResponse
 */
public interface MpcCryptoEngine {

    /**
     * 分布式密钥生成（DKG）。
     *
     * <p>GG18/GG20 第 1 阶段：所有参与方协同生成聚合公钥 X 与各自的密钥份额 x_i，
     * 满足 X = sum(x_i) * G。私钥份额永不明文离开引擎进程。</p>
     *
     * @param request DKG 请求（会话 ID、t-of-n、本节点索引、曲线、对端端点）
     * @return DKG 响应（聚合公钥、加密密钥份额、ZK 证明）；失败时 {@code success=false}
     */
    DkgResponse dkg(DkgRequest request);

    /**
     * 部分签名。
     *
     * <p>每个参与方本地执行 GG18/GG20 签名轮次（共 7 轮），产出部分签名 s_i
     * 及其正确性 ZK 证明。轮次间的 P2P / broadcast 消息由引擎内部通过
     * {@code peerEndpoints} 建立的 gRPC 通道交换。</p>
     *
     * @param request 签名请求（会话 ID、公钥、密钥份额、消息哈希、对端端点）
     * @return 签名响应（部分签名、ZK 证明）；失败时 {@code success=false}
     */
    SignResponse sign(SignRequest request);

    /**
     * 聚合部分签名。
     *
     * <p>收集至少 t 个部分签名后，聚合为最终 ECDSA 签名 (r, s)。
     * 聚合可由任意一方执行（通常为协调方），无需所有参与方在线。</p>
     *
     * @param request 聚合请求（会话 ID、公钥、消息哈希、部分签名列表）
     * @return 聚合响应（签名 r||s、r、s、recovery_id）；失败时 {@code success=false}
     */
    AggregateResponse aggregate(AggregateRequest request);

    /**
     * 引擎健康检查。
     *
     * <p>用于 Spring Boot 启动探针、Kubernetes liveness/readiness、
     * 以及运行时熔断器（Sentinel）状态判断。</p>
     *
     * @return {@code true} 若引擎进程可达且就绪
     */
    boolean healthCheck();
}