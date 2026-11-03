-- ============================================================================
-- migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.sql
-- ============================================================================
-- PLAN-069：最终性历史脏行清理（PLAN-067 遗留项）
-- PLAN-071：扩展到 finality_checkpoints（本脚本负责**两张表**）
--
-- 背景
-- ----
-- PLAN-067 起，生产侧 FinalityGadget.hasValidCheckpointHash() 要求检查点哈希
-- 必须是 Block.HASH_SIZE = 32 字节（hex 表示 64 字符），并在 admission /
-- convergence / restore 三路 fail-closed。但**库里的历史行从未被清理**：
-- 非 32 字节哈希的行仍然躺在 finality_votes 里，每 5 秒被收敛路径重扫一次。
--
-- 两张表都要清，原因不同：
--   * finality_votes：每 5 秒被收敛路径重扫（PLAN-069）；
--   * finality_checkpoints：脏行会经 FinalityGadget.restoreFromStore 的
--     「已终局分支」进入 epochCheckpointWeights（该分支**不做**长度校验，
--     hasValidCheckpointKey 只挡"建索引"），随后被 sweepFinalization 遍历，
--     造成「库里写着已终局、isFinalized() 返回 false」的长期视图不一致，
--     且每次重启都把同一行重新载入、永不清除（PLAN-071 实测）。
--   脏行**不能由外部攻击者制造**（submitVote/收敛路径均已 fail-closed），
--   只能来自迁移前历史库或手工写库 ⇒ 历史污染，非可达攻击面。
--
-- 为什么是「隔离」而不是「DELETE」
-- --------------------------------
-- 删除是**不可回滚**的破坏性操作。本脚本把待清理的行**原样搬运**到
-- finality_votes_quarantine（additive-only 新表）后才从 finality_votes 移除，
-- 于是：
--   * 生产逻辑不再看到这些行（效果等同删除）；
--   * 数据仍可查、可导出、可**原样搬回**（真正可回滚）；
--   * 审计有据：谁、何时、为什么被隔离。
--
-- 判定口径（必须与生产代码逐字一致）
-- ----------------------------------
-- 生产侧：
--   hasValidCheckpointHash(byte[] h)  =>  h != null && h.length == 32
--   hasValidCheckpointKey(String key) =>  hexOfKey(key).length() == 64
-- 写入侧 JdbcFinalityStateStore.hashStr() 用 HexFormat.of().formatHex(bytes)，
-- 即 32 字节 => 恰好 64 个小写 hex 字符。
--
-- 因此一条行是「合法」的充要条件：
--   * checkpoint_hash 长度为 64；且
--   * checkpoint_hash 只含 hex 字符（否则 hexToBytes() 解不出来，
--     读路径会退化成 null ⇒ 校验必然失败）。
-- 其余一律视为脏行（含长度不足、超长、非 hex、大小写混用因长度达标而合法——
-- 注意：PostgreSQL 的 ~ 正则区分大小写，故用 [0-9a-fA-F] 接受大写）。
--
-- 可重复执行
-- ----------
-- 全部语句幂等：重复执行只会把「仍在 finality_votes 里的脏行」再搬一次，
-- 已在隔离表里的行因主键冲突被 ON CONFLICT DO NOTHING 跳过。
--
-- 执行方式
-- --------
-- 按 docs/db-schema-versioning.md §三.2：**由运维在升级窗口手动执行**，
-- 应用启动不会自动跑本脚本。
--
--   psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 \
--        -f migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.sql
--
-- 建议先只跑「§1 审计」段看清楚要动多少行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- §0 隔离表（additive-only，与 finality_votes 同构 + 审计列）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS finality_votes_quarantine (
    epoch           BIGINT       NOT NULL,
    checkpoint_hash VARCHAR(128) NOT NULL,
    validator       VARCHAR(128) NOT NULL,
    signature       VARCHAR(256),
    public_key      VARCHAR(256),
    -- 审计列
    reason          VARCHAR(64)  NOT NULL,
    quarantined_at  BIGINT       NOT NULL,
    quarantined_by  VARCHAR(64)  NOT NULL,
    migration_id    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (epoch, checkpoint_hash, validator)
);

-- ---------------------------------------------------------------------------
-- §0b 检查点隔离表（PLAN-071）
-- ---------------------------------------------------------------------------
-- 为什么不复用上面的表：finality_checkpoints 的主键是 (epoch, checkpoint_hash)，
-- **没有 validator 列**。硬塞进 votes 隔离表就得给 validator 编一个哨兵值
-- （空串/占位符），那是数据建模上的谎——审计表一旦说谎，事后就无法可靠反查。
-- 每张源表配一张同构隔离表，"搬回哪张表"由表身份决定，不靠判别列（判别列也会漂）。
-- 审计列与 votes 隔离表完全一致，查"001 这次动了什么"用 UNION ALL 即可。
CREATE TABLE IF NOT EXISTS finality_checkpoints_quarantine (
    epoch           BIGINT       NOT NULL,
    checkpoint_hash VARCHAR(128) NOT NULL,
    -- 审计列（与 finality_votes_quarantine 同名同义）
    reason          VARCHAR(64)  NOT NULL,
    quarantined_at  BIGINT       NOT NULL,
    quarantined_by  VARCHAR(64)  NOT NULL,
    migration_id    VARCHAR(64)  NOT NULL,
    PRIMARY KEY (epoch, checkpoint_hash)
);

-- ---------------------------------------------------------------------------
-- §1 审计（只读）：先看清楚将被隔离的行
-- ---------------------------------------------------------------------------
-- 单独执行本段即可预览，不产生任何写入。
SELECT
    epoch,
    validator,
    checkpoint_hash,
    length(checkpoint_hash)                      AS hex_len,
    (length(checkpoint_hash) = 64
     AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$')  AS is_valid,
    (signature IS NULL)                          AS has_no_signature,
    CASE
        WHEN length(checkpoint_hash) <> 64 THEN 'checkpoint_hash_len_' || length(checkpoint_hash)
        WHEN checkpoint_hash !~ '^[0-9a-fA-F]{64}$' THEN 'checkpoint_hash_not_hex'
        ELSE 'valid'
    END                                          AS classification
FROM finality_votes
ORDER BY epoch, validator;

-- §1b 审计（只读）：finality_checkpoints 的待隔离行（PLAN-071）
SELECT
    epoch,
    checkpoint_hash,
    length(checkpoint_hash)                      AS hex_len,
    CASE
        WHEN length(checkpoint_hash) <> 64 THEN 'checkpoint_hash_len_' || length(checkpoint_hash)
        WHEN checkpoint_hash !~ '^[0-9a-fA-F]{64}$' THEN 'checkpoint_hash_not_hex'
        ELSE 'valid'
    END                                          AS classification
FROM finality_checkpoints
ORDER BY epoch;

-- ---------------------------------------------------------------------------
-- §2 搬运脏行到隔离表
-- ---------------------------------------------------------------------------
-- 判定口径与生产一致（见文件头）。quarantined_by / migration_id 写死在脚本里，
-- 便于事后从审计表反查是哪个迁移动作造成的。
INSERT INTO finality_votes_quarantine
    (epoch, checkpoint_hash, validator, signature, public_key,
     reason, quarantined_at, quarantined_by, migration_id)
SELECT
    v.epoch,
    v.checkpoint_hash,
    v.validator,
    v.signature,
    v.public_key,
    CASE
        WHEN length(v.checkpoint_hash) <> 64 THEN 'checkpoint_hash_len_' || length(v.checkpoint_hash)
        ELSE 'checkpoint_hash_not_hex'
    END,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    'migrations/pg/001',
    'PLAN-069/001'
FROM finality_votes v
WHERE NOT (length(v.checkpoint_hash) = 64
           AND v.checkpoint_hash ~ '^[0-9a-fA-F]{64}$')
ON CONFLICT (epoch, checkpoint_hash, validator) DO NOTHING;

-- ---------------------------------------------------------------------------
-- §2b 搬运 finality_checkpoints 的脏行（PLAN-071）
-- ---------------------------------------------------------------------------
-- 同一套判定口径。这张表的脏行会经 FinalityGadget.restoreFromStore 的
-- 「已终局分支」进入 epochCheckpointWeights（该分支**不做**长度校验），
-- 造成「库里写着已终局、isFinalized() 却返回 false」的长期视图不一致。
INSERT INTO finality_checkpoints_quarantine
    (epoch, checkpoint_hash, reason, quarantined_at, quarantined_by, migration_id)
SELECT
    c.epoch,
    c.checkpoint_hash,
    CASE
        WHEN length(c.checkpoint_hash) <> 64 THEN 'checkpoint_hash_len_' || length(c.checkpoint_hash)
        ELSE 'checkpoint_hash_not_hex'
    END,
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT,
    'migrations/pg/001',
    'PLAN-071/001'
FROM finality_checkpoints c
WHERE NOT (length(c.checkpoint_hash) = 64
           AND c.checkpoint_hash ~ '^[0-9a-fA-F]{64}$')
ON CONFLICT (epoch, checkpoint_hash) DO NOTHING;

-- ---------------------------------------------------------------------------
-- §3 从 finality_votes 移除已隔离的行
-- ---------------------------------------------------------------------------
-- 只删「已确认存在于隔离表」的行（双保险：即使 §2 因故少搬，也不会丢数据）。
DELETE FROM finality_votes v
WHERE NOT (length(v.checkpoint_hash) = 64
           AND v.checkpoint_hash ~ '^[0-9a-fA-F]{64}$')
  AND EXISTS (
        SELECT 1 FROM finality_votes_quarantine q
        WHERE q.epoch = v.epoch
          AND q.checkpoint_hash = v.checkpoint_hash
          AND q.validator = v.validator
  );

-- ---------------------------------------------------------------------------
-- §3b 从 finality_checkpoints 移除已隔离的行（PLAN-071）
-- ---------------------------------------------------------------------------
-- 同样带 EXISTS 双保险：宁可留脏行，也不静默丢数据。
DELETE FROM finality_checkpoints c
WHERE NOT (length(c.checkpoint_hash) = 64
           AND c.checkpoint_hash ~ '^[0-9a-fA-F]{64}$')
  AND EXISTS (
        SELECT 1 FROM finality_checkpoints_quarantine q
        WHERE q.epoch = c.epoch
          AND q.checkpoint_hash = c.checkpoint_hash
  );

-- ---------------------------------------------------------------------------
-- §4 验证（只读）：执行后自检
-- ---------------------------------------------------------------------------
-- 期望：remaining_invalid = 0
SELECT
    (SELECT count(*) FROM finality_votes)             AS rows_in_votes,
    (SELECT count(*) FROM finality_votes_quarantine)  AS rows_in_quarantine,
    (SELECT count(*) FROM finality_checkpoints)       AS rows_in_checkpoints,
    (SELECT count(*) FROM finality_checkpoints_quarantine) AS rows_in_cp_quarantine,
    (SELECT count(*) FROM finality_votes
      WHERE NOT (length(checkpoint_hash) = 64
                 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$')) AS remaining_invalid,
    (SELECT count(*) FROM finality_checkpoints
      WHERE NOT (length(checkpoint_hash) = 64
                 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$')) AS invalid_in_checkpoints;
-- 期望：remaining_invalid = 0 且 invalid_in_checkpoints = 0（PLAN-071 起后者也清零）
