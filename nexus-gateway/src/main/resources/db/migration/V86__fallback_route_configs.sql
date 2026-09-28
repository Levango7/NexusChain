-- Wave 16 模块三：商户/渠道级降级路由配置表
CREATE TABLE IF NOT EXISTS fallback_route_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NULL COMMENT 'NULL=全局/渠道级配置',
    primary_connector VARCHAR(64) NOT NULL,
    fallback_connectors_csv VARCHAR(512) NOT NULL COMMENT '有序备选，逗号分隔',
    conditions_json VARCHAR(1024) COMMENT '金额/币种/时段条件',
    priority INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_frc_merchant_primary (merchant_id, primary_connector),
    KEY idx_frc_primary (primary_connector),
    KEY idx_frc_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户/渠道级降级路由配置';