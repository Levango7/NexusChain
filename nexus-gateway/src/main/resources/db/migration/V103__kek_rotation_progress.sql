-- V103: KEK 轮换迁移进度表（跨副本/重启可见的断点续传，#102 PLAN §4.2 · #31-Step3）
--
-- 号段说明：立项时记的是 V99。落地时 V99–V102 已被在途 PR #106（清结算/币种钱包/汇率/
--   实体漂移）占用；Flyway 遇同号双份会直接 "Found more than one migration with version"
--   把 migrate 判红，所以本表取当时全 ref 现查未占用的 V103。号要在提交前再查一次，
--   "立项时先占号"不足以防并发立项。
--
-- 背景：KeyRotationScheduler 此前把渐进式 DEK 迁移的进度（migrated/failed/断点
--   lastProcessedId/completed）放在单个 JVM 的 ConcurrentHashMap migrationProgressMap:48。
--   prod nexus-gateway replicaCount=3 ⇒
--     * checkKekRotationPeriod:63 遍历的是"各副本自己的内存表"来决定"是否续跑迁移/是否需轮换"，
--       三副本各看各的、判断不一致；
--     * initiateRotation 生成的进度只登记在发起副本，另两副本 getMigrationProgress 永远拿不到，
--       运维观测到的进度取决于打到哪个副本；
--     * 副本重调度后内存态丢失，断点归零、迁移从头再扫（migrateDeks 幂等重建，不至资损，
--       但破坏"断点续传"这一设计语义，见设计文档 §8.2.3）。
--
-- 语义：一行 = 一个 (old_version → new_version) 迁移任务的共享进度。last_processed_id 成为
--   跨副本共享断点——任一副本接着迁都从同一游标续，不从头扫；completed 标志位落库，避免多副本
--   重复触发 archiveKek（archiveKek 本身按"是否仍被引用"判定、幂等）。
--   old_version 唯一：同一旧 KEK 版本只对应一个迁移任务。
--
-- 这是"先持久化后加锁"链条的第一步：本表落地后，checkKekRotationPeriod 才适合补
--   @SchedulerLock（锁住"谁来做本轮检查"才有意义——状态已在库里，不再怕把执行权交给
--   没有该状态的副本）。
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL8 双兼容写法（与 V98/V11 同口径）。
CREATE TABLE kek_rotation_progress (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    old_version        INT          NOT NULL,
    new_version        INT          NOT NULL,
    total_records      BIGINT       NOT NULL,
    migrated_records   BIGINT       NOT NULL DEFAULT 0,
    failed_records     BIGINT       NOT NULL DEFAULT 0,
    last_processed_id  BIGINT       NOT NULL DEFAULT 0,
    completed          BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_krp_old_version UNIQUE (old_version)
);
