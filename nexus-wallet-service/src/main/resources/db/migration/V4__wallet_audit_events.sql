-- V4: 钱包侧操作审计表（P0 补齐 2026-10-11）
--
-- 背景：网关侧审计已落库（audit_events，v2.63.0），但 wallet-service 是**独立模块、独立库**，
-- 提现（资金出口）的请求/审批/执行/失败**只写日志文件**——跨服务审计若要落库，
-- 最省架构代价的做法是让本模块**自带审计表**（不引入事件总线/Kafka 等新设施）。
--
-- 写入方：DefaultWithdrawalApprovalService（提现四个关键节点）。
-- 语义：append-only（应用层只插入，不提供改/删接口）；审计写失败**不得阻断**资金流程（旁路）。
--
-- H2 兼容性说明（application-dev.yml 用 H2 MODE=MySQL）：
--   * 不使用 ENGINE=InnoDB / DEFAULT CHARSET 后缀（H2 不支持；MySQL 8 默认即 InnoDB+utf8mb4）
--   * DATETIME(6) H2 MODE=MySQL 支持
CREATE TABLE IF NOT EXISTS wallet_audit_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_time  DATETIME(6)   NOT NULL COMMENT '事件时间',
    action      VARCHAR(64)   NOT NULL COMMENT 'WITHDRAWAL_REQUESTED/APPROVED/EXECUTED/FAILED',
    request_id  VARCHAR(64)   NOT NULL COMMENT '提现请求 ID（资源标识）',
    to_address  VARCHAR(128)  NULL     COMMENT '收款地址（资金去向，排障需要）',
    amount      VARCHAR(64)   NULL     COMMENT '金额（字符串存放，避免精度语义分歧）',
    currency    VARCHAR(16)   NULL,
    detail      VARCHAR(512)  NULL     COMMENT '附加信息（审批人 / 链上哈希 / 失败原因）'
);
CREATE INDEX idx_wae_request ON wallet_audit_events(request_id);
CREATE INDEX idx_wae_action ON wallet_audit_events(action);
CREATE INDEX idx_wae_time ON wallet_audit_events(event_time);
