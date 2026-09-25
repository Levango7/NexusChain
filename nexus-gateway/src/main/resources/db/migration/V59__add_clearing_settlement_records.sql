-- V59: 清算结算记录表 — Wave 11 资金调拨与清算结算集成
-- 记录清算批次入账和结算划拨的全过程，支持对账追溯

CREATE TABLE clearing_settlement_records (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    record_no           VARCHAR(64)  NOT NULL COMMENT '记录编号，唯一标识',
    record_type         VARCHAR(32)  NOT NULL COMMENT '记录类型: CLEARING/SETTLEMENT_TRANSFER',
    batch_no            VARCHAR(64)  COMMENT '关联清算批次编号',
    merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
    amount              DECIMAL(36,0) NOT NULL COMMENT '金额',
    direction           VARCHAR(8)   NOT NULL COMMENT '方向: CREDIT/DEBIT',
    booking_status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '入账状态: PENDING/BOOKED/FAILED',
    account_id          VARCHAR(64)  COMMENT '关联账户编号',
    operation_type      VARCHAR(32)  COMMENT '操作类型（对应 AccountOperationType）',
    transfer_reference  VARCHAR(128) COMMENT '转账关联凭证',
    error_message       VARCHAR(512) COMMENT '失败原因（BOOKED=失败时记录）',
    settled_at          TIMESTAMP(6) COMMENT '结算时间',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_csr_record_no UNIQUE (record_no),
    INDEX idx_csr_batch_no (batch_no),
    INDEX idx_csr_merchant_id (merchant_id),
    INDEX idx_csr_booking_status (booking_status),
    INDEX idx_csr_record_type (record_type),
    INDEX idx_csr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='清算结算记录表';