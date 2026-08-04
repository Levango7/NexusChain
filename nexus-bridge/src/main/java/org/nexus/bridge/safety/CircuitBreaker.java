package org.nexus.bridge.safety;

/**
 * 熔断器接口。
 *
 * <p>当桥出现异常（如失败率超阈值、对账不一致）时熔断，
 * 阻止后续跨链操作以保护资产安全。</p>
 *
 * @since 1.2
 */
public interface CircuitBreaker {

    /**
     * 触发熔断。
     *
     * @param reason 熔断原因
     */
    void trip(String reason);

    /**
     * 重置熔断，恢复正常服务。
     */
    void reset();

    /**
     * 查询当前是否处于熔断状态。
     *
     * @return 熔断中返回 true
     */
    boolean isTripped();

    /**
     * 获取当前熔断原因。
     *
     * @return 熔断原因；未熔断时返回 {@code null}
     */
    String getTripReason();
}