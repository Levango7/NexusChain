package org.nexus.gateway.escrow;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 担保交易服务实现 — 担保交易完整生命周期管理。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>创建担保交易（CREATED）</li>
 *   <li>买家付款冻结资金（CREATED→FUNDED），通过 AccountService.freeze()</li>
 *   <li>确认收货释放资金给商户（FUNDED→CONFIRMED→RELEASED），通过 AccountService.unfreeze() + deposit()</li>
 *   <li>退款退回买家（FUNDED→REFUNDED），通过 AccountService.unfreeze()</li>
 * </ul>
 *
 * <p>所有资金操作通过 AccountService 执行，不直接操作 Repository。
 * 使用 @Version 乐观锁保证并发安全，状态变更发布 EscrowStatusChangedEvent。</p>
 */
@Service
public class EscrowServiceImpl implements EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowServiceImpl.class);

    /** 乐观锁冲突时最大重试次数 */
    private static final int MAX_RETRY = 1;

    private final EscrowTransactionRepository escrowRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public EscrowServiceImpl(EscrowTransactionRepository escrowRepository,
                              AccountService accountService,
                              ApplicationEventPublisher eventPublisher) {
        this.escrowRepository = escrowRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    // === 创建担保交易 ===

    @Override
    @Transactional
    public EscrowTransaction createEscrow(Long merchantId, Long orderId, BigDecimal amount,
                                           String buyerAddress, Integer autoConfirmDays) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户 ID 不能为空");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("订单 ID 不能为空");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("担保金额必须大于 0");
        }
        if (buyerAddress == null || buyerAddress.isBlank()) {
            throw new IllegalArgumentException("买家地址不能为空");
        }

        // 幂等检查：同一订单不可重复创建担保交易
        escrowRepository.findByOrderId(orderId).ifPresent(existing -> {
            throw new IllegalStateException("订单已存在担保交易: orderId=" + orderId + ", escrowNo=" + existing.getEscrowNo());
        });

        // 确保商户拥有 FROZEN 类型账户（担保冻结账户）
        MerchantAccount frozenAccount = accountService.getOrCreateAccount(merchantId, AccountType.FROZEN);

        EscrowTransaction escrow = new EscrowTransaction();
        escrow.setEscrowNo(generateEscrowNo());
        escrow.setMerchantId(merchantId);
        escrow.setOrderId(orderId);
        escrow.setAmount(amount);
        escrow.setStatus(EscrowStatus.CREATED);
        escrow.setBuyerAddress(buyerAddress);
        escrow.setEscrowAccountId(frozenAccount.getAccountId());
        escrow.setAutoConfirmDays(autoConfirmDays != null ? autoConfirmDays : 7);
        escrow.setTenantId(TenantContext.getCurrentTenantId());

        escrow = escrowRepository.save(escrow);
        log.info("创建担保交易: escrowNo={}, merchantId={}, orderId={}, amount={}",
                escrow.getEscrowNo(), merchantId, orderId, amount);

        publishStatusChangedEvent(escrow, null, EscrowStatus.CREATED);
        return escrow;
    }

    // === 买家付款 ===

    @Override
    @Transactional
    public EscrowTransaction fundEscrow(String escrowNo, String payerAddress) {
        if (escrowNo == null || escrowNo.isBlank()) {
            throw new IllegalArgumentException("担保交易编号不能为空");
        }

        return executeWithRetry(() -> {
            EscrowTransaction escrow = escrowRepository.findByEscrowNo(escrowNo)
                    .orElseThrow(() -> new IllegalArgumentException("担保交易不存在: " + escrowNo));

            if (escrow.getStatus() != EscrowStatus.CREATED) {
                throw new IllegalStateException(
                        "担保交易状态不允许付款: 当前状态=" + escrow.getStatus() + ", 期望状态=CREATED");
            }

            EscrowStatus fromStatus = escrow.getStatus();

            // 冻结买家资金到担保账户（使用商户的 FROZEN 账户）
            accountService.freeze(escrow.getMerchantId(), escrow.getAmount(),
                    "ESCROW_FREEZE:" + escrowNo);

            escrow.setStatus(EscrowStatus.FUNDED);
            escrow.setFundedAt(LocalDateTime.now());
            escrow = escrowRepository.save(escrow);

            log.info("担保交易付款成功: escrowNo={}, merchantId={}, amount={}",
                    escrowNo, escrow.getMerchantId(), escrow.getAmount());

            publishStatusChangedEvent(escrow, fromStatus, EscrowStatus.FUNDED);
            return escrow;
        });
    }

    // === 确认收货 ===

    @Override
    @Transactional
    public EscrowTransaction confirmEscrow(String escrowNo) {
        if (escrowNo == null || escrowNo.isBlank()) {
            throw new IllegalArgumentException("担保交易编号不能为空");
        }

        return executeWithRetry(() -> {
            EscrowTransaction escrow = escrowRepository.findByEscrowNo(escrowNo)
                    .orElseThrow(() -> new IllegalArgumentException("担保交易不存在: " + escrowNo));

            if (escrow.getStatus() != EscrowStatus.FUNDED) {
                throw new IllegalStateException(
                        "担保交易状态不允许确认收货: 当前状态=" + escrow.getStatus() + ", 期望状态=FUNDED");
            }

            EscrowStatus fromStatus = escrow.getStatus();

            // 1. 解冻担保账户中的资金
            accountService.unfreeze(escrow.getMerchantId(), escrow.getAmount());

            // 2. 将解冻资金转入商户余额账户
            accountService.deposit(escrow.getMerchantId(), escrow.getAmount(),
                    "ESCROW_RELEASE:" + escrowNo);

            // 3. 更新担保交易状态：FUNDED → CONFIRMED → RELEASED
            escrow.setStatus(EscrowStatus.CONFIRMED);
            escrow.setConfirmedAt(LocalDateTime.now());
            escrow = escrowRepository.save(escrow);

            publishStatusChangedEvent(escrow, fromStatus, EscrowStatus.CONFIRMED);

            // 再转为 RELEASED
            EscrowStatus confirmedFrom = escrow.getStatus();
            escrow.setStatus(EscrowStatus.RELEASED);
            escrow.setReleasedAt(LocalDateTime.now());
            escrow = escrowRepository.save(escrow);

            log.info("担保交易确认收货成功: escrowNo={}, merchantId={}, amount={}",
                    escrowNo, escrow.getMerchantId(), escrow.getAmount());

            publishStatusChangedEvent(escrow, confirmedFrom, EscrowStatus.RELEASED);
            return escrow;
        });
    }

    // === 退款 ===

    @Override
    @Transactional
    public EscrowTransaction refundEscrow(String escrowNo) {
        if (escrowNo == null || escrowNo.isBlank()) {
            throw new IllegalArgumentException("担保交易编号不能为空");
        }

        return executeWithRetry(() -> {
            EscrowTransaction escrow = escrowRepository.findByEscrowNo(escrowNo)
                    .orElseThrow(() -> new IllegalArgumentException("担保交易不存在: " + escrowNo));

            if (escrow.getStatus() != EscrowStatus.FUNDED) {
                throw new IllegalStateException(
                        "担保交易状态不允许退款: 当前状态=" + escrow.getStatus() + ", 期望状态=FUNDED");
            }

            EscrowStatus fromStatus = escrow.getStatus();

            // 解冻担保账户中的资金退回买家（资金回到商户 BALANCE 账户，由商户退给买家）
            accountService.unfreeze(escrow.getMerchantId(), escrow.getAmount());

            escrow.setStatus(EscrowStatus.REFUNDED);
            escrow = escrowRepository.save(escrow);

            log.info("担保交易退款成功: escrowNo={}, merchantId={}, amount={}",
                    escrowNo, escrow.getMerchantId(), escrow.getAmount());

            publishStatusChangedEvent(escrow, fromStatus, EscrowStatus.REFUNDED);
            return escrow;
        });
    }

    // === 查询担保交易状态 ===

    @Override
    @Transactional(readOnly = true)
    public EscrowTransaction getEscrowStatus(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("订单 ID 不能为空");
        }
        return escrowRepository.findByOrderId(orderId).orElse(null);
    }

    // === 内部方法 ===

    /**
     * 生成担保交易编号：EC{timestamp}{random}。
     */
    private String generateEscrowNo() {
        return "EC" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 发布担保交易状态变更事件。
     */
    private void publishStatusChangedEvent(EscrowTransaction escrow, EscrowStatus fromStatus, EscrowStatus toStatus) {
        EscrowStatusChangedEvent event = new EscrowStatusChangedEvent(
                this, escrow.getOrderId(), null, escrow.getMerchantId(),
                escrow.getEscrowNo(), fromStatus, toStatus);
        eventPublisher.publishEvent(event);
    }

    /**
     * 带乐观锁重试的操作执行器。
     */
    private <T> T executeWithRetry(java.util.function.Supplier<T> action) {
        int attempts = 0;
        while (true) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                attempts++;
                if (attempts > MAX_RETRY) {
                    log.error("乐观锁冲突，重试 {} 次后仍失败", MAX_RETRY, e);
                    throw e;
                }
                log.warn("乐观锁冲突，正在重试 (attempt={})", attempts);
            }
        }
    }
}