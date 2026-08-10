-- V2: Performance indexes for production queries（PostgreSQL 兼容版本，P2-T8）
-- 标准 SQL 语法，与 MySQL 版本完全一致，PostgreSQL 原生支持
CREATE INDEX IF NOT EXISTS idx_orders_merchant_status ON payment_orders(merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_checkout_token ON payment_orders(checkout_token);
CREATE INDEX IF NOT EXISTS idx_orders_expires ON payment_orders(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_refunds_order ON refunds(order_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_next_charge ON subscriptions(status, next_charge_at);
CREATE INDEX IF NOT EXISTS idx_apikeys_merchant ON merchant_api_keys(merchant_id, active);