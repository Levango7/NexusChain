-- V95: 商户出款去向（结算账户）绑定表（P0 修复 2026-10-11）
--
-- 背景：网关的"自动提现"（fundreport/AutoWithdrawService）此前只扣商户账本余额、
-- **没有任何出款动作**——"余额被扣但钱没离开平台"，交易对手收不到钱。
-- 且资金去向无模型：出款到哪、谁批准、是否有效，全部没有落点。
--
-- 本迁移建立"每商户绑定结算账户"这一地基：
--   · 出款去向必须是**商户自己绑定的结算账户**（BANK_CARD / ALIPAY / 平台结算账户）；
--   · 平台侧的 PLATFORM_SETTLEMENT 账户要求 compliance_entity=true（持牌主体），
--     否则禁止绑定 —— 这是"二清"红线的代码护栏：
--     平台方用个人账户/非持牌主体收商户款再对外出款，即无牌资金归集与再清算。
--   · 账户状态机：PENDING_VERIFY → ACTIVE / DISABLED；变更需重新走核验。

CREATE TABLE IF NOT EXISTS merchant_payout_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL COMMENT '商户 ID',
    account_type VARCHAR(32) NOT NULL COMMENT 'BANK_CARD / ALIPAY / PLATFORM_SETTLEMENT',
    account_no VARCHAR(128) NOT NULL COMMENT '账户标识（卡号/支付宝账号；禁止存明文完整卡号的风险字段另见应用层）',
    account_name VARCHAR(128) NOT NULL COMMENT '账户户名（应与商户主体一致）',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFY' COMMENT 'PENDING_VERIFY / ACTIVE / DISABLED',
    -- 平台结算账户专用：是否持牌主体（仅持牌方可作为出款主体）
    compliance_entity BOOLEAN NOT NULL DEFAULT FALSE,
    remark VARCHAR(256) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_mpa_merchant (merchant_id),
    KEY idx_mpa_merchant_type (merchant_id, account_type),
    UNIQUE KEY uk_mpa_merchant_type_no (merchant_id, account_type, account_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户出款去向（结算账户）绑定';
