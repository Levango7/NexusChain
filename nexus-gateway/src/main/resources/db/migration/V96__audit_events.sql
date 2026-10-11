-- V96: 审计事件落库（P0 修复 2026-10-11）
--
-- 背景：审计此前只写 AUDIT logger（logback → audit.json 文件）：既不落库、也不可查询，
-- 且支付/退款/提现等资金主链路零覆盖。资金纠纷时"谁在什么时候动了钱"无从查起。
--
-- append-only：业务上只 INSERT、不 UPDATE、不 DELETE（应用层不提供改/删接口）。
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sequence_no BIGINT NOT NULL COMMENT '进程内单调序号（与日志序号一致）',
    event_time TIMESTAMP(6) NOT NULL COMMENT '事件时间',
    actor VARCHAR(128) NULL COMMENT '行为主体（merchant:<id> / system / admin:<id>）',
    action VARCHAR(64) NOT NULL COMMENT '动作，如 PAYMENT_CONFIRMED / REFUND_* / WITHDRAW_*',
    resource VARCHAR(256) NULL COMMENT '资源标识，如 order:<orderNo>',
    detail VARCHAR(1024) NULL COMMENT '详情（不得放敏感明文）',
    ip_address VARCHAR(64) NULL COMMENT '来源 IP',
    merchant_id BIGINT NULL COMMENT '商户 ID',
    KEY idx_audit_merchant (merchant_id),
    KEY idx_audit_action (action),
    KEY idx_audit_ts (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计事件（append-only）';
