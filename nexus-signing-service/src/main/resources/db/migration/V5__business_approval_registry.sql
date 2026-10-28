-- V5: A2 业务批准凭据登记表（ADR-036 §2.2 / docs/adr/A2-business-approval-design-draft-2026-10-27.md §3.3）
--
-- 用途：wallet-service 把"业务层已批准"的提现单登记到这里，signing-service 的
-- /api/v1/transfers/sign 收到可选 businessApprovalId 时据此跳过自身大额审批。
-- 单笔豁免、一次性消费（CAS）、带 TTL——与 signing_approval_request 的
-- "等待签名人批准"状态机刻意分开（TTL 回退 vs 终局消费，混用即 #294 防双花同型坑）。
--
-- 方言说明（对齐 V1 风格）：生产 MySQL 8.x；开发/测试 H2 MODE=MySQL。
-- 不使用 ENGINE=InnoDB / DEFAULT CHARSET 后缀（H2 不支持，MySQL 默认行为一致）。

CREATE TABLE IF NOT EXISTS signing_business_approval (
    id                   BIGINT         NOT NULL AUTO_INCREMENT, -- 自增主键
    business_approval_id VARCHAR(64)    NOT NULL,               -- 业务单号（wallet 提现 requestId）
    payload_hash         CHAR(64)       NOT NULL,               -- canonical sha256 hex（A2-v1 前缀，双侧共享算法）
    amount               DECIMAL(36,18) NOT NULL,               -- 登记金额（审计展示用；校验走 payload_hash）
    currency             VARCHAR(16)    NOT NULL DEFAULT 'USDT',-- 币种（仅元数据：signTransfer 契约当前 USDT 固定）
    from_pubkey          VARCHAR(512)   NOT NULL,               -- 转出公钥（审计展示用）
    to_pubkey_hash       VARCHAR(128)   NOT NULL,               -- 转入公钥 hash（审计展示用）
    approvers_json       TEXT,                                  -- 审批人快照（审计展示，不参与校验）
    state                VARCHAR(16)    NOT NULL DEFAULT 'REGISTERED', -- REGISTERED / CONSUMED
    consumed_at          TIMESTAMP      NULL,                   -- 消费时间（CAS 成功时写入）
    expires_at           TIMESTAMP      NOT NULL,               -- 凭据失效时间（过期不豁免、不续期）
    created_at           TIMESTAMP      NOT NULL,               -- 登记时间（UTC）
    version              BIGINT         NOT NULL DEFAULT 0,     -- 保留列（并发控制由 state 条件更新承担，见仓储 CAS）
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_approval_id (business_approval_id),
    INDEX idx_state_expires (state, expires_at)
);
