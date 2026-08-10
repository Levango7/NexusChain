-- V1: wallet-service 业务表初始化（Phase 4 任务 #68，设计文档 §4.1）
--
-- 创建 4 张业务表，替代 DefaultCustodyService / DefaultAddressWhitelistService /
-- DefaultWithdrawalApprovalService / DefaultApprovalPolicy 中的进程内内存存储。
--
-- H2 兼容性说明（application-dev.yml / application-test.yml 使用 H2 MODE=MySQL）：
--   * DECIMAL(36,18) — H2 MODE=MySQL 支持
--   * 不使用 ENGINE=InnoDB / DEFAULT CHARSET=utf8mb4 后缀 — H2 不支持；
--     MySQL 8.x 默认存储引擎为 InnoDB、默认字符集为 utf8mb4，省略后行为一致
--   * 不使用内联 COMMENT '...' — 改用 SQL 行注释说明字段语义

-- 4.1.1 custody_balances：托管余额表
-- 替代 DefaultCustodyService 的 hotBalance / coldBalance（AtomicReference<BigDecimal>）
-- 以 tier 为主键的多行设计，便于未来扩展 WARM 层级
CREATE TABLE IF NOT EXISTS custody_balances (
    tier       VARCHAR(16)    NOT NULL,             -- 托管层级：HOT / WARM / COLD
    balance    DECIMAL(36,18) NOT NULL DEFAULT 0,   -- 余额，36 位总精度 / 18 位小数
    updated_at TIMESTAMP      NOT NULL,             -- 最后更新时间
    version    BIGINT         NOT NULL DEFAULT 0,   -- 乐观锁版本号（@Version）
    PRIMARY KEY (tier)
);

-- 4.1.2 address_whitelist：地址白名单表
-- 统一 DefaultAddressWhitelistService.entries 和 DefaultApprovalPolicy.whitelist（消除双重存储）
CREATE TABLE IF NOT EXISTS address_whitelist (
    id                              BIGINT       NOT NULL AUTO_INCREMENT, -- 自增主键
    address                         VARCHAR(128) NOT NULL,               -- 钱包地址（业务唯一键）
    label                           VARCHAR(256),                        -- 地址标签
    merchant_id                     VARCHAR(64)  NOT NULL,               -- 商户 ID
    added_at                        TIMESTAMP    NOT NULL,               -- 加入白名单时间
    first_withdrawal_available_at   TIMESTAMP,                           -- 首次提币放行时间（addedAt + delay）
    active                          BOOLEAN      NOT NULL DEFAULT TRUE,  -- 是否活跃（软删除标记）
    created_at                      TIMESTAMP    NOT NULL,               -- 记录创建时间
    updated_at                      TIMESTAMP    NOT NULL,               -- 记录更新时间
    PRIMARY KEY (id),
    UNIQUE KEY uk_address (address),
    INDEX idx_merchant_active (merchant_id, active)
);

-- 4.1.3 withdrawal_requests：提现审批请求表
-- 替代 DefaultWithdrawalApprovalService.requests（ConcurrentHashMap<String, WithdrawalRequest>）
CREATE TABLE IF NOT EXISTS withdrawal_requests (
    id                  BIGINT        NOT NULL AUTO_INCREMENT, -- 自增主键
    request_id          VARCHAR(64)   NOT NULL,               -- 业务请求 ID（WD-<uuid>）
    to_address          VARCHAR(128)  NOT NULL,               -- 目标提现地址
    amount              DECIMAL(36,18) NOT NULL,              -- 提现金额
    currency            VARCHAR(16)   NOT NULL,               -- 币种
    status              VARCHAR(32)   NOT NULL DEFAULT 'PENDING', -- 状态：PENDING/APPROVED/REJECTED/EXECUTED/FAILED
    required_approvers  INT           NOT NULL,               -- 所需审批人数
    approved_count      INT           NOT NULL DEFAULT 0,     -- 已审批人数
    chain_tx_hash       VARCHAR(128),                         -- 链上交易哈希（EXECUTED 后填充）
    rejection_reason    VARCHAR(256),                         -- 拒绝原因（REJECTED / FAILED 时填充）
    created_at          TIMESTAMP     NOT NULL,               -- 创建时间
    executed_at         TIMESTAMP,                            -- 执行时间
    updated_at          TIMESTAMP     NOT NULL,               -- 更新时间
    version             BIGINT        NOT NULL DEFAULT 0,     -- 乐观锁版本号
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_id (request_id),
    INDEX idx_status (status)
);

-- 4.1.4 withdrawal_approvers：提现审批人表
-- 替代 WithdrawalRequest.approvers（List<String>，一对多关联到 withdrawal_requests）
CREATE TABLE IF NOT EXISTS withdrawal_approvers (
    id          BIGINT      NOT NULL AUTO_INCREMENT, -- 自增主键
    request_id  VARCHAR(64) NOT NULL,               -- 关联提现请求 ID
    approver_id VARCHAR(64) NOT NULL,               -- 审批人 ID
    approved_at TIMESTAMP   NOT NULL,               -- 审批时间
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_approver (request_id, approver_id),
    CONSTRAINT fk_approver_request FOREIGN KEY (request_id) REFERENCES withdrawal_requests (request_id)
);