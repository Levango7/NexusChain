-- V0: bridge_transactions / bridge_tx_validators 建表（P1-F2 / P2-F2）。
--
-- 背景（2026-08-28 修复）：nexus-bridge 此前只有 V1~V3（建索引/幂等表/Saga 表），
-- 缺少 bridge_transactions 基础建表脚本——表由 Hibernate ddl-auto 在运行时创建，
-- 导致 CI Flyway 预检在干净 MySQL 库上执行 V1 建索引时
-- "Table 'bridge_transactions' doesn't exist" 失败。
-- gateway（V1__init_schema.sql）/ wallet（V1__init_wallet_tables.sql）均有建表脚本，
-- 此处补齐，与 Hibernate 实体 BridgeTransaction 字段保持一致。
--
-- 语法说明：
--   * CREATE TABLE IF NOT EXISTS 兼容 MySQL 8.0 / H2 / PostgreSQL / SQLite。
--   * 唯一索引不在此建（V1__bridge_tx_source_hash_unique.sql 已建
--     idx_bridge_tx_source_hash），避免重复。
--   * 本脚本放 V0：干净库先建表再跑 V1~V3；已应用 V1 的环境补跑 V0 时
--     IF NOT EXISTS 直接跳过，不破坏已发布版本号与 checksum。

CREATE TABLE IF NOT EXISTS bridge_transactions (
    tx_id               VARCHAR(64)   NOT NULL,
    operation_type      VARCHAR(32)   NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    source_chain_id     VARCHAR(64),
    target_chain_id     VARCHAR(64),
    amount              BIGINT        NOT NULL,
    user_address        VARCHAR(128),
    target_address      VARCHAR(128),
    source_tx_hash      VARCHAR(128),
    target_tx_hash      VARCHAR(128),
    related_tx_id       VARCHAR(64),
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    timelock_expires_at TIMESTAMP,
    failure_reason      VARCHAR(512),
    memo                VARCHAR(512),
    CONSTRAINT pk_bridge_transactions PRIMARY KEY (tx_id)
);

-- @ElementCollection 集合表：参与签名的验证者 ID 集合（BridgeTransaction.validatorIds）
CREATE TABLE IF NOT EXISTS bridge_tx_validators (
    tx_id        VARCHAR(64)  NOT NULL,
    validator_id VARCHAR(64)  NOT NULL,
    CONSTRAINT pk_bridge_tx_validators PRIMARY KEY (tx_id, validator_id)
);
