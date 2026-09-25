-- V49: PaymentOrder 状态扩展 — Wave 10 核心资金能力
-- 新增 VOIDED（已撤销）和 REVERSED（已冲正）两个状态值
-- payment_orders 表的 status 列为 VARCHAR(32)，已支持任意字符串值
-- 无需 DDL 变更，仅添加注释说明新增的枚举值
-- PAID → VOIDED（当日撤销，T+1 日 24:00 前）
-- PAID → REVERSED（隔日冲正，已结算交易）
-- VOIDED 和 REVERSED 均为终态，不可再转换

ALTER TABLE payment_orders MODIFY COLUMN status VARCHAR(32) NOT NULL COMMENT '订单状态: PENDING/PAYING/SUBMITTED/PAID/REORGED/EXPIRED/REFUNDED/FAILED/REFUND_PENDING/VOIDED/REVERSED';