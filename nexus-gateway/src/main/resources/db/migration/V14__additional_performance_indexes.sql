-- V14: Additional performance indexes for high-frequency queries (性能优化任务 #310).
--
-- 本迁移为已识别的高频查询路径补充复合索引，减少全表扫描与回表开销：
--   1. refunds(status, created_at)          —— CompensationService 扫描 PENDING 超时退款
--   2. payment_orders(merchant_id, status, paid_at) —— SettlementService 按商户+状态+时间窗口查询
--   3. payment_orders(tenant_id, merchant_id, status) —— 多租户订单列表查询
--   4. payment_orders(tenant_id, created_at) —— 租户计费/风控按时间窗口汇总
--   5. refund_requests(order_id)            —— 已有 idx_rr_order，此处补充确保
--   6. webhook_deliveries(payment_id, status) —— WebhookDeliveryService 去重查询
--
-- 所有索引使用 IF NOT EXISTS 保证幂等，兼容 MySQL 8 / H2 / PostgreSQL。
-- PostgreSQL 版本见 db/migration-pg/V9__additional_performance_indexes.sql。

-- refunds: CompensationService.findByStatusAndCreatedAtBefore
CREATE INDEX IF NOT EXISTS idx_refunds_status_created ON refunds(status, created_at);

-- payment_orders: SettlementService.findByMerchantIdAndStatusAndPaidAtBetween
-- 复合索引覆盖 merchant_id + status + paid_at，避免回表扫描
CREATE INDEX IF NOT EXISTS idx_orders_merchant_status_paidat ON payment_orders(merchant_id, status, paid_at);

-- payment_orders: 多租户订单列表 findByTenantIdAndMerchantIdAndStatus
CREATE INDEX IF NOT EXISTS idx_orders_tenant_merchant_status ON payment_orders(tenant_id, merchant_id, status);

-- payment_orders: 租户计费/风控按时间窗口汇总 sumTenantAmountSince / countTenantPaidInWindow
CREATE INDEX IF NOT EXISTS idx_orders_tenant_created ON payment_orders(tenant_id, created_at);

-- payment_orders: 租户按 paid_at 时间窗口汇总 sumTenantPaidInWindow
CREATE INDEX IF NOT EXISTS idx_orders_tenant_paidat ON payment_orders(tenant_id, paid_at);

-- webhook_deliveries: WebhookDeliveryService.findByPaymentIdAndStatus 去重查询
CREATE INDEX IF NOT EXISTS idx_wd_payment_status ON webhook_deliveries(payment_id, status);

-- refund_requests: 按 order_id 查询已有退款（sumPendingRefundsByOrderId）
-- 注：V6 已创建 idx_rr_order，此处 IF NOT EXISTS 保证幂等
CREATE INDEX IF NOT EXISTS idx_rr_order_id ON refund_requests(order_id);