package org.nexus.walletsvc.approval;

import java.math.BigDecimal;

/**
 * 提现审批服务接口（钱包管理服务侧）。
 *
 * <p>定义钱包管理服务对提现审批流程的服务边界。原实现位于
 * {@code org.nexus.wallet.wallet.approval.WithdrawalApprovalService}（exchange-wallet），
 * 本接口为独立部署后的服务边界抽象。</p>
 *
 * <p>PoC 阶段：仅定义接口边界，实际审批流程仍由 exchange-wallet 进程内提供。
 * 完整迁移涉及 DefaultWithdrawalApprovalService / WithdrawalRequest 等组件，
 * 见 README.md 迁移计划。所需审批人数由 {@code org.nexus.sdk.signing.ApprovalPolicy}
 * （已迁至 nexus-sdk 共享层）决定。</p>
 */
public interface WithdrawalApprovalService {

    /**
     * 发起一笔提现申请，进入 PENDING 状态，所需审批人数由
     * {@link ApprovalPolicy} 决定。
     *
     * @param to       目标钱包地址
     * @param amount   提现金额
     * @param currency 币种符号
     * @return 提现申请句柄
     */
    WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency);

    /**
     * 记入一次审批。当审批数达到阈值时，申请转入 APPROVED 状态。
     *
     * @param approvalId 提现申请 ID
     * @param approverId 审批人 ID
     * @return 更新后的提现申请
     */
    WithdrawalRequest approve(String approvalId, String approverId);

    /**
     * 拒绝提现申请。
     *
     * @param approvalId 提现申请 ID
     * @param approverId 审批人 ID
     * @param reason     拒绝原因
     * @return 更新后的提现申请（REJECTED 状态）
     */
    WithdrawalRequest reject(String approvalId, String approverId, String reason);

    /**
     * 执行已审批通过的提现，上链广播。
     *
     * @param approvalId 提现申请 ID
     * @return 更新后的提现申请（EXECUTED 或 FAILED 状态）
     */
    WithdrawalRequest executeApprovedWithdrawal(String approvalId);
}