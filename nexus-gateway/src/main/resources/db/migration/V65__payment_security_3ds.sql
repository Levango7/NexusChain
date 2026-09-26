-- V65: 3D Secure 2.0 — 3DS 参数配置表 + 3DS 认证记录表

-- 3DS 参数配置表
CREATE TABLE three_ds_configs (
    id                          BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id                   VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    merchant_id                 BIGINT        NULL DEFAULT NULL COMMENT '商户 ID (NULL=租户级配置)',
    enabled                     BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '3DS 启用开关',
    frictionless_threshold_score INT          NOT NULL DEFAULT 60 COMMENT 'Frictionless 阈值分数 (0-100)',
    challenge_timeout_seconds   INT           NOT NULL DEFAULT 300 COMMENT 'Challenge 超时时间(秒), 默认5分钟',
    acs_url                     VARCHAR(512)  NULL DEFAULT NULL COMMENT 'ACS URL (模拟)',
    created_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                     BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    INDEX idx_tdsc_tenant_merchant (tenant_id, merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='3DS 参数配置表';

-- 3DS 认证记录表
CREATE TABLE three_ds_auth_records (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id               VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    merchant_id             BIGINT        NOT NULL COMMENT '商户 ID',
    payment_order_id        BIGINT        NOT NULL COMMENT '关联支付订单 ID',
    auth_status             VARCHAR(32)   NOT NULL DEFAULT 'INITIATED' COMMENT '认证状态: INITIATED/COMPLETED/FAILED/TIMEOUT',
    trans_status            VARCHAR(16)   NOT NULL COMMENT '交易状态: Y/C/N/R/U',
    message_version         VARCHAR(16)   NOT NULL DEFAULT '2.2.0' COMMENT '3DS 消息版本',
    acs_trans_id            VARCHAR(128)  NULL DEFAULT NULL COMMENT 'ACS 交易 ID',
    ds_trans_id             VARCHAR(128)  NULL DEFAULT NULL COMMENT 'DS 交易 ID',
    acs_challenge_url       VARCHAR(512)  NULL DEFAULT NULL COMMENT 'ACS Challenge URL',
    risk_score              INT           NULL DEFAULT NULL COMMENT '风险评分 (0-100)',
    frictionless_flow       BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否 Frictionless 流程',
    challenge_flow          BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否 Challenge 流程',
    initiated_at            TIMESTAMP(6)  NOT NULL COMMENT '认证发起时间',
    completed_at            TIMESTAMP(6)  NULL DEFAULT NULL COMMENT '认证完成时间',
    error_code              VARCHAR(32)   NULL DEFAULT NULL COMMENT '错误码',
    error_detail            VARCHAR(512)  NULL DEFAULT NULL COMMENT '错误详情',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    INDEX idx_tdsar_payment_order (payment_order_id),
    INDEX idx_tdsar_tenant_merchant_status (tenant_id, merchant_id, auth_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='3DS 认证记录表';