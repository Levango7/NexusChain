-- V71: 微信平台证书缓存表 — 存储微信支付平台证书，支持自动轮换与过期检测

CREATE TABLE wechat_platform_certificates (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    serial_no               VARCHAR(128)  NOT NULL COMMENT '证书序列号',
    certificate_content     TEXT          NOT NULL COMMENT '证书内容（PEM格式）',
    effective_time          TIMESTAMP     NOT NULL COMMENT '证书生效时间',
    expire_time             TIMESTAMP     NOT NULL COMMENT '证书过期时间',
    fetched_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '获取时间',
    status                  VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/EXPIRED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wpc_serial_no (serial_no),
    KEY idx_wpc_status_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信平台证书缓存表';