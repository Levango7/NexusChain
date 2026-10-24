package org.nexus.gateway.approval;

/**
 * 一次"签名或等待审批"调用的结果快照。
 *
 * @param status     链接状态（{@code null} 仅出现在未落库的直接成功/失败上）
 * @param approvalId 审批请求 ID（无审批时为 null）
 * @param txHash     链上交易哈希（仅 {@link ApprovalExecutionLink.Status#EXECUTED} 非空）
 * @param error      失败/不可核对的原因
 */
public record SigningOutcome(ApprovalExecutionLink.Status status,
                             String approvalId,
                             String txHash,
                             String error) {

    /** 交易已签名并广播，且拿到了交易哈希。 */
    public boolean isExecuted() {
        return status == ApprovalExecutionLink.Status.EXECUTED;
    }

    /** 非终态：审批中/待执行/执行中，调用方应保存 approvalId 后重试。 */
    public boolean isAwaiting() {
        return status == ApprovalExecutionLink.Status.AWAITING_APPROVAL
                || status == ApprovalExecutionLink.Status.APPROVED
                || status == ApprovalExecutionLink.Status.EXECUTING;
    }

    /** 明确失败（确定未动账）。 */
    public boolean isFailed() {
        return status == ApprovalExecutionLink.Status.FAILED;
    }

    /** 结果不可核对：资金可能已转出，禁止自动重试。 */
    public boolean requiresManualVerification() {
        return status != null && status.requiresManualVerification();
    }
}
