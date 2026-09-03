-- V18: Nexus settlement ledger / clearing order / reconciliation record persistence.
-- Backs the durable settlement accounting core:
--   ledger_entry       -> Ledger via JdbcTemplate (hand-managed, no JPA entity)
--   clearing_order     -> JPA @Entity (ClearingOrder)
--   settlement_record  -> JPA @Entity (SettlementRecord)
-- Required in go-live (ddl-auto=validate) so entity mappings MUST match columns verbatim.
-- H2 (dev/test, MODE=MySQL) and MySQL8 both accept these DDL constructs.

CREATE TABLE ledger_entry (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    entry_id   VARCHAR(64)    NOT NULL,
    account    VARCHAR(128)   NOT NULL,
    direction  VARCHAR(16)    NOT NULL,
    amount     DECIMAL(36,8)  NOT NULL,
    reference  VARCHAR(128),
    booked_at  TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ledger_entry_id UNIQUE (entry_id),
    CONSTRAINT uk_ledger_entry_ref_account UNIQUE (reference, account)
);
CREATE INDEX idx_le_account   ON ledger_entry(account);
CREATE INDEX idx_le_reference ON ledger_entry(reference);

CREATE TABLE clearing_order (
    order_id           VARCHAR(64)   NOT NULL,
    merchant_id        VARCHAR(64)   NOT NULL,
    amount             DECIMAL(36,8) NOT NULL,
    currency           VARCHAR(8)    NOT NULL,
    settlement_cycle   VARCHAR(16),
    status             VARCHAR(16)   NOT NULL,
    created_at         TIMESTAMP(6)  NOT NULL,
    payment_id         BIGINT,
    chain_tx_hash      VARCHAR(128),
    connector_id       VARCHAR(32),
    routing_latency_ms BIGINT,
    cost_bps           INT,
    payer_address      VARCHAR(128),
    payee_address      VARCHAR(128),
    settlement_tx_hash VARCHAR(128),
    PRIMARY KEY (order_id)
);
CREATE INDEX idx_co_status   ON clearing_order(status);
CREATE INDEX idx_co_merchant ON clearing_order(merchant_id);
CREATE INDEX idx_co_created  ON clearing_order(created_at);

CREATE TABLE settlement_record (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    reference   VARCHAR(128)   NOT NULL,
    source      VARCHAR(16)    NOT NULL,
    amount      DECIMAL(36,8)  NOT NULL,
    currency    VARCHAR(8),
    recorded_at TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_settlement_record_ref_src UNIQUE (reference, source)
);
CREATE INDEX idx_sr_source ON settlement_record(source);