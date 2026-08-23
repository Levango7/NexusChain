-- V9: Additional performance indexes for high-frequency queries (性能优化任务 #310, PostgreSQL 版本).
--
-- 与 db/migration/V14__additional_performance_indexes.sql 内容一致，
-- 适配 PostgreSQL 语法（IF NOT EXISTS 在 PG 9.5+ 支持）。
-- 详见 MySQL 版本的注释。

-- refunds: CompensationService.findByStatusAndCreatedAtBefore
CREATE INDEX IF NOT EXISTS idx_refunds_status_created ON refunds(status, created_at);

-- payment_orders: SettlementService.findByMerchantIdAndStatusAndPaidAtBetween
CREATE INDEX IF NOT EXISTS idx_orders_merchant_status_paidat ON payment_orders(merchant_id, status, paid_at);

-- payment_orders: 多租户订单列表 findByTenantIdAndMerchantIdAndStatus
CREATE INDEX IF NOT EXISTS idx_orders_tenant_merchant_status ON payment_orders(tenant_id, merchant_id, status);

-- payment_orders: 租户计费/风控按时间窗口汇总
CREATE INDEX IF NOT EXISTS idx_orders_tenant_created ON payment_orders(tenant_id, created_at);

-- payment_orders: 租户按 paid_at 时间窗口汇总
CREATE INDEX IF NOT EXISTS idx_orders_tenant_paidat ON payment_orders(tenant_id, paid_at);

-- webhook_deliveries: 去重查询
CREATE INDEX IF NOT EXISTS idx_wd_payment_status ON webhook_deliveries(payment_id, status);

-- refund_requests: 按 order_id 查询
CREATE INDEX IF NOT EXISTS idx_rr_order_id ON refund_requests(order_id);