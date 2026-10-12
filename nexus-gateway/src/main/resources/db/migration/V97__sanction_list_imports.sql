-- V97: 制裁名单导入记录（离线名单包「版本台账」，2026-10-12）
--
-- 背景：v2.68.0 给制裁名单做了"能导入"的入口（文件加载 + 管理端点 + 审计），但
-- **"当前生效的名单是哪个版本、从哪来的、谁导入的"无法回答** ——
-- 合规尽调必问，而审计事件里的 JSON detail 只适合逐条追溯、不适合一眼看版本。
--
-- 本表记每次导入的元信息（append-only，与 audit_events 同口径：只 INSERT）。
-- 与 audit_events 的分工：
--   · audit_events  = 谁在什么时候动了名单（逐条事件流，含单条增删）
--   · 本表          = 名单**版本**台账（source/version/格式/条目数/内容哈希）
CREATE TABLE IF NOT EXISTS sanction_list_imports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    imported_at TIMESTAMP(6) NOT NULL COMMENT '导入时间',
    actor VARCHAR(128) NULL COMMENT '操作人（JWT 主体；无认证上下文时为 system）',
    source VARCHAR(64) NOT NULL COMMENT '名单来源，如 OFAC_SDN / UN_CONSOLIDATED / 客户自定义',
    version VARCHAR(64) NULL COMMENT '名单版本（官方发布日期或客户给的口径）',
    format VARCHAR(16) NOT NULL COMMENT '解析出的格式：OFAC_CSV / UN_XML / LINES',
    mode VARCHAR(16) NOT NULL COMMENT '导入模式：append（追加）/ replace（整体替换）',
    imported_count INT NOT NULL COMMENT '本次解析出的有效条目数',
    skipped_count INT NOT NULL COMMENT '本次跳过的行数（-0- 占位/空行等）',
    removed_count INT NOT NULL COMMENT 'replace 模式下真正消失的旧条目数（append 恒 0）',
    total_after INT NOT NULL COMMENT '导入后名单总条目数',
    content_sha256 VARCHAR(64) NULL COMMENT '导入内容 SHA-256（同一份包重复导入可识别）',
    KEY idx_sli_time (imported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='制裁名单导入版本台账（append-only）';
