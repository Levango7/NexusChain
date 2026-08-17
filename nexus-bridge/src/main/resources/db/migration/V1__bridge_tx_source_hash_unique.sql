-- V1: Unique index on bridge_transactions(source_tx_hash).
-- P1-F2: 防止中继者重放同一笔源链 lock/burn 导致双倍 mint/unlock。
-- 应用层 (BridgeServiceImpl.lock/burn) 已先做 findBySourceTxHash 检查，
-- 此处为 DB 层硬约束兜底，确保即使应用层遗漏或并发竞态也无法写入重复哈希。
--
-- source_tx_hash 可空（mint/unlock 阶段不设置），MySQL/H2/PostgreSQL 的唯一索引
-- 均允许多个 NULL 共存，不影响无源链哈希的记录。
--
-- 语法说明：
--   * H2（dev profile）/ PostgreSQL / SQLite 支持 CREATE UNIQUE INDEX IF NOT EXISTS。
--   * MySQL 8.0 不支持 IF NOT EXISTS，但生产环境由 Flyway schema history 保证
--     本脚本仅执行一次，不会触发重复创建错误。
--   * 若需在 MySQL 上手动幂等执行，可改为 ALTER TABLE ... ADD CONSTRAINT
--     并捕获重复键错误，或拆分为条件性 DDL。

CREATE UNIQUE INDEX IF NOT EXISTS idx_bridge_tx_source_hash
    ON bridge_transactions (source_tx_hash);