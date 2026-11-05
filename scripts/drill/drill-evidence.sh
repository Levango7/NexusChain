#!/usr/bin/env bash
# ============================================================
# drill-evidence.sh — 场景证据快照采集（docs/drill/multi-node-drill-plan.md §5）
#
# 用法:
#   bash scripts/drill/drill-evidence.sh <场景id> [采样窗数] [采样间隔s]
#   场景id 例: s1-stop-restart / s2-partition / s3-pg-failover / s4-disk-loss / s5-registered
#
# 产出（场景证据目录下）:
#   timeline.tsv      每行: 时间 A.h B.h C.h
#   metrics-<node>/   /actuator/prometheus 快照（不可达时记 unreachable.txt）
#   finality.json     三节点 finality 视图原始响应（口径候选，待 nex 复核）
#   pg-counts.txt     finality 三表行数（Tier-0 docker psql / Tier-2 ssh）
#   rejected.json     拒绝计数器按 reason 聚合（从最新 metrics 快照提取）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/drill/drill-common.sh

SCENARIO="${1:?用法: drill-evidence.sh <场景id> [窗数] [间隔s]}"
WINDOWS="${2:-12}"
INTERVAL="${3:-5}"

D=$(evidence_dir "$SCENARIO")
run_meta "$D"
echo "[EVIDENCE] 采集 $SCENARIO → $D（$WINDOWS 窗 × ${INTERVAL}s）"

# --- timeline 主循环 ---
for i in $(seq 1 "$WINDOWS"); do
  row=$(timeline_row "$D")
  echo "[T+$(( (i-1)*INTERVAL ))s] heights(A/B/C) = $row"
  [ "$i" -lt "$WINDOWS" ] && sleep "$INTERVAL"
done

# --- metrics 快照（每节点一份当前快照）---
for n in a b c; do
  mdir="$D/metrics-$n"
  mkdir -p "$mdir"
  body=$(drill_curl "$n" '/actuator/prometheus')
  if [ -n "$body" ]; then
    printf '%s\n' "$body" > "$mdir/final.txt"
    # 拒绝计数器序列提取（PLAN-065/066/067 的判据序列）
    grep -E 'nexus_finality_vote_rejected_total|nexus_finality_finalize_rejected_total' \
      "$mdir/final.txt" > "$D/rejected-$n.txt" 2>/dev/null || true
  else
    echo "unreachable at $(date '+%H:%M:%S')" > "$mdir/unreachable.txt"
  fi
done

# --- finality 视图快照（端点为候选口径，nex 复核）---
{
  echo "{"
  echo "  \"collected_at\": \"$(date '+%H:%M:%S')\","
  for n in a b c; do
    echo "  \"node_$n\": $(finality_snapshot "$n" 1 2>/dev/null || echo '\"<unreachable>\"'),"
  done
  echo "  \"_note\": \"endpoint=$FINALITY_ENDPOINT 为候选口径，未冻结\""
  echo "}"
} > "$D/finality.json"

# --- PG 计数（Tier-0 docker / Tier-2 ssh；不可达只告警不失败）---
pg_counts() {
  local q="SELECT 'votes', count(*) FROM finality_votes UNION ALL SELECT 'checkpoints', count(*) FROM finality_checkpoints UNION ALL SELECT 'outbox', count(*) FROM finality_vote_outbox;"
  if [ "$DRILL_TIER" = "2" ]; then
    ssh "${DRILL_PG_SSH:?Tier-2 需 DRILL_PG_SSH}" "psql -U nexus -d nexuschain -t -c \"$q\"" 2>&1 || echo "PG-UNREACHABLE(tier2)"
  else
    local pgc
    pgc="${PG_CONTAINER:-$(docker ps --filter publish=55432 --format '{{.Names}}' 2>/dev/null | head -1)}"
    pgc="${pgc:-nexus-pgsql}"
    docker exec "$pgc" psql -U nexus -d nexuschain -t -c "$q" 2>&1 || echo "PG-UNREACHABLE(tier0/1)"
  fi
}
pg_counts > "$D/pg-counts.txt" 2>&1 || true

echo "[EVIDENCE] 完成: $D"
ls -1 "$D"
