-- Wave 16 模块二：渠道健康度历史记录表 + connector_configs 扩展
CREATE TABLE IF NOT EXISTS channel_health_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connector_id VARCHAR(64) NOT NULL,
    overall_score DOUBLE NOT NULL COMMENT '综合健康度 [0,1]',
    success_rate_score DOUBLE NOT NULL,
    latency_score DOUBLE NOT NULL,
    error_rate_score DOUBLE NOT NULL,
    capacity_score DOUBLE NOT NULL,
    level VARCHAR(16) NOT NULL COMMENT 'HEALTHY/DEGRADED/UNHEALTHY',
    sampled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_chh_connector_time (connector_id, sampled_at),
    KEY idx_chh_sampled_at (sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道健康度历史记录';

ALTER TABLE connector_configs
    ADD COLUMN max_concurrent INT NULL COMMENT '渠道最大并发容量，NULL=未配置（capacityScore 取中性 0.5）';