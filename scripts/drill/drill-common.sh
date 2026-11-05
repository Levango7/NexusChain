#!/usr/bin/env bash
# ============================================================
# drill-common.sh — 多机演练公共库（E3 准备半边，docs/drill/multi-node-drill-plan.md）
#
# 被 drill-node-up.sh / drill-evidence.sh / drill-scenarios.sh source。
# Tier 约定：0=本机三进程共享PG（dev-cluster 形态）/ 1=单机docker独立PG / 2=多机独立PG
# ============================================================
# 环境覆盖（带默认值）：
#   DRILL_TIER            0|1|2（默认 0）
#   DRILL_DRY_RUN         1=注入类命令只打印不执行（默认 0）
#   DRILL_EVIDENCE_ROOT   证据根目录（默认 ./evidence）
#   RUN_ID                证据子目录名（默认 时间戳-tierN）
#   DRILL_NODE_HOST_A/B/C Tier-2 各节点 SSH 主机（例 user@10.0.0.11）
#   FINALITY_ENDPOINT     finality 视图端点（候选口径，待 nex 复核）
# ============================================================

DRILL_TIER="${DRILL_TIER:-0}"
DRILL_DRY_RUN="${DRILL_DRY_RUN:-0}"
DRILL_EVIDENCE_ROOT="${DRILL_EVIDENCE_ROOT:-./evidence}"
RUN_ID="${RUN_ID:-$(date '+%Y%m%d-%H%M')-tier${DRILL_TIER}}"

# --- 端点表（Tier-0/1 走 localhost 端口；Tier-2 走 DRILL_NODE_HOST_* 的 SSH 隧道或直连）---
NODE_RPC_PORT_A=19585
NODE_RPC_PORT_B=19586
NODE_RPC_PORT_C=19587
NODE_P2P_PORT_A=9235
NODE_P2P_PORT_B=9236
NODE_P2P_PORT_C=9237
NODE_HOST_A="${DRILL_NODE_HOST_A:-localhost}"
NODE_HOST_B="${DRILL_NODE_HOST_B:-localhost}"
NODE_HOST_C="${DRILL_NODE_HOST_C:-localhost}"

# 演练用固定验证人私钥（与 scripts/dev-cluster-3nodes.sh 常量一致；仅演练）
PRIVA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PRIVB="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PRIVC="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

FINALITY_ENDPOINT="${FINALITY_ENDPOINT:-/rpc/v1/finality/epoch}"

# --- 工具函数 ---

node_host()   { case "$1" in a) echo "$NODE_HOST_A";; b) echo "$NODE_HOST_B";; c) echo "$NODE_HOST_C";; esac; }
node_port()   { case "$1" in a) echo "$NODE_RPC_PORT_A";; b) echo "$NODE_RPC_PORT_B";; c) echo "$NODE_RPC_PORT_C";; esac; }
node_key()    { case "$1" in a) echo "$PRIVA";; b) echo "$PRIVB";; c) echo "$PRIVC";; esac; }
node_cache()  { case "$1" in a) echo "data-a";; b) echo "data-b";; c) echo "data-c";; esac; }
node_log()    { case "$1" in a) echo "nodeA.log";; b) echo "nodeB.log";; c) echo "nodeC.log";; esac; }

# drill_curl <node> <path> —— 对节点发 GET（Tier-2 直连主机端口，前置 SSH 隧道由部署方建立）
drill_curl() {
  local n="$1" path="$2"
  curl -s --max-time 5 "http://$(node_host "$n"):$(node_port "$n")${path}" 2>/dev/null
}

# height_of <node> —— 返回链高度整数；/height 不可用时回退 -1（调用方判据应容忍）
height_of() {
  local n="$1" h
  h=$(drill_curl "$n" '/height' | tr -dc '0-9')
  if [ -z "$h" ]; then echo "-1"; else echo "$h"; fi
}

# finality_snapshot <node> —— finality 视图原始快照（口径候选，nex 复核）
finality_snapshot() {
  local n="$1" ep="${2:-1}"
  drill_curl "$n" "${FINALITY_ENDPOINT}/${ep}"
}

# inject <描述> <命令...> —— 注入类命令统一入口（dry-run 感知）
inject() {
  local desc="$1"; shift
  echo "[INJECT] $desc"
  echo "[INJECT-CMD] $*"
  if [ "$DRILL_DRY_RUN" = "1" ]; then
    echo "[INJECT] DRY-RUN 跳过执行"
    return 0
  fi
  "$@"
}

# evidence_dir <场景id> —— 建场景证据目录并返回路径
evidence_dir() {
  local s="$1" d
  d="$DRILL_EVIDENCE_ROOT/$RUN_ID/$s"
  mkdir -p "$d/logs"
  echo "$d"
}

# timeline_row <场景dir> —— 追加一行：时间戳 + 三节点高度（5s 粒度由调用方循环驱动）
timeline_row() {
  local d="$1"
  local ha hb hc
  ha=$(height_of a); hb=$(height_of b); hc=$(height_of c)
  printf '%s\t%s\t%s\t%s\n' "$(date '+%H:%M:%S')" "$ha" "$hb" "$hc" >> "$d/timeline.tsv"
  echo "$ha $hb $hc"
}

# rubric <场景dir> <PASS|FAIL|PENDING> <判据id> <证据指针/说明>
rubric() {
  local d="$1" verdict="$2" cid="$3" note="$4"
  printf '| %s | %s | %s |\n' "$cid" "$verdict" "$note" >> "$d/report.md"
  echo "[RUBRIC] $cid=$verdict ($note)"
}

# run_meta <场景dir|根> —— 写 run 元数据（只写一次）
run_meta() {
  local d="$1"
  [ -f "$DRILL_EVIDENCE_ROOT/$RUN_ID/run-meta.json" ] && return 0
  mkdir -p "$DRILL_EVIDENCE_ROOT/$RUN_ID"
  cat > "$DRILL_EVIDENCE_ROOT/$RUN_ID/run-meta.json" <<EOF
{
  "run_id": "$RUN_ID",
  "tier": "$DRILL_TIER",
  "dry_run": "$DRILL_DRY_RUN",
  "hosts": {"a": "$NODE_HOST_A", "b": "$NODE_HOST_B", "c": "$NODE_HOST_C"},
  "finality_endpoint_candidate": "$FINALITY_ENDPOINT",
  "started_utc": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "plan": "docs/drill/multi-node-drill-plan.md"
}
EOF
  echo "$DRILL_EVIDENCE_ROOT/$RUN_ID/run-meta.json"
}

# 良性 WARN 白名单（docs §5；不计 ERROR 判据）
WARN_WHITELIST='unknown proposer|block will be retried after validator-set sync|Validator-set add.*registered=false'
