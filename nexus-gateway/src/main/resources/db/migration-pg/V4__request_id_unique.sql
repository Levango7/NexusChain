-- V4: Idempotency key on orchestrated_payments（PostgreSQL 兼容版本，P2-T8）
-- 标准 SQL 语法（ALTER TABLE ADD COLUMN / ADD CONSTRAINT UNIQUE），PostgreSQL 原生支持
-- request_id is nullable: only caller-supplied idempotency keys are constrained.

ALTER TABLE orchestrated_payments
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128) NULL;

ALTER TABLE orchestrated_payments
    ADD CONSTRAINT uk_op_request_id UNIQUE (request_id);