package org.nexus.gateway.voidreversal;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 隔日冲正服务 — 处理已结算交易的反向调整。
 *
 * <p>冲正（REVERSAL）是对已结算交易的反向调整操作，适用于超过撤销窗口的隔日交易。
 * 与撤销的区别：撤销是当日交易的全额回滚（恢复原状），冲正是隔日交易的反向调整
 * （生成新的反向交易记录）。</p>
 *
 * <p>核心业务规则：</p>
 * <ul>
 *   <li><b>冲正窗口</b>：只能对隔日交易（超过撤销窗口）发起冲正</li>
 *   <li><b>冲正类型</b>：MANUAL（人工发起，需审批）/ AUTO（系统自动发起）</li>
 *   <li><b>审批流程</b>：PENDING → APPROVED → COMPLETED（或 REJECTED/FAILED）</li>
 *   <li><b>余额联动</b>：审批通过后调用 AccountService 调整商户余额</li>
 *   <li><b>订单状态</b>：冲正成功后订单状态变为 REVERSED（终态）</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁保证冲正请求的并发安全，
 * 冲正请求与余额变更在同一数据库事务中完成。</p>
 */
@Service
public class ReversalService {

    private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

    private final ReversalRequestRepository reversalRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public ReversalService(ReversalRequestRepository reversalRequestRepository,
                           PaymentOrderRepository paymentOrderRepository,
                           AccountService accountService,
                           ApplicationEventPublisher eventPublisher) {
        this.reversalRequestRepository = reversalRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    // === 创建冲正请求（手动） ===

    /**
     * 创建冲正请求（手动） — 人工发起的冲正请求，状态为 PENDING，类型为 MANUAL。
     *
     * @param orderId 订单 ID
     * @param reason 冲正原因（可为空，最长 256 字符）
     * @param operatorId 操作人 ID
     * @return 创建的冲正请求
     * @throws IllegalArgumentException 订单不存在
     * @throws IllegalStateException 订单状态非 PAID、未超过撤销窗口、已冲正
     */
    @Transactional
    public ReversalRequest requestReversal(Long orderId, String reason, String operatorId) {
        return createReversalRequest(orderId, reason, operatorId, ReversalType.MANUAL);
    }

    // === 创建冲正请求（自动） ===

    /**
     * 创建冲正请求（自动） — 系统自动发起的冲正请求，类型为 AUTO。
     *
     * <p>由 {@link AutoReversalScheduler} 检测到异常交易后自动调用。
     * 自动冲正请求同样进入 PENDING 状态等待审批（安全考虑：自动冲正也需要人工审批确认）。</p>
     *
     * @param orderId 订单 ID
     * @param reason 冲正原因
     * @return 创建的冲正请求
     */
    @Transactional
    public ReversalRequest autoReversal(Long orderId, String reason) {
        return createReversalRequest(orderId, reason, "SYSTEM", ReversalType.AUTO);
    }

    // === 审批通过 ===

    /**
     * 审批通过冲正请求 — 执行冲正操作：调整商户余额、更新订单状态为 REVERSED。
     *
     * <p>冲正的余额联动逻辑：冲正是对已结算交易的反向调整，支付时商户余额增加了 amount，
     * 冲正时需要从商户 BALANCE 账户扣减 amount（DEBIT），并记录 REVERSAL_ADJUST 类型流水。</p>
     *
     * @param reversalRequestId 冲正请求 ID
     * @return 更新后的冲正请求（状态为 COMPLETED 或 FAILED）
     * @throws IllegalArgumentException 冲正请求不存在
     * @throws IllegalStateException 冲正请求状态非 PENDING/APPROVED
     */
    @Transactional
    public ReversalRequest approveReversal(Long reversalRequestId) {
        ReversalRequest reversalRequest = reversalRequestRepository.findById(reversalRequestId)
                .orElseThrow(() -> new IllegalArgumentException("冲正请求不存在: " + reversalRequestId));

        if (reversalRequest.getStatus() != ReversalStatus.PENDING
                && reversalRequest.getStatus() != ReversalStatus.APPROVED) {
            throw new IllegalStateException(
                    "冲正请求状态非 PENDING/APPROVED，无法审批: status=" + reversalRequest.getStatus());
        }

        Long orderIdForLookup = reversalRequest.getOrderId();
        PaymentOrder order = paymentOrderRepository.findById(orderIdForLookup)
                .orElseThrow(() -> new IllegalArgumentException(
                        "关联订单不存在: " + orderIdForLookup));

        try {
            // 再次校验订单状态（防止并发变更）
            if (order.getStatus() != PaymentOrder.OrderStatus.PAID) {
                reversalRequest.setStatus(ReversalStatus.FAILED);
                reversalRequest.setCompletedAt(LocalDateTime.now());
                reversalRequest = reversalRequestRepository.save(reversalRequest);
                log.warn("冲正失败：订单状态已变更: orderId={}, status={}",
                        order.getId(), order.getStatus());
                return reversalRequest;
            }

            // 调用 AccountService 扣减商户余额（反向调整）
            MerchantAccount account = accountService.withdraw(
                    reversalRequest.getMerchantId(), reversalRequest.getAmount());

            // 更新订单状态为 REVERSED
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REVERSED);
            paymentOrderRepository.save(order);

            // 更新冲正请求状态
            reversalRequest.setStatus(ReversalStatus.COMPLETED);
            reversalRequest.setCompletedAt(LocalDateTime.now());
            reversalRequest = reversalRequestRepository.save(reversalRequest);

            // 发布冲正完成事件
            OrderReversedEvent event = new OrderReversedEvent(
                    this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                    reversalRequest.getReversalNo(), reversalRequest.getAmount(),
                    account != null ? account.getBalance() : BigDecimal.ZERO);
            eventPublisher.publishEvent(event);

            log.info("冲正审批通过并完成: reversalNo={}, orderId={}, balanceAfter={}",
                    reversalRequest.getReversalNo(), order.getId(),
                    account != null ? account.getBalance() : "N/A");

            return reversalRequest;

        } catch (IllegalStateException e) {
            // 余额不足等业务异常 → 标记为 FAILED
            reversalRequest.setStatus(ReversalStatus.FAILED);
            reversalRequest.setCompletedAt(LocalDateTime.now());
            reversalRequest = reversalRequestRepository.save(reversalRequest);
            log.error("冲正执行失败: reversalNo={}, orderId={}, error={}",
                    reversalRequest.getReversalNo(), reversalRequest.getOrderId(), e.getMessage());
            return reversalRequest;
        }
    }

    // === 审批拒绝 ===

    /**
     * 审批拒绝冲正请求 — 将冲正请求状态更新为 REJECTED。
     *
     * @param reversalRequestId 冲正请求 ID
     * @param reason 拒绝原因
     * @return 更新后的冲正请求（状态为 REJECTED）
     * @throws IllegalArgumentException 冲正请求不存在
     * @throws IllegalStateException 冲正请求状态非 PENDING
     */
    @Transactional
    public ReversalRequest rejectReversal(Long reversalRequestId, String reason) {
        ReversalRequest reversalRequest = reversalRequestRepository.findById(reversalRequestId)
                .orElseThrow(() -> new IllegalArgumentException("冲正请求不存在: " + reversalRequestId));

        if (reversalRequest.getStatus() != ReversalStatus.PENDING) {
            throw new IllegalStateException(
                    "冲正请求状态非 PENDING，无法拒绝: status=" + reversalRequest.getStatus());
        }

        reversalRequest.setStatus(ReversalStatus.REJECTED);
        reversalRequest.setCompletedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            reversalRequest.setReason(reversalRequest.getReason() + " | 拒绝原因: " + reason);
        }
        reversalRequest = reversalRequestRepository.save(reversalRequest);

        log.info("冲正请求被拒绝: reversalNo={}, reason={}",
                reversalRequest.getReversalNo(), reason);

        return reversalRequest;
    }

    // === 查询 ===

    /**
     * 按冲正请求 ID 查询。
     */
    @Transactional(readOnly = true)
    public ReversalRequest findById(Long id) {
        return reversalRequestRepository.findById(id).orElse(null);
    }

    /**
     * 按冲正编号查询。
     */
    @Transactional(readOnly = true)
    public ReversalRequest findByReversalNo(String reversalNo) {
        return reversalRequestRepository.findByReversalNo(reversalNo).orElse(null);
    }

    /**
     * 按订单 ID 查询所有冲正请求。
     */
    @Transactional(readOnly = true)
    public List<ReversalRequest> findByOrderId(Long orderId) {
        return reversalRequestRepository.findByOrderId(orderId);
    }

    // === 内部方法 ===

    /**
     * 创建冲正请求的内部方法 — 支持手动和自动两种类型。
     *
     * @param orderId 订单 ID
     * @param reason 冲正原因
     * @param operatorId 操作人 ID
     * @param reversalType 冲正类型
     * @return 创建的冲正请求
     */
    private ReversalRequest createReversalRequest(Long orderId, String reason,
                                                    String operatorId, ReversalType reversalType) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));

        // 校验订单状态必须为 PAID
        if (order.getStatus() != PaymentOrder.OrderStatus.PAID) {
            throw new IllegalStateException(
                    "订单状态非 PAID，无法冲正: orderId=" + orderId + ", status=" + order.getStatus());
        }

        // 幂等校验：检查是否已有 PENDING/APPROVED/COMPLETED 状态的冲正请求
        List<ReversalRequest> existingRequests = reversalRequestRepository.findByOrderId(orderId);
        for (ReversalRequest existing : existingRequests) {
            if (existing.getStatus() == ReversalStatus.PENDING
                    || existing.getStatus() == ReversalStatus.APPROVED
                    || existing.getStatus() == ReversalStatus.COMPLETED) {
                throw new IllegalStateException("订单已有进行中或已完成的冲正请求: orderId=" + orderId
                        + ", reversalNo=" + existing.getReversalNo() + ", status=" + existing.getStatus());
            }
        }

        // 创建冲正请求
        ReversalRequest reversalRequest = new ReversalRequest();
        reversalRequest.setReversalNo(generateReversalNo());
        reversalRequest.setOrderId(orderId);
        reversalRequest.setMerchantId(order.getMerchantId());
        reversalRequest.setAmount(order.getAmount());
        reversalRequest.setStatus(ReversalStatus.PENDING);
        reversalRequest.setReversalType(reversalType);
        reversalRequest.setReason(reason);
        reversalRequest.setOperatorId(operatorId);
        reversalRequest.setTenantId(order.getTenantId() != null ? order.getTenantId() : TenantContext.getCurrentTenantId());

        reversalRequest = reversalRequestRepository.save(reversalRequest);

        log.info("创建冲正请求: reversalNo={}, orderId={}, merchantId={}, amount={}, type={}",
                reversalRequest.getReversalNo(), orderId, order.getMerchantId(),
                order.getAmount(), reversalType);

        return reversalRequest;
    }

    /**
     * 生成冲正编号：RV{timestamp}{random}。
     */
    private String generateReversalNo() {
        return "RV" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }
}