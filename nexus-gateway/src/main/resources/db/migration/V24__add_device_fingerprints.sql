CREATE TABLE device_fingerprints (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint_hash VARCHAR(128) NOT NULL UNIQUE,
    merchant_id BIGINT,
    payer_address VARCHAR(66),
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    accept_language VARCHAR(64),
    screen_resolution VARCHAR(32),
    timezone VARCHAR(64),
    platform VARCHAR(32),
    first_seen_at TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
    blacklisted BOOLEAN NOT NULL DEFAULT FALSE,
    linked_addresses VARCHAR(2048),
    transaction_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_device_fp_hash ON device_fingerprints(fingerprint_hash);
CREATE INDEX idx_device_fp_payer ON device_fingerprints(payer_address);
CREATE INDEX idx_device_fp_blacklisted ON device_fingerprints(blacklisted);
CREATE INDEX idx_device_fp_risk_level ON device_fingerprints(risk_level);