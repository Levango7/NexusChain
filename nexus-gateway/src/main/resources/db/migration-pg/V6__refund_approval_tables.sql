-- =============================================================================
-- V6: Refund approval workflow tables（PostgreSQL 兼容版本，P2-T8）
-- =============================================================================
-- 由 V6__refund_approval_tables.sql（MySQL/H2 语法）转换而来：
--   * BIGINT AUTO_INCREMENT → BIGSERIAL

CREATE TABLE IF NOT EXISTS refund_requests (
    id BIGSERIAL PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    amount DECIMAL(36,0) NOT NULL,
    reason VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    approver_id VARCHAR(64),
    rejection_reason VARCHAR(256),
    chain_tx_hash VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP,
    executed_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE INDEX idx_rr_order ON refund_requests(order_id);
CREATE INDEX idx_rr_merchant ON refund_requests(merchant_id);
CREATE INDEX idx_rr_status ON refund_requests(status);