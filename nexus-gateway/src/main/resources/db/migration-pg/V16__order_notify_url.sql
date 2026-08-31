-- =============================================================================
-- V16: payment_orders.notify_url column (PostgreSQL)
-- A1 修复（2026-08-31 交付前审计）：商户支付结果异步通知地址持久化。
-- 由 V16__order_notify_url.sql（MySQL）转换——标准 ALTER 语法两者一致。
-- nullable：兼容存量订单（旧订单回退配置 callback-url）。
-- =============================================================================

ALTER TABLE payment_orders ADD COLUMN notify_url VARCHAR(512);
