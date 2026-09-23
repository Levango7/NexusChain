-- SLA 测量记录表
-- 每次 SLA 监控检查时生成的测量结果，记录实际指标值、目标值、是否达标及时间窗口
CREATE TABLE sla_measurements (
    id              BIGINT   NOT NULL AUTO_INCREMENT,
    target_id       BIGINT   NOT NULL COMMENT '关联的 SlaTarget ID',
    measured_value  DOUBLE   NOT NULL COMMENT '实际测量值',
    target_value    DOUBLE   NOT NULL COMMENT '目标值（冗余存储）',
    is_met          BOOLEAN  NOT NULL COMMENT '是否达标',
    measured_at     DATETIME NOT NULL COMMENT '测量时间',
    window_start    DATETIME NOT NULL COMMENT '统计窗口起始时间',
    window_end      DATETIME NOT NULL COMMENT '统计窗口结束时间',
    PRIMARY KEY (id),
    INDEX idx_sla_measurements_target_id (target_id),
    INDEX idx_sla_measurements_measured_at (measured_at),
    INDEX idx_sla_measurements_target_id_met (target_id, is_met),
    INDEX idx_sla_measurements_target_id_time (target_id, measured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA 测量记录表';