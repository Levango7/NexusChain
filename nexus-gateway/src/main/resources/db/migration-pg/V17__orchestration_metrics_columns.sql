-- V17: Add latency / cost observability columns to orchestrated_payments（PostgreSQL）。
-- 与 MySQL 版本语义一致；PostgreSQL 使用标准 ADD COLUMN 语法。
-- Nullable: 仅当 connector 实际返回测量值后填充。

ALTER TABLE orchestrated_payments
    ADD COLUMN IF NOT EXISTS latency_ms BIGINT NULL;

ALTER TABLE orchestrated_payments
    ADD COLUMN IF NOT EXISTS cost_bps INT NULL;

-- Optional: composite index for "per connector: average latency/cost over time"
CREATE INDEX IF NOT EXISTS idx_op_connector_metrics ON orchestrated_payments(connector_id, created_at);
