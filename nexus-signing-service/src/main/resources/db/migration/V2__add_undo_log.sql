-- V2: Seata AT 模式 undo_log 表
--
-- signing-service 作为 Seata AT 模式的 RM，在本地库维护 undo_log 以支持全局事务回滚。
-- 表结构与 gateway V7 / wallet V3 一致；本迁移补齐 signing 缺失的建表（L3 预演
-- 缺陷 #16 的配套项——安装脚本已预建该表，本迁移覆盖非脚本建库/存量库升级场景）。
--
-- H2 兼容性（dev/test profile 使用 H2 MODE=MySQL）：BLOB 代替 LONGBLOB；
-- 省略 ENGINE/CHARSET 后缀（MySQL 8 默认 InnoDB + utf8mb4）。
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
