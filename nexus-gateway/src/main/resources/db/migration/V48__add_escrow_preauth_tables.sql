-- V48: 担保交易/预授权表 — Wave 10 核心资金能力
-- 担保交易（ESCROW）：买家付款冻结在担保账户，确认收货后释放给商户
-- 担保状态：CREATED/FUNDED/CONFIRMED/RELEASED/REFUNDED
-- 预授权（PREAUTH）：先冻结金额，后续可扣款（全额或部分）或释放
-- 预授权状态：AUTHORIZED/CAPTURED/VOIDED/EXPIRED

-- 1. 担保交易表
CREATE TABLE escrow_transactions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    escrow_no           VARCHAR(64)  NOT NULL COMMENT '担保交易编号，格式 EC{timestamp}{random}',
    merchant_id         BIGINT       NOT NULL COMMENT '关联商户 ID',
    order_id            BIGINT       NOT NULL COMMENT '关联支付订单 ID',
    amount              DECIMAL(36,0) NOT NULL COMMENT '担保金额',
    status              VARCHAR(32)  NOT NULL DEFAULT 'CREATED' COMMENT '担保状态: CREATED/FUNDED/CONFIRMED/RELEASED/REFUNDED',
    buyer_address       VARCHAR(66)  NOT NULL COMMENT '买家钱包地址',
    escrow_account_id   VARCHAR(64)  NOT NULL COMMENT '担保冻结账户编号',
    auto_confirm_days   INT          NOT NULL DEFAULT 7 COMMENT '超时自动确认天数',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    funded_at           TIMESTAMP(6) NULL DEFAULT NULL COMMENT '买家付款时间',
    confirmed_at        TIMESTAMP(6) NULL DEFAULT NULL COMMENT '确认收货时间',
    released_at         TIMESTAMP(6) NULL DEFAULT NULL COMMENT '资金释放时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_et_escrow_no UNIQUE (escrow_no),
    INDEX idx_et_merchant_id_status (merchant_id, status),
    INDEX idx_et_order_id (order_id),
    INDEX idx_et_status_funded_at (status, funded_at),
    INDEX idx_et_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='担保交易表';

-- 2. 预授权交易表
CREATE TABLE preauth_transactions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    preauth_no          VARCHAR(64)  NOT NULL COMMENT '预授权编号，格式 PA{timestamp}{random}',
    merchant_id         BIGINT       NOT NULL COMMENT '关联商户 ID',
    order_id            BIGINT       NULL DEFAULT NULL COMMENT '关联支付订单 ID（可选）',
    freeze_amount       DECIMAL(36,0) NOT NULL COMMENT '冻结金额',
    capture_amount      DECIMAL(36,0) NOT NULL DEFAULT 0 COMMENT '已扣款金额',
    status              VARCHAR(32)  NOT NULL DEFAULT 'AUTHORIZED' COMMENT '预授权状态: AUTHORIZED/CAPTURED/VOIDED/EXPIRED',
    frozen_account_id   VARCHAR(64)  NOT NULL COMMENT '冻结账户编号',
    auto_release_days   INT          NOT NULL DEFAULT 3 COMMENT '超时自动释放天数',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    authorized_at       TIMESTAMP(6) NULL DEFAULT NULL COMMENT '授权时间',
    captured_at         TIMESTAMP(6) NULL DEFAULT NULL COMMENT '扣款时间',
    voided_at           TIMESTAMP(6) NULL DEFAULT NULL COMMENT '撤销时间',
    expired_at          TIMESTAMP(6) NULL DEFAULT NULL COMMENT '过期时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_pat_preauth_no UNIQUE (preauth_no),
    INDEX idx_pat_merchant_id_status (merchant_id, status),
    INDEX idx_pat_status_authorized_at (status, authorized_at),
    INDEX idx_pat_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预授权交易表';