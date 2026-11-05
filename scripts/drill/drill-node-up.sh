#!/usr/bin/env bash
# ============================================================
# drill-node-up.sh — 单节点拉起（Tier-0 本机 gradle 形态）
#
# 用法: bash scripts/drill/drill-node-up.sh a|b|c
#
# 参数形状与 scripts/dev-cluster-3nodes.sh 一致（local profile + 固定验证人密钥
# + round-robin + min-validators-to-mine=3 + 各自 cache-dir）。
# Tier-1/2 的拉起由部署侧 compose/ansible 承担，本脚本只覆盖 Tier-0 回归基线。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/drill/drill-common.sh

N="${1:?用法: drill-node-up.sh a|b|c}"
case "$N" in
  a|b|c) ;;
  *) echo "节点名只能是 a|b|c" >&2; exit 2 ;;
esac

if [ "$DRILL_TIER" != "0" ]; then
  echo "[SKIP] drill-node-up 仅覆盖 Tier-0（当前 tier=$DRILL_TIER）；Tier-1/2 由部署侧拉起" >&2
  exit 0
fi

if [ -x "./gradlew" ]; then
  GRADLE_BIN="./gradlew"
else
  GRADLE_BIN="$(command -v gradle || echo gradle)"
fi
LOG="${LOG_DIR:-$(pwd)}/$(node_log "$N")"
CACHE=$(node_cache "$N")
PORT=$(node_port "$N")
P2P=$((PORT - 12540))   # 19585-9235=12540：p2p 端口自 rpc 端口推导（0=别动）

echo "[NODE-UP] node-$N rpc=$PORT p2p=$P2P cache=$CACHE log=$LOG"
"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=$PORT --p2p.address=nexus://localhost:$P2P --p2p.enable-discovery=true --nexus.cache-dir=./$CACHE --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$(node_key "$N") --nexus.consensus.min-validators-to-mine=3" \
  --console=plain > "$LOG" 2>&1 &
mkdir -p .drill-pids
PID=$!
echo "$PID" > ".drill-pids/node-$N.pid"
echo "[NODE-UP] gradle 后台 pid=$PID（.drill-pids/node-$N.pid）— 就绪判定见 drill-scenarios.sh 的 wait_ready"
