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
 * 预授权服务实现 — 预授权完整生命周期管理。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>创建预授权冻结金额（AUTHORIZED），通过 AccountService.freeze()</li>
 *   <li>扣款（AUTHORIZED→CAPTURED），通过 AccountService.unfreeze() + deposit()，支持部分扣款</li>
 *   <li>撤销预授权（AUTHORIZED→VOIDED），通过 AccountService.unfreeze() 释放全部冻结金额</li>
 * </ul>
 *
 * <p>所有资金操作通过 AccountService 执行，不直接操作 Repository。
 * 使用 @Version 乐观锁保证并发安全，状态变更发布 PreAuthStatusChangedEvent。</p>
 */
@Service
public class PreAuthServiceImpl implements PreAuthService {

    private static final Logger log = LoggerFactory.getLogger(PreAuthServiceImpl.class);

    /** 乐观锁冲突时最大重试次数 */
    private static final int MAX_RETRY = 1;

    private final PreAuthTransactionRepository preauthRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public PreAuthServiceImpl(PreAuthTransactionRepository preauthRepository,
                               AccountService accountService,
                               ApplicationEventPublisher eventPublisher) {
        this.preauthRepository = preauthRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    // === 创建预授权 ===

    @Override
    @Transactional
    public PreAuthTransaction createPreAuth(Long merchantId, Long orderId, BigDecimal freezeAmount,
                                             Integer autoReleaseDays) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户 ID 不能为空");
        }
        if (freezeAmount == null || freezeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("冻结金额必须大于 0");
        }

        // 确保商户拥有 FROZEN 类型账户
        MerchantAccount frozenAccount = accountService.getOrCreateAccount(merchantId, AccountType.FROZEN);

        // 冻结金额
        accountService.freeze(merchantId, freezeAmount, "PREAUTH_FREEZE");

        PreAuthTransaction preauth = new PreAuthTransaction();
        preauth.setPreauthNo(generatePreauthNo());
        preauth.setMerchantId(merchantId);
        preauth.setOrderId(orderId);
        preauth.setFreezeAmount(freezeAmount);
        preauth.setCaptureAmount(BigDecimal.ZERO);
        preauth.setStatus(PreAuthStatus.AUTHORIZED);
        preauth.setFrozenAccountId(frozenAccount.getAccountId());
        preauth.setAutoReleaseDays(autoReleaseDays != null ? autoReleaseDays : 3);
        preauth.setAuthorizedAt(LocalDateTime.now());
        preauth.setTenantId(TenantContext.getCurrentTenantId());

        preauth = preauthRepository.save(preauth);
        log.info("创建预授权: preauthNo={}, merchantId={}, freezeAmount={}",
                preauth.getPreauthNo(), merchantId, freezeAmount);

        publishStatusChangedEvent(preauth, null, PreAuthStatus.AUTHORIZED);
        return preauth;
    }

    // === 扣款 ===

    @Override
    @Transactional
    public PreAuthTransaction capturePreAuth(String preauthNo, BigDecimal captureAmount) {
        if (preauthNo == null || preauthNo.isBlank()) {
            throw new IllegalArgumentException("预授权编号不能为空");
        }
        if (captureAmount == null || captureAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("扣款金额必须大于 0");
        }

        return executeWithRetry(() -> {
            PreAuthTransaction preauth = preauthRepository.findByPreauthNo(preauthNo)
                    .orElseThrow(() -> new IllegalArgumentException("预授权不存在: " + preauthNo));

            if (preauth.getStatus() != PreAuthStatus.AUTHORIZED) {
                throw new IllegalStateException(
                        "预授权状态不允许扣款: 当前状态=" + preauth.getStatus() + ", 期望状态=AUTHORIZED");
            }

            // 校验扣款金额 ≤ 冻结金额
            if (captureAmount.compareTo(preauth.getFreezeAmount()) > 0) {
                throw new IllegalArgumentException(
                        "扣款金额超过冻结金额: captureAmount=" + captureAmount + ", freezeAmount=" + preauth.getFreezeAmount());
            }

            PreAuthStatus fromStatus = preauth.getStatus();

            // 1. 解冻全部冻结金额
            accountService.unfreeze(preauth.getMerchantId(), preauth.getFreezeAmount());

            // 2. 将扣款金额转入商户余额账户
            if (captureAmount.compareTo(BigDecimal.ZERO) > 0) {
                accountService.deposit(preauth.getMerchantId(), captureAmount,
                        "PREAUTH_CAPTURE:" + preauthNo);
            }

            // 3. 剩余金额（freezeAmount - captureAmount）已通过 unfreeze 回到商户 BALANCE 账户

            preauth.setCaptureAmount(captureAmount);
            preauth.setStatus(PreAuthStatus.CAPTURED);
            preauth.setCapturedAt(LocalDateTime.now());
            preauth = preauthRepository.save(preauth);

            BigDecimal releasedAmount = preauth.getFreezeAmount().subtract(captureAmount);
            log.info("预授权扣款成功: preauthNo={}, captureAmount={}, releasedAmount={}",
                    preauthNo, captureAmount, releasedAmount);

            publishStatusChangedEvent(preauth, fromStatus, PreAuthStatus.CAPTURED);
            return preauth;
        });
    }

    // === 撤销预授权 ===

    @Override
    @Transactional
    public PreAuthTransaction voidPreAuth(String preauthNo) {
        if (preauthNo == null || preauthNo.isBlank()) {
            throw new IllegalArgumentException("预授权编号不能为空");
        }

        return executeWithRetry(() -> {
            PreAuthTransaction preauth = preauthRepository.findByPreauthNo(preauthNo)
                    .orElseThrow(() -> new IllegalArgumentException("预授权不存在: " + preauthNo));

            if (preauth.getStatus() != PreAuthStatus.AUTHORIZED) {
                throw new IllegalStateException(
                        "预授权状态不允许撤销: 当前状态=" + preauth.getStatus() + ", 期望状态=AUTHORIZED");
            }

            PreAuthStatus fromStatus = preauth.getStatus();

            // 解冻全部冻结金额释放回原账户
            accountService.unfreeze(preauth.getMerchantId(), preauth.getFreezeAmount());

            preauth.setStatus(PreAuthStatus.VOIDED);
            preauth.setVoidedAt(LocalDateTime.now());
            preauth = preauthRepository.save(preauth);

            log.info("预授权撤销成功: preauthNo={}, releasedAmount={}",
                    preauthNo, preauth.getFreezeAmount());

            publishStatusChangedEvent(preauth, fromStatus, PreAuthStatus.VOIDED);
            return preauth;
        });
    }

    // === 查询预授权状态 ===

    @Override
    @Transactional(readOnly = true)
    public PreAuthTransaction getPreAuthStatus(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("订单 ID 不能为空");
        }
        return preauthRepository.findByOrderId(orderId).orElse(null);
    }

    // === 内部方法 ===

    /**
     * 生成预授权编号：PA{timestamp}{random}。
     */
    private String generatePreauthNo() {
        return "PA" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 发布预授权状态变更事件。
     */
    private void publishStatusChangedEvent(PreAuthTransaction preauth, PreAuthStatus fromStatus, PreAuthStatus toStatus) {
        PreAuthStatusChangedEvent event = new PreAuthStatusChangedEvent(
                this, preauth.getOrderId(), null, preauth.getMerchantId(),
                preauth.getPreauthNo(), fromStatus, toStatus);
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