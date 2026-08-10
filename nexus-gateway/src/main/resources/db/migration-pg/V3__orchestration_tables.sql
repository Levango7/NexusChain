-- V3: Payment Orchestration Engine tables（PostgreSQL 兼容版本，P2-T8）
-- 标准 SQL 语法，与 MySQL 版本完全一致
-- id 为 VARCHAR(64) 业务主键（非自增），PostgreSQL 原生支持
CREATE TABLE IF NOT EXISTS orchestrated_payments (
    id VARCHAR(64) PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    connector_id VARCHAR(64),
    connector_payment_id VARCHAR(128),
    transaction_hash VARCHAR(128),
    notify_url VARCHAR(512),
    routing_strategy VARCHAR(32),
    metadata VARCHAR(1024),
    created_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    expires_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX idx_op_merchant ON orchestrated_payments(merchant_id);
CREATE INDEX idx_op_status ON orchestrated_payments(status);
CREATE INDEX idx_op_connector ON orchestrated_payments(connector_id);