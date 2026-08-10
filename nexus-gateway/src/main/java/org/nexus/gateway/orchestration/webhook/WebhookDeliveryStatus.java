package org.nexus.gateway.orchestration.webhook;

/**
 * Webhook 投递状态（P4-T5）。
 *
 * <p>状态机：
 * <pre>
 *   PENDING → DELIVERED        （首次投递成功）
 *   PENDING → RETRYING         （首次投递失败，进入重试）
 *   RETRYING → DELIVERED       （重试成功）
 *   RETRYING → DEAD_LETTER     （重试耗尽，转入死信队列）
 *   DEAD_LETTER → RETRYING     （手动重投触发）
 * </pre>
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
public enum WebhookDeliveryStatus {
    /** 待投递：已记录但尚未发起首次投递。 */
    PENDING,
    /** 已投递：HTTP 2xx 响应。 */
    DELIVERED,
    /** 重试中：投递失败，正在按指数退避策略重试。 */
    RETRYING,
    /** 死信：重试耗尽，已转入死信队列等待人工处理或重投。 */
    DEAD_LETTER
}