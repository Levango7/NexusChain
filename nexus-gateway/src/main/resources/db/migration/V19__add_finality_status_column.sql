-- V19: Add finality_status column to payment_orders for tracking on-chain finality.
-- Step 2 of unified transaction/payment finality model.
-- Allows null to maintain backward compatibility with existing data.

ALTER TABLE payment_orders ADD COLUMN finality_status VARCHAR(32);