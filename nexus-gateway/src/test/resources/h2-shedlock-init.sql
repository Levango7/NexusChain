-- S3/S1 测试适配（2026-08-31 交付前审计）：H2 测试库的 shedlock 表初始化。
-- 背景：application-test.yml 禁用 Flyway 且 ddl-auto=create-drop 只建 @Entity 表；
-- shedlock 表无 JPA 实体（ShedLock JDBC provider 契约表），调度器启动期首个
-- @Scheduled 任务（含经 @PreAuthorize AOP 链激活的路径）INSERT 该表即
-- Table not found → 异常冒泡为端点 500。
-- 与 db/migration/V11__shedlock.sql（MySQL 版）同构；H2 兼容语法。

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
