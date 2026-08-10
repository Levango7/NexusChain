-- V10: Multi-tenant tables (P4-T6: Multi-tenant transformation).
-- Backs Tenant, TenantUsageRecord entities and adds tenant_id column to
-- payment_orders for row-level data isolation.
-- Required in Flyway-enabled profiles (dev/prod) where JPA ddl-auto=validate.

-- === 租户表 ===
-- 每个租户代表一个独立客户（商户/平台方），通过 api_key 鉴权，
-- 配置（限流/费率/币种白名单）以 config_* 列嵌入存储。
CREATE TABLE IF NOT EXISTS tenants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    api_key VARCHAR(128) NOT NULL UNIQUE,
    api_secret VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    -- 嵌入式 TenantConfig 字段
    rate_limit_per_second INT NOT NULL DEFAULT 100,
    rate_limit_per_minute INT NOT NULL DEFAULT 6000,
    max_payment_amount BIGINT NOT NULL DEFAULT 10000000000,
    allowed_currencies VARCHAR(256) NOT NULL DEFAULT 'NEX',
    fee_rate_bps INT NOT NULL DEFAULT 100,
    webhook_url VARCHAR(256)
);

CREATE INDEX idx_tenants_status ON tenants(status);

-- === 租户使用量记录表（计费报表） ===
-- 按月聚合每个租户的交易笔数/总金额/总手续费。
CREATE TABLE IF NOT EXISTS tenant_usage_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    period VARCHAR(16) NOT NULL,
    transaction_count BIGINT NOT NULL DEFAULT 0,
    total_amount DECIMAL(36,0) NOT NULL DEFAULT 0,
    total_fee DECIMAL(36,0) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_tenant_usage_tenant_period UNIQUE (tenant_id, period)
);

CREATE INDEX idx_tenant_usage_period ON tenant_usage_records(period);

-- === payment_orders 添加 tenant_id 列（数据隔离键） ===
-- 允许 NULL 以兼容多租户改造前的存量数据；新订单由 OrderServiceImpl 从
-- TenantContext 填充。查询时按 tenant_id 过滤实现行级隔离。
ALTER TABLE payment_orders ADD COLUMN tenant_id VARCHAR(64);
CREATE INDEX idx_payment_orders_tenant ON payment_orders(tenant_id);