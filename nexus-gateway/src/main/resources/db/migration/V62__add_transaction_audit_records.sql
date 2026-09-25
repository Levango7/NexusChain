-- V54: 交易对账审计记录表 — 对账与资金账户联动
-- 记录每日正向+反向对账审计的结果
-- 审计方向: FORWARD(正向)/REVERSE(反向)
-- 审计结论: BALANCED(平衡)/DISCREPANCY_FOUND(有差异)/AUDIT_FAILED(审计失败)

CREATE TABLE transaction_audit_records (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    audit_date              DATE          NOT NULL COMMENT '审计日期',
    merchant_id             BIGINT        NULL DEFAULT NULL COMMENT '商户 ID（null 表示全量审计）',
    audit_direction         VARCHAR(32)   NOT NULL COMMENT '审计方向: FORWARD/REVERSE',
    internal_total_count    BIGINT        NULL DEFAULT NULL COMMENT '内部侧总交易数',
    channel_total_count     BIGINT        NULL DEFAULT NULL COMMENT '渠道侧总交易数',
    forward_matched_count   BIGINT        NULL DEFAULT NULL COMMENT '正向匹配数',
    reverse_matched_count   BIGINT        NULL DEFAULT NULL COMMENT '反向匹配数',
    discrepancy_count       BIGINT        NULL DEFAULT NULL COMMENT '差异总数',
    discrepancy_amount      DECIMAL(36,0) NULL DEFAULT NULL COMMENT '差异总金额',
    conclusion              VARCHAR(32)   NOT NULL COMMENT '审计结论: BALANCED/DISCREPANCY_FOUND/AUDIT_FAILED',
    description             VARCHAR(2048) COMMENT '审计描述',
    executed_at             TIMESTAMP(6)  NOT NULL COMMENT '审计执行时间',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_tar_audit_date (audit_date),
    INDEX idx_tar_merchant_id (merchant_id),
    INDEX idx_tar_conclusion (conclusion),
    INDEX idx_tar_audit_date_merchant (audit_date, merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易对账审计记录表';