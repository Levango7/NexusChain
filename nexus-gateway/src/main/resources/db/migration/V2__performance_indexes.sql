-- V2: Performance indexes for production queries
CREATE INDEX idx_orders_merchant_status ON payment_orders(merchant_id, status);
CREATE INDEX idx_orders_checkout_token ON payment_orders(checkout_token);
CREATE INDEX idx_orders_expires ON payment_orders(status, expires_at);
CREATE INDEX idx_refunds_order ON refunds(order_id);
CREATE INDEX idx_subscriptions_next_charge ON subscriptions(status, next_charge_at);
CREATE INDEX idx_apikeys_merchant ON merchant_api_keys(merchant_id, active);