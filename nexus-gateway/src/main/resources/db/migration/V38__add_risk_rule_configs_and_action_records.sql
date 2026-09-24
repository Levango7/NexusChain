-- 风控规则配置表 — DB 驱动的规则配置管理
-- 支持动态启用/禁用规则、配置阈值和处置动作，无需重启即可调整风控策略
CREATE TABLE risk_rule_configs (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    rule_name     VARCHAR(128) NOT NULL COMMENT '规则名称（唯一）',
    rule_type     VARCHAR(32)  NOT NULL COMMENT '规则类型: SCORING/BLOCKING/COMPOSITE',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否启用',
    priority      INT          NOT NULL DEFAULT 50 COMMENT '优先级（数字越小优先级越高）',
    threshold     INT          COMMENT '阈值（评分阈值或拦截阈值）',
    action        VARCHAR(32)  COMMENT '处置动作: ALERT/BLOCK/MANUAL_REVIEW/CAPTURE',
    config_json   VARCHAR(2048) COMMENT '扩展配置（JSON 格式，存储规则特定参数）',
    created_at    DATETIME     NOT NULL COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL COMMENT '更新时间',
    version       BIGINT       COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rrc_rule_name (rule_name),
    INDEX idx_rrc_enabled_priority (enabled, priority),
    INDEX idx_rrc_rule_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则配置表';

-- 风控处置记录表 — 记录每次风控处置的执行结果
-- 每次风控评估触发处置动作时生成一条记录，用于审计追踪和风控闭环管理
CREATE TABLE risk_action_records (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    action_type     VARCHAR(32)  NOT NULL COMMENT '处置动作: ALERT/BLOCK/MANUAL_REVIEW/CAPTURE',
    action_status   VARCHAR(32)  NOT NULL DEFAULT 'EXECUTED' COMMENT '处置状态: EXECUTED/FAILED/PENDING/REVOKED',
    merchant_id     BIGINT       COMMENT '关联商户 ID',
    order_id        VARCHAR(64)  COMMENT '关联订单号',
    risk_score      INT          COMMENT '风控评分（0-100）',
    risk_decision   VARCHAR(32)  COMMENT '风控决策: APPROVED/REJECTED/PENDING_REVIEW/FROZEN',
    triggered_rules VARCHAR(1024) COMMENT '触发的规则列表（逗号分隔，含评分）',
    description     VARCHAR(512) COMMENT '处置描述',
    executed_at     DATETIME     NOT NULL COMMENT '处置执行时间',
    completed_at    DATETIME     COMMENT '处置完成时间',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    version         BIGINT       COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_rar_merchant_id (merchant_id),
    INDEX idx_rar_action_type (action_type),
    INDEX idx_rar_action_status (action_status),
    INDEX idx_rar_executed_at (executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控处置记录表';

-- 规则组合表 — 定义规则之间的组合逻辑、优先级和处置动作映射
-- 支持 AND/OR 组合表达式，将多条规则组合为一个复合判断单元
CREATE TABLE rule_compositions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    composition_name  VARCHAR(128) NOT NULL COMMENT '组合名称（唯一）',
    composition_type  VARCHAR(16)  NOT NULL COMMENT '组合逻辑类型: AND/OR',
    expression        VARCHAR(1024) NOT NULL COMMENT '组合表达式（如 IP_SCORE & REGION_SCORE）',
    priority          INT          NOT NULL DEFAULT 50 COMMENT '优先级（数字越小优先级越高）',
    action            VARCHAR(32)  NOT NULL COMMENT '处置动作: ALERT/BLOCK/MANUAL_REVIEW/CAPTURE',
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否启用',
    score_threshold   INT          COMMENT '评分阈值（组合触发时所需的最小评分）',
    created_at        DATETIME     NOT NULL COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL COMMENT '更新时间',
    version           BIGINT       COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rc_composition_name (composition_name),
    INDEX idx_rc_enabled_priority (enabled, priority),
    INDEX idx_rc_composition_type (composition_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则组合表';