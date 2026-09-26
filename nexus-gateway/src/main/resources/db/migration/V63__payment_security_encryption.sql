-- V63: 交易加密增强 — 加密密钥元数据表 + 加密策略配置表 + payment_orders 扩展

-- 加密密钥元数据表
CREATE TABLE encryption_key_metadata (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id               VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    field_name              VARCHAR(64)   NOT NULL COMMENT '加密字段名',
    encryption_algorithm    VARCHAR(32)   NOT NULL DEFAULT 'AES-256-GCM' COMMENT '加密算法',
    kek_version             INT           NOT NULL COMMENT 'KEK 版本号',
    encrypted_dek           VARBINARY(512) NOT NULL COMMENT 'KEK 加密后的 DEK',
    iv                      VARBINARY(12) NOT NULL COMMENT '初始化向量 (96-bit)',
    auth_tag                VARBINARY(16) NOT NULL COMMENT 'GCM 认证标签 (128-bit)',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    rotated_at              TIMESTAMP(6)  NULL DEFAULT NULL COMMENT 'DEK 轮换时间',
    status                  VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/ARCHIVED',
    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    INDEX idx_ekm_tenant_field_kekversion (tenant_id, field_name, kek_version),
    INDEX idx_ekm_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密密钥元数据表';

-- 加密策略配置表
CREATE TABLE encryption_configs (
    id                              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id                       VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    merchant_id                     BIGINT        NULL DEFAULT NULL COMMENT '商户 ID (NULL=全局策略)',
    encrypted_fields                TEXT          NOT NULL COMMENT '加密字段列表 (JSON)',
    encryption_algorithm            VARCHAR(32)   NOT NULL DEFAULT 'AES-256-GCM' COMMENT '加密算法',
    kek_rotation_period_days        INT           NOT NULL DEFAULT 90 COMMENT 'KEK 轮换周期(天)',
    app_layer_encryption_enabled    BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '应用层加密开关',
    created_at                      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                         BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    INDEX idx_ec_tenant_merchant (tenant_id, merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密策略配置表';

-- payment_orders 扩展
ALTER TABLE payment_orders
    ADD COLUMN encryption_metadata_id BIGINT NULL DEFAULT NULL COMMENT '加密元数据关联',
    ADD COLUMN three_ds_auth_id       BIGINT NULL DEFAULT NULL COMMENT '3DS 认证记录关联',
    ADD COLUMN payment_password_verified BOOLEAN NOT NULL DEFAULT FALSE COMMENT '支付密码是否已验证',
    ADD COLUMN second_factor_verified    BOOLEAN NOT NULL DEFAULT FALSE COMMENT '二次验证是否已完成',
    ADD INDEX idx_po_encryption_metadata (encryption_metadata_id),
    ADD INDEX idx_po_three_ds_auth (three_ds_auth_id);