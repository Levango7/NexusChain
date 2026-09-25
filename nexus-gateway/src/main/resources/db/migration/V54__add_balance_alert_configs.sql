-- V54: 余额预警配置表 — Wave 11 方向 3
-- 为商户配置余额预警阈值，支持多级预警（WARNING/CRITICAL/EMERGENCY）
-- 阈值约束：warning_threshold > critical_threshold > emergency_threshold > 0

CREATE TABLE balance_alert_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL COMMENT '商户 ID',
    warning_threshold DECIMAL(36, 8) NOT NULL COMMENT '预警阈值（余额低于此值触发 WARNING）',
    critical_threshold DECIMAL(36, 8) NOT NULL COMMENT '严重阈值（余额低于此值触发 CRITICAL）',
    emergency_threshold DECIMAL(36, 8) NOT NULL COMMENT '紧急阈值（余额低于此值触发 EMERGENCY）',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用预警',
    notify_channels VARCHAR(256) NULL DEFAULT 'WEBHOOK' COMMENT '通知渠道（逗号分隔：WEBHOOK,EMAIL,SMS）',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    version BIGINT NULL COMMENT '乐观锁版本号',
    CONSTRAINT idx_bac_merchant_id UNIQUE (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='余额预警配置表';