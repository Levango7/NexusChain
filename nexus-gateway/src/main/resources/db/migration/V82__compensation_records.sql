-- V82: 补偿记录表
-- 记录自动补偿操作的执行过程和结果

CREATE TABLE compensation_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    discrepancy_id BIGINT NOT NULL COMMENT '关联的差错记录ID',
    merchant_id BIGINT COMMENT '商户ID',
    compensation_type VARCHAR(32) NOT NULL COMMENT '补偿类型: REFUND/INTERNAL_ADJUST',
    amount DECIMAL(36, 2) COMMENT '补偿金额',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/EXECUTING/SUCCESS/FAILED',
    transaction_id VARCHAR(64) COMMENT '关联交易ID',
    account_transaction_ref VARCHAR(128) COMMENT '内部账户操作引用',
    channel_refund_ref VARCHAR(128) COMMENT '渠道退款引用',
    failure_reason VARCHAR(1024) COMMENT '失败原因',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    executed_at TIMESTAMP NULL COMMENT '执行时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动补偿记录';

CREATE UNIQUE INDEX uk_cr_discrepancy_id ON compensation_records(discrepancy_id);
CREATE INDEX idx_cr_merchant_status ON compensation_records(merchant_id, status);