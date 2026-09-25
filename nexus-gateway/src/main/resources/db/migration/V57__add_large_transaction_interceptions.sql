-- V57: 大额交易拦截表 — Wave 11 方向 3
-- 记录被拦截的大额交易，等待人工审核或自动超时升级告警

CREATE TABLE large_transaction_interceptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    interception_id VARCHAR(64) NOT NULL COMMENT '拦截记录唯一标识',
    merchant_id BIGINT NOT NULL COMMENT '商户 ID',
    order_id VARCHAR(64) NULL COMMENT '关联订单号',
    amount DECIMAL(36, 8) NOT NULL COMMENT '交易金额',
    currency VARCHAR(16) NULL DEFAULT 'USD' COMMENT '币种',
    threshold DECIMAL(36, 8) NOT NULL COMMENT '触发拦截的阈值',
    interception_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '拦截状态: PENDING_REVIEW/APPROVED/REJECTED/TIMEOUT_ESCALATED',
    risk_event_id VARCHAR(64) NULL COMMENT '关联风控事件 ID',
    reviewer_id BIGINT NULL COMMENT '审核人 ID',
    review_comment VARCHAR(512) NULL COMMENT '审核意见',
    reviewed_at DATETIME NULL COMMENT '审核时间',
    timeout_at DATETIME NULL COMMENT '超时时间（24h 后）',
    escalated_at DATETIME NULL COMMENT '升级告警时间',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    version BIGINT NULL COMMENT '乐观锁版本号',
    CONSTRAINT idx_lti_interception_id UNIQUE (interception_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大额交易拦截表';

CREATE INDEX idx_lti_merchant_id ON large_transaction_interceptions(merchant_id);
CREATE INDEX idx_lti_interception_status ON large_transaction_interceptions(interception_status);
CREATE INDEX idx_lti_timeout_at ON large_transaction_interceptions(timeout_at);