-- V3: Seata AT 模式 undo_log 表（Phase 4 任务 #68，设计文档 §4.1.5 / §4.5.3）
--
-- wallet-service 作为 Seata AT 模式的 RM（Resource Manager），在本地库维护 undo_log
-- 以支持全局事务回滚。该表由 Seata RM 自动读写，应用层不直接操作。
-- 与 gateway V7__add_undo_log.sql 结构一致（设计文档 §4.5.4）。
--
-- H2 兼容性说明（application-dev.yml / application-test.yml 使用 H2 MODE=MySQL）：
--   * 用 BLOB 代替 LONGBLOB — H2 不支持 LONGBLOB；BLOB 在 H2 和 MySQL 均可用，
--     rollback_info 实际大小远小于 64KB 上限，功能等价
--   * 不使用 ENGINE=InnoDB / DEFAULT CHARSET=utf8mb4 后缀 — H2 不支持；
--     MySQL 8.x 默认 InnoDB + utf8mb4，省略后行为一致
--   * DATETIME(6) — H2 MODE=MySQL 支持微秒精度
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL, -- branch transaction id
    xid           VARCHAR(128) NOT NULL, -- global transaction id
    context       VARCHAR(128) NOT NULL, -- undo_log context, such as serialization
    rollback_info BLOB         NOT NULL, -- rollback info
    log_status    INT          NOT NULL, -- 0:normal status,1:defense status
    log_created   DATETIME(6)  NOT NULL, -- create datetime
    log_modified  DATETIME(6)  NOT NULL, -- modify datetime
    UNIQUE KEY ux_undo_log (xid, branch_id)
);