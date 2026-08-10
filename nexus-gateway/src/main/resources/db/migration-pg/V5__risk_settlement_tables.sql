-- =============================================================================
-- V5: Risk control & settlement tables（PostgreSQL 兼容版本，P2-T8）
-- =============================================================================
-- 由 V5__risk_settlement_tables.sql（MySQL/H2 语法）转换而来：
--   * BIGINT AUTO_INCREMENT → BIGSERIAL
--   * BOOLEAN / FALSE → PostgreSQL 原生支持

CREATE TABLE IF NOT EXISTS risk_profiles (
    id BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT NOT NULL UNIQUE,
    risk_level INT NOT NULL DEFAULT 0,
    per_tx_limit DECIMAL(36,0),
    daily_limit DECIMAL(36,0),
    monthly_limit DECIMAL(36,0),
    blacklisted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS settlement_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    merchant_id BIGINT NOT NULL,
    period VARCHAR(16) NOT NULL,
    total_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    fee_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    net_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    chain_tx_hash VARCHAR(128),
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    transaction_ids VARCHAR(4096),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    executed_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX idx_sb_merchant ON settlement_batches(merchant_id);
CREATE INDEX idx_sb_status ON settlement_batches(status);