-- V45: 账户流水表 — Wave 10 核心资金能力
-- 记录每一笔账户余额变更的不可篡改审计流水
-- 操作类型：RECHARGE/WITHDRAW/FREEZE/UNFREEZE/TRANSFER/PAYMENT_CREDIT/REFUND_DEBIT/VOID_REVERSE/REVERSAL_ADJUST/ESCROW_FREEZE/ESCROW_RELEASE/PREAUTH_FREEZE/PREAUTH_CAPTURE/PREAUTH_RELEASE
-- 方向：CREDIT（入账）/ DEBIT（出账）

CREATE TABLE account_transactions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tx_no           VARCHAR(64)  NOT NULL COMMENT '流水编号，格式 AT{timestamp}{random}',
    account_id      VARCHAR(64)  NOT NULL COMMENT '关联账户编号',
    merchant_id     BIGINT       NOT NULL COMMENT '关联商户 ID',
    operation_type  VARCHAR(32)  NOT NULL COMMENT '操作类型: RECHARGE/WITHDRAW/FREEZE/UNFREEZE/TRANSFER/PAYMENT_CREDIT/REFUND_DEBIT/VOID_REVERSE/REVERSAL_ADJUST/ESCROW_FREEZE/ESCROW_RELEASE/PREAUTH_FREEZE/PREAUTH_CAPTURE/PREAUTH_RELEASE',
    direction       VARCHAR(8)   NOT NULL COMMENT '方向: CREDIT(入账)/DEBIT(出账)',
    amount          DECIMAL(36,0) NOT NULL COMMENT '操作金额，必须 > 0',
    balance_before  DECIMAL(36,0) NOT NULL COMMENT '操作前余额',
    balance_after   DECIMAL(36,0) NOT NULL COMMENT '操作后余额',
    reference       VARCHAR(128) NOT NULL COMMENT '关联业务凭证（orderNo/refundNo/escrowNo/preauthNo/voidNo/reversalNo）',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_at_tx_no UNIQUE (tx_no),
    INDEX idx_at_account_id (account_id),
    INDEX idx_at_merchant_id_created (merchant_id, created_at),
    INDEX idx_at_reference (reference),
    INDEX idx_at_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户流水表';