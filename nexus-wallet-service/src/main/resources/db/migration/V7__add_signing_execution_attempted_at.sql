-- ============================================================================
-- V7：提现防双花——记录 signing 执行尝试时间（2026-10-27）
--
-- 背景（打捞自 #280 的设计，见板 20261027-1830）：
--   signing 侧的审批状态会因 TTL 超时从 EXECUTING 回退到 APPROVED。
--   若上一次 executeApproved 其实已广播、只是响应丢失，wallet 侧重试时
--   会看到 APPROVED 而**再次执行 ⇒ 二次放款（双花）**。
--
-- 本列记录「上次尝试 executeApproved 的时刻」；推进逻辑据此判别：
--   * APPROVED + attempted 非空 ⇒ 上次可能已广播，判 EXECUTED_UNVERIFIED
--     交人工核对，**不重试**（不是 FAILED——钱可能已出）。
--   * 5000（审批未通过）/ 5001（并发被抢）⇒ 确定未动账，清空 attempted，
--     避免一笔从未广播的提现被永久判死。
--   * 结果不明（网络失败 / 响应形状异常）⇒ 保留 attempted。
--
-- 可空列，无回填需求：历史数据 attempted=NULL ⇒ 行为与加列前一致（可直接重试）。
--
-- DDL 与 V6 同口径：H2(MODE=MySQL) 与 MySQL 8 双兼容。
-- ============================================================================

ALTER TABLE withdrawal_requests
    ADD COLUMN signing_execution_attempted_at TIMESTAMP NULL;
