-- V108: 自动提现「出款认领」协议列（批次⑤ P0 加固，2026-10-18）
--
-- 背景：executeAutoWithdraw 原实现把「扣账 + 出款调用 + 规则推进」放在同一个 DB 事务里，
-- 而出款（链上/渠道/人工台账登记）是**事务内不可逆的外部动作**——出款成功后的 commit
-- 失败会把扣账与规则状态一起回滚，下个周期重跑即二次出款。
--
-- 现改为三段式协议（见 AutoWithdrawService#executeAutoWithdraw）：
--   ① 认领（本表 payout_claim_reference，事务内与扣账一起先提交）
--   ② 出款（事务外执行）
--   ③ 结果落库（成功：last_executed_at + last_payout_ref + 清认领；失败：补偿入账 + 清认领）
--
-- 不变量：payout_claim_reference 非空 ⇒ 有一笔已扣账的出款处于未决状态
-- （进行中，或崩溃/落库失败需人工核对）。未决期间该规则拒绝重复执行（fail-closed）；
-- 人工核对链上/渠道实际结果后方可处置（清空认领，或按核对结果校账）。
--
-- 号段规则（docs/agent-board/20261017-1900）：从顶部顺取——登记时顶部 V107，本笔取 V108。
ALTER TABLE auto_withdraw_rules
    ADD COLUMN payout_claim_reference VARCHAR(128) NULL COMMENT '出款认领引用（非空=有未决出款，需人工核对）',
    ADD COLUMN payout_claim_at        TIMESTAMP(6) NULL COMMENT '出款认领时间',
    ADD COLUMN last_payout_ref        VARCHAR(128) NULL COMMENT '最近一次成功出款的凭据号（执行器返回）';
