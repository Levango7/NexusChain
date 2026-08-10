-- V9: Subscription engine tables (P4-T8: Subscription & recurring billing engine).
-- Backs SubscriptionPlan and Subscription (v2) entities.
-- Required in Flyway-enabled profiles (dev/prod) where JPA ddl-auto=validate.
-- Table names subscription_plans / subscription_v2 are separated from the P1
-- legacy subscriptions table to avoid JPA validation conflicts.

CREATE TABLE IF NOT EXISTS subscription_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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