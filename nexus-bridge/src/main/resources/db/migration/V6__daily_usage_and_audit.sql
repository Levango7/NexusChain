-- V6: 日限额用量与审计快照持久化（PLAN-038 / roadmap #33 ②）。
--
-- 背景：BridgeServiceImpl 的日限额计数器（dailyUsed）与
-- DailyLimitAuditService 的快照历史此前均为进程内状态：
--   * 计数器重启清零 ⇒ 当日限额可被"重启"绕过；多副本各持一份 ⇒
--     实际可允出 N 倍限额；
--   * 快照历史重启丢失，且多副本各自为政。
-- 本迁移为 DailyUsageStore 的 jpa 档（JdbcDailyUsageStore）与
-- DailyLimitAuditService 的落库快照提供存储。
--
-- 与实体的关系：与 nexus-bridge 其它迁移一致，本模块运行时 schema 由
-- Hibernate ddl-auto=update 维护，本脚本是 CI Flyway 预检用的"未接线"
-- 历史文件（见 V0 头注），字段必须与 BridgeDailyUsageRecord /
-- BridgeDailyLimitSnapshot 实体一致。
--
-- 语法说明：
--   * CREATE TABLE IF NOT EXISTS 兼容 MySQL 8.0 / H2（同 V0）。
--   * bridge_daily_usage 为单行表（id 恒为 1），行级悲观锁由
--     BridgeDailyUsageRepository.findByIdForUpdate 在运行时施加，
--     无需额外索引。
--   * window_start / used 用 BIGINT（epoch millis / 最小单位整数），
--     与实体 long 字段一致。

CREATE TABLE IF NOT EXISTS bridge_daily_usage (
    id           BIGINT    NOT NULL,
    window_start BIGINT    NOT NULL,
    used         BIGINT    NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    CONSTRAINT pk_bridge_daily_usage PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS bridge_daily_limit_snapshot (
    date_key    VARCHAR(16) NOT NULL,
    used        BIGINT      NOT NULL,
    captured_at TIMESTAMP   NOT NULL,
    CONSTRAINT pk_bridge_daily_limit_snapshot PRIMARY KEY (date_key)
);
