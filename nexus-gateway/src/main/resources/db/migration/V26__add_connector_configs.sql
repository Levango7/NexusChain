CREATE TABLE connector_configs (
    id VARCHAR(100) NOT NULL PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    display_name VARCHAR(200),
    base_url VARCHAR(500),
    api_key_env VARCHAR(200),
    app_id VARCHAR(200),
    mch_id VARCHAR(200),
    merchant_private_key TEXT,
    alipay_public_key TEXT,
    currencies VARCHAR(200),
    fee_bps INT DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_connector_configs_active ON connector_configs(active);
CREATE INDEX idx_connector_configs_type ON connector_configs(type);