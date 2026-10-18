-- V104: clearing_settlement_records 补乐观锁列 version
-- ============================================================================
-- L3 预演缺陷 #26：实体 org.nexus.gateway.clearing.ClearingSettlementRecord
-- 含 @Version @Column(name="version")，而 V59__add_clearing_settlement_records.sql
-- 建表时**没有** version 列 ⇒ 任何真实库上 ddl-auto=validate 启动即失败
-- （Schema validation: missing column [version] in table [clearing_settlement_records]）。
-- 此前被"网关侧 Flyway 静默不执行"掩盖（缺陷 #20），Flyway 修复后立即暴露。
-- 说明保留可移植写法（无 COMMENT 子句），H2 MODE=MySQL 亦可用。
-- ============================================================================
ALTER TABLE clearing_settlement_records ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
