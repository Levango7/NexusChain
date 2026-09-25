-- V47: 撤销/冲正表 — Wave 10 核心资金能力
-- 当日撤销（VOID）：回滚当日已支付交易，资金直接退回买家
-- 隔日冲正（REVERSAL）：对已结算交易进行反向调整，需生成新的链上转账
-- 撤销状态：PENDING/COMPLETED/FAILED/PARTIAL_VOIDED
-- 冲正状态：PENDING/COMPLETED/FAILED/REVERSAL_PENDING
-- 冲正类型：MANUAL（人工发起）/ AUTO（自动触发）

-- 1. 撤销请求表
CREATE TABLE void_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    void_no         VARCHAR(64)  NOT NULL COMMENT '撤销编号，格式 VD{timestamp}{random}',
    order_id        BIGINT       NOT NULL COMMENT '关联原支付订单 ID',
    merchant_id     BIGINT       NOT NULL COMMENT '关联商户 ID',
    amount          DECIMAL(36,0) NOT NULL COMMENT '撤销金额',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '撤销状态: PENDING/COMPLETED/FAILED/PARTIAL_VOIDED',
    reason          VARCHAR(256) COMMENT '撤销原因',
    operator_id     VARCHAR(64)  NOT NULL COMMENT '操作人 ID',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    completed_at    TIMESTAMP(6) NULL DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_vr_void_no UNIQUE (void_no),
    INDEX idx_vr_order_id (order_id),
    INDEX idx_vr_status (status),
    INDEX idx_vr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='撤销请求表';

-- 2. 冲正请求表
CREATE TABLE reversal_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    reversal_no     VARCHAR(64)  NOT NULL COMMENT '冲正编号，格式 RV{timestamp}{random}',
    order_id        BIGINT       NOT NULL COMMENT '关联原支付订单 ID',
    merchant_id     BIGINT       NOT NULL COMMENT '关联商户 ID',
    amount          DECIMAL(36,0) NOT NULL COMMENT '冲正金额',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '冲正状态: PENDING/COMPLETED/FAILED/REVERSAL_PENDING',
    reversal_type   VARCHAR(8)   NOT NULL DEFAULT 'MANUAL' COMMENT '冲正类型: MANUAL/AUTO',
    reason          VARCHAR(256) COMMENT '冲正原因',
    operator_id     VARCHAR(64)  NOT NULL COMMENT '操作人 ID',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    completed_at    TIMESTAMP(6) NULL DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_rr_reversal_no UNIQUE (reversal_no),
    INDEX idx_rr_order_id (order_id),
    INDEX idx_rr_status (status),
    INDEX idx_rr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冲正请求表';