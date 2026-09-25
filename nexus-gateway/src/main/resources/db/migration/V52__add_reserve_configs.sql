-- V52: 备付金配置表 — Wave 11 资金调拨与清算结算集成
-- 配置商户备付金的阈值、自动补充规则和监控参数

CREATE TABLE reserve_configs (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
    config_code         VARCHAR(64)  NOT NULL COMMENT '配置编号，唯一标识',
    min_reserve_amount  DECIMAL(36,0) NOT NULL DEFAULT 0 COMMENT '最低备付金金额',
    max_reserve_amount  DECIMAL(36,0) COMMENT '最高备付金金额',
    auto_replenish_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否自动补充: 0=禁用, 1=启用',
    replenish_threshold DECIMAL(36,0) COMMENT '自动补充触发阈值',
    replenish_amount    DECIMAL(36,0) COMMENT '每次补充金额',
    replenish_source_account_type VARCHAR(32) NOT NULL DEFAULT 'BALANCE' COMMENT '补充资金来源账户类型',
    alert_threshold     DECIMAL(36,0) COMMENT '预警阈值',
    alert_flag          VARCHAR(32)  COMMENT '预警级别: WARNING/CRITICAL/EMERGENCY',
    monitoring_enabled  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用监控: 0=禁用, 1=启用',
    description         VARCHAR(512) COMMENT '配置描述',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_rc_config_code UNIQUE (config_code),
    INDEX idx_rc_merchant_id (merchant_id),
    INDEX idx_rc_auto_replenish (auto_replenish_enabled),
    INDEX idx_rc_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='备付金配置表';