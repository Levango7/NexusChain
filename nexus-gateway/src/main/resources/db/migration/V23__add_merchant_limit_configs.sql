CREATE TABLE merchant_limit_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL UNIQUE,
    single_transaction_min_amount DECIMAL(36,8),
    single_transaction_max_amount DECIMAL(36,8),
    daily_accumulated_max_amount DECIMAL(36,8),
    monthly_accumulated_max_amount DECIMAL(36,8),
    daily_max_transaction_count INT,
    monthly_max_transaction_count INT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_merchant_limit_config_merchant ON merchant_limit_configs(merchant_id);