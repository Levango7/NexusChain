package org.nexus.governance;

/**
 * 治理提案状态枚举。
 *
 * @since 1.2
 */
public enum ProposalStatus {
    /** 投票进行中 */
    VOTING,
    /** 已通过待执行 */
    PASSED,
    /** 已被否决 */
    REJECTED,
    /** 已执行生效 */
    EXECUTED,
    /** 已过期 */
    EXPIRED
}