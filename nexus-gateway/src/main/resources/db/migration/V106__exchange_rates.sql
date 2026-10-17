-- V101: exchange_rates 建表
-- ============================================================================
-- L3 预演缺陷 #31（续）：实体 org.nexus.gateway.currency.ExchangeRate 映射
-- exchange_rates，与 currency_wallets 同型——迁移集里从未有该表。
-- 全仓实体↔迁移对账脚本确认这是网关扫描面内最后一张缺表
-- （另两处疑似：governance_audit_log ∈ nexus-oracle 自有库；
--   nexus-consortium/common 的 Transaction 非 JPA 实体）。
-- 列与实体严格对齐（@Enumerated(STRING) → VARCHAR(16)）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS exchange_rates (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    from_currency VARCHAR(16)   NOT NULL,
    to_currency   VARCHAR(16)   NOT NULL,
    rate          DECIMAL(36,18) NOT NULL,
    quoted_at     DATETIME(6)   NOT NULL,
    source        VARCHAR(64)   NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
);
