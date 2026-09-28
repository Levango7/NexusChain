-- 告警规则表
-- 注意：比较条件列命名为 match_condition 而非 condition —— condition 是
-- MySQL 保留字（MySQL 8.0 严格语法下裸用报 1064），改名避免全链路引号依赖。
CREATE TABLE alert_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    metric_name VARCHAR(128) NOT NULL,
    match_condition VARCHAR(8) NOT NULL,
    threshold DOUBLE NOT NULL,
    window_minutes INT NOT NULL DEFAULT 5,
    cooldown_minutes INT NOT NULL DEFAULT 10,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    severity VARCHAR(16) NOT NULL DEFAULT 'WARN',
    description VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alert_rules_enabled ON alert_rules(enabled);
CREATE INDEX idx_alert_rules_metric ON alert_rules(metric_name);