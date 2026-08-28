-- V3: Saga instances table (P2-F2).
--
-- 跨链桥 Saga 协调器状态持久化：每个 lock→mint / burn→unlock Saga
-- 创建一条记录，用于崩溃恢复、重试与人工审计。
--
-- 字段说明：
--   id                 — UUID 主键
--   saga_type          — Saga 类型（LOCK_MINT / BURN_UNLOCK）
--   state              — Saga 状态（PENDING / EXECUTING / COMPENSATING / COMPLETED / FAILED）
--   current_step_index — 当前步骤下标（从 0 开始）
--   payload            — Saga 上下文 JSON（LONGTEXT，可长）
--
-- 语法说明：
--   * H2 / MySQL 8.0 不支持 CLOB 类型；用 LONGTEXT 兼容（H2 亦支持），
--     PostgreSQL 版本见 db/migration-pg。
--   * 索引使用不带 IF NOT EXISTS 的 CREATE INDEX——MySQL 8.0 不支持
--     IF NOT EXISTS（语法错误 1064），Flyway schema history 保证脚本单次执行。
--   related_tx_id      — 关联桥交易 ID（如 lockTxId / burnTxId）
--   retry_count        — 已重试次数
--   max_retries        — 最大重试次数
--   last_error         — 最近一次错误信息
--   created_at         — 创建时间
--   updated_at         — 最后更新时间

CREATE TABLE IF NOT EXISTS saga_instances (
    id                 VARCHAR(64)   NOT NULL,
    saga_type          VARCHAR(32)   NOT NULL,
    state              VARCHAR(32)   NOT NULL,
    current_step_index INTEGER       NOT NULL,
    payload            LONGTEXT,
    related_tx_id      VARCHAR(64),
    retry_count        INTEGER       NOT NULL,
    max_retries        INTEGER       NOT NULL,
    last_error         VARCHAR(1024),
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL,
    CONSTRAINT pk_saga_instances PRIMARY KEY (id)
);

CREATE INDEX idx_saga_state
    ON saga_instances (state);

CREATE INDEX idx_saga_related_tx
    ON saga_instances (related_tx_id);