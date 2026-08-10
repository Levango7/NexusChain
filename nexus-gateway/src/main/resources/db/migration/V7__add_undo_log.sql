-- V7: Seata AT 模式 undo_log 表.
-- gateway 的 refund / charge 方法标注了 @GlobalTransactional, 开启 Seata AT
-- 分布式事务后, 每个分支事务的 RM 需要在本地库中维护 undo_log 以支持自动回滚.
-- 该表与业务表同库 (nexus-gateway 自身 DataSource), 由 Seata RM 自动读写,
-- 应用层不直接操作. IF NOT EXISTS 保证幂等, 可在已有库上重复执行.

CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';