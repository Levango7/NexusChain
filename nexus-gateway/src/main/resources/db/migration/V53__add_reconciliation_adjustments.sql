-- V53: 对账差异调整记录表 — 对账与资金账户联动
-- 记录对账差异自动调整和人工审批调整的全过程
-- 调整类型: CREDIT_ADJUST(加钱)/DEBIT_ADJUST(扣钱)
-- 审批状态: AUTO_APPROVED(自动通过)/PENDING_APPROVAL(待审批)/APPROVED(已通过)/REJECTED(已拒绝)

CREATE TABLE reconciliation_adjustments (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id         BIGINT        NOT NULL COMMENT '商户 ID',
    discrepancy_id      BIGINT        NULL DEFAULT NULL COMMENT '关联的对账差错记录 ID',
    adjustment_type     VARCHAR(32)   NOT NULL COMMENT '调整类型: CREDIT_ADJUST/DEBIT_ADJUST',
    amount              DECIMAL(36,0) NOT NULL COMMENT '调整金额',
    approval_status     VARCHAR(32)   NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '审批状态: AUTO_APPROVED/PENDING_APPROVAL/APPROVED/REJECTED',
    reference           VARCHAR(128)  NOT NULL COMMENT '关联业务凭证（幂等键）',
    description         VARCHAR(1024) COMMENT '调整描述',
    approved_by         VARCHAR(64)   COMMENT '审批人',
    approved_at         TIMESTAMP(6)  NULL DEFAULT NULL COMMENT '审批时间',
    rejection_reason    VARCHAR(1024) COMMENT '拒绝原因',
    executed            BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '是否已执行资金调整',
    executed_at         TIMESTAMP(6)  NULL DEFAULT NULL COMMENT '资金调整执行时间',
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version             BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_ra_reference (reference),
    INDEX idx_ra_merchant_id (merchant_id),
    INDEX idx_ra_discrepancy_id (discrepancy_id),
    INDEX idx_ra_approval_status (approval_status),
    INDEX idx_ra_adjustment_type (adjustment_type),
    INDEX idx_ra_merchant_status (merchant_id, approval_status),
    INDEX idx_ra_executed (executed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异调整记录表';