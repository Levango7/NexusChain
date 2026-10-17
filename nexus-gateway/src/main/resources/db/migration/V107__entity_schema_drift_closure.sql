-- V102: 实体↔schema 漂移收口（L3 复验缺陷 #38）
-- ============================================================================
-- 背景：缺陷 #20（网关 Flyway 静默不执行）修复后，ddl-auto=validate 逐表暴露出
-- 历史遗留的实体↔迁移漂移。以**集群真实库 schema（information_schema）**为基准
-- 对网关全部实体做过全量对账，本迁移收口全部真实差异（已排除嵌套实体误报）：
--
-- ① merchants：实体 Merchant.feeBasisPoints（@Column(name="fee_basis_points",
--    nullable=false, Integer，默认 50）在 V1 建表时遗漏 → 补列（DEFAULT 50 与
--    实体字段初始值/DefaultSettlementService 默认一致）。
-- ② transaction_logs：实体后加的 error_message（VARCHAR(2048)）与乐观锁
--    version（@Version Long）从未有迁移 → 补列。
-- ③ webhook_subscriptions：V33 建表用了 **camelCase 列名**（merchantId/targetUrl/
--    eventTypes/signingSecret/retryPolicy/filterExpression/createdAt/updatedAt），
--    而全仓其余表与 Hibernate 命名策略均为 snake_case → Hibernate validate 恒
--    判"缺列"。统一 RENAME 为 snake_case（MySQL 8 / H2 均支持；索引随列自动更新）。
--
-- 说明：webhook_deliveries.deliveryId 的差异在**实体侧**修正
-- （@Column(name="id")，库列名 id 不变，避免动表）；merchants.secret_hash 属
-- Merchant.ApiKey 嵌套实体（merchant_api_keys 表），对账误报，不改。
-- 语句拆分为单列 ALTER，兼容 H2（CI 迁移预检）。
-- ============================================================================
ALTER TABLE merchants ADD COLUMN fee_basis_points INT NOT NULL DEFAULT 50;

ALTER TABLE transaction_logs ADD COLUMN error_message VARCHAR(2048);

ALTER TABLE transaction_logs ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE webhook_subscriptions RENAME COLUMN merchantId TO merchant_id;

ALTER TABLE webhook_subscriptions RENAME COLUMN targetUrl TO target_url;

ALTER TABLE webhook_subscriptions RENAME COLUMN eventTypes TO event_types;

ALTER TABLE webhook_subscriptions RENAME COLUMN signingSecret TO signing_secret;

ALTER TABLE webhook_subscriptions RENAME COLUMN retryPolicy TO retry_policy;

ALTER TABLE webhook_subscriptions RENAME COLUMN filterExpression TO filter_expression;

ALTER TABLE webhook_subscriptions RENAME COLUMN createdAt TO created_at;

ALTER TABLE webhook_subscriptions RENAME COLUMN updatedAt TO updated_at;
