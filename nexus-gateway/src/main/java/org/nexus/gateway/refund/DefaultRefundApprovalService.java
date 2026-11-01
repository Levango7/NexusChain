package org.nexus.gateway.refund;

import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Default refund approval service implementation.
 *
 * <p>Drives the refund approval workflow backed by {@link RefundRequestRepository}:</p>
 * <ul>
 *   <li>{@link #requestRefund}：校验订单可退（{@link RefundPolicy}）→ 金额不超上限
 *       → 退款窗口未过期 → 落库 PENDING</li>
 *   <li>{@link #approveRefund}：校验 PENDING → 置 APPROVED 并记录审批人与时间</li>
 *   <li>{@link #rejectRefund}：校验 PENDING → 置 REJECTED 并记录拒绝原因</li>
 *   <li>{@link #executeRefund}：三段式执行（批次⑤ P0 加固，2026-10-18）——
 *       ① 独立事务内 CAS 认领 APPROVED → EXECUTING 并<b>先提交</b>；
 *       ② 链上退款调用发生在<b>事务外</b>；③ 独立事务把结果落库（成功置 EXECUTED
 *       带交易哈希并将订单迁移至 REFUNDED，失败置 FAILED）。
 *       原实现把链上调用放在单一大事务内：出款成功后的 commit 失败会连认领一起
 *       回滚，重试即有二次打款风险；若链上已出款但结果落库失败，单据停留在
 *       EXECUTING（结果未知），由 {@link RefundExecutionReconciler} 扫描告警、
 *       人工核对处置（不自动改状态、不自动重试）。</li>
 * </ul>
 *
 * <p><b>链上退款执行已接入：</b>{@link #executeOnChain} 通过
 * {@link OnChainExecutionChannel} 发起链上退款转账，返回真实交易哈希
 * （sandbox 模式下返回 "SIMULATED-..." 前缀的模拟哈希）。</p>
 */
@Service
public class DefaultRefundApprovalService implements RefundApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefundApprovalService.class);

    /**
     * 占位符字面量：不是真实地址（既非合法 Base58 也非 hex 公钥）。
     * 仅用于识别"被显式配成了占位符"并 WARN；不再作为未配置时的回退值
     * （2026-11-02，板 20261102-0321，与 wallet 2026-10-27 同型修复）。
     */
    private static final String PLACEHOLDER_PLATFORM_REFUND_ADDRESS = "PLATFORM_HOT_WALLET";

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundPolicy refundPolicy;
    private final OnChainExecutionChannel executionChannel;
    private final String platformRefundAddress;

    /**
     * 三段式执行的编程式事务模板（批次⑤ P0 加固，2026-10-18）。
     * 由 {@link PlatformTransactionManager} 构建（与 AccountService 同款装配）；
     * 认领段 / 结果落库段各自独立提交，链上出款位于两者之间的事务外。
     */
    private final TransactionTemplate transactionTemplate;

    public DefaultRefundApprovalService(RefundRequestRepository refundRequestRepository,
                                        PaymentOrderRepository paymentOrderRepository,
                                        RefundPolicy refundPolicy) {
        this(refundRequestRepository, paymentOrderRepository, refundPolicy, null,
                "", null);
    }

    /**
     * 审计（P0 修复 2026-10-11，setter 注入）。
     *
     * <p>退款是资金出账，必须可追查（谁批的、哪笔单、多少金额、是否已上链）。
     * 用 setter 注入：本类已有两个构造器，再加参数会重蹈"多构造器"的坑。</p>
     */
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(org.nexus.gateway.audit.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Autowired
    public DefaultRefundApprovalService(RefundRequestRepository refundRequestRepository,
                                        PaymentOrderRepository paymentOrderRepository,
                                        RefundPolicy refundPolicy,
                                        OnChainExecutionChannel executionChannel,
                                        @Value("${nexus.gateway.refund.platform-address:}") String platformRefundAddress,
                                        PlatformTransactionManager transactionManager) {
        this.refundRequestRepository = refundRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundPolicy = refundPolicy;
        this.executionChannel = executionChannel;
        this.platformRefundAddress = platformRefundAddress == null ? "" : platformRefundAddress.trim();
        this.transactionTemplate = transactionManager != null
                ? new TransactionTemplate(transactionManager) : null;
    }

    @Override
    @Transactional
    public RefundRequest requestRefund(Long orderId, BigDecimal amount, String reason) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        // P0-2 修复：使用悲观锁查询订单，防止并发双花
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));

        if (!refundPolicy.canRefund(order)) {
            throw new IllegalStateException("order is not eligible for refund: status=" + order.getStatus());
        }
        BigDecimal maxRefund = refundPolicy.getMaxRefundAmount(order);
        if (amount.compareTo(maxRefund) > 0) {
            throw new IllegalArgumentException(
                    "refund amount exceeds maximum: " + amount + " > " + maxRefund);
        }
        // P0-1 修复：检查已有退款总和，防止超额退款
        BigDecimal pendingSum = refundRequestRepository.sumPendingRefundsByOrderId(orderId);
        BigDecimal availableAmount = order.getAmount().subtract(pendingSum);
        if (amount.compareTo(availableAmount) > 0) {
            throw new IllegalArgumentException(
                    "refund exceeds available: requested=" + amount + ", available=" + availableAmount
                            + " (order amount=" + order.getAmount() + ", pending refunds=" + pendingSum + ")");
        }
        if (refundPolicy.getRefundWindow(order).isZero()) {
            throw new IllegalStateException("refund window has expired for order: " + orderId);
        }

        RefundRequest request = new RefundRequest();
        request.setRefundNo("RF" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        request.setOrderId(orderId);
        request.setMerchantId(order.getMerchantId());
        request.setAmount(amount);
        request.setReason(reason);
        request.setStatus(RefundRequest.RefundStatus.PENDING);

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund requested: refundNo={}, orderId={}, amount={}, reason={}",
                saved.getRefundNo(), orderId, amount, reason);
        return saved;
    }

    @Override
    @Transactional
    public RefundRequest approveRefund(Long refundId, String approverId) {
        if (refundId == null || approverId == null) {
            throw new IllegalArgumentException("refundId and approverId are required");
        }
        RefundRequest request = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("refund request not found: " + refundId));
        if (request.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("refund is not pending: status=" + request.getStatus());
        }

        request.setStatus(RefundRequest.RefundStatus.APPROVED);
        request.setApproverId(approverId);
        request.setApprovedAt(LocalDateTime.now());

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund approved: refundId={}, approver={}", refundId, approverId);
        audit("REFUND_APPROVED", saved, "approver=" + approverId);
        return saved;
    }

    @Override
    @Transactional
    public RefundRequest rejectRefund(Long refundId, String approverId, String reason) {
        if (refundId == null || approverId == null) {
            throw new IllegalArgumentException("refundId and approverId are required");
        }
        RefundRequest request = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("refund request not found: " + refundId));
        if (request.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("refund is not pending: status=" + request.getStatus());
        }

        request.setStatus(RefundRequest.RefundStatus.REJECTED);
        request.setRejectionReason(reason == null ? "rejected by " + approverId : reason);
        request.setApprovedAt(LocalDateTime.now());

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund rejected: refundId={}, approver={}, reason={}",
                refundId, approverId, saved.getRejectionReason());
        audit("REFUND_REJECTED", saved, "approver=" + approverId);
        return saved;
    }

    @Override
    public RefundRequest executeRefund(Long refundId) {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId is required");
        }
        if (transactionTemplate == null) {
            // fail-closed：执行退款必须有能力开启独立事务（认领段先提交），
            // 否则无法保证"链上出款不在 DB 事务内"这一资金安全前提。
            throw new IllegalStateException(
                    "transaction support is not configured; refund execution refused (fail-closed)");
        }

        // ── 第一段（独立事务，先提交）：CAS 认领 APPROVED → EXECUTING ──────────────
        // 认领必须先于链上出款且独立提交：即使后续链上出款或结果落库失败，
        // "已被这笔执行认领"这一事实也不会回滚。原实现把认领、链上出款、结果落库
        // 放在同一个大事务里——出款成功后的 commit 失败会连认领一起回滚，
        // 重试即可造成二次打款。
        RefundRequest claimed = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int claimedRows = refundRequestRepository.claimForExecution(refundId, now);
            if (claimedRows == 0) {
                // 审批窗口结束后的重新认领：AWAITING_APPROVAL → EXECUTING。
                // 两个入口都走同一条"先认领后出款"的三段式，故并发语义不变。
                claimedRows = refundRequestRepository.claimAwaitingForExecution(refundId, now);
            }
            if (claimedRows == 0) {
                throw new IllegalStateException(
                        "refund is not claimable for execution (not approved/awaiting or already executing/executed): id="
                                + refundId);
            }
            return refundRequestRepository.findById(refundId)
                    .orElseThrow(() -> new IllegalStateException(
                            "refund request disappeared after claim: " + refundId));
        });

        // ── 第二段（无事务）：链上退款。不可逆的外部调用不得待在 DB 事务内 ─────────
        String txHash;
        try {
            txHash = executeOnChain(claimed);
        } catch (RefundApprovalPendingException e) {
            // 大额退款：签名服务只创建了审批请求，资金未动。单据置 AWAITING_APPROVAL
            // （非终态、仍占退款额度），审批通过后由 RefundApprovalExecutionReconciler
            // 重新认领执行；绝不落 FAILED。
            markAwaitingApprovalAndAudit(refundId, claimed, e);
            return refundRequestRepository.findById(refundId).orElse(claimed);
        } catch (RefundSubmitOutcomeUnknownException e) {
            // 已提交、结果未知（如已广播未确认）：保持 EXECUTING。
            // 不得落 FAILED（假阴性，且 FAILED 是终态无出路）；不得自动重试出款（二次打款）。
            // 由 RefundExecutionReconciler 扫描 EXECUTING 超时后告警，人工核对链上事实。
            audit("REFUND_SUBMIT_OUTCOME_UNKNOWN", claimed, e.getMessage());
            log.error("CRITICAL: refund submitted but outcome unknown; refund stays EXECUTING "
                    + "for manual verification: refundId={}, {}", refundId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // 出款确定未发生（参数校验失败 / 通道返回确定性失败结果）：独立事务落 FAILED
            markFailedAndAudit(refundId, claimed, e);
            return refundRequestRepository.findById(refundId).orElse(claimed);
        }

        // ── 第三段（独立事务）：把"链上已出款"落库为 EXECUTED ────────────────────
        RefundRequest executed;
        try {
            executed = transactionTemplate.execute(status -> {
                int updated = refundRequestRepository.markExecuted(refundId, txHash, LocalDateTime.now());
                if (updated == 0) {
                    throw new IllegalStateException(
                            "refund state changed while finalizing execution: id=" + refundId);
                }
                return refundRequestRepository.findById(refundId)
                        .orElseThrow(() -> new IllegalStateException(
                                "refund request disappeared after finalize: " + refundId));
            });
        } catch (RuntimeException e) {
            // 链上已出款但落库失败：单据停留在 EXECUTING，结果未知。
            // 此处不得改状态、不得自动重试（二次打款风险）；RefundExecutionReconciler
            // 会在超时后发出人工核对告警。
            audit("REFUND_FINALIZE_FAILED_STUCK", claimed,
                    "txHash=" + txHash + ", error=" + e.getMessage());
            log.error("CRITICAL: refund executed on-chain but DB finalize failed; refund stays "
                    + "EXECUTING for manual verification: refundId={}, txHash={}", refundId, txHash, e);
            throw e;
        }

        log.info("Refund executed: refundId={}, txHash={}", refundId, txHash);
        audit("REFUND_EXECUTED", executed, "txHash=" + txHash);

        // 订单终态迁移（独立事务，best-effort）：超退防线不依赖订单状态
        // （sumPendingRefundsByOrderId 已计入 EXECUTED），失败不构成资金风险但必须可追查。
        transitionOrderToRefunded(claimed.getOrderId(), refundId, claimed);
        return executed;
    }

    /** 大额签名转审批：EXECUTING → AWAITING_APPROVAL（独立事务；审计旁路）。 */
    private void markAwaitingApprovalAndAudit(Long refundId, RefundRequest claimed,
                                              RefundApprovalPendingException cause) {
        String reason = "awaiting signing approval: approvalId=" + cause.getApprovalId();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int updated = refundRequestRepository.markAwaitingApproval(
                        refundId, reason, LocalDateTime.now());
                if (updated == 0) {
                    log.warn("refund not in EXECUTING while marking AWAITING_APPROVAL (state moved): refundId={}",
                            refundId);
                }
            });
        } catch (RuntimeException persistError) {
            // 落库失败：保持 EXECUTING，由 RefundExecutionReconciler 告警人工核对（不得改 FAILED）。
            log.error("failed to persist AWAITING_APPROVAL for refund: refundId={}", refundId, persistError);
        }
        log.warn("Refund awaiting signing approval: refundId={}, approvalId={}",
                refundId, cause.getApprovalId());
        audit("REFUND_AWAITING_APPROVAL", claimed, "approvalId=" + cause.getApprovalId());
    }

    /** 出款确定未发生时的失败落库（独立事务；审计旁路，任何异常不阻断流程）。 */
    private void markFailedAndAudit(Long refundId, RefundRequest claimed, RuntimeException cause) {
        String reason = "execution failed: " + cause.getMessage();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int updated = refundRequestRepository.markFailed(refundId, reason, LocalDateTime.now());
                if (updated == 0) {
                    log.warn("refund not in EXECUTING while marking failed (state moved): refundId={}", refundId);
                }
            });
        } catch (RuntimeException persistError) {
            log.error("failed to persist FAILED status for refund: refundId={}", refundId, persistError);
        }
        log.error("Refund execution failed: refundId={}", refundId, cause);
        audit("REFUND_EXECUTION_FAILED", claimed, "error=" + cause.getMessage());
    }

    /** 执行成功后把订单迁移到 REFUNDED（独立事务，best-effort，可追查）。 */
    private void transitionOrderToRefunded(Long orderId, Long refundId, RefundRequest claimed) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                PaymentOrder order = paymentOrderRepository.findById(orderId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Order not found after refund execution: orderId=" + orderId));
                if (order.getStatus() == PaymentOrder.OrderStatus.REFUNDED) {
                    // 幂等：同一订单的多笔（部分）退款，第一笔已把订单置为终态
                    return;
                }
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUNDED);
                paymentOrderRepository.save(order);
            });
        } catch (RuntimeException e) {
            log.error("Refund EXECUTED but order transition to REFUNDED failed: refundId={}, orderId={}",
                    refundId, orderId, e);
            audit("REFUND_ORDER_TRANSITION_FAILED", claimed,
                    "orderId=" + orderId + ", error=" + e.getMessage());
        }
    }

    @Override
    public RefundRequest getRefund(Long refundId) {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId is required");
        }
        return refundRequestRepository.findById(refundId).orElse(null);
    }

    /** 写审计（旁路：任何异常都不得阻断退款流程）。 */
    private void audit(String action, RefundRequest request, String detail) {
        if (auditLogService == null || request == null) {
            return;
        }
        try {
            auditLogService.recordRefund(String.valueOf(request.getOrderId()), action, detail);
        } catch (RuntimeException e) {
            log.warn("Refund audit write failed (ignored): action={}, refundId={}, error={}",
                    action, request.getId(), e.getMessage());
        }
    }

    /**
     * Trigger the on-chain refund transfer via {@link OnChainExecutionChannel}.
     *
     * <p>构造 {@link TransactionRequest}（type=REFUND）并调用统一链上执行通道。
     * sandbox 模式下返回 "SIMULATED-..." 前缀的模拟交易哈希；
     * 生产模式下返回真实链上交易哈希。</p>
     *
     * @param request the approved refund request to execute
     * @return the on-chain transaction hash (real or simulated)
     * @throws IllegalStateException if the execution channel is not injected or execution fails
     */
    private String executeOnChain(RefundRequest request) {
        // 2026-11-02（板 20261102-0321，与 wallet 2026-10-27 同型）：平台退款地址未配置 ⇒
        // 执行前 fail-closed 拒绝。原实现回退占位符字面量，把 "PLATFORM_HOT_WALLET" 假地址
        // 发给执行通道——失败必然发生但原因伪装成链/签名错误，排障成本高。此处检查位于
        // 任何外部调用之前，拒绝即"出款确定未发生"，落入 markFailedAndAudit（FAILED + 原因明写）。
        if (platformRefundAddress == null || platformRefundAddress.isBlank()) {
            throw new IllegalStateException("platform refund address not configured "
                    + "(nexus.gateway.refund.platform-address / NEX_GATEWAY_REFUND_PLATFORM_ADDRESS): "
                    + "refund aborted (fail-closed)");
        }
        if (PLACEHOLDER_PLATFORM_REFUND_ADDRESS.equals(platformRefundAddress)) {
            log.warn("platform refund address equals the placeholder literal '{}' — it is NOT a real "
                            + "address; set NEX_GATEWAY_REFUND_PLATFORM_ADDRESS for this deployment",
                    PLACEHOLDER_PLATFORM_REFUND_ADDRESS);
        }
        if (executionChannel == null) {
            throw new IllegalStateException("OnChainExecutionChannel is not injected");
        }
        // P0-2 修复（v2.27.0）：退款收款方必须是原付款人地址，而非字符串常量 "REFUND:"+refundNo。
        // 原实现将退款转到一个不存在的地址 "REFUND:RF..."，资金永远无法被收款人领取。
        PaymentOrder order = paymentOrderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order not found for refund: orderId=" + request.getOrderId()));
        String payerAddress = order.getPayerAddress();
        if (payerAddress == null || payerAddress.isBlank()) {
            throw new IllegalStateException(
                    "Payer address is null for order: " + request.getOrderId()
                            + "; refund cannot be sent to an unknown recipient");
        }
        TransactionRequest txReq = new TransactionRequest(
                TransactionRequest.Type.REFUND,
                platformRefundAddress,
                payerAddress,
                request.getAmount(),
                "NEX",
                "refund:" + request.getRefundNo(),
                "refund:" + request.getId());
        TransactionResult result = executionChannel.execute(txReq);
        if (result != null && result.getStatus() == TransactionResult.Status.AWAITING_APPROVAL) {
            // 2001：签名服务已受理并创建审批请求，未签名/未广播。必须交给"等待审批"
            // 状态机，不能走下面的失败分支（那是一笔会被永久丢掉的退款）。
            throw new RefundApprovalPendingException(result.getApprovalId());
        }
        if (result != null && result.getStatus() == TransactionResult.Status.EXECUTED_UNVERIFIED) {
            // 已执行但无哈希：资金可能已出，必须保持 EXECUTING 交人工核对，绝不落 FAILED。
            throw new RefundSubmitOutcomeUnknownException(
                    "refund signed but result unverified: approvalId=" + result.getApprovalId()
                            + ", error=" + result.getError());
        }
        // P0 修复（2026-10-21）：已广播但未达确认数 ⇒ 链上结果未知，绝不能当"确定失败"。
        // TransactionResult.isSuccess() 只认 SUCCESS；PENDING_CONFIRMATION 会被下面的
        // !isSuccess() 误判为失败，而交易其实已上链 ⇒ 落 FAILED 会造成账实不符且无路径纠正。
        // 必须走"结果未知"分支：保持 EXECUTING 交由 RefundExecutionReconciler 告警人工核对。
        if (result != null
                && result.getStatus() == TransactionResult.Status.PENDING_CONFIRMATION
                && result.getTxHash() != null) {
            throw new RefundSubmitOutcomeUnknownException(
                    "refund broadcast but not yet confirmed: txHash=" + result.getTxHash());
        }
        if (result == null || !result.isSuccess() || result.getTxHash() == null) {
            String err = result != null ? result.getError() : "execution channel returned null";
            throw new IllegalStateException("on-chain refund execution failed: " + err);
        }
        // P0-2 修复（v2.27.0）：生产环境不应使用模拟交易，记录安全告警。
        if (result.isSimulated()) {
            log.warn("SECURITY: refund executed in simulated mode: refundId={}, txHash={}. "
                    + "MUST not happen in production.",
                    request.getId(), result.getTxHash());
        }
        log.info("refund on-chain executed: refundId={}, txHash={}, simulated={}",
                request.getId(), result.getTxHash(), result.isSimulated());
        return result.getTxHash();
    }
}
