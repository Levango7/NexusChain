-- V81: 对账规则配置表
-- 支持多层级配置：全局默认(merchant_id=NULL, channel_type=NULL)、渠道默认(channel_type=WECHAT/ALIPAY)、商户级、商户+渠道级

CREATE TABLE reconciliation_rule_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NULL COMMENT '商户ID，NULL表示全局配置',
    channel_type VARCHAR(16) NULL COMMENT '渠道类型(WECHAT/ALIPAY)，NULL表示不限渠道',
    amount_tolerance DECIMAL(36, 2) DEFAULT 0.01 COMMENT '金额容差',
    time_window_minutes INT DEFAULT 5 COMMENT '时间窗口(分钟)，差异在此窗口内视为匹配',
    status_mapping_json TEXT COMMENT '渠道状态到内部状态的映射JSON',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(64),
    updated_by VARCHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账规则配置';

CREATE INDEX idx_rrc_merchant_channel ON reconciliation_rule_configs(merchant_id, channel_type);

-- 种子数据：全局默认配置（空映射，使用内置默认）
INSERT INTO reconciliation_rule_configs (merchant_id, channel_type, amount_tolerance, time_window_minutes, status_mapping_json, created_by)
VALUES (NULL, NULL, 0.01, 5, '{}', 'system');

-- 种子数据：微信渠道默认配置
INSERT INTO reconciliation_rule_configs (merchant_id, channel_type, amount_tolerance, time_window_minutes, status_mapping_json, created_by)
VALUES (NULL, 'WECHAT', 0.01, 5,
    '{"SUCCESS":"SUCCEEDED","REFUND":"REFUNDED","NOTPAY":"PENDING","CLOSED":"CANCELLED","REVOKED":"CANCELLED","USERPAYING":"PENDING","PAYERROR":"FAILED"}',
    'system');

-- 种子数据：支付宝渠道默认配置
INSERT INTO reconciliation_rule_configs (merchant_id, channel_type, amount_tolerance, time_window_minutes, status_mapping_json, created_by)
VALUES (NULL, 'ALIPAY', 0.01, 5,
    '{"TRADE_SUCCESS":"SUCCEEDED","TRADE_FINISHED":"SUCCEEDED","WAIT_BUYER_PAY":"PENDING","TRADE_CLOSED":"CANCELLED","TRADE_REFUND":"REFUNDED"}',
    'system');