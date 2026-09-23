-- 数据导出请求表
-- 记录商户发起的数据导出请求的完整生命周期：PENDING → PROCESSING → COMPLETED/FAILED → EXPIRED
CREATE TABLE data_export_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT       NOT NULL COMMENT '发起导出的商户 ID',
    export_type     VARCHAR(32)  NOT NULL COMMENT '导出类型: TRANSACTIONS/SETTLEMENTS/REFUNDS/SPLITS/RISK_EVENTS/WEBHOOK_DELIVERIES',
    format          VARCHAR(16)  NOT NULL COMMENT '导出格式: CSV/JSON',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '请求状态: PENDING/PROCESSING/COMPLETED/FAILED/EXPIRED',
    date_from       DATETIME     NOT NULL COMMENT '数据时间范围起始',
    date_to         DATETIME     NOT NULL COMMENT '数据时间范围结束',
    filters         VARCHAR(2048) COMMENT '额外过滤条件（JSON 格式）',
    file_path       VARCHAR(512) COMMENT '生成文件的存储路径',
    file_size_bytes BIGINT       COMMENT '文件大小（字节）',
    record_count    INT          COMMENT '导出记录数',
    created_at      DATETIME     NOT NULL COMMENT '请求创建时间',
    completed_at    DATETIME     COMMENT '处理完成时间',
    expired_at      DATETIME     COMMENT '过期时间（清理任务设置）',
    error_message   VARCHAR(1024) COMMENT '失败时的错误信息',
    version         BIGINT       COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_der_merchant_id (merchant_id),
    INDEX idx_der_status (status),
    INDEX idx_der_merchant_status (merchant_id, status),
    INDEX idx_der_completed_at (completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据导出请求表';