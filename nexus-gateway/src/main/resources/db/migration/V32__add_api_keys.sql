-- API Key 生命周期管理表
-- 存储 API Key 的公开标识、密钥哈希、权限范围、状态、过期时间等
-- 密钥（key_secret）以 SHA-256 哈希存储，不存明文
CREATE TABLE api_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    key_id          VARCHAR(64)  NOT NULL COMMENT '公开标识（ak_live_xxxx）',
    key_secret      VARCHAR(256) NOT NULL COMMENT 'SHA-256 哈希后的密钥',
    merchant_id     VARCHAR(64)  NOT NULL COMMENT '所属商户 ID（关联 tenants.tenant_id）',
    scopes          VARCHAR(256) NOT NULL COMMENT '权限范围（逗号分隔：PAYMENTS,REFUNDS,...）',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/EXPIRED/REVOKED/ROTATED',
    expire_at       DATETIME     NULL COMMENT '过期时间（NULL 表示永不过期）',
    rotated_from_id VARCHAR(64)  NULL COMMENT '轮换时关联的旧 Key 的 keyId',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    last_used_at    DATETIME     NULL COMMENT '最后使用时间',
    revoked_at      DATETIME     NULL COMMENT '撤销时间',
    revoked_reason  VARCHAR(512) NULL COMMENT '撤销原因',
    description     VARCHAR(512) NULL COMMENT 'Key 用途描述',
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_keys_key_id (key_id),
    INDEX idx_api_keys_merchant_id (merchant_id),
    INDEX idx_api_keys_status (status),
    INDEX idx_api_keys_merchant_status (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key 生命周期管理表';