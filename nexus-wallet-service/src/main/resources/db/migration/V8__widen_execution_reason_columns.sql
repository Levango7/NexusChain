-- 提现终态与审计的文案列太窄：执行失败原因里嵌入 Feign 异常（自带完整 URL），
-- 实测一条 319 字符 → `rejection_reason VARCHAR(256)` 写入失败 → FAILED/UNVERIFIED
-- 这类**终态永远落不了库**，行停在 EXECUTING，端点 500，失败原因只剩日志。
-- 审计表 `detail` 同型溢出且被 catch 忽略，资金相关状态转换的审计静默丢失。
ALTER TABLE withdrawal_requests
    MODIFY COLUMN rejection_reason VARCHAR(1024) NULL;

ALTER TABLE wallet_audit_events
    MODIFY COLUMN detail VARCHAR(2048) NULL;
