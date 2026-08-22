-- V12: Unique constraint on payment_orders.chain_tx_hash (P0-5 fix, v2.27.0).
--
-- Prevents the same on-chain transaction hash from being bound to multiple orders.
-- Without this constraint, an attacker could reuse a legitimate txHash to confirm
-- multiple orders, or a race condition could double-bind a txHash during concurrent
-- confirmPayment calls.
--
-- chain_tx_hash is nullable (orders that have not yet been confirmed have NULL).
-- Standard SQL UNIQUE constraints allow multiple NULLs in PostgreSQL, MySQL, and H2,
-- so unconfirmed orders are not affected.
--
-- The constraint is also enforced at the application level in
-- PaymentServiceImpl.confirmPayment (findByChainTxHash pre-check) for a
-- user-friendly error message before the DB constraint violation.
ALTER TABLE payment_orders
    ADD CONSTRAINT uk_payment_orders_chain_tx_hash UNIQUE (chain_tx_hash);