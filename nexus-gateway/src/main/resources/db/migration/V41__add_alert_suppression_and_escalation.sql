-- V41: 告警/运维深化 — 告警聚合 + 告警抑制 + 告警升级

-- 1. 告警聚合记录表
CREATE TABLE alert_aggregations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregation_key VARCHAR(256) NOT NULL UNIQUE,
    rule_name VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    count INT NOT NULL DEFAULT 0,
    first_event_id BIGINT,
    last_event_id BIGINT,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    aggregated_at TIMESTAMP(6) NOT NULL,
    notified BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_alert_aggregations_rule ON alert_aggregations(rule_name);
CREATE INDEX idx_alert_aggregations_notified ON alert_aggregations(notified, window_end);
CREATE INDEX idx_alert_aggregations_severity ON alert_aggregations(rule_name, severity);

-- 2. 告警抑制规则表
CREATE TABLE alert_suppression_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_rule_name VARCHAR(128) NOT NULL,
    child_rule_name VARCHAR(128) NOT NULL,
    suppress_duration_minutes INT NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_suppression_parent_child UNIQUE (parent_rule_name, child_rule_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_alert_suppression_rules_parent ON alert_suppression_rules(parent_rule_name, enabled);
CREATE INDEX idx_alert_suppression_rules_child ON alert_suppression_rules(child_rule_name, enabled);

-- 3. 告警升级规则表
CREATE TABLE alert_escalation_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL UNIQUE,
    escalate_after_minutes INT NOT NULL DEFAULT 30,
    from_severity VARCHAR(16) NOT NULL,
    to_severity VARCHAR(16) NOT NULL,
    escalation_channel VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_alert_escalation_rules_severity ON alert_escalation_rules(from_severity, enabled);
CREATE INDEX idx_alert_escalation_rules_enabled ON alert_escalation_rules(enabled);