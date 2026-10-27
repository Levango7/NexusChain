-- ============================================================================
-- V6：(c)-lite —— 提现执行腿复用 signing 审批（2026-10-27）
--
-- 背景：#261 的 (b2)（按地址豁免 signing 大额审批）前提被 P0 复核推翻——平台热钱包
-- 只有一把私钥，wallet 提现与 gateway 五条路径物理共用同一 fromPubkey，"豁免粒度是
-- 地址、风险粒度是业务"，按 (b2) 本意配置会连带打掉 gateway 支付/退款/订阅的唯一审批。
-- 用户拍板改走 (c)-lite：wallet 侧持久化 signing 的 approvalId 并在重试时复用
-- （不再新建审批单），双层审批与全部门槛保留。
--
-- 与既有 (c) 的差别（为什么需要本列）：
--   原缺陷是"wallet 执行腿拿到 2001 后每次重试都调 signTransfer → 每次新建一条
--   signing 审批，且 wallet 侧已记 FAILED" ⇒ 悬空审批随重试次数线性增长。
--   持久化 approvalId 后，重试路径改为：查询既有审批状态（PENDING 保持等待 /
--   APPROVED 走 sign-approved / REJECTED·EXPIRED 落 FAILED），**不再产生新审批单**。
--
-- 状态列无需迁移：`status` 为 VARCHAR(32) 字符串持久化（@Enumerated(STRING)），
-- 新增值 AWAITING_APPROVAL（17 字符）/ UNVERIFIED（10 字符）均在长度内。
--
-- DDL 与 V5 同口径：H2(MODE=MySQL) 与 MySQL 8 双兼容（无 ENGINE/CHARSET 后缀、
-- 无内联 COMMENT），保证 @DataJpaTest / ddl-auto=validate 双方可过。
-- ============================================================================

ALTER TABLE withdrawal_requests
    ADD COLUMN signing_approval_id VARCHAR(64) NULL;