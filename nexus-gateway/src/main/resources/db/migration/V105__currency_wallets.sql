-- V100: currency_wallets 建表
-- ============================================================================
-- L3 预演缺陷 #31：实体 org.nexus.gateway.currency.CurrencyWallet 映射
-- currency_wallets，但**迁移集里从未有过该表的建表脚本**（网关 dev yml 的注释
-- 早已点出"若有新增实体（如 currency_wallets）未在迁移脚本中则启动失败"——
-- 该缺口一直靠"网关侧 Flyway 静默不执行"掩盖，缺陷 #20 修复后立即暴露：
-- Schema validation: missing table [currency_wallets]）。
-- 列与实体/索引严格对齐（@Table uniqueConstraints: uk_merchant_currency）。
-- 数据源：MySQL/OceanBase（MySQL 模式）；写法保持可移植（无 COMMENT 子句）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS currency_wallets (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id   BIGINT        NOT NULL,
    currency      VARCHAR(16)   NOT NULL,
    balance       DECIMAL(36,0) NOT NULL DEFAULT 0,
    frozen_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_merchant_currency UNIQUE (merchant_id, currency)
);
