-- Wave 16 模块四（边界收尾）：路由实验组统计持久化表
-- 背景：RoutingExperimentService 的每组统计（count/successes/latency/cost）原为进程内
-- ConcurrentHashMap——重启清零，A/B 结论的样本积累被部署打断（CHANGELOG 已知边界登记项）。
-- 本表承载 recordOutcome 的 write-through 累加，启动时回灌为基线，重启后无缝续算。
CREATE TABLE IF NOT EXISTS routing_experiment_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id VARCHAR(64) NOT NULL,
    group_id VARCHAR(64) NOT NULL,
    event_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    total_cost_bps BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_res_exp_group (experiment_id, group_id),
    KEY idx_res_experiment (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由实验组统计（跨重启持久化）';
