package org.nexus.gateway.subscription;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Dunning 通知事件（P4-T8 订阅与循环计费引擎）。
 *
 * <p>当订阅扣款失败进入 dunning 流程时发布，携带订阅 ID、当前重试次数与
 * 采取的动作（RETRY/NOTIFY/SUSPEND）。监听器可据此发送邮件/短信/ webhook
 * 通知商户或客户。</p>
 */
public class DunningNotificationEvent extends ApplicationEvent {

    /** Dunning 动作类型。 */
    public enum Action {
        /** 将在指定间隔后重试扣款。 */
        RETRY,
        /** 通知商户扣款持续失败（达到通知阈值）。 */
        NOTIFY,
        /** 暂停订阅（重试耗尽）。 */
        SUSPEND
    }

    private final String subscriptionId;
    private final int attemptCount;
    private final Action action;
    private final LocalDateTime nextRetryAt;
    private final LocalDateTime occurredAt;

    public DunningNotificationEvent(Object source, String subscriptionId, int attemptCount,
                                    Action action, LocalDateTime nextRetryAt) {
        super(source);
        this.subscriptionId = subscriptionId;
        this.attemptCount = attemptCount;
        this.action = action;
        this.nextRetryAt = nextRetryAt;
        this.occurredAt = LocalDateTime.now();
    }

    public String getSubscriptionId() { return subscriptionId; }
    public int getAttemptCount() { return attemptCount; }
    public Action getAction() { return action; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public LocalDateTime getOccurredAt() { return occurredAt; }

    @Override
    public String toString() {
        return "DunningNotificationEvent{subscriptionId='" + subscriptionId + '\'' +
                ", attemptCount=" + attemptCount +
                ", action=" + action +
                ", nextRetryAt=" + nextRetryAt +
                ", occurredAt=" + occurredAt + '}';
    }
}