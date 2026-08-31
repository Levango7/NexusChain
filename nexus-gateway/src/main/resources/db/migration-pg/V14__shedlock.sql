-- =============================================================================
-- V14: ShedLock distributed lock table (PostgreSQL)
-- S5 修复：由 V11__shedlock.sql（MySQL）转换。
-- Backs @SchedulerLock（ReconciliationTask 多实例去重）。
-- Schema follows ShedLock JDBC provider contract:
--   name/lock_until/locked_at/locked_by
-- =============================================================================

CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
