-- V2: 预置 custody_balances 初始行（PostgreSQL 兼容版本，P2-T8）
-- 标准 SQL 语法（INSERT + CURRENT_TIMESTAMP），与 MySQL/H2 版本完全一致
-- PostgreSQL 原生支持 CURRENT_TIMESTAMP，无需转换

INSERT INTO custody_balances (tier, balance, updated_at, version) VALUES ('HOT',  0, CURRENT_TIMESTAMP, 0);
INSERT INTO custody_balances (tier, balance, updated_at, version) VALUES ('COLD', 0, CURRENT_TIMESTAMP, 0);