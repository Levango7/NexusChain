-- Wave 16 模块四：路由实验配置表
CREATE TABLE IF NOT EXISTS routing_experiments (
    experiment_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/RUNNING/PAUSED/COMPLETED/TERMINATED',
    control_group_json VARCHAR(1024) NOT NULL COMMENT '对照组配置',
    experiment_groups_json VARCHAR(2048) NOT NULL COMMENT '实验组配置列表',
    target_metric VARCHAR(32) NOT NULL COMMENT 'SUCCESS_RATE/LATENCY/COST',
    significance_threshold DOUBLE NOT NULL DEFAULT 0.05,
    min_sample_size INT NOT NULL DEFAULT 1000,
    start_time TIMESTAMP NULL,
    end_time TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    KEY idx_re_status (status),
    KEY idx_re_time_range (start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由实验配置';