-- V93: orchestrated_payments 补付款/收款地址列（P0 修复，2026-10-09）
--
-- 背景：编排支付链调用 chain/consortium 连接器时，连接器需要收款地址才能
-- 构造链上交易（AddressToPubkeyHash(request.getPayeeAddress())）；而
-- OrchestratedPayment 此前根本没有这两个字段，OrchestrationService 构造
-- ConnectorPaymentRequest 时也不传地址 —— 任何走真实链的编排支付必然以
-- "invalid payee address" 失败并静默降级到 mock 连接器（返回假 txHash）。
--
-- 语义：链上支付必填（NEX/代币），法币渠道（微信/支付宝等）可为空。
-- 两个地址同时冗余存储在支付单上，便于审计与链上对账时回溯付款路径。
ALTER TABLE orchestrated_payments
    ADD COLUMN payer_address VARCHAR(128) NULL COMMENT '付款方地址（链上支付必需；法币渠道可空）',
    ADD COLUMN payee_address VARCHAR(128) NULL COMMENT '收款方地址（链上支付必需；法币渠道可空）';
