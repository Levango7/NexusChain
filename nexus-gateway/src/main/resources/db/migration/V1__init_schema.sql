-- V1: Initial schema for ConPay Gateway

CREATE TABLE IF NOT EXISTS merchants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_code VARCHAR(64) NOT NULL UNIQUE,
    merchant_name VARCHAR(128) NOT NULL,
    email VARCHAR(128) NOT NULL,
    settlement_address VARCHAR(66) NOT NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS merchant_api_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    api_key VARCHAR(128) NOT NULL UNIQUE,
    secret_hash VARCHAR(256) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_apikey_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE TABLE IF NOT EXISTS payment_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    merchant_id BIGINT NOT NULL,
    token_symbol VARCHAR(16) NOT NULL DEFAULT 'CPAY',
    amount DECIMAL(36,0) NOT NULL,
    description VARCHAR(256),
    payer_address VARCHAR(66),
    payee_address VARCHAR(66) NOT NULL,
    chain_tx_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    checkout_token VARCHAR(128) UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    version BIGINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    amount DECIMAL(36,0) NOT NULL,
    token_symbol VARCHAR(16) NOT NULL DEFAULT 'CPAY',
    receiver_address VARCHAR(66) NOT NULL,
    sender_address VARCHAR(66) NOT NULL,
    chain_tx_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS subscriptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subscription_no VARCHAR(64) NOT NULL UNIQUE,
    merchant_id BIGINT NOT NULL,
    payer_address VARCHAR(66) NOT NULL,
    payee_address VARCHAR(66) NOT NULL,
    token_symbol VARCHAR(16) NOT NULL DEFAULT 'CPAY',
    amount DECIMAL(36,0) NOT NULL,
    cycle_days INT NOT NULL,
    charged_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    auth_tx_hash VARCHAR(128),
    next_charge_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    cancelled_at TIMESTAMP
);