-- =============================================================================
-- V15: Unique constraint on payment_orders.chain_tx_hash (PostgreSQL)
-- S5 修复：由 V12__chain_tx_hash_unique.sql（MySQL）转换。
-- P0-5 fix, v2.27.0：防止同一链上交易哈希绑定到多个订单（防重复支付 DB 兜底）。
-- chain_tx_hash 可空（未确认订单为 NULL）；PostgreSQL 标准 UNIQUE 约束
-- 允许多个 NULL，不影响未确认订单。
-- =============================================================================

-- IF NOT EXISTS 语法避免重复执行冲突（Flyway 单次执行保障下为防御性写法）
ALTER TABLE payment_orders DROP CONSTRAINT IF EXISTS uk_payment_orders_chain_tx_hash;
ALTER TABLE payment_orders ADD CONSTRAINT uk_payment_orders_chain_tx_hash UNIQUE (chain_tx_hash);
