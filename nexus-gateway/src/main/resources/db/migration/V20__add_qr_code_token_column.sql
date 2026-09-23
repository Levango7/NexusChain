-- V20: 扫码支付功能 — 为 payment_orders 表增加 qr_code_token 列
-- qr_code_token 用于生成支付二维码内容中的安全令牌，扫码支付时校验防止伪造

ALTER TABLE payment_orders ADD COLUMN qr_code_token VARCHAR(128);

CREATE UNIQUE INDEX idx_payment_orders_qr_code_token ON payment_orders(qr_code_token);