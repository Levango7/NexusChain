package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcProtocolException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每轮次超时配置：为每个轮次独立设置超时时间。
 *
 * <p>GG18/GG20 不同轮次的计算量与消息大小差异巨大（如轮次 2/3 的 MtA
 * 涉及 Paillier 加密，比轮次 1 的广播慢得多）。该类允许为每个轮次独立
 * 配置超时，未配置的轮次使用 {@code defaultTimeoutMillis}。</p>
 */
public class RoundTimeoutConfig {

    private final long defaultTimeoutMillis;
    private final Map<Integer, Long> perRoundTimeouts = new ConcurrentHashMap<>();

    /**
     * 构造默认超时配置。
     *
     * @param defaultTimeoutMillis 默认超时（毫秒）
     */
    public RoundTimeoutConfig(long defaultTimeoutMillis) {
        if (defaultTimeoutMillis <= 0) {
            throw new IllegalArgumentException("defaultTimeoutMillis must be > 0");
        }
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    /**
     * 为指定轮次设置超时。
     *
     * @param round           轮次号
     * @param timeoutMillis   超时（毫秒）
     * @return this（链式）
     */
    public RoundTimeoutConfig withRoundTimeout(int round, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        perRoundTimeouts.put(round, timeoutMillis);
        return this;
    }

    /**
     * 获取指定轮次的超时。
     *
     * @param round 轮次号
     * @return 超时（毫秒）
     */
    public long getTimeout(int round) {
        return perRoundTimeouts.getOrDefault(round, defaultTimeoutMillis);
    }

    /**
     * 校验超时是否合法（非负、有限）。
     *
     * @param round 轮次号
     * @throws MpcProtocolException 若超时配置非法
     */
    public void validate(int round) {
        long t = getTimeout(round);
        if (t <= 0) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "invalid timeout for round " + round + ": " + t);
        }
    }

    /**
     * @return 默认超时（毫秒）
     */
    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    /**
     * @return 所有非默认轮次超时的快照
     */
    public Map<Integer, Long> getPerRoundTimeouts() {
        return new HashMap<>(perRoundTimeouts);
    }
}