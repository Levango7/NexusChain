-- =============================================================================
-- Seata Server 2.0.0 DB 建表 SQL（store.mode=db 模式必需）
--
-- 数据库：seata（需先创建：CREATE DATABASE seata DEFAULT CHARSET utf8mb4;）
-- 设计文档 §3.1.3 / §4.2.1
--
-- 使用方式：
--   mysql -h<host> -P<port> -u<user> -p<password> seata < seata-server-db.sql
--
-- 表说明：
--   global_table       - 全局事务表（TC 记录全局事务状态）
--   branch_table       - 分支事务表（TC 记录分支事务状态）
--   lock_table         - 锁表（TC 全局锁，AT 模式防脏写）
--   distributed_lock   - 分布式锁表（Seata 2.0.0 新增，用于 TC 集群选主 / 防并发）
-- =============================================================================

-- the table to store GlobalSession data
CREATE TABLE IF NOT EXISTS `global_table`
(
    `xid`                       VARCHAR(128) NOT NULL,
    `transaction_id`            BIGINT,
    `status`                    TINYINT      NOT NULL,
    `application_id`            VARCHAR(32),
    `transaction_service_group` VARCHAR(32),
    `transaction_name`          VARCHAR(128),
    `timeout`                   INT,
    `begin_time`                BIGINT,
    `application_data`          VARCHAR(2000),
    `gmt_create`                DATETIME(6),
    `gmt_modified`              DATETIME(6),
    PRIMARY KEY (`xid`),
    KEY `idx_status_gmt_modified` (`status` , `gmt_modified`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- the table to store BranchSession data
CREATE TABLE IF NOT EXISTS `branch_table`
(
    `branch_id`         BIGINT       NOT NULL,
    `xid`               VARCHAR(128) NOT NULL,
    `transaction_id`    BIGINT,
    `resource_group_id` VARCHAR(32),
    `resource_id`       VARCHAR(256),
    `branch_type`       VARCHAR(8),
    `status`            TINYINT,
    `client_id`         VARCHAR(64),
    `application_data`  VARCHAR(2000),
    `gmt_create`        DATETIME(6),
    `gmt_modified`      DATETIME(6),
    PRIMARY KEY (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- the table to store lock data
CREATE TABLE IF NOT EXISTS `lock_table`
(
    `row_lock_id`     BIGINT       NOT NULL,
    `xid`             VARCHAR(128) NOT NULL,
    `transaction_id`  BIGINT,
    `branch_id`       BIGINT       NOT NULL,
    `resource_id`     VARCHAR(256),
    `table_name`      VARCHAR(32),
    `pk`              VARCHAR(36),
    `status`          TINYINT      NOT NULL DEFAULT '0' COMMENT '0:locked ,1:rollbacking',
    `gmt_create`      DATETIME(6),
    `gmt_modified`    DATETIME(6),
    PRIMARY KEY (`row_lock_id`),
    KEY `idx_branch_id` (`branch_id`),
    KEY `idx_xid` (`xid`),
    KEY `idx_xid_branch_id` (`xid` , `branch_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- the table to store distributed lock
CREATE TABLE IF NOT EXISTS `distributed_lock`
(
    `lock_key`   VARCHAR(20) NOT NULL,
    `lock_value` VARCHAR(20) NOT NULL,
    `expire`     BIGINT,
    primary key (`lock_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 初始化 distributed_lock（Seata 2.0.0 需要）
INSERT IGNORE INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('handleAllSession', '1', 0);