-- V39: Dead letter records table (Wave 8-A5: Webhook reliability enhancement).
-- Persists dead-letter messages to DB, replacing/augmenting Kafka DLQ.
-- Ensures dead-letter records survive even when Kafka is unavailable.
-- Supports operational query, manual replay, and status tracking.

CREATE TABLE IF NOT EXISTS dead_letter_records (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    webhook_url     VARCHAR(512) NOT NULL COMMENT '回调地址（原 notifyUrl）',
    event_id        VARCHAR(64)  NOT NULL COMMENT '事件 ID（关联 webhook_deliveries.deliveryId）',
    payload         VARCHAR(4096) NOT NULL COMMENT '原始 Webhook payload（JSON 字符串）',
    error_message   VARCHAR(1024) COMMENT '失败原因（最后一次错误信息）',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING_REPLAY' COMMENT '死信状态: PENDING_REPLAY/REPLAYED/ARCHIVED',
    created_at      TIMESTAMP    NOT NULL COMMENT '转入死信队列时间',
    last_retry_at   TIMESTAMP    COMMENT '最后重试时间',
    PRIMARY KEY (id),
    INDEX idx_dlr_event_id (event_id),
    INDEX idx_dlr_status (status),
    INDEX idx_dlr_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信队列持久化记录表';