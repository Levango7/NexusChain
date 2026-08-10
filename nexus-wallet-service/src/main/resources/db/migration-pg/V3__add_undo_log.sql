-- =============================================================================
-- V3: Seata AT 模式 undo_log 表（PostgreSQL 兼容版本，P2-T8）
-- =============================================================================
-- 由 V3__add_undo_log.sql（MySQL/H2 语法）转换而来：
--   * BLOB → BYTEA（PostgreSQL 二进制大对象类型）
--   * DATETIME(6) → TIMESTAMP(6)（PostgreSQL 不支持 DATETIME 关键字）
--   * UNIQUE KEY name (cols) → CONSTRAINT name UNIQUE (cols)
--
-- wallet-service 作为 Seata AT 模式的 RM（Resource Manager），在本地库维护 undo_log
-- 以支持全局事务回滚。该表由 Seata RM 自动读写，应用层不直接操作。
-- 与 gateway V7__add_undo_log.sql 结构一致（设计文档 §4.5.4）。

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL,
    xid           VARCHAR(128) NOT NULL,
    context       VARCHAR(128) NOT NULL,
    rollback_info BYTEA        NOT NULL,
    log_status    INT          NOT NULL,
    log_created   TIMESTAMP(6) NOT NULL,
    log_modified  TIMESTAMP(6) NOT NULL,
    CONSTRAINT ux_undo_log UNIQUE (xid, branch_id)
);