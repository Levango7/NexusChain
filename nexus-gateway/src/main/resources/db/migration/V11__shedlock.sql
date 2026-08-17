-- V11: ShedLock distributed lock table (项10: ReconciliationTask multi-instance dedup).
--
-- Backs @SchedulerLock(name = "reconciliationTask") on ReconciliationTask.reconcile().
-- JdbcTemplateLockProvider (ShedLockConfig) performs atomic UPDATE/INSERT on this table
-- to acquire a distributed lock, ensuring only one gateway instance executes the
-- scheduled reconciliation task at any given moment in a multi-replica deployment.
--
-- Schema follows ShedLock JDBC provider contract (see shedlock-provider-jdbc docs):
--   name       : lock name (primary key, matches @SchedulerLock name attribute)
--   lock_until : timestamp until which the lock is held (db_time + lockAtMostFor)
--   locked_at  : timestamp when the lock was acquired (db_time)
--   locked_by  : identifier of the instance holding the lock (hostname by default)
--
-- Uses TIMESTAMP (not TIMESTAMP WITH TIME ZONE) to match the existing V1-V10 schema
-- convention (MySQL TIMESTAMP / PostgreSQL TIMESTAMP without TZ). JdbcTemplateLockProvider
-- with usingDbTime() reads/writes db-server-local time, avoiding JVM clock skew across
-- replicas. All replicas must keep db server time within acceptable skew (NTP-managed
-- in production).
--
-- IF NOT EXISTS guards against re-run on environments where the table was created
-- out-of-band (e.g. manual ops). Flyway checksums still enforce migration history.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);