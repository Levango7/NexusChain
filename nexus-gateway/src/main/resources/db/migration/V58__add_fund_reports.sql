-- V58: 资金报表表 — Wave 11 商户资金管理增强
-- 支持日报/周报/月报三种报表类型，CSV/JSON 两种格式
-- 报表内容包含账户流水汇总，账户编号脱敏（仅保留后 4 位）

CREATE TABLE fund_reports (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    report_no       VARCHAR(64)  NOT NULL COMMENT '报表编号，格式 FR{timestamp}{random}',
    merchant_id     BIGINT       NOT NULL COMMENT '商户 ID',
    report_type     VARCHAR(16)  NOT NULL COMMENT '报表类型: DAILY/WEEKLY/MONTHLY',
    report_format   VARCHAR(8)   NOT NULL COMMENT '报表格式: CSV/JSON',
    period_start    TIMESTAMP(6) NOT NULL COMMENT '报表周期开始时间',
    period_end      TIMESTAMP(6) NOT NULL COMMENT '报表周期结束时间',
    content         TEXT         NOT NULL COMMENT '报表内容（CSV 或 JSON 格式）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'GENERATED' COMMENT '状态: GENERATED/FAILED',
    tenant_id       VARCHAR(64)  COMMENT '多租户隔离键',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_fr_report_no UNIQUE (report_no),
    INDEX idx_fr_merchant_id (merchant_id),
    INDEX idx_fr_report_type (report_type),
    INDEX idx_fr_period (period_start, period_end),
    INDEX idx_fr_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金报表表';