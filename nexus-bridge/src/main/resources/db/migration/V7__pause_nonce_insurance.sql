-- V7: 补齐三张从未被迁移覆盖的实体表（2026-10-27 数据层收口）。
--
-- 背景：V0..V6 覆盖 bridge_transactions / bridge_tx_validators /
-- idempotency_keys / saga_instances / undo_log / bridge_messages /
-- bridge_daily_usage / bridge_daily_limit_snapshot，但以下三张 @Entity 表
-- 一直没有建表脚本，只靠 Hibernate ddl-auto=update 在运行时创建：
--   * bridge_nonce_records      — NonceRecord（重放保护 nonce）
--   * bridge_pause_records      — BridgePauseRecord（桥暂停状态）
--   * insurance_fund_ledger     — InsuranceFundLedgerEntry（保险基金流水）
--
-- 切到 ddl-auto=validate 后，这三张表必须由迁移建出，否则启动即校验失败。
-- 字段与实体逐一核对（长度 / 精度 / 可空性）。
--
-- 语法：CREATE TABLE IF NOT EXISTS 兼容 MySQL 8.0 / H2（同 V0）。
-- insurance_fund_ledger.entry_id 为 GenerationType.IDENTITY，用 AUTO_INCREMENT
-- （H2 与 MySQL 8 均支持）。

CREATE TABLE IF NOT EXISTS bridge_nonce_records (
    nonce      VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_bridge_nonce_records PRIMARY KEY (nonce)
);

CREATE TABLE IF NOT EXISTS bridge_pause_records (
    bridge_id    VARCHAR(64)  NOT NULL,
    state        VARCHAR(32)  NOT NULL,
    reason       VARCHAR(512),
    triggered_by VARCHAR(128),
    updated_at   TIMESTAMP,
    CONSTRAINT pk_bridge_pause_records PRIMARY KEY (bridge_id)
);

CREATE TABLE IF NOT EXISTS insurance_fund_ledger (
    entry_id      BIGINT         NOT NULL AUTO_INCREMENT,
    type          VARCHAR(16)    NOT NULL,
    amount        DECIMAL(38,18) NOT NULL,
    balance_after DECIMAL(38,18) NOT NULL,
    party_id      VARCHAR(128),
    reason        VARCHAR(512),
    created_at    TIMESTAMP,
    CONSTRAINT pk_insurance_fund_ledger PRIMARY KEY (entry_id)
);
