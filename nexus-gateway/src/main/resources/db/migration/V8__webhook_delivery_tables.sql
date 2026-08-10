-- V8: Webhook delivery records table (P4-T5: Webhook retry + DLQ enhancement).
-- Backs WebhookDeliveryRecord entity, supports delivery status query API and
-- manual replay from dead-letter queue.
-- Required in Flyway-enabled profiles (dev/prod) where JPA ddl-auto=validate.

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    merchant_id BIGINT NOT NULL,
    notify_url VARCHAR(512) NOT NULL,
    payload VARCHAR(4096) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024),
    last_attempt_at TIMESTAMP,
    delivered_at TIMESTAMP,
    dead_lettered_at TIMESTAMP,
    signature VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    version BIGINT DEFAULT 0
);

CREATE INDEX idx_wd_payment ON webhook_deliveries(payment_id);
CREATE INDEX idx_wd_merchant ON webhook_deliveries(merchant_id);
CREATE INDEX idx_wd_status ON webhook_deliveries(status);
CREATE INDEX idx_wd_last_attempt ON webhook_deliveries(last_attempt_at);