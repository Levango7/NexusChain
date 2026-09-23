package org.nexus.gateway.webhook;

/**
 * Webhook 订阅状态枚举。
 *
 * <p>状态机：
 * <pre>
 *   ACTIVE  → PAUSED   （手动暂停）
 *   PAUSED  → ACTIVE   （手动恢复）
 *   ACTIVE  → DELETED  （软删除）
 *   PAUSED  → DELETED  （软删除）
 * </pre>
 *
 * <p>DELETED 为终态，不可恢复。投递服务仅查询 ACTIVE 状态的订阅。</p>
 */
public enum WebhookSubscriptionStatus {

    /** 活跃：订阅正常工作，事件会触发回调投递。 */
    ACTIVE,

    /** 暂停：订阅暂时停止投递，可手动恢复。 */
    PAUSED,

    /** 已删除：软删除标记，不再投递，不可恢复。 */
    DELETED
}