-- V43: 链上结算确认记录表（Wave 9-C1-1）
-- 持久化每一笔链上结算交易的确认追踪状态，支持确认状态查询、超时重试、告警和最终性状态更新。

CREATE TABLE settlement_confirmation_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    tx_hash VARCHAR(128) NOT NULL,
    confirmations BIGINT NOT NULL DEFAULT 0,
    required_confirmations BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    confirmed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    last_checked_at TIMESTAMP(6),
    retry_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024),
    version BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_scr_payment_id ON settlement_confirmation_records(payment_id);
CREATE INDEX idx_scr_tx_hash ON settlement_confirmation_records(tx_hash);
CREATE INDEX idx_scr_status ON settlement_confirmation_records(status);
CREATE INDEX idx_scr_last_checked ON settlement_confirmation_records(last_checked_at);

-- payment_id 唯一约束：同一支付只有一条确认记录（幂等）
ALTER TABLE settlement_confirmation_records ADD CONSTRAINT uk_scr_payment_id UNIQUE (payment_id);