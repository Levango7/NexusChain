-- =============================================================================
-- V7: Seata AT 模式 undo_log 表（PostgreSQL 兼容版本，P2-T8）
-- =============================================================================
-- 由 V7__add_undo_log.sql（MySQL 语法）转换而来：
--   * LONGBLOB → BYTEA（PostgreSQL 二进制大对象类型）
--   * DATETIME(6) → TIMESTAMP(6)（PostgreSQL 不支持 DATETIME 关键字）
--   * UNIQUE KEY name (cols) → CONSTRAINT name UNIQUE (cols)（PostgreSQL 语法）
--   * 内联 COMMENT '...' → 删除（改用行注释；如需可加 COMMENT ON COLUMN 语句）
--   * ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='...' → 删除
--     （PostgreSQL 无存储引擎概念，字符集在库级别 initdb --encoding=UTF8 设置）
--
-- gateway 的 refund / charge 方法标注了 @GlobalTransactional, 开启 Seata AT
-- 分布式事务后, 每个分支事务的 RM 需要在本地库中维护 undo_log 以支持自动回滚.
-- 该表与业务表同库 (nexus-gateway 自身 DataSource), 由 Seata RM 自动读写,
-- 应用层不直接操作. IF NOT EXISTS 保证幂等, 可在已有库上重复执行.

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