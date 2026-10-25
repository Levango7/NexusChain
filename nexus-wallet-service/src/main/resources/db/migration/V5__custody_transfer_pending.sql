-- V5: 托管转账待处置表（2026-10-25，Board 裁决 20261025-0955 §五）
--
-- 背景：DefaultCustodyService 的链上执行原来只看「txHash 是否为空」，
--   空就抛异常 ⇒ 外层 @Transactional 回滚冷热余额。这在"确定未动账"时是对的，
--   但 #220 之后执行通道会返回两种新结果：
--     * AWAITING_APPROVAL —— 大额转人工审批，钱**没**出（回滚是对的，但审批请求还开着，
--       不留痕就没人知道有一笔被悬空的托管出金）；
--     * EXECUTED_UNVERIFIED —— 签名已执行但拿不到哈希，钱**可能已经出去**，
--       此时回滚冷热余额 = 账面与链上相反（对账缺口），而且调用方会把这笔当成
--       「确定未出账」从而重新发起 = 同一意图二次授权。
--   所以两种结果都必须落一条待处置记录；且这条记录要能在主事务回滚后存活，
--   服务层用 REQUIRES_NEW 的独立事务写它。
--
-- 状态语义（两者都需要人动手，但动作完全不同）：
--   APPROVAL_OPEN    —— 余额调整已回滚，钱未出；处置=驳回审批，或核对后重新发起
--   VERIFY_REQUIRED  —— 余额调整**已提交**，钱可能已出；处置=核对链上后补记哈希或冲正
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL 8 双兼容写法（与 V1 同口径：
--   不用 ENGINE/CHARSET 后缀、不用内联 COMMENT、DECIMAL(36,18)）。
CREATE TABLE IF NOT EXISTS custody_transfer_pending (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    request_key   VARCHAR(128)   NOT NULL,             -- 本次托管出金的幂等键（= requestId）
    transfer_type VARCHAR(16)    NOT NULL,             -- SWEEP / WITHDRAWAL
    purpose       VARCHAR(128),                        -- 业务用途（memo，如 custody hot->cold sweep）
    address       VARCHAR(128)   NOT NULL,             -- 目标地址
    amount        DECIMAL(36,18) NOT NULL,             -- 金额（链上最小单位）
    currency      VARCHAR(16),                         -- 币种
    status        VARCHAR(32)    NOT NULL,             -- APPROVAL_OPEN / VERIFY_REQUIRED
    approval_id   VARCHAR(64),                         -- 签名侧审批请求 ID（未分配时为 null）
    error_note    VARCHAR(512),                        -- 不可核对/失败原因，供人工排查
    created_at    TIMESTAMP      NOT NULL,             -- 记录创建时间
    updated_at    TIMESTAMP      NOT NULL,             -- 最后更新时间
    version       BIGINT         NOT NULL DEFAULT 0,   -- 乐观锁版本号（@Version）
    PRIMARY KEY (id),
    CONSTRAINT uk_custody_transfer_pending_key UNIQUE (request_key)
);

-- 人工核对队列按状态取用；不加外键（与 V1 口径一致，表内自洽即可）
CREATE INDEX idx_custody_pending_status ON custody_transfer_pending (status, created_at);
