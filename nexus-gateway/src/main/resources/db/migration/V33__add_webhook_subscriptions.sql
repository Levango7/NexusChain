-- Webhook 订阅管理表
-- 商户可创建 Webhook 订阅，指定目标 URL、事件类型、签名密钥等，
-- 投递服务根据订阅配置向目标 URL 发起 POST 回调。
CREATE TABLE webhook_subscriptions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    merchantId        BIGINT       NOT NULL COMMENT '商户 ID（租户隔离）',
    targetUrl         VARCHAR(512) NOT NULL COMMENT '回调目标 URL',
    eventTypes        VARCHAR(256) NOT NULL COMMENT '订阅的事件类型（逗号分隔）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '订阅状态：ACTIVE/PAUSED/DELETED',
    signingSecret     VARCHAR(64)  NOT NULL COMMENT '签名密钥（SecureRandom 生成，Base64 编码）',
    description       VARCHAR(256) COMMENT '订阅描述（可选）',
    retryPolicy       VARCHAR(512) COMMENT '重试策略（JSON，默认 3 次指数退避）',
    filterExpression  VARCHAR(512) COMMENT '可选 JSONPath 过滤表达式',
    createdAt         DATETIME     NOT NULL COMMENT '创建时间',
    updatedAt         DATETIME     NOT NULL COMMENT '更新时间',
    version           BIGINT       COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_ws_merchant (merchantId),
    INDEX idx_ws_status (status),
    INDEX idx_ws_merchant_status (merchantId, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Webhook 订阅管理表';