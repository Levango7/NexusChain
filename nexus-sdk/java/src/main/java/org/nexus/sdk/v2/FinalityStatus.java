package org.nexus.sdk.v2;

/**
 * 支付最终性状态（与 gateway 端 FinalityStatus 对齐的 SDK 侧数据模型）。
 *
 * <p>商户 SDK 用于解析网关 {@code finality} 字段或 {@code GET /{id}/finality} 响应，
 * 实现「确认度%」与「不可逆结算」的产品语义。</p>
 *
 * <p>与业务支付状态（SUCCEEDED/FAILED 等）正交——它描述的是「这笔已成功的交易，
 * 其链上记录不可逆的程度」。</p>
 *
 * <ul>
 *   <li>{@link #OPTIMISTIC} — 已入块，可被重组（小额适用）</li>
 *   <li>{@link #FINALIZING} — 确认数过半，趋向最终化</li>
 *   <li>{@link #FINALIZED} — 达到最终化阈值，不可逆（大额结算必须等到此）</li>
 *   <li>{@link #UNKNOWN} — 链不可达/交易未上链</li>
 * </ul>
 */
public enum FinalityStatus {
    OPTIMISTIC,
    FINALIZING,
    FINALIZED,
    UNKNOWN;

    /**
     * 从网关/链端字符串解析（大小写不敏感，未知值映射为 UNKNOWN）。
     */
    public static FinalityStatus parse(String value) {
        if (value == null) return UNKNOWN;
        try {
            return FinalityStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
