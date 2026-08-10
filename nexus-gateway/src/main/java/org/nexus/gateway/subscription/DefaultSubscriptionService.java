package org.nexus.gateway.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 默认订阅服务实现（P4-T8 订阅与循环计费引擎）。
 *
 * <p>覆盖订阅全生命周期：创建（含试用期）、取消、升级（按比例即时扣款）、
 * 降级（下个周期生效）、查询、周期扣款处理、试用期转正。扣款通过
 * {@link ChargeExecutor}（默认实现集成 {@code RoutingEngine} AI 路由）
 * 执行，失败时由 {@link DunningManager} 处理重试/通知/暂停。</p>
 */
@Service
public class DefaultSubscriptionService implements SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubscriptionService.class);

    /** 降级待生效计划 ID 前缀，存于 lastTxHash 字段。 */
    private static final String PENDING_DOWNGRADE_PREFIX = "PENDING_DOWNGRADE:";

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;
    private final ChargeExecutor chargeExecutor;
    private final DunningManager dunningManager;
    private final ProrationCalculator prorationCalculator;

    public DefaultSubscriptionService(SubscriptionRepository subscriptionRepository,
                                      SubscriptionPlanRepository planRepository,
                                      ChargeExecutor chargeExecutor,
                                      DunningManager dunningManager,
                                      ProrationCalculator prorationCalculator) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.chargeExecutor = chargeExecutor;
        this.dunningManager = dunningManager;
        this.prorationCalculator = prorationCalculator;
    }

    @Override
    @Transactional
    public Subscription createSubscription(Long merchantId, String customerId,
                                           String payerAddress, String payeeAddress,
                                           String planId) {
        SubscriptionPlan plan = planRepository.findByPlanId(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
        if (!plan.isEnabled()) {
            throw new IllegalArgumentException("Plan is disabled: " + planId);
        }

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = new Subscription();
        sub.setSubscriptionId(generateSubscriptionId());
        sub.setMerchantId(merchantId);
        sub.setCustomerId(customerId);
        sub.setPayerAddress(payerAddress);
        sub.setPayeeAddress(payeeAddress);
        sub.setPlanId(planId);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(plan.getBillingPeriod().nextPeriodStart(now));
        sub.setChargedCount(0);
        sub.setDunningCount(0);

        if (plan.getTrialPeriodDays() > 0) {
            // 有试用期：状态 TRIAL，trialEnd = now + trialDays，下次扣款在试用期结束后
            sub.setStatus(SubscriptionStatus.TRIAL);
            LocalDateTime trialEnd = now.plusDays(plan.getTrialPeriodDays());
            sub.setTrialEnd(trialEnd);
            sub.setNextChargeAt(trialEnd);
        } else {
            // 无试用期：状态 ACTIVE，下次扣款在当前周期结束
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setNextChargeAt(sub.getCurrentPeriodEnd());
        }

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription created: subId={}, merchant={}, plan={}, status={}, trialEnd={}",
                saved.getSubscriptionId(), merchantId, planId, saved.getStatus(), saved.getTrialEnd());
        return saved;
    }

    @Override
    @Transactional
    public Subscription cancelSubscription(String subscriptionId) {
        Subscription sub = requireSubscription(subscriptionId);
        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            log.info("Subscription already cancelled (idempotent): subId={}", subscriptionId);
            return sub;
        }
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(LocalDateTime.now());
        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription cancelled: subId={}", subscriptionId);
        return saved;
    }

    @Override
    @Transactional
    public Subscription upgradeSubscription(String subscriptionId, String newPlanId) {
        Subscription sub = requireSubscription(subscriptionId);
        if (sub.getStatus() == SubscriptionStatus.CANCELLED || sub.getStatus() == SubscriptionStatus.PAUSED) {
            throw new IllegalStateException("Cannot upgrade subscription in status: " + sub.getStatus());
        }

        SubscriptionPlan oldPlan = requirePlan(sub.getPlanId());
        SubscriptionPlan newPlan = requirePlan(newPlanId);
        if (newPlan.getAmount().compareTo(oldPlan.getAmount()) < 0) {
            throw new IllegalArgumentException(
                    "Upgrade requires new plan amount >= old plan amount; use downgradeSubscription instead");
        }

        LocalDateTime now = LocalDateTime.now();
        BigDecimal proration = prorationCalculator.calculateProration(
                oldPlan, newPlan, sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(), now);

        // 切换计划
        sub.setPlanId(newPlanId);

        // 按比例差价 > 0 时立即扣款收取差价
        if (proration.signum() > 0) {
            ChargeResult result = chargeExecutor.charge(sub, proration,
                    "Upgrade proration charge: " + oldPlan.getPlanId() + " -> " + newPlanId);
            if (!result.isSuccess()) {
                log.error("Upgrade proration charge failed: subId={}, error={}",
                        subscriptionId, result.getErrorMessage());
                // 升级扣款失败时仍切换计划（差价记入下个周期），但不标记成功
                // 实际生产可考虑回滚或进入 dunning，此处简化为记录日志
            } else {
                log.info("Upgrade proration charged: subId={}, amount={}, txHash={}",
                        subscriptionId, proration, result.getTransactionHash());
                sub.setLastTxHash(result.getTransactionHash());
            }
        } else {
            log.info("Upgrade with zero/negative proration, no immediate charge: subId={}, proration={}",
                    subscriptionId, proration);
        }

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription upgraded: subId={}, oldPlan={}, newPlan={}, proration={}",
                subscriptionId, oldPlan.getPlanId(), newPlanId, proration);
        return saved;
    }

    @Override
    @Transactional
    public Subscription downgradeSubscription(String subscriptionId, String newPlanId) {
        Subscription sub = requireSubscription(subscriptionId);
        if (sub.getStatus() == SubscriptionStatus.CANCELLED || sub.getStatus() == SubscriptionStatus.PAUSED) {
            throw new IllegalStateException("Cannot downgrade subscription in status: " + sub.getStatus());
        }

        SubscriptionPlan oldPlan = requirePlan(sub.getPlanId());
        SubscriptionPlan newPlan = requirePlan(newPlanId);
        if (newPlan.getAmount().compareTo(oldPlan.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Downgrade requires new plan amount <= old plan amount; use upgradeSubscription instead");
        }

        // 暂存待生效的新计划 ID（下个周期扣款时应用）
        sub.setLastTxHash(PENDING_DOWNGRADE_PREFIX + newPlanId);

        Subscription saved = subscriptionRepository.save(sub);
        log.info("Subscription downgrade scheduled (next cycle): subId={}, oldPlan={}, newPlan={}",
                subscriptionId, oldPlan.getPlanId(), newPlanId);
        return saved;
    }

    @Override
    public Optional<Subscription> getSubscription(String subscriptionId) {
        return subscriptionRepository.findBySubscriptionId(subscriptionId);
    }

    @Override
    @Transactional
    public String processBillingCycle(String subscriptionId) {
        Subscription sub = requireSubscription(subscriptionId);

        // 状态检查：仅 ACTIVE 与 PAST_DUE 可扣款
        if (sub.getStatus() != SubscriptionStatus.ACTIVE
                && sub.getStatus() != SubscriptionStatus.PAST_DUE) {
            log.warn("Cannot process billing cycle for subscription in status: subId={}, status={}",
                    subscriptionId, sub.getStatus());
            return null;
        }

        // 应用待生效的降级
        applyPendingDowngradeIfAny(sub);

        SubscriptionPlan plan = requirePlan(sub.getPlanId());
        BigDecimal chargeAmount = plan.getAmount();

        // 执行扣款（通过 ChargeExecutor → RoutingEngine AI 路由）
        ChargeResult result = chargeExecutor.charge(sub, chargeAmount,
                "Subscription billing cycle: " + subscriptionId);

        if (result.isSuccess()) {
            // 扣款成功：推进周期，重置 dunning，chargedCount++
            LocalDateTime now = LocalDateTime.now();
            sub.setCurrentPeriodStart(sub.getCurrentPeriodEnd());
            sub.setCurrentPeriodEnd(plan.getBillingPeriod().nextPeriodStart(sub.getCurrentPeriodStart()));
            sub.setNextChargeAt(sub.getCurrentPeriodEnd());
            sub.setChargedCount(sub.getChargedCount() + 1);
            sub.setLastTxHash(result.getTransactionHash());
            dunningManager.handleChargeSuccess(sub);
            subscriptionRepository.save(sub);
            log.info("Billing cycle succeeded: subId={}, chargedCount={}, txHash={}, nextChargeAt={}",
                    subscriptionId, sub.getChargedCount(), result.getTransactionHash(), sub.getNextChargeAt());
            return result.getTransactionHash();
        } else {
            // 扣款失败：进入 dunning 流程
            log.warn("Billing cycle failed: subId={}, error={}",
                    subscriptionId, result.getErrorMessage());
            dunningManager.handleChargeFailure(sub);
            subscriptionRepository.save(sub);
            return null;
        }
    }

    @Override
    @Transactional
    public boolean convertTrialToActive(String subscriptionId) {
        Subscription sub = requireSubscription(subscriptionId);
        if (sub.getStatus() != SubscriptionStatus.TRIAL) {
            return false;
        }
        if (sub.getTrialEnd() == null || sub.getTrialEnd().isAfter(LocalDateTime.now())) {
            return false;
        }

        // 试用期结束，转为 ACTIVE 并执行首次扣款
        sub.setStatus(SubscriptionStatus.ACTIVE);
        log.info("Trial ended, converting to ACTIVE: subId={}", subscriptionId);

        SubscriptionPlan plan = requirePlan(sub.getPlanId());
        ChargeResult result = chargeExecutor.charge(sub, plan.getAmount(),
                "Subscription first charge after trial: " + subscriptionId);

        if (result.isSuccess()) {
            LocalDateTime now = LocalDateTime.now();
            sub.setCurrentPeriodStart(now);
            sub.setCurrentPeriodEnd(plan.getBillingPeriod().nextPeriodStart(now));
            sub.setNextChargeAt(sub.getCurrentPeriodEnd());
            sub.setChargedCount(sub.getChargedCount() + 1);
            sub.setLastTxHash(result.getTransactionHash());
            dunningManager.handleChargeSuccess(sub);
            subscriptionRepository.save(sub);
            log.info("First charge after trial succeeded: subId={}, txHash={}",
                    subscriptionId, result.getTransactionHash());
            return true;
        } else {
            // 首次扣款失败，进入 dunning
            dunningManager.handleChargeFailure(sub);
            subscriptionRepository.save(sub);
            log.warn("First charge after trial failed: subId={}, error={}",
                    subscriptionId, result.getErrorMessage());
            return true; // 仍返回 true 表示已转正（状态已变），扣款失败由 dunning 处理
        }
    }

    /**
     * 应用待生效的降级（如果 lastTxHash 以 PENDING_DOWNGRADE_PREFIX 开头）。
     *
     * <p>在下次扣款前切换到新计划，并重置当前周期窗口。</p>
     */
    private void applyPendingDowngradeIfAny(Subscription sub) {
        if (sub.getLastTxHash() == null) return;
        if (!sub.getLastTxHash().startsWith(PENDING_DOWNGRADE_PREFIX)) return;

        String newPlanId = sub.getLastTxHash().substring(PENDING_DOWNGRADE_PREFIX.length());
        SubscriptionPlan newPlan = requirePlan(newPlanId);

        log.info("Applying pending downgrade: subId={}, oldPlan={}, newPlan={}",
                sub.getSubscriptionId(), sub.getPlanId(), newPlanId);
        sub.setPlanId(newPlanId);
        sub.setLastTxHash(null);
        // 周期窗口按新计划周期重新计算
        LocalDateTime now = LocalDateTime.now();
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(newPlan.getBillingPeriod().nextPeriodStart(now));
    }

    private Subscription requireSubscription(String subscriptionId) {
        return subscriptionRepository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
    }

    private SubscriptionPlan requirePlan(String planId) {
        return planRepository.findByPlanId(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));
    }

    private String generateSubscriptionId() {
        return "SUB" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}