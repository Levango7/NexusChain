-- V9: Additional performance indexes for high-frequency queries (性能优化任务 #310, PostgreSQL 版本).
--
-- 与 db/migration/V14__additional_performance_indexes.sql 内容一致，
-- 适配 PostgreSQL 语法（IF NOT EXISTS 在 PG 9.5+ 支持）。
-- 详见 MySQL 版本的注释。
--
-- S5 修复（2026-08-31，Docker 实证发现）：原 V9 还包含 tenant_id 三索引与
-- webhook_deliveries 索引——tenant_id 列由 V13 加、webhook_deliveries 表由
-- V11 建（编号重排后 V9 先于二者执行）→ PG 全新库顺序迁移失败。
-- 依赖索引已分别移至 V11（webhook）与 V13（tenant）尾部。

-- refunds: CompensationService.findByStatusAndCreatedAtBefore
CREATE INDEX IF NOT EXISTS idx_refunds_status_created ON refunds(status, created_at);

-- payment_orders: SettlementService.findByMerchantIdAndStatusAndPaidAtBetween
CREATE INDEX IF NOT EXISTS idx_orders_merchant_status_paidat ON payment_orders(merchant_id, status, paid_at);

-- refund_requests: 按 order_id 查询
CREATE INDEX IF NOT EXISTS idx_rr_order_id ON refund_requests(order_id);