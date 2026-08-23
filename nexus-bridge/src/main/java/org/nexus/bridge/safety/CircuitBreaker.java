package org.nexus.bridge.safety;

/**
 * 熔断器接口。
 *
 * <p>当桥出现异常（如失败率超阈值、对账不一致）时熔断，
 * 阻止后续跨链操作以保护资产安全。</p>
 *
 * <p>B-21 修复：扩展接口，添加 {@link #acquirePermission()}、
 * {@link #recordSuccess()}、{@link #recordFailure(String)} 方法，
 * 供桥主流程（lock/mint/burn/unlock）在操作前后调用，
 * 实现熔断器的主动接入而非被动检查。</p>
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

    /**
     * 获取执行许可（B-21 修复）。
     *
     * <p>桥操作（lock/mint/burn/unlock）入口应在执行前调用本方法。
     * 若返回 {@code false}（熔断中），调用方应拒绝操作并返回失败响应。</p>
     *
     * @return 允许执行返回 {@code true}；熔断中返回 {@code false}
     */
    default boolean acquirePermission() {
        return !isTripped();
    }

    /**
     * 记录操作成功（B-21 修复）。
     *
     * <p>桥操作成功完成后调用，用于统计成功率、自动恢复等。
     * 默认实现为空操作，子类可重写以实现基于失败率的自动熔断。</p>
     */
    default void recordSuccess() {
        // 默认空操作：基于手动 trip/reset 的简单实现无需统计
    }

    /**
     * 记录操作失败（B-21 修复）。
     *
     * <p>桥操作失败后调用，用于统计失败率、自动触发熔断等。
     * 默认实现为空操作，子类可重写以实现基于失败率的自动熔断。</p>
     *
     * @param reason 失败原因
     */
    default void recordFailure(String reason) {
        // 默认空操作：基于手动 trip/reset 的简单实现无需统计
    }
}