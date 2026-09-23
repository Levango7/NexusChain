-- V22: 商户结算周期配置表
-- 支持灵活结算周期：T0/T1/T2/T3/WEEKLY/MONTHLY/CUSTOM

CREATE TABLE merchant_settlement_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL UNIQUE,
    settlement_period VARCHAR(16) NOT NULL DEFAULT 'T1',
    custom_days INT,
    auto_settle_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_merchant_settlement_config_merchant ON merchant_settlement_configs(merchant_id);