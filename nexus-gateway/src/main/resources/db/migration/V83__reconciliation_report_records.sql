-- V83: 对账报表记录表
-- 存储T+1对账报表的元数据和汇总统计

CREATE TABLE reconciliation_report_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL COMMENT '商户ID',
    report_date DATE NOT NULL COMMENT '报表日期',
    channel_type VARCHAR(16) COMMENT '渠道类型(WECHAT/ALIPAY/ALL)',
    file_format VARCHAR(8) NOT NULL COMMENT '文件格式: JSON/CSV',
    total_transactions INT DEFAULT 0 COMMENT '总交易数',
    matched_count INT DEFAULT 0 COMMENT '匹配成功数',
    discrepancy_count INT DEFAULT 0 COMMENT '差异数',
    compensation_count INT DEFAULT 0 COMMENT '补偿数',
    suspense_count INT DEFAULT 0 COMMENT '挂账数',
    total_discrepancy_amount DECIMAL(36, 2) DEFAULT 0 COMMENT '差异总金额',
    file_size_bytes BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账报表记录';

CREATE INDEX idx_rrr_merchant_date ON reconciliation_report_records(merchant_id, report_date);
CREATE INDEX idx_rrr_merchant_channel_date ON reconciliation_report_records(merchant_id, channel_type, report_date);