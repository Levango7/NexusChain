package org.nexus.gateway.subscription;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

    /**
     * 单次扫描耗时的告警阈值 = {@code @SchedulerLock(lockAtMostFor = "PT30M")} 的 2/3。
     * 超过它就说明"锁可能在扫描跑完之前先过期"，届时第二副本可进入同一次扫描 ——
     * 这正是要提前看见的信号，而不是等它变成重复扣款。
     */
    private static final long SWEEP_WARN_AFTER_MS = 20 * 60 * 1000L;

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final SubscriptionProperties properties;

    public BillingCycleScheduler(SubscriptionRepository subscriptionRepository,
                                 SubscriptionService subscriptionService,
                                 SubscriptionProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.properties = properties;
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
    @SchedulerLock(name = "billingCycleSweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void processDueSubscriptions() {
        long startNanos = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        log.debug("Billing cycle scheduler triggered at {}", now);

        // 1. 处理试用期到期转正
        int trialConverted = processTrialConversions(now);

        // 2. 处理到期扣款
        int billingProcessed = processDueBillingCycles(now);

        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startNanos);
        if (trialConverted > 0 || billingProcessed > 0) {
            log.info("Billing cycle completed: trialConverted={}, billingProcessed={}, elapsedMs={}",
                    trialConverted, billingProcessed, elapsedMs);
        }
        // 锁顶穿预警：本次扫描已经接近 @SchedulerLock 的 lockAtMostFor(30 分钟)。
        // 顶穿后锁提前释放、第二副本可以进入同一次扫描，而本副本的账期推进要到
        // 提交才可见 ⇒ 重复扣款窗口从"推断"变成"现实"。宁可这里吵，也不要静默。
        if (elapsedMs > SWEEP_WARN_AFTER_MS) {
            log.warn("Billing sweep approaching lock expiry: elapsedMs={} > warnAfterMs={} "
                            + "(lockAtMostFor=PT30M); 请调低 nexus.subscription.billing-sweep-batch-limit "
                            + "或缩短扣款链超时，否则同一次扫描可能跨副本重复扣款: trialConverted={}, billingProcessed={}",
                    elapsedMs, SWEEP_WARN_AFTER_MS, trialConverted, billingProcessed);
        }
    }

    /**
     * 按配置上限截断，并在截断时打 WARN（剩余顺延到下一轮）。
     *
     * <p>{@code limit <= 0} 表示<b>显式关闭限流</b>（全量处理）—— 不能把它当成
     * "处理 0 条"，那等于静默丢单。</p>
     *
     * <p>未超限时返回原列表本身，不给调用方引入新的空/异常分支。</p>
     */
    private List<Subscription> capSweep(List<Subscription> candidates, String phase) {
        int limit = properties.getBillingSweepBatchLimit();
        if (limit <= 0 || candidates.size() <= limit) {
            return candidates;
        }
        log.warn("Billing sweep truncated: phase={}, candidates={}, limit={}, deferred={} —— "
                        + "顺延到下一轮扫描（每小时一次）",
                phase, candidates.size(), limit, candidates.size() - limit);
        return candidates.subList(0, limit);
    }

    /**
     * 处理试用期到期转正。
     *
     * @param now 当前时间
     * @return 转正的订阅数
     */
    private int processTrialConversions(LocalDateTime now) {
        List<Subscription> trialSubs = capSweep(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                SubscriptionStatus.TRIAL, now), "trialConversion");
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
        List<Subscription> dueSubs = capSweep(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), now), "billingCycle");
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