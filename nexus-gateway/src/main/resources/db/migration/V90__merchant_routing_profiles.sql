-- Wave 16 模块六：商户路由策略配置表 + merchants 扩展
CREATE TABLE IF NOT EXISTS merchant_routing_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_id VARCHAR(64) NOT NULL COMMENT '配置唯一标识',
    merchant_id BIGINT NULL COMMENT '商户ID，NULL=行业级配置',
    industry VARCHAR(32) COMMENT 'E_COMMERCE/DIGITAL_SERVICES/PHYSICAL_RETAIL/SERVICES/GAMING/FINANCE/TRAVEL/OTHER',
    amount_tier_rules_json VARCHAR(1024) COMMENT '金额区间规则',
    time_window_rules_json VARCHAR(1024) COMMENT '时段规则',
    preferred_objectives_json VARCHAR(512) COMMENT '偏好目标排序',
    connector_preferences_json VARCHAR(1024) COMMENT '渠道偏好/排除列表',
    priority INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mrp_profile_id (profile_id),
    KEY idx_mrp_merchant (merchant_id, enabled),
    KEY idx_mrp_industry (industry, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户路由策略配置';

ALTER TABLE merchants
    ADD COLUMN industry VARCHAR(32) NULL COMMENT '行业分类，入驻审核通过时从 MerchantApplication.businessType 回填';