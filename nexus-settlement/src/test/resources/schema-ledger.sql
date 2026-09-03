-- H2 schema for LedgerJdbcTest (@JdbcTest 真实落库验证)
-- Mirrors ledger_entry DDL in gateway V18__settlement_persistence.sql.
-- DROP IF EXISTS: @JdbcTest slice 可能多次执行 schema 脚本（事务回滚后重复初始化），
-- 幂等建表避免 "Table already exists"。

DROP TABLE IF EXISTS ledger_entry;
CREATE TABLE ledger_entry (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    entry_id   VARCHAR(64)    NOT NULL,
    account    VARCHAR(128)   NOT NULL,
    direction  VARCHAR(16)    NOT NULL,
    amount     DECIMAL(36,8)  NOT NULL,
    reference  VARCHAR(128),
    booked_at  TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ledger_entry_id UNIQUE (entry_id),
    CONSTRAINT uk_ledger_entry_ref_account UNIQUE (reference, account)
);