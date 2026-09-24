-- V42: 挂账资金记录表 — 差错资金自动挂账 + 人工处理流程
-- 对账发现差错时自动创建挂账记录，追踪差错资金的处置过程
-- 挂账生命周期：PENDING → RESOLVED / WRITTEN_OFF

CREATE TABLE suspense_accounts (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    discrepancy_id    BIGINT       NOT NULL COMMENT '关联的对账差错记录 ID',
    merchant_id       BIGINT       NOT NULL COMMENT '商户 ID',
    amount            DECIMAL(36,0) NOT NULL COMMENT '挂账金额',
    discrepancy_type  VARCHAR(32)  NOT NULL COMMENT '差错类型: LONG_PAYMENT/SHORT_PAYMENT/AMOUNT_MISMATCH/STATUS_MISMATCH',
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '挂账状态: PENDING/RESOLVED/WRITTEN_OFF',
    description       VARCHAR(1024) COMMENT '挂账描述',
    created_at        DATETIME     NOT NULL COMMENT '创建时间',
    resolved_at       DATETIME     COMMENT '核销/注销时间',
    resolved_by       VARCHAR(64)  COMMENT '核销/注销操作人',
    resolution_note   VARCHAR(1024) COMMENT '核销/注销备注',
    proposal          VARCHAR(1024) COMMENT '人工处理方案',
    proposed_by       VARCHAR(64)  COMMENT '方案提交人',
    proposal_status   VARCHAR(32)  COMMENT '方案审批状态: PENDING/APPROVED/REJECTED',
    approved_by       VARCHAR(64)  COMMENT '审批人',
    rejection_reason  VARCHAR(1024) COMMENT '拒绝原因',
    PRIMARY KEY (id),
    INDEX idx_sa_merchant_id (merchant_id),
    INDEX idx_sa_status (status),
    INDEX idx_sa_discrepancy_type (discrepancy_type),
    INDEX idx_sa_discrepancy_id (discrepancy_id),
    INDEX idx_sa_merchant_status (merchant_id, status),
    INDEX idx_sa_proposal_status (proposal_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂账资金记录表';