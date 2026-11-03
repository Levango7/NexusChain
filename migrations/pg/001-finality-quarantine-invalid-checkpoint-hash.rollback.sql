-- ============================================================================
-- migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.rollback.sql
-- ============================================================================
-- PLAN-069 回滚脚本：把隔离表里的行**原样搬回** finality_votes。
--
-- 这是真正的回滚（不是"删掉隔离表假装没发生"）：
--   * 主键 (epoch, checkpoint_hash, validator) 保证不会造出重复行；
--   * 搬回后该行会重新被生产侧判定为脏行（hasValidCheckpointHash 仍拒绝它），
--     所以回滚是**安全**的——只是恢复到"脏行留在生产表里"的原始状态；
--   * 隔离表**不删除**，保留审计痕迹（quarantined_at/by/reason 仍在）。
--     若确需彻底清空审计痕迹，是另一个需要显式决策的动作，不放在回滚路径里。
--
-- 执行方式（同正向脚本：运维手动执行）：
--   psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 \
--        -f migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.rollback.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- §1 审计（只读）：将要搬回多少行（可按 migration_id 收窄）
-- ---------------------------------------------------------------------------
SELECT
    migration_id,
    reason,
    count(*)         AS rows_to_restore,
    min(quarantined_at) AS first_at,
    max(quarantined_at) AS last_at
FROM finality_votes_quarantine
GROUP BY migration_id, reason
ORDER BY migration_id, reason;

-- §1b 审计（只读）：检查点隔离表（PLAN-071）
SELECT
    migration_id,
    reason,
    count(*)         AS rows_to_restore,
    min(quarantined_at) AS first_at,
    max(quarantined_at) AS last_at
FROM finality_checkpoints_quarantine
GROUP BY migration_id, reason
ORDER BY migration_id, reason;

-- ---------------------------------------------------------------------------
-- §2 搬回
-- ---------------------------------------------------------------------------
-- 只搬本迁移隔离的行（migration_id = 'PLAN-069/001'）。其他来源的隔离行
-- 不应被本切片的回滚顺带恢复。
INSERT INTO finality_votes (epoch, checkpoint_hash, validator, signature, public_key)
SELECT q.epoch, q.checkpoint_hash, q.validator, q.signature, q.public_key
FROM finality_votes_quarantine q
WHERE q.migration_id = 'PLAN-069/001'
ON CONFLICT (epoch, checkpoint_hash, validator) DO NOTHING;

-- §2b 把检查点隔离行搬回 finality_checkpoints（PLAN-071）
-- 按 migration_id 分别收窄：PLAN-071/001 是本次新增的来源。
INSERT INTO finality_checkpoints (epoch, checkpoint_hash)
SELECT q.epoch, q.checkpoint_hash
FROM finality_checkpoints_quarantine q
WHERE q.migration_id = 'PLAN-071/001'
ON CONFLICT (epoch, checkpoint_hash) DO NOTHING;

-- ---------------------------------------------------------------------------
-- §3 验证（只读）
-- ---------------------------------------------------------------------------
-- 期望：restored_rows 与 §1 的 rows_to_restore 相等（若期间没有并发写入同一主键）
SELECT
    (SELECT count(*) FROM finality_votes)                                   AS rows_in_votes,
    (SELECT count(*) FROM finality_votes_quarantine
      WHERE migration_id = 'PLAN-069/001')                                  AS still_quarantined,
    (SELECT count(*) FROM finality_checkpoints)                             AS rows_in_checkpoints,
    (SELECT count(*) FROM finality_checkpoints_quarantine
      WHERE migration_id = 'PLAN-071/001')                                  AS cp_still_quarantined,
    (SELECT count(*) FROM finality_votes
      WHERE NOT (length(checkpoint_hash) = 64
                 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$'))               AS invalid_back_in_votes,
    (SELECT count(*) FROM finality_checkpoints
      WHERE NOT (length(checkpoint_hash) = 64
                 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$'))               AS invalid_back_in_checkpoints;
