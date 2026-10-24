-- V109: 审批执行链接表（审批通过后的执行腿，2026-10-25）
--
-- 背景：signing-service 对大额签名返回 statusCode=2001 + approvalId（审批请求），
--   但调用方此前一律把"取不到 txHash"当签名失败 ⇒ 大额退款/结算被直接标 FAILED，
--   审批通过后也没有任何调用方执行 /transfers/sign/approved（见 Board #213）。
--   按裁决"调用方执行"：调用方保存 approvalId，审批通过后自行调用执行端点。
--
-- 本表是 gateway 侧的通用链接表（不改任何既有业务表）：
--   request_key 业务请求标识（唯一），用于幂等——同一笔业务只对应一个审批请求；
--   status      调用方视角的执行状态：
--                 AWAITING_APPROVAL / APPROVED / EXECUTING 为非终态；
--                 EXECUTED（有 tx_hash）/ EXECUTED_UNVERIFIED（签名已执行但无哈希，
--                 禁止自动重试）/ EXECUTION_UNKNOWN（结果不可判定，禁止自动重试）/ FAILED。
--   tx_hash     仅在拿到确定交易哈希时写入；任何路径都不允许把它改回 NULL。
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL8 双兼容写法（与 V98/V103 同口径）。
CREATE TABLE approval_execution_link (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    request_key             VARCHAR(160)  NOT NULL,
    request_type            VARCHAR(32)   NOT NULL,
    approval_id             VARCHAR(64)   NOT NULL,
    from_pubkey             VARCHAR(512)  NULL,
    to_pubkey_hash          VARCHAR(128)  NULL,
    amount                  DECIMAL(36,18) NULL,
    currency                VARCHAR(16)   NULL,
    status                  VARCHAR(32)   NOT NULL,
    tx_hash                 VARCHAR(128)  NULL,
    last_error              VARCHAR(512)  NULL,
    attempts                INT           NOT NULL DEFAULT 0,
    execution_attempted_at  TIMESTAMP(6)  NULL,
    created_at              TIMESTAMP(6)  NOT NULL,
    updated_at              TIMESTAMP(6)  NOT NULL,
    executed_at             TIMESTAMP(6)  NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_approval_execution_link_request_key UNIQUE (request_key)
);
CREATE INDEX idx_approval_execution_link_status_updated
    ON approval_execution_link (status, updated_at);
