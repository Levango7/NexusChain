-- V55: 自动提现规则表 — Wave 11 商户资金管理增强
-- 商户可配置自动提现规则，支持 DAILY/WEEKLY/MONTHLY 三种频率
-- 规则启用后由 AutoWithdrawScheduler 定时触发执行

CREATE TABLE auto_withdraw_rules (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT       NOT NULL COMMENT '商户 ID',
    frequency       VARCHAR(16)  NOT NULL COMMENT '提现频率: DAILY/WEEKLY/MONTHLY',
    threshold       DECIMAL(36,0) NOT NULL COMMENT '触发阈值，余额 >= 此值时自动提现',
    target_amount   DECIMAL(36,0) COMMENT '目标提现金额（NULL 表示提现全部余额）',
    min_retain      DECIMAL(36,0) NOT NULL DEFAULT 0 COMMENT '最低保留余额',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否启用',
    last_executed_at TIMESTAMP(6) COMMENT '上次执行时间',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_awr_merchant_id (merchant_id),
    INDEX idx_awr_enabled (enabled),
    INDEX idx_awr_frequency (frequency),
    INDEX idx_awr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动提现规则表';