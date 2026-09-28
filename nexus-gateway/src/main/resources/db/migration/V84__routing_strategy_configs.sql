-- Wave 16 模块一：多目标路由策略配置表 + routing_rules 扩展
CREATE TABLE IF NOT EXISTS routing_strategy_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL COMMENT '策略配置名称，唯一',
    objective_weights_json VARCHAR(512) NOT NULL COMMENT '目标权重 {"COST":0.25,...}',
    conditions_json VARCHAR(1024) COMMENT '适用条件，复用 RoutingRule.conditions 语义',
    priority INT NOT NULL DEFAULT 0 COMMENT '优先级，越大越优先',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_by VARCHAR(64) COMMENT '创建人',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rsc_name (name),
    KEY idx_rsc_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多目标路由策略配置';

ALTER TABLE routing_rules
    ADD COLUMN strategy_config_id BIGINT NULL COMMENT '关联 routing_strategy_configs.id，NULL=使用默认权重';
CREATE INDEX idx_rr_strategy_config ON routing_rules (strategy_config_id);