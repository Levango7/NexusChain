-- V16: payment_orders.notify_url column (A1 fix, 2026-08-31 交付前审计).
--
-- 商户支付结果异步通知地址。CreateOrderRequest.notifyUrl 此前为必填字段
-- 但实体无对应列——通知地址从未持久化，支付完成事件只回环到自身
-- /webhooks/chain-events（配置的 callback-url），商户异步通知为死功能。
-- nullable：兼容存量订单（无通知地址的旧订单回退配置 URL）。

ALTER TABLE payment_orders ADD COLUMN notify_url VARCHAR(512);
