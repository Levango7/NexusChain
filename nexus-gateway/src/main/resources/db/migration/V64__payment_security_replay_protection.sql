-- V64: 防重放攻击增强 — 防重放配置表 + 拦截统计表
-- 设计依据: Wave 12 设计文档 §3.2 V64

-- 防重放保护配置表（支持热更新）
CREATE TABLE replay_protection_configs (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id               VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    replay_window_ms        BIGINT        NOT NULL DEFAULT 180000 COMMENT '防重放窗口(毫秒), 默认3分钟',
    nonce_min_length_bytes  INT           NOT NULL DEFAULT 16 COMMENT 'nonce 最小长度(字节)',
    idempotency_ttl_hours   INT           NOT NULL DEFAULT 24 COMMENT '幂等性键 TTL(小时)',
    created_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at              TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                 BIGINT        NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    INDEX idx_rpc_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防重放保护配置表';

-- 防重放拦截统计表（按商户+错误码维度）
CREATE TABLE replay_interception_stats (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id               VARCHAR(64)   NOT NULL COMMENT '租户 ID',
    merchant_id             BIGINT        NULL DEFAULT NULL COMMENT '商户 ID',
    error_code              VARCHAR(32)   NOT NULL COMMENT '错误码: 40103/40106/40109/40110/40111/40112',
    intercepted_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '拦截时间',
    PRIMARY KEY (id),
    INDEX idx_ris_tenant_merchant_time (tenant_id, merchant_id, intercepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防重放拦截统计表';