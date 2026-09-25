-- V56: 风控联动记录表 — Wave 11 方向 3
-- 记录风控事件与账户操作的联动执行记录，支持幂等检查
-- 联合索引 (risk_event_id, link_action) 保证同一风控事件+联动动作不重复执行

CREATE TABLE risk_account_link_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    risk_event_id VARCHAR(64) NOT NULL COMMENT '风控事件 ID',
    link_action VARCHAR(32) NOT NULL COMMENT '联动动作: FREEZE/UNFREEZE/STATUS_FROZEN/STATUS_CLOSED/STATUS_RESTORED',
    execution_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '执行状态: PENDING/SUCCESS/FAILED/SKIPPED',
    merchant_id BIGINT NOT NULL COMMENT '商户 ID',
    amount DECIMAL(36, 8) NULL COMMENT '冻结/解冻金额（状态变更类动作可为空）',
    reason VARCHAR(512) NULL COMMENT '联动原因',
    error_message VARCHAR(1024) NULL COMMENT '执行失败时的错误信息',
    executed_at DATETIME NULL COMMENT '执行时间',
    completed_at DATETIME NULL COMMENT '完成时间',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    version BIGINT NULL COMMENT '乐观锁版本号',
    CONSTRAINT idx_risk_event_link_action UNIQUE (risk_event_id, link_action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控联动记录表';

CREATE INDEX idx_ralr_merchant_id ON risk_account_link_records(merchant_id);
CREATE INDEX idx_ralr_execution_status ON risk_account_link_records(execution_status);