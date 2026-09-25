-- V50: 资金调拨规则表 — Wave 11 资金调拨与清算结算集成
-- 支持基于余额阈值/定时触发/手动触发的自动资金调拨规则

CREATE TABLE transfer_rules (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    rule_name           VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_code           VARCHAR(64)  NOT NULL COMMENT '规则编号，唯一标识',
    from_account_type   VARCHAR(32)  NOT NULL COMMENT '转出账户类型: BALANCE/FROZEN/RESERVE',
    to_account_type     VARCHAR(32)  NOT NULL COMMENT '转入账户类型: BALANCE/FROZEN/RESERVE',
    trigger_type        VARCHAR(32)  NOT NULL COMMENT '触发类型: BALANCE_THRESHOLD/SCHEDULED/MANUAL',
    threshold_amount    DECIMAL(36,0) COMMENT '阈值金额（BALANCE_THRESHOLD 类型使用）',
    threshold_direction VARCHAR(16)  COMMENT '阈值方向: ABOVE/BELOW（BALANCE_THRESHOLD 类型使用）',
    transfer_amount_type VARCHAR(32) NOT NULL COMMENT '调拨金额类型: FIXED/PERCENTAGE/ALL',
    transfer_amount     DECIMAL(36,0) COMMENT '固定金额（FIXED 类型使用）',
    transfer_percentage DECIMAL(8,4) COMMENT '百分比（PERCENTAGE 类型使用，0-100）',
    cron_expression     VARCHAR(64)  COMMENT '定时表达式（SCHEDULED 类型使用）',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用: 0=禁用, 1=启用',
    description         VARCHAR(512) COMMENT '规则描述',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_tr_rule_code UNIQUE (rule_code),
    INDEX idx_tr_trigger_type (trigger_type),
    INDEX idx_tr_enabled (enabled),
    INDEX idx_tr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金调拨规则表';