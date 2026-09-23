-- V21: 分账/分润体系表
-- 创建 split_rules 和 split_orders 两张表，支持商户分账规则管理与分账明细记录

CREATE TABLE split_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    receiver_address VARCHAR(66) NOT NULL,
    split_type VARCHAR(16) NOT NULL,
    split_value DECIMAL(36,8) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    description VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT
);

CREATE TABLE split_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    payment_id BIGINT,
    merchant_id BIGINT NOT NULL,
    receiver_address VARCHAR(66) NOT NULL,
    amount DECIMAL(36,8) NOT NULL,
    split_type VARCHAR(16) NOT NULL,
    split_value DECIMAL(36,8) NOT NULL,
    split_rule_id BIGINT,
    description VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL,
    settled_at TIMESTAMP(6),
    version BIGINT
);

CREATE INDEX idx_split_rules_merchant ON split_rules(merchant_id, active);
CREATE INDEX idx_split_orders_order ON split_orders(order_id);
CREATE INDEX idx_split_orders_payment ON split_orders(payment_id);
CREATE INDEX idx_split_orders_status ON split_orders(status);