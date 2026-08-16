-- =============================================================================
-- REQ-21 安全加固：consortium 初始 schema 迁移脚本
-- =============================================================================
-- 替代 Hibernate ddl-auto=create-drop，确保生产环境 schema 受控、可审计、可回滚。
-- 基于 org.nexus.consortium.entity 包内 @Entity 类生成：
--   - Header (HeaderAdapter) : 区块头表
--   - Transaction            : 交易表
--
-- Flyway baseline-on-migrate=true：对已存在 schema 的数据库建立 baseline (version=0)，
-- 本脚本 (V1) 仅在空库或 baseline 之后执行，不会破坏既有数据。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 区块头表 header
-- 对应 org.nexus.consortium.entity.Header / HeaderAdapter
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS header (
    block_hash   BYTEA       NOT NULL,
    version      INT         NOT NULL,
    hash_prev    BYTEA       NOT NULL,
    merkle_root  BYTEA       NOT NULL,
    block_height BIGINT      NOT NULL,
    created_at   BIGINT      NOT NULL,
    payload      BYTEA       NOT NULL,
    CONSTRAINT pk_header PRIMARY KEY (block_hash)
);

CREATE INDEX IF NOT EXISTS block_hash_index   ON header (block_hash);
CREATE INDEX IF NOT EXISTS hash_prev_index    ON header (hash_prev);
CREATE INDEX IF NOT EXISTS height_index       ON header (block_height);
CREATE INDEX IF NOT EXISTS created_at_index   ON header (created_at);

-- -----------------------------------------------------------------------------
-- 交易表 transaction
-- 对应 org.nexus.consortium.entity.Transaction
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction (
    tx_block_hash    BYTEA       NOT NULL,
    tx_block_height  BIGINT      NOT NULL,
    tx_hash          BYTEA       NOT NULL,
    tx_version       INT         NOT NULL,
    tx_type          INT         NOT NULL,
    tx_created_at    BIGINT      NOT NULL,
    tx_nonce         BIGINT      NOT NULL,
    tx_from          BYTEA       NOT NULL,
    tx_gas_price     BIGINT      NOT NULL,
    tx_amount        BIGINT      NOT NULL,
    tx_payload       BYTEA       NOT NULL,
    tx_to            BYTEA       NOT NULL,
    tx_signature     BYTEA       NOT NULL,
    tx_position      INT         NOT NULL,
    rlpbytes         BYTEA,
    CONSTRAINT pk_transaction PRIMARY KEY (tx_hash)
);

CREATE INDEX IF NOT EXISTS tx_block_hash_index     ON transaction (tx_block_hash);
CREATE INDEX IF NOT EXISTS tx_block_height_index   ON transaction (tx_block_height);
CREATE INDEX IF NOT EXISTS tx_hash_index           ON transaction (tx_hash);
CREATE INDEX IF NOT EXISTS tx_type_index           ON transaction (tx_type);
CREATE INDEX IF NOT EXISTS tx_created_at_index     ON transaction (tx_created_at);
CREATE INDEX IF NOT EXISTS tx_nonce_index          ON transaction (tx_nonce);
CREATE INDEX IF NOT EXISTS tx_from_index           ON transaction (tx_from);
CREATE INDEX IF NOT EXISTS tx_amount_index         ON transaction (tx_amount);
CREATE INDEX IF NOT EXISTS tx_to_index             ON transaction (tx_to);
CREATE INDEX IF NOT EXISTS tx_position_index       ON transaction (tx_position);