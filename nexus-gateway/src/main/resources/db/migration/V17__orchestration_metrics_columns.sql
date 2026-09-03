-- V17: Add latency / cost observability columns to orchestrated_payments.
-- Backs the application-level MetricsCollector persistence so per-connector
-- latency and cost can be reported/audited historically (not only in-memory).
-- Nullable: only populated once a connector actually returned a value.

ALTER TABLE orchestrated_payments
    ADD COLUMN latency_ms BIGINT NULL;

ALTER TABLE orchestrated_payments
    ADD COLUMN cost_bps INT NULL;

-- Optional: composite index for "per connector: average latency/cost over time"
CREATE INDEX idx_op_connector_metrics ON orchestrated_payments(connector_id, created_at);
