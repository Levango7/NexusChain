-- Wave 16 A3（口径拍板后实施）：商户限额滚动累加表
-- 语义变更（CHANGELOG [2.51.3] 明示）：日/月限额从「滚动 24h/30 天窗口 SUM」
-- 改为「自然日/自然月计数器」——行业惯例口径（商户平台限额均为自然日），
-- 且计数器天然按日分桶，O(1) 读写、零查询滞后。
-- 成员集与原 SUM 严格一致：仅 PAID + PAYING 状态计入（SUBMITTED 不计，
-- 与 sumMerchantAmountSince 的状态过滤完全对齐）。
-- 维护方式：OrderStateMachine.transition 咽喉钩子——状态进入 {PAID,PAYING}
-- 时 +amount、离开时 -amount（含 PAID→REFUND_PENDING / PAID→REORGED 等全部出边）。
CREATE TABLE IF NOT EXISTS risk_limit_accruals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    period_type VARCHAR(8) NOT NULL COMMENT 'DAILY / MONTHLY',
    period_key DATE NOT NULL COMMENT '自然日（DAILY）或月首日（MONTHLY）',
    accrued_amount DECIMAL(36, 2) NOT NULL DEFAULT 0 COMMENT '窗口内 PAID+PAYING 累计金额（可负：回退离场）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rla_merchant_period (merchant_id, period_type, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户限额滚动计数（自然日/月，O(1) 精确口径）';
