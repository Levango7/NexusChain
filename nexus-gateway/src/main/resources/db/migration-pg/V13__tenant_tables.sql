-- =============================================================================
-- V13: Multi-tenant tables (PostgreSQL)
-- S5 修复：由 V10__tenant_tables.sql（MySQL）转换。
-- Backs Tenant, TenantUsageRecord entities + payment_orders.tenant_id 隔离键。
-- =============================================================================

-- 租户表（api_key 鉴权，限流/费率/币种白名单嵌入 config_* 列）
CREATE TABLE IF NOT EXISTS tenants (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    api_key VARCHAR(128) NOT NULL UNIQUE,
    api_secret VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    rate_limit_per_second INT NOT NULL DEFAULT 100,
    rate_limit_per_minute INT NOT NULL DEFAULT 6000,
    max_payment_amount BIGINT NOT NULL DEFAULT 10000000000,
    allowed_currencies VARCHAR(256) NOT NULL DEFAULT 'NEX',
    fee_rate_bps INT NOT NULL DEFAULT 100,
    webhook_url VARCHAR(256)
);

CREATE INDEX idx_tenants_status ON tenants(status);

-- 租户使用量记录表（按月聚合计费报表）
CREATE TABLE IF NOT EXISTS tenant_usage_records (
    id BIGSERIAL PRIMARY KEY,
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

-- payment_orders 添加 tenant_id 列（行级数据隔离键）。
-- NULL 兼容存量数据；新订单由 OrderServiceImpl 从 TenantContext 填充。
ALTER TABLE payment_orders ADD COLUMN tenant_id VARCHAR(64);
CREATE INDEX idx_payment_orders_tenant ON payment_orders(tenant_id);
-- 多租户查询/计费索引（原 V9 内容，编号重排后随加列迁移落地——见 V9 头注释）
CREATE INDEX IF NOT EXISTS idx_orders_tenant_merchant_status ON payment_orders(tenant_id, merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_created ON payment_orders(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_paidat ON payment_orders(tenant_id, paid_at);
