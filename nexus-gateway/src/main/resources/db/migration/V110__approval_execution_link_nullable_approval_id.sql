-- V110: approval_execution_link.approval_id 放开非空约束（2026-10-30）
--
-- 背景：SigningApprovalExecutionService 首腿（直接签名）在「结果不明」时
--   （异常/降级 null/非契约未动账码）需要落一条终态 EXECUTION_UNKNOWN 链接，
--   作为同一 requestKey 的**阻塞闸口**——否则重试会再次调用 signTransfer，
--   若首次调用已广播即二次广播。这类行**不对应任何 signing-service 审批**
--   （首腿是直接签名路径，信封里没有 approvalId），approval_id 必须可为 NULL。
--
-- 影响面：仅放开本表一列的非空约束，不动数据、不动其他表。
--   既有行全部有 approval_id 值，约束放开后不受影响；
--   新行为终态（EXECUTION_UNKNOWN），任何轮询都因 advance() 的终态短路而不会
--   据此查询审批状态（repository 无 findByApprovalId 之类的调用）。
-- 依据：实体 ApprovalExecutionLink.approvalId 同步改为 nullable=true
--   （ddl-auto=validate 要求实体与迁移一致，见 V107 漂移收口径）。
-- 语法：MODIFY COLUMN 与 V60/V49（同表所在模块、CI MySQL8 预检 + H2 双跑已验证）。

ALTER TABLE approval_execution_link
    MODIFY COLUMN approval_id VARCHAR(64) NULL;
