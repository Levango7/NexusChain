-- V66: 支付密码验证 — 商户支付密码表 + 密码历史表 + 密码安全策略配置表 + 二次验证记录表

-- 商户支付密码表
CREATE TABLE merchant_payment_passwords (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id             BIGINT        NOT NULL COMMENT '商户 ID',
    password_hash           VARCHAR(255)  NOT NULL COMMENT 'bcrypt 哈希',
    salt                    VARCHAR(255)  NULL DEFAULT NULL COMMENT '盐值（bcrypt 内置盐，此字段保留扩展）',
    bcrypt_cost             INT           NOT NULL DEFAULT 10 COMMENT 'bcrypt cost 参数',
    status                  VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/LOCKED/EXPIRED',
    locked_until            TIMESTAMP(6)  NULL DEFAULT NULL COMMENT '锁定截止时间',
    failed_attempts         INT           NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    last_changed_at         TIMESTAMP(6)  NOT NULL COMMENT '最后修改时间',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_merchant_status (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户支付密码表';

-- 密码历史表
CREATE TABLE password_history (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id             BIGINT        NOT NULL COMMENT '商户 ID',
    password_hash           VARCHAR(255)  NOT NULL COMMENT 'bcrypt 哈希',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_merchant_time (merchant_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密码历史表';

-- 密码安全策略配置表
CREATE TABLE password_security_configs (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id               VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    min_length              INT           NOT NULL DEFAULT 8 COMMENT '最小密码长度',
    require_uppercase       BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '需大写字母',
    require_lowercase       BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '需小写字母',
    require_digit           BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '需数字',
    require_special_char    BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '需特殊字符',
    max_failed_attempts     INT           NOT NULL DEFAULT 5 COMMENT '最大失败尝试次数',
    lock_duration_minutes   INT           NOT NULL DEFAULT 30 COMMENT '锁定时长(分钟)',
    password_expiry_days    INT           NOT NULL DEFAULT 90 COMMENT '密码过期周期(天)',
    password_history_count  INT           NOT NULL DEFAULT 5 COMMENT '历史密码重复检查数',
    second_factor_required  BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否需要二次验证',
    second_factor_methods   VARCHAR(128)  NOT NULL DEFAULT 'OTP' COMMENT '二次验证方式: OTP/TOTP/EMAIL',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密码安全策略配置表';

-- 二次验证记录表
CREATE TABLE second_factor_records (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id             BIGINT        NOT NULL COMMENT '商户 ID',
    factor_type             VARCHAR(16)   NOT NULL COMMENT '验证类型: OTP/TOTP/EMAIL',
    code_hash               VARCHAR(255)  NOT NULL COMMENT '验证码哈希',
    expires_at              TIMESTAMP(6)  NOT NULL COMMENT '过期时间',
    consumed                BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否已消费',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_merchant_expires (merchant_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='二次验证记录表';