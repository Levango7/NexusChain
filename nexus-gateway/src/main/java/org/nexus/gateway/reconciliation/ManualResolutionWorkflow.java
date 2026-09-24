package org.nexus.gateway.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 人工处理流程服务。
 *
 * <p>提供挂账资金的人工审核流程，包括：
 * <ul>
 *   <li>{@code submitResolutionProposal}：提交处理方案（挂账 → 待审批）</li>
 *   <li>{@code approveResolution}：审批通过（自动核销挂账）</li>
 *   <li>{@code rejectResolution}：审批拒绝（退回待处理，记录拒绝原因）</li>
 * </ul>
 * </p>
 *
 * <p>审批流程：
 * <pre>
 *   PENDING (挂账) → 提交方案 → PROPOSAL_PENDING (待审批)
 *                              → 审批通过 → 自动调用 resolveSuspenseAccount → RESOLVED
 *                              → 审批拒绝 → 退回 PENDING，记录拒绝原因
 * </pre>
 * </p>
 */
@Service
public class ManualResolutionWorkflow {

    /** 方案审批状态常量 */
    public static final String PROPOSAL_PENDING = "PENDING";
    public static final String PROPOSAL_APPROVED = "APPROVED";
    public static final String PROPOSAL_REJECTED = "REJECTED";

    private final SuspenseAccountService suspenseAccountService;
    private final SuspenseAccountRepository suspenseAccountRepository;

    public ManualResolutionWorkflow(
            SuspenseAccountService suspenseAccountService,
            SuspenseAccountRepository suspenseAccountRepository) {
        this.suspenseAccountService = suspenseAccountService;
        this.suspenseAccountRepository = suspenseAccountRepository;
    }

    /**
     * 提交处理方案。
     *
     * <p>操作人针对挂账记录提交具体的处理方案，方案提交后进入待审批状态。
     * 挂账必须处于 PENDING 状态才能提交方案。</p>
     *
     * @param suspenseAccountId 挂账记录 ID
     * @param proposal 处理方案描述
     * @param proposedBy 方案提交人
     * @return 更新后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 挂账状态不允许提交方案（非 PENDING）
     */
    @Transactional
    public SuspenseAccount submitResolutionProposal(
            Long suspenseAccountId, String proposal, String proposedBy) {
        SuspenseAccount account = suspenseAccountService.findSuspenseAccountById(suspenseAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + suspenseAccountId));

        if (account.getStatus() != SuspenseAccount.SuspenseStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot submit proposal: suspense account status is "
                            + account.getStatus() + ", expected PENDING");
        }

        account.setProposal(proposal);
        account.setProposedBy(proposedBy);
        account.setProposalStatus(PROPOSAL_PENDING);

        return suspenseAccountRepository.save(account);
    }

    /**
     * 审批通过。
     *
     * <p>审批人审核处理方案后通过审批，系统自动调用 SuspenseAccountService.resolveSuspenseAccount
     * 核销挂账记录。挂账必须已提交方案且方案状态为 PENDING 才能审批。</p>
     *
     * @param suspenseAccountId 挂账记录 ID
     * @param approvedBy 审批人
     * @return 更新后的挂账记录（已核销）
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 方案状态不允许审批（未提交方案或已审批）
     */
    @Transactional
    public SuspenseAccount approveResolution(Long suspenseAccountId, String approvedBy) {
        SuspenseAccount account = suspenseAccountService.findSuspenseAccountById(suspenseAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + suspenseAccountId));

        if (!PROPOSAL_PENDING.equals(account.getProposalStatus())) {
            throw new IllegalStateException(
                    "Cannot approve: proposal status is "
                            + account.getProposalStatus() + ", expected PENDING");
        }

        // 记录审批人，更新方案状态
        account.setApprovedBy(approvedBy);
        account.setProposalStatus(PROPOSAL_APPROVED);
        suspenseAccountRepository.save(account);

        // 审批通过后自动核销挂账
        String resolutionNote = "Approved by " + approvedBy
                + ": " + (account.getProposal() != null ? account.getProposal() : "");
        return suspenseAccountService.resolveSuspenseAccount(suspenseAccountId, resolutionNote);
    }

    /**
     * 审批拒绝。
     *
     * <p>审批人审核处理方案后拒绝审批，挂账退回 PENDING 状态（方案状态标记为 REJECTED），
     * 记录拒绝原因。操作人可以重新提交新的处理方案。</p>
     *
     * @param suspenseAccountId 挂账记录 ID
     * @param rejectedBy 审批人
     * @param reason 拒绝原因
     * @return 更新后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 方案状态不允许审批（未提交方案或已审批）
     */
    @Transactional
    public SuspenseAccount rejectResolution(
            Long suspenseAccountId, String rejectedBy, String reason) {
        SuspenseAccount account = suspenseAccountService.findSuspenseAccountById(suspenseAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + suspenseAccountId));

        if (!PROPOSAL_PENDING.equals(account.getProposalStatus())) {
            throw new IllegalStateException(
                    "Cannot reject: proposal status is "
                            + account.getProposalStatus() + ", expected PENDING");
        }

        account.setApprovedBy(rejectedBy);
        account.setProposalStatus(PROPOSAL_REJECTED);
        account.setRejectionReason(reason);
        // 挂账状态保持 PENDING，允许重新提交方案

        return suspenseAccountRepository.save(account);
    }
}