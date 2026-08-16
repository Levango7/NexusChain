#!/usr/bin/env bash
# ============================================================
# NexFinality 三节点集群部署脚本（均匀错峰启动）
#
# 3 节点拓扑：A(19585/9235) B(19586/9236) C(19587/9237)
# - 共享 PG（nexuschain）
# - 固定验证人密钥（validator-private-key）
# - 均匀错峰（节点间隔 15s）——广播互达收敛对启动时序敏感（实证 4/6 vs 1/6）
#
# 用法: bash scripts/dev-cluster-3nodes.sh
# 前置: Docker PG @55432 就绪
# ============================================================
set -e
cd "$(dirname "$0")/.."

GRADLE_BIN="C:/Users/winge/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/bin/gradle.bat"
PRIVA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PRIVB="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
PRIVC="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
LOG_DIR="F:/Nexus/NexusChain"

echo "[1/4] 清理旧状态..."
pkill -f "org.nexus.Start" 2>/dev/null || true
sleep 2
rm -rf data-a data-b data-c nexus-core/nexus-core/data-a nexus-core/nexus-core/data-b nexus-core/nexus-core/data-c 2>/dev/null || true
docker exec nexus-pg psql -U nexus -d nexuschain -c \
  "TRUNCATE transaction_index, transaction, header, account, incubator_state, payment_channels, stablecoin_positions, bridge_transactions; DROP TABLE IF EXISTS validators_synced;" >/dev/null 2>&1 || true

echo "[2/4] 启动 node-A (19585)..."
"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=19585 --p2p.address=nexus://localhost:9235 --p2p.enable-discovery=true --nexus.cache-dir=./data-a --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$PRIVA --nexus.consensus.min-validators-to-mine=3" \
  --console=plain > "$LOG_DIR/nodeA.log" 2>&1 &

for i in $(seq 1 60); do
  grep -q "Started Start" "$LOG_DIR/nodeA.log" 2>/dev/null && break
  sleep 3
done
# 完整 peer 地址（含 @host:port——bootstraps 解析必需）
PA=$(grep -oE "listening on nexus://[^ ]+" "$LOG_DIR/nodeA.log" 2>/dev/null | head -1 | sed 's/.*nexus:\/\///')
[ -n "$PA" ] || { echo "❌ node-A P2P 地址未就绪"; exit 1; }
echo "   PEER_A=nexus://$PA"

echo "[3/4] 错峰启动 node-B (19586) ... 15s"
sleep 15
"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=19586 --p2p.address=nexus://localhost:9236 --p2p.bootstraps=nexus://$PA --p2p.enable-discovery=true --nexus.cache-dir=./data-b --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$PRIVB --nexus.consensus.min-validators-to-mine=3" \
  --console=plain > "$LOG_DIR/nodeB.log" 2>&1 &

echo "[4/4] 错峰启动 node-C (19587) ... 15s"
sleep 15
"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=19587 --p2p.address=nexus://localhost:9237 --p2p.bootstraps=nexus://$PA --p2p.enable-discovery=true --nexus.cache-dir=./data-c --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$PRIVC --nexus.consensus.min-validators-to-mine=3" \
  --console=plain > "$LOG_DIR/nodeC.log" 2>&1 &

echo "✅ 三节点已启动（错峰 15s）。验证: bash scripts/dev-cluster-verify.sh"
echo "    等待广播互达收敛（startHalf 60s + 广播传播，建议 120s+）"
