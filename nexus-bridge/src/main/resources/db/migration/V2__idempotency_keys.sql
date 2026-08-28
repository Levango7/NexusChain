-- V2: Idempotency keys table (P2-F2).
--
-- 全链路幂等性保障：lock / mint / burn / unlock 操作执行前先按
-- (key, operation) 查询是否已存在有效记录，若存在且未过期则直接
-- 反序列化返回之前的结果，避免重复执行副作用操作。
--
-- (key_value, operation) 联合唯一约束：DB 层硬性防止同一幂等键 +
-- 同一操作并发写入两条记录。应用层先查后写，DB 层兜底。
--
-- 字段说明：
--   id          — UUID 主键
--   key_value   — 幂等键（sourceTxHash 或 requestId），最长 128
--   operation   — 操作类型（LOCK / MINT / BURN / UNLOCK），最长 32
--   result      — 操作结果 JSON，最长 4096
--   created_at  — 创建时间
--   expires_at  — 过期时间（默认 created_at + 24h）
--
-- 语法说明：
--   * H2 / PostgreSQL / SQLite / MySQL 8.0 均支持 CREATE TABLE IF NOT EXISTS。
--   * 字段名使用 key_value 而非 key，避免与 SQL 保留字冲突。

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id          VARCHAR(64)   NOT NULL,
    key_value   VARCHAR(128)  NOT NULL,
    operation   VARCHAR(32)   NOT NULL,
    result      VARCHAR(4096) NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    expires_at  TIMESTAMP     NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_idempotency_key_op
    ON idempotency_keys (key_value, operation);