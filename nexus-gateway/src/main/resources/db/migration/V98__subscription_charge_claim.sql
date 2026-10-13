-- V98: 订阅周期扣款幂等认领表（并发重复扣款防线，2026-10-13）
--
-- 背景：v2 订阅的自动扣款（BillingCycleScheduler → DefaultSubscriptionService
--   .processBillingCycle / .convertTrialToActive）最终走 ChargeExecutor →
--   connector.createPayment，是不可逆的真实动账。动账前只有 status 校验与
--   nextChargeAt 到期校验（check-then-act），且 Subscription 实体无 @Version 乐观锁
--   ⇒ prod 三副本（values-prod.yaml nexus-gateway.replicaCount=3）或
--   "整点扫描 + 手工/管理端点触发"并发时，同一账期会被两个执行方各自读到"已到期"、
--   各扣一次。对照 v1 体系（SubscriptionServiceImpl#charge 用 claimTemplate +
--   条件 UPDATE 原子认领，"宁可漏收、不可双扣"）：v2 这条路径缺这道原子认领。
--
-- 语义：一行 = 一个 (订阅, 账期) 的扣款执行权认领。cycle_key 取扣款时的 nextChargeAt
--   （本次到期的账期点）——扣款成功会推进周期、dunning 失败会重设 nextRetryAt，
--   两者都会让下一次的 nextChargeAt 变成新值，因此：
--     * "同一次到期的并发重复" 共享同一 key ⇒ 被唯一键挡下（只有一个执行方拿到执行权）；
--     * "失败后的重试" key 已变 ⇒ 可再次认领，dunning 重试语义不受影响。
--   认领在一次独立的短事务里立即提交（见 SubscriptionChargeClaimService），先于动账提交，
--   与 v1 的"认领已提交，链上转账在事务外执行"口径一致。
--
-- 失败语义（与 v1 一致）：认领后动账失败不回滚认领（回滚会在"转账超时但实际已广播"的
--   场景下重新打开双扣窗口）——宁可漏收、不可双扣；漏收由 dunning 重试与人工/补偿跟进。
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL8 双兼容写法（与 V18 同款，便于本地/CI 以真实
--   唯一键约束做机制回归）。
CREATE TABLE subscription_charge_claim (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    subscription_id VARCHAR(64)  NOT NULL,
    cycle_key       VARCHAR(64)  NOT NULL,
    claimed_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_scc_subscription_cycle UNIQUE (subscription_id, cycle_key)
);
