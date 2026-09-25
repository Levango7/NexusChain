package org.nexus.gateway.voidreversal;

/**
 * 冲正类型枚举。
 *
 * <p>区分冲正请求的发起方式：</p>
 * <ul>
 *   <li>{@link #MANUAL} — 人工发起，商户或运营人员手动提交冲正请求</li>
 *   <li>{@link #AUTO} — 系统自动发起，由 {@code AutoReversalScheduler} 检测异常交易后自动触发</li>
 * </ul>
 */
public enum ReversalType {
    /** 人工发起 — 商户或运营人员手动提交 */
    MANUAL,
    /** 自动发起 — 系统检测异常交易后自动触发 */
    AUTO
}