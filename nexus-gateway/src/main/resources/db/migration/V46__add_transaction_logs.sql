-- V46: 分布式事务日志表 — Wave 10 核心资金能力
-- 持久化 TCC/SAGA 分布式事务的每一步执行状态，支持故障恢复和超时处理
-- 事务类型：TCC（Try-Confirm-Cancel）/ SAGA（编排式补偿）
-- 步骤状态：TRYING/CONFIRMED/CANCELLED/COMPENSATING/COMPLETED/FAILED

CREATE TABLE transaction_logs (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tx_id               VARCHAR(64)  NOT NULL COMMENT '事务编号，格式 DT{timestamp}{random}',
    tx_type             VARCHAR(8)   NOT NULL COMMENT '事务类型: TCC/SAGA',
    step_name           VARCHAR(64)  NOT NULL COMMENT '步骤名称',
    step_index          INT          NOT NULL COMMENT '步骤序号: TCC(0/1/2), SAGA(0..N)',
    status              VARCHAR(32)  NOT NULL COMMENT '步骤状态: TRYING/CONFIRMED/CANCELLED/COMPENSATING/COMPLETED/FAILED',
    business_reference  VARCHAR(128) NOT NULL COMMENT '关联业务凭证',
    participant_id      VARCHAR(64)  NOT NULL COMMENT '参与方标识',
    payload             TEXT         COMMENT 'JSON 格式载荷',
    tenant_id           VARCHAR(64)  COMMENT '多租户隔离键',
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_tl_tx_id UNIQUE (tx_id),
    INDEX idx_tl_status (status),
    INDEX idx_tl_business_ref (business_reference),
    INDEX idx_tl_tx_type_status (tx_type, status),
    INDEX idx_tl_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分布式事务日志表';