-- V4: Idempotency key on orchestrated_payments.
-- Backs the application-level OrchestrationIdempotencyStore with a hard DB
-- uniqueness guarantee so duplicate creates (same request_id) are rejected
-- at write time, not only detected in the application cache.
-- request_id is nullable: only caller-supplied idempotency keys are constrained.

ALTER TABLE orchestrated_payments
    ADD COLUMN request_id VARCHAR(128) NULL;

ALTER TABLE orchestrated_payments
    ADD CONSTRAINT uk_op_request_id UNIQUE (request_id);
