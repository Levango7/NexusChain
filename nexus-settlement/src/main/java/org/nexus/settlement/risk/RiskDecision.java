package org.nexus.settlement.risk;

/**
 * 风控决策枚举。
 */
public enum RiskDecision {

    /** 放行 */
    APPROVED,

    /** 拒绝 */
    REJECTED,

    /** 待人工复核 */
    PENDING_REVIEW,

    /** 冻结 */
    FROZEN
}