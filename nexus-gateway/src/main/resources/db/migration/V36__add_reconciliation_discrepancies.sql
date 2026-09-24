-- 对账差错记录表
-- 记录自动对账过程中发现的每条差异，包含差异类型分类、双边金额/状态信息、处置规则和状态流转
-- 差错生命周期：DISCOVERED → INVESTIGATING → RESOLVED/ESCALATED
CREATE TABLE reconciliation_discrepancies (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id            BIGINT       NOT NULL COMMENT '商户 ID',
    reconciliation_file_id BIGINT       COMMENT '关联的对账文件记录 ID',
    discrepancy_type       VARCHAR(32)  NOT NULL COMMENT '差异类型: LONG_AMOUNT/SHORT_AMOUNT/AMOUNT_MISMATCH/STATUS_MISMATCH/INFO_MISMATCH',
    resolution_type        VARCHAR(32)  NOT NULL COMMENT '处置规则: AUTO_RESOLVE/MANUAL_REVIEW/PENDING_INVESTIGATION',
    status                 VARCHAR(32)  NOT NULL DEFAULT 'DISCOVERED' COMMENT '差错状态: DISCOVERED/INVESTIGATING/RESOLVED/ESCALATED',
    transaction_id         VARCHAR(64)  NOT NULL COMMENT '交易 ID（订单号或渠道交易号）',
    channel_amount         DECIMAL(36,0) COMMENT '渠道侧金额',
    internal_amount        DECIMAL(36,0) COMMENT '内部侧金额',
    amount_diff            DECIMAL(36,0) COMMENT '金额差异绝对值',
    channel_status         VARCHAR(32)  COMMENT '渠道侧状态',
    internal_status        VARCHAR(32)  COMMENT '内部侧状态',
    description            VARCHAR(1024) COMMENT '差错描述',
    resolution_note        VARCHAR(1024) COMMENT '解决备注（人工审核时填写）',
    created_at             DATETIME     NOT NULL COMMENT '创建时间',
    updated_at             DATETIME     NOT NULL COMMENT '更新时间',
    resolved_at            DATETIME     COMMENT '解决时间',
    PRIMARY KEY (id),
    INDEX idx_rd_merchant_id (merchant_id),
    INDEX idx_rd_status (status),
    INDEX idx_rd_discrepancy_type (discrepancy_type),
    INDEX idx_rd_file_id (reconciliation_file_id),
    INDEX idx_rd_merchant_status (merchant_id, status),
    INDEX idx_rd_transaction_id (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差错记录表';