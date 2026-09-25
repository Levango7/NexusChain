-- V51: 归集策略表 — Wave 11 资金调拨与清算结算集成
-- 支持将多个商户账户资金按策略归集到指定目标账户

CREATE TABLE collection_strategies (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_name       VARCHAR(128) NOT NULL COMMENT '策略名称',
    strategy_code       VARCHAR(64)  NOT NULL COMMENT '策略编号，唯一标识',
    source_merchant_ids TEXT         NOT NULL COMMENT '源商户ID列表，逗号分隔',
    target_merchant_id  BIGINT       NOT NULL COMMENT '目标商户ID',
    source_account_type VARCHAR(32)  NOT NULL COMMENT '源账户类型: BALANCE/FROZEN/RESERVE',
    target_account_type VARCHAR(32)  NOT NULL COMMENT '目标账户类型: BALANCE/FROZEN/RESERVE',
    collection_type     VARCHAR(32)  NOT NULL COMMENT '归集类型: FULL/PERCENTAGE/FIXED',
    collection_amount   DECIMAL(36,0) COMMENT '固定归集金额（FIXED 类型使用）',
    collection_percentage DECIMAL(8,4) COMMENT '归集百分比（PERCENTAGE 类型使用，0-100）',
    min_retain_amount   DECIMAL(36,0) NOT NULL DEFAULT 0 COMMENT '源账户最低保留金额',
    cron_expression     VARCHAR(64)  COMMENT '定时表达式（定时归集使用）',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用: 0=禁用, 1=启用',
    description         VARCHAR(512) COMMENT '策略描述',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_cs_strategy_code UNIQUE (strategy_code),
    INDEX idx_cs_target_merchant (target_merchant_id),
    INDEX idx_cs_enabled (enabled),
    INDEX idx_cs_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归集策略表';