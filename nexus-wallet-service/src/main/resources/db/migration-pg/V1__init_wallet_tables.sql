-- =============================================================================
-- V1: wallet-service 业务表初始化（PostgreSQL 兼容版本，P2-T8）
-- =============================================================================
-- 由 V1__init_wallet_tables.sql（MySQL/H2 语法）转换而来：
--   * BIGINT NOT NULL AUTO_INCREMENT → BIGSERIAL（PostgreSQL 自增序列语法）
--   * UNIQUE KEY name (cols) → CONSTRAINT name UNIQUE (cols)
--   * INDEX name (cols) 内联 → 单独 CREATE INDEX 语句
--   * DECIMAL(36,18) / BOOLEAN / TIMESTAMP → 标准 SQL，PostgreSQL 原生支持
--
-- 创建 4 张业务表，替代 DefaultCustodyService / DefaultAddressWhitelistService /
-- DefaultWithdrawalApprovalService / DefaultApprovalPolicy 中的进程内内存存储。

-- 4.1.1 custody_balances：托管余额表
-- 替代 DefaultCustodyService 的 hotBalance / coldBalance（AtomicReference<BigDecimal>）
-- 以 tier 为主键的多行设计，便于未来扩展 WARM 层级
CREATE TABLE IF NOT EXISTS custody_balances (
    tier       VARCHAR(16)    NOT NULL,
    balance    DECIMAL(36,18) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP      NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (tier)
);

-- 4.1.2 address_whitelist：地址白名单表
-- 统一 DefaultAddressWhitelistService.entries 和 DefaultApprovalPolicy.whitelist（消除双重存储）
CREATE TABLE IF NOT EXISTS address_whitelist (
    id                              BIGSERIAL,
    address                         VARCHAR(128) NOT NULL,
    label                           VARCHAR(256),
    merchant_id                     VARCHAR(64)  NOT NULL,
    added_at                        TIMESTAMP    NOT NULL,
    first_withdrawal_available_at   TIMESTAMP,
    active                          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP    NOT NULL,
    updated_at                      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_address UNIQUE (address)
);

CREATE INDEX idx_merchant_active ON address_whitelist(merchant_id, active);

-- 4.1.3 withdrawal_requests：提现审批请求表
-- 替代 DefaultWithdrawalApprovalService.requests（ConcurrentHashMap<String, WithdrawalRequest>）
CREATE TABLE IF NOT EXISTS withdrawal_requests (
    id                  BIGSERIAL,
    request_id          VARCHAR(64)   NOT NULL,
    to_address          VARCHAR(128)  NOT NULL,
    amount              DECIMAL(36,18) NOT NULL,
    currency            VARCHAR(16)   NOT NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    required_approvers  INT           NOT NULL,
    approved_count      INT           NOT NULL DEFAULT 0,
    chain_tx_hash       VARCHAR(128),
    rejection_reason    VARCHAR(256),
    created_at          TIMESTAMP     NOT NULL,
    executed_at         TIMESTAMP,
    updated_at          TIMESTAMP     NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_request_id UNIQUE (request_id)
);

CREATE INDEX idx_status ON withdrawal_requests(status);

-- 4.1.4 withdrawal_approvers：提现审批人表
-- 替代 WithdrawalRequest.approvers（List<String>，一对多关联到 withdrawal_requests）
CREATE TABLE IF NOT EXISTS withdrawal_approvers (
    id          BIGSERIAL,
    request_id  VARCHAR(64) NOT NULL,
    approver_id VARCHAR(64) NOT NULL,
    approved_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_request_approver UNIQUE (request_id, approver_id),
    CONSTRAINT fk_approver_request FOREIGN KEY (request_id) REFERENCES withdrawal_requests (request_id)
);