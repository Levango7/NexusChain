package org.nexus.gateway.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 周期扣款调度器（P4-T8 订阅与循环计费引擎）。
 *
 * <p>使用 Spring {@code @Scheduled} cron 定时扫描到期订阅，对每个到期订阅
 * 调用 {@link SubscriptionService#processBillingCycle} 或
 * {@link SubscriptionService#convertTrialToActive}。</p>
 *
 * <p>调度逻辑：</p>
 * <ol>
 *   <li>扫描 TRIAL 状态且 trialEnd 已到的订阅，转为 ACTIVE 并执行首次扣款</li>
 *   <li>扫描 ACTIVE/PAST_DUE 状态且 nextChargeAt 已到的订阅，执行周期扣款</li>
 * </ol>
 *
 * <p>cron 表达式由 {@code nexus.subscription.billing-cycle-cron} 配置，默认
 * 每小时整点执行（{@code 0 0 * * * *}）。注意：Spring 6 的 cron 支持 6 字段
 * （含秒），与标准 Unix cron 5 字段不同。</p>
 */
@Component
public class BillingCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingCycleScheduler.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    public BillingCycleScheduler(SubscriptionRepository subscriptionRepository,
                                 SubscriptionService subscriptionService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * 定时处理到期订阅的扣款。
     *
     * <p>cron 表达式通过 SpEL 从 {@link SubscriptionProperties} 读取，默认
     * {@code 0 0 * * * *}（每小时整点）。注意：本方法使用固定 cron
     * {@code 0 0 * * * *} 而非动态读取，因为 {@code @Scheduled} 的 cron
     * 属性必须是编译时常量或 SpEL 字面量，无法直接引用配置属性。
     * 动态 cron 可通过 {@code SchedulingConfigurer} 实现，此处简化为固定值，
     * 与配置中的默认值一致。</p>
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void processDueSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("Billing cycle scheduler triggered at {}", now);

        // 1. 处理试用期到期转正
        int trialConverted = processTrialConversions(now);

        // 2. 处理到期扣款
        int billingProcessed = processDueBillingCycles(now);

        if (trialConverted > 0 || billingProcessed > 0) {
            log.info("Billing cycle completed: trialConverted={}, billingProcessed={}",
                    trialConverted, billingProcessed);
        }
    }

    /**
     * 处理试用期到期转正。
     *
     * @param now 当前时间
     * @return 转正的订阅数
     */
    private int processTrialConversions(LocalDateTime now) {
        List<Subscription> trialSubs = subscriptionRepository.findByStatusAndNextChargeAtBefore(
                SubscriptionStatus.TRIAL, now);
        int converted = 0;
        for (Subscription sub : trialSubs) {
            try {
                if (subscriptionService.convertTrialToActive(sub.getSubscriptionId())) {
                    converted++;
                }
            } catch (RuntimeException e) {
                log.error("Trial conversion failed: subId={}, error={}",
                        sub.getSubscriptionId(), e.getMessage());
            }
        }
        return converted;
    }

    /**
     * 处理到期扣款。
     *
     * @param now 当前时间
     * @return 处理的订阅数（含成功与失败）
     */
    private int processDueBillingCycles(LocalDateTime now) {
        List<Subscription> dueSubs = subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), now);
        int processed = 0;
        for (Subscription sub : dueSubs) {
            try {
                subscriptionService.processBillingCycle(sub.getSubscriptionId());
                processed++;
            } catch (RuntimeException e) {
                log.error("Billing cycle failed: subId={}, error={}",
                        sub.getSubscriptionId(), e.getMessage());
            }
        }
        return processed;
    }

    /**
     * 手动触发一次扣款扫描（供测试与管理端点使用）。
     *
     * @return 处理的订阅总数（转正 + 扣款）
     */
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now();
        return processTrialConversions(now) + processDueBillingCycles(now);
    }
}