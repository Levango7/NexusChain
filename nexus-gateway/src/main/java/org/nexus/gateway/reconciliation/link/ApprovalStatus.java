package org.nexus.gateway.reconciliation.link;

/**
 * 对账差异调整审批状态枚举。
 *
 * <p>审批状态流转：
 * <ul>
 *   <li>{@code AUTO_APPROVED}：自动通过 — 金额 ≤ 1000 元，系统自动审批</li>
 *   <li>{@code PENDING_APPROVAL}：待审批 — 金额 > 1000 元，需人工审批</li>
 *   <li>{@code APPROVED}：已通过 — 人工审批通过</li>
 *   <li>{@code REJECTED}：已拒绝 — 人工审批拒绝</li>
 * </ul>
 * </p>
 */
public enum ApprovalStatus {
    /** 自动通过 — 金额在容差范围内，系统自动审批 */
    AUTO_APPROVED,
    /** 待审批 — 金额超出自动审批阈值，需人工审批 */
    PENDING_APPROVAL,
    /** 已通过 — 人工审批通过 */
    APPROVED,
    /** 已拒绝 — 人工审批拒绝 */
    REJECTED
}