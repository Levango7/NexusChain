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
    EXPIRED,
    /** 已排队等待 timelock 到期（PASSED 之后、EXECUTED 之前的中间态） */
    QUEUED,
    /** timelock 已到期、就绪可执行 */
    READY,
    /** 执行失败（已回滚） */
    FAILED,
    /** 守护人审核中：提案通过投票后进入守护人多签审核阶段，等待 m-of-n 守护人批准放行 */
    GUARDIAN_REVIEW,
    /** 被守护人否决（任一守护人 veto）：提案终止，不进入执行 */
    GUARDIAN_VETOED
}
