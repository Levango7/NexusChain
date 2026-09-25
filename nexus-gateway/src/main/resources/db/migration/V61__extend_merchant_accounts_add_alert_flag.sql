-- V61: 商户账户表新增预警级别字段 — Wave 11 基础设施层
-- alert_flag 标记当前预警级别：NULL(正常)/WARNING(预警)/CRITICAL(严重)/EMERGENCY(紧急)
-- 位于 status 字段之后

ALTER TABLE merchant_accounts
    ADD COLUMN alert_flag VARCHAR(32) NULL DEFAULT NULL COMMENT '预警级别: NULL(正常)/WARNING(预警)/CRITICAL(严重)/EMERGENCY(紧急)'
    AFTER status;