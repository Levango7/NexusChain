-- =============================================================================
-- V11: Webhook delivery records table (PostgreSQL)
-- S5 修复（2026-08-31 交付前审计）：补齐 migration-pg 与 db/migration 的缺口。
-- 原因：PG 迁移集在 v2.16 时期编号重排（merchant_keypairs 占 V8）后停止同步，
-- webhook/subscription/tenant/shedlock/chain-hash-unique 五组迁移只存在于
-- MySQL 集（V8-V12），prod profile（PostgreSQL + ddl-auto:validate）下
-- 实体校验启动失败，且 chain_tx_hash 唯一约束缺失 = 防重复支付 DB 兜底缺失。
-- 由 V8__webhook_delivery_tables.sql（MySQL）转换：BIGINT AUTO_INCREMENT → BIGSERIAL。
-- Backs WebhookDeliveryRecord entity.
-- =============================================================================

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
-- 去重查询复合索引（原 V9 内容，编号重排后随建表迁移落地——见 V9 头注释）
CREATE INDEX IF NOT EXISTS idx_wd_payment_status ON webhook_deliveries(payment_id, status);
