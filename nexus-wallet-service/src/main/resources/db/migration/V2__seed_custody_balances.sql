-- V2: 预置 custody_balances 初始行（Phase 4 任务 #68，设计文档 §4.1.1）
--
-- 替代 DefaultCustodyService.seedBalances() 测试用种子方法。
-- 预置 HOT 和 COLD 两个层级，初始余额为 0。
-- Flyway 在 V1 建表后执行，保证 custody_balances 行已存在，Service 启动即可查询。

INSERT INTO custody_balances (tier, balance, updated_at, version) VALUES ('HOT',  0, CURRENT_TIMESTAMP, 0);
INSERT INTO custody_balances (tier, balance, updated_at, version) VALUES ('COLD', 0, CURRENT_TIMESTAMP, 0);