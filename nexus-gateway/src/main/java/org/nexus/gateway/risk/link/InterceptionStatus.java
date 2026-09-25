package org.nexus.gateway.risk.link;

/**
 * 大额交易拦截状态枚举 — 标识拦截记录的审核状态。
 *
 * <ul>
 *   <li>{@link #PENDING_REVIEW} — 待审核（拦截后等待人工审核）</li>
 *   <li>{@link #APPROVED} — 已批准（审核通过，交易放行）</li>
 *   <li>{@link #REJECTED} — 已拒绝（审核驳回，交易阻断）</li>
 *   <li>{@link #TIMEOUT_ESCALATED} — 超时升级告警（24h 未审核，自动升级告警）</li>
 * </ul>
 */
public enum InterceptionStatus {
    /** 待审核 */
    PENDING_REVIEW,
    /** 已批准 */
    APPROVED,
    /** 已拒绝 */
    REJECTED,
    /** 超时升级告警 */
    TIMEOUT_ESCALATED
}