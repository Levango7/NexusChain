-- 告警事件表
CREATE TABLE alert_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    metric_name VARCHAR(128) NOT NULL,
    current_value DOUBLE NOT NULL,
    threshold DOUBLE NOT NULL,
    severity VARCHAR(16) NOT NULL,
    message VARCHAR(512),
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at TIMESTAMP NULL
);

CREATE INDEX idx_alert_events_rule ON alert_events(rule_name);
CREATE INDEX idx_alert_events_timestamp ON alert_events(timestamp);
CREATE INDEX idx_alert_events_resolved ON alert_events(resolved);