package org.nexus.gateway.orchestration.settlement;

/**
 * 支付最终性状态（NexFinality 双层确认模型的网关侧体现）。
 *
 * <p>与 {@link PaymentStatus}（业务状态机：成功/失败）正交，
 * `FinalityStatus` 描述一笔链上交易<strong>不可逆的程度</strong>：</p>
 *
 * <ul>
 *   <li>{@link #OPTIMISTIC} —— 已入块，确认数低于最终化阈值，理论上仍可被重组。
 *       适用于小额、低价值支付场景（商户可选择性接受）。</li>
 *   <li>{@link #FINALIZING} —— 确认数已过半但未满阈值，正在走向最终化。</li>
 *   <li>{@link #FINALIZED} —— 确认数达到最终化阈值，视为不可逆。
 *       大额结算、跨链桥锁定、法币出金<strong>必须</strong>等待此状态。</li>
 *   <li>{@link #UNKNOWN} —— 链不可达或交易不存在，无法判定最终性。</li>
 * </ul>
 *
 * <p>未来接入 NexFinality BFT 投票层后，FINALIZED 的语义将升级为
 * "≥2/3 质押权重投票通过"，而不仅仅是块数阈值（见 ADR-030）。</p>
 */
public enum FinalityStatus {
    OPTIMISTIC,
    FINALIZING,
    FINALIZED,
    UNKNOWN
}
