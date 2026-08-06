package org.nexus.signing.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.nexus.signing.mpc.MpcKeyShare;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.MpcSigningSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MPC 阈值签名降级策略。
 *
 * <p>对应设计文档 §4.3.1 规则清单中 {@code MpcSigner.runSigningRounds}
 * 慢调用熔断（RT &gt; 30s / 50% / 10s）的降级处理。</p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>慢调用熔断（{@link DegradeException}）：抛出 {@link MpcProtocolException}
 *       标记 session 为 FAILED，触发上层重试或告警</li>
 *   <li>限流降级（{@link FlowException}）：抛出 {@link MpcProtocolException}
 *       标记 session 为 FAILED</li>
 *   <li>不静默返回：MPC 签名无法降级为「部分成功」，必须明确失败以触发重试</li>
 * </ul></p>
 *
 * <p>本类提供静态 fallback 方法，供 {@code @SentinelResource} 注解的
 * {@code fallbackMethod} 引用。</p>
 */
public final class MpcSignFallback {

    private static final Logger log = LoggerFactory.getLogger(MpcSignFallback.class);

    private MpcSignFallback() {
        // 工具类，禁止实例化
    }

    /**
     * {@code MpcSigner.runSigningRounds} 慢调用/限流降级处理。
     *
     * <p>将 session 标记为 FAILED 并抛出 {@link MpcProtocolException}，
     * 上层调用方（如 ColdWalletMultiSigService）捕获后决定重试或告警。</p>
     *
     * @param session 签名会话
     * @param shares  各参与方密钥分片
     * @param ex      Sentinel 阻断异常
     * @throws MpcProtocolException 始终抛出，表示签名流程失败
     */
    public static void runSigningRoundsFallback(MpcSigningSession session,
                                                List<MpcKeyShare> shares,
                                                BlockException ex) throws MpcProtocolException {
        String reason = classify(ex);
        String sessionId = session != null ? session.getSessionId() : "null";
        log.error("MPC 签名降级触发: reason={}, session={}, 标记 FAILED 并抛出协议异常",
                reason, sessionId);

        // 将 session 标记为 FAILED（如果 session 不为 null）
        if (session != null) {
            try {
                session.markFailed(MpcProtocolException.Reason.ILLEGAL_STATE,
                        "Sentinel fallback: " + reason, null);
            } catch (Exception markEx) {
                log.warn("标记 session FAILED 失败: sessionId={}, cause={}",
                        sessionId, markEx.getMessage());
            }
        }

        // TODO: 上报 Prometheus + 告警

        throw new MpcProtocolException(
                MpcProtocolException.Reason.ILLEGAL_STATE,
                "MPC signing rounds blocked by Sentinel: " + reason);
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
            return "SLOW_CALL_CIRCUIT";
        }
        return "UNKNOWN";
    }
}
