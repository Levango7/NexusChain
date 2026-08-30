-- ShedLock table for H2 embedded database (dev/DAST profile).
-- Created by spring.sql.init before Flyway runs, ensuring the table exists
-- even if Flyway migrations fail or are skipped.
-- IF NOT EXISTS guards against conflict with Flyway V11__shedlock.sql.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);