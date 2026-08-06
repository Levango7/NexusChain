package org.nexus.signing.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 签名服务签名广播端点降级策略。
 *
 * <p>对应设计文档 §4.3.2 降级 fallback 类设计。覆盖以下资源：
 * <ul>
 *   <li>{@code POST:/api/v1/transfers/sign}：平台密钥库签名 + 广播</li>
 *   <li>{@code POST:/ClientToTransferAccount}：legacy 签名广播端点</li>
 *   <li>{@code POST:/api/v1/transfers}：legacy 转账端点（调用方提供私钥）</li>
 * </ul></p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>限流降级（{@link FlowException}）：返回 null + 告警日志，调用方按 null 处理失败</li>
 *   <li>熔断降级（{@link DegradeException}）：返回 null + 告警 + 上报 Prometheus</li>
 *   <li>不抛异常：避免调用方需额外 try-catch（方案 D10）</li>
 * </ul></p>
 *
 * <p>本类提供静态 fallback 方法，供 {@code @SentinelResource} 注解的
 * {@code fallbackMethod} 引用。方法签名与被保护方法一致，并追加一个
 * {@link BlockException} 参数。</p>
 */
public final class SignTransferFallback {

    private static final Logger log = LoggerFactory.getLogger(SignTransferFallback.class);

    private SignTransferFallback() {
        // 工具类，禁止实例化
    }

    /**
     * {@code POST /api/v1/transfers/sign} 降级处理。
     *
     * @param fromPubkey   源公钥
     * @param toPubkeyHash 目标公钥哈希
     * @param amount       转账金额
     * @param ex           Sentinel 阻断异常
     * @return 始终返回 {@code null}，调用方按签名失败处理
     */
    public static String signTransferFallback(String fromPubkey, String toPubkeyHash,
                                              BigDecimal amount, BlockException ex) {
        String reason = classify(ex);
        log.error("signTransfer 降级触发: reason={}, from={}, to={}, amount={}",
                reason, fromPubkey, toPubkeyHash, amount);
        // TODO: 上报 Prometheus + 告警
        return null;
    }

    /**
     * {@code POST /ClientToTransferAccount} 与 {@code POST /api/v1/transfers}
     * legacy 端点降级处理。
     *
     * @param fromPubkey   源公钥
     * @param toPubkeyHash 目标公钥哈希
     * @param amount       转账金额
     * @param privateKey   调用方私钥（legacy）
     * @param ex           Sentinel 阻断异常
     * @return 始终返回 {@code null}
     */
    public static String transferFallback(String fromPubkey, String toPubkeyHash,
                                          BigDecimal amount, String privateKey,
                                          BlockException ex) {
        String reason = classify(ex);
        log.error("transfer 降级触发: reason={}, from={}, to={}, amount={}",
                reason, fromPubkey, toPubkeyHash, amount);
        // TODO: 上报 Prometheus + 告警
        return null;
    }

    /**
     * {@code GET /api/v1/signing/capability} 降级处理。
     *
     * <p>MPC 签名能力查询被限流/熔断时，fail-closed 返回 {@code false}，
     * 即不再尝试 MPC 流程，退化为平台密钥库签名。</p>
     *
     * @param amount 查询金额
     * @param ex     Sentinel 阻断异常
     * @return 始终返回 {@code false}（fail-closed）
     */
    public static boolean canSignViaMpcFallback(BigDecimal amount, BlockException ex) {
        String reason = classify(ex);
        log.warn("canSignViaMpc 降级触发: reason={}, amount={}, fail-closed 返回 false",
                reason, amount);
        return false;
    }

    /**
     * {@code GET /getNoncePool} 降级处理。
     *
     * <p>Nonce 池查询被限流/熔断时返回 {@code null}，调用方应回退到
     * 链节点 RPC 直接查询当前 nonce。</p>
     *
     * @param address 钱包地址
     * @param ex      Sentinel 阻断异常
     * @return 始终返回 {@code null}
     */
    public static Object getNoncePoolFallback(String address, BlockException ex) {
        String reason = classify(ex);
        log.warn("getNoncePool 降级触发: reason={}, address={}, 调用方应回退到 RPC 查询",
                reason, address);
        return null;
    }

    /**
     * 分类 Sentinel 阻断原因。
     *
     * @param ex Sentinel 阻断异常
     * @return 人类可读的原因标签
     */
    private static String classify(BlockException ex) {
        if (ex instanceof FlowException) {
            return "FLOW_LIMIT";
        }
        if (ex instanceof DegradeException) {
            return "CIRCUIT_OPEN";
        }
        return "UNKNOWN";
    }
}