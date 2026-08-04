package org.nexus.oracle.governance;

/**
 * 提案状态枚举。
 */
public enum ProposalState {

    /** 待提交（创建后尚未进入投票期） */
    PENDING,

    /** 投票进行中 */
    ACTIVE,

    /** 投票通过，等待执行 */
    PASSED,

    /** 投票未通过 */
    REJECTED,

    /** 已执行（提案落地） */
    EXECUTED,

    /** 已取消 */
    CANCELED
}