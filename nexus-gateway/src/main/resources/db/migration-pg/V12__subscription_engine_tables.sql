-- =============================================================================
-- V12: Subscription engine tables (PostgreSQL)
-- S5 修复：由 V9__subscription_engine_tables.sql（MySQL）转换。
-- Backs SubscriptionPlan and Subscription (v2) entities.
-- =============================================================================

CREATE TABLE IF NOT EXISTS subscription_plans (
    id BIGSERIAL PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    billing_period VARCHAR(16) NOT NULL,
    trial_period_days INT NOT NULL DEFAULT 0,
    amount DECIMAL(36,0) NOT NULL,
    currency VARCHAR(16) NOT NULL DEFAULT 'NEX',
    features VARCHAR(1024),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS subscription_v2 (
    id BIGSERIAL PRIMARY KEY,
    subscription_id VARCHAR(64) NOT NULL UNIQUE,
    merchant_id BIGINT NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    payer_address VARCHAR(66) NOT NULL,
    payee_address VARCHAR(66) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_period_start TIMESTAMP NOT NULL,
    current_period_end TIMESTAMP NOT NULL,
    trial_end TIMESTAMP,
    dunning_count INT NOT NULL DEFAULT 0,
    next_charge_at TIMESTAMP NOT NULL,
    charged_count INT NOT NULL DEFAULT 0,
    last_tx_hash VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    cancelled_at TIMESTAMP,
    paused_at TIMESTAMP
);

CREATE INDEX idx_sub_v2_merchant ON subscription_v2(merchant_id);
CREATE INDEX idx_sub_v2_status ON subscription_v2(status);
CREATE INDEX idx_sub_v2_next_charge ON subscription_v2(next_charge_at);
CREATE INDEX idx_sub_v2_plan ON subscription_v2(plan_id);
CREATE INDEX idx_sub_plans_enabled ON subscription_plans(enabled);
