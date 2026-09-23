-- API 版本策略表
-- 存储 API 版本生命周期治理元数据：状态（ACTIVE/DEPRECATED/SUNSET/RETIRED）、
-- 废弃日期、日落日期、退役日期、后继版本、迁移指南等
CREATE TABLE api_version_policies (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    version             VARCHAR(16)  NOT NULL COMMENT '版本标签，如 v1、v2',
    status              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '版本状态：ACTIVE/DEPRECATED/SUNSET/RETIRED',
    deprecated_at       DATE         NULL COMMENT '废弃声明日期',
    sunset_at           DATE         NULL COMMENT '日落日期（此日期后返回 410 Gone）',
    retired_at          DATE         NULL COMMENT '完全移除日期',
    successor_version   VARCHAR(16)  NULL COMMENT '后继版本，如 v1 的后继是 v2',
    migration_guide     VARCHAR(2048) NULL COMMENT '迁移指南文本',
    description         VARCHAR(512) NULL COMMENT '描述信息',
    created_at          DATETIME     NOT NULL COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_version_policies_version (version),
    INDEX idx_api_version_policies_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API 版本策略表';

-- 初始化 v1 与 v2 的默认策略
-- v1: 当前状态为 DEPRECATED，Sunset 日期 2027-02-09，后继版本 v2
-- v2: 当前状态为 ACTIVE
INSERT INTO api_version_policies (version, status, deprecated_at, sunset_at, successor_version, migration_guide, description, created_at, updated_at) VALUES
    ('v1', 'DEPRECATED', '2026-08-09', '2027-02-09', 'v2',
     'v1 API has been deprecated. Please migrate to v2. Key changes: cursor pagination, field selection, unified error codes. See https://docs.nexus.network/api/v2-migration-guide',
     'Original API version, deprecated in favor of v2',
     NOW(), NOW()),
    ('v2', 'ACTIVE', NULL, NULL, NULL,
     NULL,
     'Current stable API version',
     NOW(), NOW());