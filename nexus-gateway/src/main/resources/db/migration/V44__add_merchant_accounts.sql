-- V44: 商户虚拟账户表 — Wave 10 核心资金能力
-- 商户虚拟账户支持三种类型：BALANCE（可用余额）、FROZEN（冻结资金）、RESERVE（备付金）
-- 账户状态机：ACTIVE → FROZEN → ACTIVE / ACTIVE → CLOSED（终态）

CREATE TABLE merchant_accounts (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    account_id      VARCHAR(64)  NOT NULL COMMENT '账户编号，格式 MA{merchantId}{accountType}',
    merchant_id     BIGINT       NOT NULL COMMENT '商户 ID',
    account_type    VARCHAR(32)  NOT NULL COMMENT '账户类型: BALANCE/FROZEN/RESERVE',
    balance         DECIMAL(36,0) NOT NULL DEFAULT 0 COMMENT '当前余额',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '账户状态: ACTIVE/FROZEN/CLOSED',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_ma_account_id UNIQUE (account_id),
    INDEX idx_ma_merchant_id_type (merchant_id, account_type),
    INDEX idx_ma_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户虚拟账户表';