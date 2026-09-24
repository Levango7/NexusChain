-- V40: 分账/限额策略扩展 — 阶梯分账、延迟分账、渠道限额、限额调整记录

-- 1. 阶梯分账规则表
CREATE TABLE tiered_split_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    receiver_address VARCHAR(66) NOT NULL,
    tier_min_amount DECIMAL(36,8) NOT NULL,
    tier_max_amount DECIMAL(36,8),
    split_ratio DECIMAL(36,8) NOT NULL,
    description VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    tier_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT,
    UNIQUE KEY uk_tiered_split_merchant_order (merchant_id, tier_order)
);

CREATE INDEX idx_tiered_split_rules_merchant ON tiered_split_rules(merchant_id, active);

-- 2. 延迟分账订单表
CREATE TABLE delayed_split_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    payment_id BIGINT,
    merchant_id BIGINT NOT NULL,
    receiver_address VARCHAR(66) NOT NULL,
    amount DECIMAL(36,8) NOT NULL,
    split_type VARCHAR(16) NOT NULL,
    split_value DECIMAL(36,8) NOT NULL,
    split_rule_id BIGINT,
    description VARCHAR(256),
    delay_status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at TIMESTAMP(6) NOT NULL,
    executed_at TIMESTAMP(6),
    delay_days INT,
    failure_reason VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_delayed_split_orders_merchant ON delayed_split_orders(merchant_id);
CREATE INDEX idx_delayed_split_orders_payment ON delayed_split_orders(payment_id);
CREATE INDEX idx_delayed_split_orders_order ON delayed_split_orders(order_id);
CREATE INDEX idx_delayed_split_orders_status ON delayed_split_orders(delay_status);
CREATE INDEX idx_delayed_split_orders_scheduled ON delayed_split_orders(delay_status, scheduled_at);

-- 3. 渠道限额配置表
CREATE TABLE channel_limit_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    single_limit DECIMAL(36,8),
    daily_cumulative_limit DECIMAL(36,8),
    monthly_cumulative_limit DECIMAL(36,8),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT,
    CONSTRAINT uk_channel_limit_merchant_channel UNIQUE (merchant_id, channel_type)
);

CREATE INDEX idx_channel_limit_configs_merchant ON channel_limit_configs(merchant_id, active);

-- 4. 限额调整规则表
CREATE TABLE limit_adjustment_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT,
    rule_type VARCHAR(32) NOT NULL,
    trigger_threshold INT NOT NULL,
    adjustment_direction VARCHAR(16) NOT NULL,
    adjustment_percentage DECIMAL(10,4) NOT NULL,
    adjustment_cap DECIMAL(36,8),
    target_limit_type VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_limit_adjustment_rules_merchant ON limit_adjustment_rules(merchant_id, active);

-- 5. 限额调整记录表
CREATE TABLE limit_adjustment_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    rule_id BIGINT,
    rule_type VARCHAR(32) NOT NULL,
    target_limit_type VARCHAR(32) NOT NULL,
    old_value DECIMAL(36,8),
    new_value DECIMAL(36,8),
    adjustment_percentage DECIMAL(10,4),
    adjustment_direction VARCHAR(16) NOT NULL,
    reason VARCHAR(512),
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_limit_adjustment_records_merchant ON limit_adjustment_records(merchant_id);
CREATE INDEX idx_limit_adjustment_records_rule_type ON limit_adjustment_records(merchant_id, rule_type);

-- 6. 扩展 merchant_limit_configs 表增加年度限额字段
ALTER TABLE merchant_limit_configs ADD COLUMN annual_single_limit DECIMAL(36,8);
ALTER TABLE merchant_limit_configs ADD COLUMN annual_cumulative_limit DECIMAL(36,8);