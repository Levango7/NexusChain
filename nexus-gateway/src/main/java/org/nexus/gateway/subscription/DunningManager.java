package org.nexus.gateway.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Dunning 管理器（P4-T8 订阅与循环计费引擎）。
 *
 * <p>订阅扣款失败时的重试与通知策略：</p>
 * <ul>
 *   <li>第 1-3 次失败：RETRY，按配置间隔（默认 1/3/7 天）重试</li>
 *   <li>第 4 次失败：NOTIFY，通知商户扣款持续失败（仍继续重试）</li>
 *   <li>第 5 次失败（达到 suspendAfter）：SUSPEND，暂停订阅</li>
 * </ul>
 *
 * <p>每次动作都发布 {@link DunningNotificationEvent}，监听器可据此发送
 * 邮件/短信/webhook 通知。dunningCount 在每次失败时递增，扣款成功后
 * 由 {@link DefaultSubscriptionService} 重置为 0。</p>
 */
@Component
public class DunningManager {

    private static final Logger log = LoggerFactory.getLogger(DunningManager.class);

    private final ApplicationEventPublisher eventPublisher;
    private final SubscriptionProperties properties;

    public DunningManager(ApplicationEventPublisher eventPublisher,
                          SubscriptionProperties properties) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 处理扣款失败：递增 dunningCount，决定动作并发布事件。
     *
     * @param subscription 失败的订阅（方法内会修改 dunningCount/status/nextChargeAt）
     * @return 采取的动作
     */
    public DunningNotificationEvent.Action handleChargeFailure(Subscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("subscription must not be null");
        }

        SubscriptionProperties.Dunning dunning = properties.getDunning();
        int newCount = subscription.getDunningCount() + 1;
        subscription.setDunningCount(newCount);

        DunningNotificationEvent.Action action;
        LocalDateTime nextRetryAt = null;

        if (newCount >= dunning.getSuspendAfter()) {
            // 暂停订阅
            action = DunningNotificationEvent.Action.SUSPEND;
            subscription.setStatus(SubscriptionStatus.PAUSED);
            subscription.setPausedAt(LocalDateTime.now());
            log.warn("Subscription suspended after {} failed attempts: sub={}",
                    newCount, subscription.getSubscriptionId());
        } else if (newCount > dunning.getMaxRetries()) {
            // 超过最大重试次数，通知商户但仍继续重试
            action = DunningNotificationEvent.Action.NOTIFY;
            int intervalDays = dunning.retryIntervalDays(newCount);
            nextRetryAt = LocalDateTime.now().plusDays(intervalDays);
            subscription.setNextChargeAt(nextRetryAt);
            log.warn("Subscription charge failed {} times, notifying merchant: sub={}, nextRetry={}",
                    newCount, subscription.getSubscriptionId(), nextRetryAt);
        } else {
            // 正常重试
            action = DunningNotificationEvent.Action.RETRY;
            int intervalDays = dunning.retryIntervalDays(newCount);
            nextRetryAt = LocalDateTime.now().plusDays(intervalDays);
            subscription.setNextChargeAt(nextRetryAt);
            if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                    || subscription.getStatus() == SubscriptionStatus.TRIAL) {
                subscription.setStatus(SubscriptionStatus.PAST_DUE);
            }
            log.info("Subscription charge failed, retrying: sub={}, attempt={}, nextRetry={}",
                    subscription.getSubscriptionId(), newCount, nextRetryAt);
        }

        // 发布 dunning 通知事件
        DunningNotificationEvent event = new DunningNotificationEvent(
                this, subscription.getSubscriptionId(), newCount, action, nextRetryAt);
        eventPublisher.publishEvent(event);

        return action;
    }

    /**
     * 处理扣款成功：重置 dunningCount，恢复状态为 ACTIVE。
     *
     * @param subscription 成功扣款的订阅
     */
    public void handleChargeSuccess(Subscription subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("subscription must not be null");
        }
        if (subscription.getDunningCount() > 0) {
            log.info("Subscription recovered from dunning: sub={}, previousAttempts={}",
                    subscription.getSubscriptionId(), subscription.getDunningCount());
        }
        subscription.setDunningCount(0);
        if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
            subscription.setStatus(SubscriptionStatus.ACTIVE);
        }
    }

    /**
     * 判断订阅是否应该尝试扣款。
     *
     * <p>处于 ACTIVE 或 PAST_DUE 状态，且 nextChargeAt 已到的订阅应该尝试扣款。
     * TRIAL 状态由试用期转正逻辑处理，PAUSED/CANCELLED 不扣款。</p>
     *
     * @param subscription 订阅
     * @return 是否应该尝试扣款
     */
    public boolean shouldAttemptCharge(Subscription subscription) {
        if (subscription == null) return false;
        SubscriptionStatus status = subscription.getStatus();
        return (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE)
                && subscription.getNextChargeAt() != null
                && !subscription.getNextChargeAt().isAfter(LocalDateTime.now());
    }
}