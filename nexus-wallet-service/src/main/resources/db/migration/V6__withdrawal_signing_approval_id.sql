-- V6: 提现记录持久化签名侧审批 ID（2026-10-27，(c)-lite 止血）
--
-- 背景：signing-service 对 >= largeAmountThreshold 的转账走阻断式审批——
--   POST /api/v1/transfers/sign 不签名，返回 statusCode=2001 + approvalId（无 data）。
--   而 wallet 的 executeApprovedWithdrawal 用 SigningResponses.txHash(...)（该方法
--   已标 @Deprecated，javadoc 原文："继续使用会把大额审批请求误记为 FAILED"）取哈希：
--   2001 没有 data => txHash=null => 判为 "signing service returned empty result"
--   => 提现置 FAILED。
--
--   后果三条：
--     1. 提现永久 FAILED —— 状态机不可重入（CAS 只认 APPROVED -> EXECUTING），
--        钱没出但单子死了，只能人工介入；
--     2. signing 侧那条审批请求永远没人消费（悬空审批）；
--     3. 冷提路径（DefaultCustodyService 也走本方法）会叠加 custody_transfer_pending
--        的 APPROVAL_OPEN 记录，同一笔钱在两处各留一条待处置。
--
--   修法（(c)-lite）：把 approvalId 落到本表，提现回到 APPROVED 等待；
--   由推进腿查 GET /api/v1/signing/approvals/{id}，状态 APPROVED 后调
--   POST /api/v1/transfers/sign/approved 执行。全部门槛保留
--   （wallet 多签 + signing 大额审批都要过），只是不再产生悬空审批。
--
--   不复用 custody_transfer_pending 表：那张表是托管转账的「待处置」台账
--   （APPROVAL_OPEN / VERIFY_REQUIRED），语义是「需要人动手」；提现这里是
--   「系统会自动推进」。两类东西混一张表会让处置口径含糊。
--
-- DDL 采用 H2(MODE=MySQL) 与 MySQL 8 双兼容写法（与 V1/V5 同口径：
--   不用 ENGINE/CHARSET 后缀、不用内联 COMMENT）。
ALTER TABLE withdrawal_requests
    ADD COLUMN signing_approval_id VARCHAR(64);

-- 防双花（investigator 复核提出）：signing 侧的审批状态会因 TTL 超时从
--   EXECUTING 回退到 APPROVED（SigningApprovalService 的 cleanup 阶段二）。
--   若上一次执行其实已广播、只是响应丢失，那么「看到 APPROVED 就执行」
--   会导致二次放款。本列记录「我们曾向 signing 发起过执行」，非空且再次
--   看到 APPROVED 时不盲目重试，而是判 FAILED 并交人工核对（fail-closed）。
ALTER TABLE withdrawal_requests
    ADD COLUMN signing_execution_attempted_at TIMESTAMP;
