-- Wave 16 模块五：路由决策审计记录表
CREATE TABLE IF NOT EXISTS routing_decision_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    decision_id VARCHAR(64) NOT NULL COMMENT 'rd_{UUID}',
    payment_id VARCHAR(64) NOT NULL,
    merchant_id BIGINT,
    input_json VARCHAR(1024) COMMENT '路由输入：金额/币种/商户ID',
    rule_matched VARCHAR(64),
    strategy VARCHAR(32) NOT NULL COMMENT 'PRIORITY/WEIGHT/COST/MULTI_OBJECTIVE/AI',
    candidates_json VARCHAR(1024) COMMENT '候选 connector 列表',
    scores_json VARCHAR(2048) COMMENT '各候选评分明细',
    decision_json VARCHAR(1024) COMMENT '最终决策：选中的 connector 列表',
    ab_test_group VARCHAR(32),
    experiment_id VARCHAR(64),
    outcome VARCHAR(16) COMMENT 'SUCCESS/FAILURE/PENDING',
    latency_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rdr_decision_id (decision_id),
    KEY idx_rdr_payment (payment_id),
    KEY idx_rdr_merchant_time (merchant_id, created_at),
    KEY idx_rdr_strategy (strategy),
    KEY idx_rdr_experiment (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由决策审计记录';