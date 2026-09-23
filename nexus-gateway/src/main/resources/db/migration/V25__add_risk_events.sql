CREATE TABLE risk_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    event_type VARCHAR(32) NOT NULL,
    merchant_id BIGINT,
    order_id VARCHAR(64),
    payer_address VARCHAR(66),
    payee_address VARCHAR(66),
    amount DECIMAL(36,8),
    currency VARCHAR(16),
    risk_decision VARCHAR(32),
    risk_score INT,
    triggered_rules VARCHAR(1024),
    description VARCHAR(512),
    fingerprint_hash VARCHAR(128),
    ip_address VARCHAR(64),
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE INDEX idx_risk_events_merchant ON risk_events(merchant_id, occurred_at DESC);
CREATE INDEX idx_risk_events_type ON risk_events(event_type, occurred_at DESC);
CREATE INDEX idx_risk_events_decision ON risk_events(risk_decision, occurred_at DESC);
CREATE INDEX idx_risk_events_order ON risk_events(order_id);
CREATE INDEX idx_risk_events_time ON risk_events(occurred_at);