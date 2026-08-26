-- V1: signing-service 审批请求持久化表初始化（任务 #375）
--
-- 替代 SigningApprovalService.requestStore 的进程内 ConcurrentHashMap 存储，
-- 消除多实例部署下审批状态不共享的风险。
--
-- 方言说明（对齐 nexus-wallet-service V1__init_wallet_tables.sql 风格）：
--   * 生产数据源 MySQL 8.x；开发 / 测试 profile 使用 H2 MODE=MySQL
--   * AUTO_INCREMENT / UNIQUE KEY / 内联 INDEX — H2 MODE=MySQL 均支持
--   * 不使用 ENGINE=InnoDB / DEFAULT CHARSET=utf8mb4 后缀 — H2 不支持；
--     MySQL 8.x 默认存储引擎 InnoDB、默认字符集 utf8mb4，省略后行为一致
--
-- 审批人 / 拒绝人集合（Set<String>）以 JSON 数组文本列存储：
--   * 审批请求为不可变值对象，每次状态变更整体覆写行记录，
--     集合规模 = 审批人数（通常 ≤ 5），无需独立子表（对比 wallet-service
--     withdrawal_approvers 一对多的差异：本表行级覆写频率低、集合小）

CREATE TABLE IF NOT EXISTS signing_approval_request (
    id                 BIGINT         NOT NULL AUTO_INCREMENT, -- 自增主键
    request_id         VARCHAR(64)    NOT NULL,               -- 业务请求 ID（UUID）
    from_pubkey        VARCHAR(512)   NOT NULL,               -- 转出公钥（hex，长度上限放宽）
    to_pubkey_hash     VARCHAR(128)   NOT NULL,               -- 转入公钥 hash
    amount             DECIMAL(36,18) NOT NULL,               -- 审批金额，36 位总精度 / 18 位小数
    currency           VARCHAR(16)    NOT NULL DEFAULT 'USDT',-- 币种
    required_approvers INT            NOT NULL,               -- 所需审批人数
    status             VARCHAR(32)    NOT NULL DEFAULT 'PENDING', -- 状态：PENDING/APPROVED/REJECTED/EXECUTING/EXECUTED/EXPIRED
    approvals_json     TEXT,                                  -- 已批准审批人集合（JSON 数组）
    rejections_json    TEXT,                                  -- 已拒绝审批人集合（JSON 数组）
    initiator          VARCHAR(128)   NOT NULL,               -- 发起人标识（JWT subject）
    created_at         TIMESTAMP      NOT NULL,               -- 创建时间（UTC）
    deadline           TIMESTAMP      NOT NULL,               -- 审批截止时间（UTC）
    version            BIGINT         NOT NULL DEFAULT 0,     -- 乐观锁版本号（@Version，多实例并发保护）
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_id (request_id),
    INDEX idx_status (status),
    INDEX idx_deadline (deadline)
);