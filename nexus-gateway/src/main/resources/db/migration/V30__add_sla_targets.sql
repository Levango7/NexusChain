-- SLA 目标定义表
-- 存储 SLA/SLO 监控的目标指标定义，包括可用性、延迟、吞吐量和错误率等类型
CREATE TABLE sla_targets (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(128) NOT NULL COMMENT 'SLA 目标名称',
    metric_name     VARCHAR(128) NOT NULL COMMENT '关联的 Micrometer 指标名称',
    target_value    DOUBLE       NOT NULL COMMENT '目标值（如 99.9 表示 99.9% 可用性）',
    target_type     VARCHAR(32)  NOT NULL COMMENT '目标类型: AVAILABILITY/LATENCY/THROUGHPUT/ERROR_RATE',
    window_minutes  INT          NOT NULL COMMENT '统计窗口（分钟）',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '是否启用',
    description     VARCHAR(512) COMMENT '描述信息',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_sla_targets_enabled (enabled),
    INDEX idx_sla_targets_metric_name (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 目标定义表';