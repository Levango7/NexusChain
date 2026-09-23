CREATE TABLE reconciliation_file_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    file_type VARCHAR(10) NOT NULL,
    period_type VARCHAR(10) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_transactions BIGINT NOT NULL DEFAULT 0,
    total_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    matched_count BIGINT NOT NULL DEFAULT 0,
    discrepancy_count BIGINT NOT NULL DEFAULT 0,
    file_url VARCHAR(500),
    file_size_bytes BIGINT NOT NULL DEFAULT 0,
    generated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_recon_file_merchant ON reconciliation_file_records(merchant_id);
CREATE INDEX idx_recon_file_period ON reconciliation_file_records(period_type, period_start);