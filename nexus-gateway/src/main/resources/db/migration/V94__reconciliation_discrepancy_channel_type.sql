-- V94: 对账差错补「渠道归属」字段（P0 修复 2026-10-11）
--
-- 背景：长款走渠道退款（用户 2026-10-11 决策）需要按渠道原路退回，但
-- ReconciliationDiscrepancy 此前没有任何渠道标识——只有 transactionId/金额，
-- 无法判定 wechat/alipay，也就无法选连接器。
-- 注意：ReconciliationEngine.reconcile() 本来就接收 channelType（用于解析对账规则），
-- 但调用方两处都传 null、且差错对象从不记录它——本迁移+本次改动把这条链补齐。
-- 存量数据无法回溯渠道（编码时已丢弃），置 NULL 并按"缺渠道归属"处理。
ALTER TABLE reconciliation_discrepancies
    ADD COLUMN channel_type VARCHAR(16) NULL COMMENT '渠道类型（WECHAT/ALIPAY）；NULL=存量或未标注';
