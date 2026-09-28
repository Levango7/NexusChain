-- Wave 16 模块三：跨渠道补偿路由记录表
CREATE TABLE IF NOT EXISTS compensation_routing_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    compensation_id VARCHAR(64) NOT NULL COMMENT 'comp_{UUID}',
    payment_id VARCHAR(64) NOT NULL COMMENT '关联 orchestrated_payments.id',
    merchant_id BIGINT,
    original_connector VARCHAR(64),
    compensation_connector VARCHAR(64),
    attempt_no INT NOT NULL DEFAULT 1,
    result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT/SKIPPED_IDEMPOTENT',
    error_message VARCHAR(1024),
    latency_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cmprec_compensation_id (compensation_id),
    KEY idx_cmprec_payment (payment_id),
    KEY idx_cmprec_merchant (merchant_id),
    KEY idx_cmprec_connector_time (compensation_connector, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨渠道补偿路由记录';