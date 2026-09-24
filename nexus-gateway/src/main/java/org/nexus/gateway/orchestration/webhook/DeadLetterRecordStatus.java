package org.nexus.gateway.orchestration.webhook;

/**
 * 死信记录状态（Wave 8-A5）。
 *
 * <p>状态机：
 * <pre>
 *   PENDING_REPLAY → REPLAYED     （手动/自动重投成功）
 *   PENDING_REPLAY → ARCHIVED     （归档，不再重投）
 *   REPLAYED → PENDING_REPLAY     （重投失败，重新回到待重投）
 * </pre>
 *
 * @since Wave 8-A5 - Webhook 可靠性增强
 */
public enum DeadLetterRecordStatus {
    /** 待重投：死信记录等待人工或自动重投。 */
    PENDING_REPLAY,
    /** 已重投：重投成功，投递已恢复。 */
    REPLAYED,
    /** 已归档：不再尝试重投。 */
    ARCHIVED
}