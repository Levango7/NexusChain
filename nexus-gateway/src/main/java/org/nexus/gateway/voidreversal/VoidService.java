package org.nexus.gateway.voidreversal;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.TransactionDirection;
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
 * 当日撤销服务 — 处理当日已支付交易的撤销请求。
 *
 * <p>撤销（VOID）是交易级全额回滚操作，将当日已支付交易恢复原状。
 * 与退款（REFUND）的区别：撤销是交易级回滚（恢复原状），退款是资金级退回（可能部分）。</p>
 *
 * <p>核心业务规则：</p>
 * <ul>
 *   <li><b>撤销窗口</b>：只能撤销当日交易（T+1 日 24:00 前），超过窗口只能冲正</li>
 *   <li><b>撤销与退款互斥</b>：已有退款的订单不能撤销</li>
 *   <li><b>审批流程</b>：PENDING → APPROVED → COMPLETED（或 REJECTED/FAILED）</li>
 *   <li><b>余额联动</b>：审批通过后调用 AccountService 扣减商户余额（反向操作）</li>
 *   <li><b>订单状态</b>：撤销成功后订单状态变为 VOIDED（终态）</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁保证撤销请求的并发安全，
 * 撤销请求与余额变更在同一数据库事务中完成。</p>
 */
@Service
public class VoidService {

    private static final Logger log = LoggerFactory.getLogger(VoidService.class);

    private final VoidRequestRepository voidRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public VoidService(VoidRequestRepository voidRequestRepository,
                       PaymentOrderRepository paymentOrderRepository,
                       AccountService accountService,
                       ApplicationEventPublisher eventPublisher) {
        this.voidRequestRepository = voidRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    // === 创建撤销请求 ===

    /**
     * 创建撤销请求 — 校验订单状态和撤销窗口后创建 PENDING 状态的撤销请求。
     *
     * @param orderId 订单 ID
     * @param reason 撤销原因（可为空，最长 256 字符）
     * @param operatorId 操作人 ID
     * @return 创建的撤销请求
     * @throws IllegalArgumentException 订单不存在
     * @throws IllegalStateException 订单状态非 PAID、超过撤销窗口、已有退款、已撤销
     */
    @Transactional
    public VoidRequest requestVoid(Long orderId, String reason, String operatorId) {
        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));

        // 校验撤销与退款互斥：已有退款的订单不能撤销（先于 PAID 状态校验，给出明确错误信息）
        if (order.getStatus() == PaymentOrder.OrderStatus.REFUND_PENDING
                || order.getStatus() == PaymentOrder.OrderStatus.REFUNDED) {
            throw new IllegalStateException("已有退款的订单不能撤销: orderId=" + orderId);
        }

        // 校验订单状态必须为 PAID
        if (order.getStatus() != PaymentOrder.OrderStatus.PAID) {
            throw new IllegalStateException(
                    "订单状态非 PAID，无法撤销: orderId=" + orderId + ", status=" + order.getStatus());
        }

        // 校验撤销窗口：T+1 日 24:00 前
        assertVoidWindow(order);

        // 幂等校验：检查是否已有 PENDING/APPROVED/COMPLETED 状态的撤销请求
        List<VoidRequest> existingRequests = voidRequestRepository.findByOrderId(orderId);
        for (VoidRequest existing : existingRequests) {
            if (existing.getStatus() == VoidStatus.PENDING
                    || existing.getStatus() == VoidStatus.APPROVED
                    || existing.getStatus() == VoidStatus.COMPLETED) {
                throw new IllegalStateException("订单已有进行中或已完成的撤销请求: orderId=" + orderId
                        + ", voidNo=" + existing.getVoidNo() + ", status=" + existing.getStatus());
            }
        }

        // 创建撤销请求
        VoidRequest voidRequest = new VoidRequest();
        voidRequest.setVoidNo(generateVoidNo());
        voidRequest.setOrderId(orderId);
        voidRequest.setMerchantId(order.getMerchantId());
        voidRequest.setAmount(order.getAmount());
        voidRequest.setStatus(VoidStatus.PENDING);
        voidRequest.setReason(reason);
        voidRequest.setOperatorId(operatorId);
        voidRequest.setTenantId(order.getTenantId() != null ? order.getTenantId() : TenantContext.getCurrentTenantId());

        voidRequest = voidRequestRepository.save(voidRequest);

        log.info("创建撤销请求: voidNo={}, orderId={}, merchantId={}, amount={}",
                voidRequest.getVoidNo(), orderId, order.getMerchantId(), order.getAmount());

        return voidRequest;
    }

    // === 审批通过 ===

    /**
     * 审批通过撤销请求 — 执行撤销操作：扣减商户余额、更新订单状态为 VOIDED。
     *
     * <p>撤销的余额联动逻辑：撤销是支付的反向操作，支付时商户余额增加了 amount，
     * 撤销时需要从商户 BALANCE 账户扣减 amount（DEBIT），并记录 VOID_REVERSE 类型流水。</p>
     *
     * @param voidRequestId 撤销请求 ID
     * @return 更新后的撤销请求（状态为 COMPLETED 或 FAILED）
     * @throws IllegalArgumentException 撤销请求不存在
     * @throws IllegalStateException 撤销请求状态非 PENDING/APPROVED
     */
    @Transactional
    public VoidRequest approveVoid(Long voidRequestId) {
        VoidRequest voidRequest = voidRequestRepository.findById(voidRequestId)
                .orElseThrow(() -> new IllegalArgumentException("撤销请求不存在: " + voidRequestId));

        if (voidRequest.getStatus() != VoidStatus.PENDING
                && voidRequest.getStatus() != VoidStatus.APPROVED) {
            throw new IllegalStateException(
                    "撤销请求状态非 PENDING/APPROVED，无法审批: status=" + voidRequest.getStatus());
        }

        Long orderIdForLookup = voidRequest.getOrderId();
        PaymentOrder order = paymentOrderRepository.findById(orderIdForLookup)
                .orElseThrow(() -> new IllegalArgumentException(
                        "关联订单不存在: " + orderIdForLookup));

        try {
            // 再次校验订单状态（防止并发变更）
            if (order.getStatus() != PaymentOrder.OrderStatus.PAID) {
                voidRequest.setStatus(VoidStatus.FAILED);
                voidRequest.setCompletedAt(LocalDateTime.now());
                voidRequest = voidRequestRepository.save(voidRequest);
                log.warn("撤销失败：订单状态已变更: orderId={}, status={}",
                        order.getId(), order.getStatus());
                return voidRequest;
            }

            // 再次校验撤销窗口
            assertVoidWindow(order);

            // 调用 AccountService 扣减商户余额（反向操作）
            // 撤销 = 支付的反向操作，支付时 CREDIT 余额增加，撤销时 DEBIT 余额减少
            MerchantAccount account = accountService.withdraw(
                    voidRequest.getMerchantId(), voidRequest.getAmount());

            // 更新订单状态为 VOIDED
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.VOIDED);
            paymentOrderRepository.save(order);

            // 更新撤销请求状态
            voidRequest.setStatus(VoidStatus.COMPLETED);
            voidRequest.setCompletedAt(LocalDateTime.now());
            voidRequest = voidRequestRepository.save(voidRequest);

            // 发布撤销完成事件
            OrderVoidedEvent event = new OrderVoidedEvent(
                    this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                    voidRequest.getVoidNo(), voidRequest.getAmount(),
                    account != null ? account.getBalance() : BigDecimal.ZERO);
            eventPublisher.publishEvent(event);

            log.info("撤销审批通过并完成: voidNo={}, orderId={}, balanceAfter={}",
                    voidRequest.getVoidNo(), order.getId(),
                    account != null ? account.getBalance() : "N/A");

            return voidRequest;

        } catch (IllegalStateException e) {
            // 余额不足等业务异常 → 标记为 FAILED
            voidRequest.setStatus(VoidStatus.FAILED);
            voidRequest.setCompletedAt(LocalDateTime.now());
            voidRequest = voidRequestRepository.save(voidRequest);
            log.error("撤销执行失败: voidNo={}, orderId={}, error={}",
                    voidRequest.getVoidNo(), voidRequest.getOrderId(), e.getMessage());
            return voidRequest;
        }
    }

    // === 审批拒绝 ===

    /**
     * 审批拒绝撤销请求 — 将撤销请求状态更新为 REJECTED。
     *
     * @param voidRequestId 撤销请求 ID
     * @param reason 拒绝原因
     * @return 更新后的撤销请求（状态为 REJECTED）
     * @throws IllegalArgumentException 撤销请求不存在
     * @throws IllegalStateException 撤销请求状态非 PENDING
     */
    @Transactional
    public VoidRequest rejectVoid(Long voidRequestId, String reason) {
        VoidRequest voidRequest = voidRequestRepository.findById(voidRequestId)
                .orElseThrow(() -> new IllegalArgumentException("撤销请求不存在: " + voidRequestId));

        if (voidRequest.getStatus() != VoidStatus.PENDING) {
            throw new IllegalStateException(
                    "撤销请求状态非 PENDING，无法拒绝: status=" + voidRequest.getStatus());
        }

        voidRequest.setStatus(VoidStatus.REJECTED);
        voidRequest.setCompletedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            voidRequest.setReason(voidRequest.getReason() + " | 拒绝原因: " + reason);
        }
        voidRequest = voidRequestRepository.save(voidRequest);

        log.info("撤销请求被拒绝: voidNo={}, reason={}", voidRequest.getVoidNo(), reason);

        return voidRequest;
    }

    // === 查询 ===

    /**
     * 按撤销请求 ID 查询。
     */
    @Transactional(readOnly = true)
    public VoidRequest findById(Long id) {
        return voidRequestRepository.findById(id).orElse(null);
    }

    /**
     * 按撤销编号查询。
     */
    @Transactional(readOnly = true)
    public VoidRequest findByVoidNo(String voidNo) {
        return voidRequestRepository.findByVoidNo(voidNo).orElse(null);
    }

    /**
     * 按订单 ID 查询所有撤销请求。
     */
    @Transactional(readOnly = true)
    public List<VoidRequest> findByOrderId(Long orderId) {
        return voidRequestRepository.findByOrderId(orderId);
    }

    // === 内部方法 ===

    /**
     * 校验撤销窗口 — 只能撤销当日交易（T+1 日 24:00 前）。
     *
     * <p>撤销窗口规则：订单支付日（paidAt）的次日 24:00 前可以撤销。
     * 例如：订单在 9月25日 15:00 支付，则 9月26日 24:00 前均可撤销，
     * 9月27日 00:00 起只能冲正。</p>
     *
     * @param order 支付订单
     * @throws IllegalStateException 超过撤销窗口
     */
    private void assertVoidWindow(PaymentOrder order) {
        if (order.getPaidAt() == null) {
            throw new IllegalStateException("订单未支付，无法撤销: orderId=" + order.getId());
        }

        LocalDate paidDate = order.getPaidAt().toLocalDate();
        LocalDate voidDeadline = paidDate.plusDays(1); // T+1 日
        LocalDateTime voidCutoff = voidDeadline.atTime(23, 59, 59); // T+1 日 24:00

        if (LocalDateTime.now().isAfter(voidCutoff)) {
            throw new IllegalStateException(
                    "超过撤销窗口，请发起冲正: orderId=" + order.getId()
                            + ", paidAt=" + order.getPaidAt()
                            + ", voidCutoff=" + voidCutoff);
        }
    }

    /**
     * 生成撤销编号：VD{timestamp}{random}。
     */
    private String generateVoidNo() {
        return "VD" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }
}