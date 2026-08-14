#!/usr/bin/env bash
# ============================================================
# NexFinality 双节点集群启动脚本（PLAN-007/008 收敛验证）
# 用法: bash scripts/dev-cluster-up.sh
# 前提: Docker Postgres @55432（nexuschain 库）已就绪
# ============================================================
set -e
cd "$(dirname "$0")/.."

GRADLE_BIN="C:/Users/winge/.gradle/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh/gradle-8.5/bin/gradle.bat"
PRIVA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
PRIVB="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
LOG_DIR="F:/Nexus/NexusChain"

echo "[1/3] 清理旧状态..."
pkill -f "org.nexus.Start" 2>/dev/null || true
sleep 2
rm -rf data-a data-b nexus-core/nexus-core/data-a nexus-core/nexus-core/data-b 2>/dev/null || true
docker exec nexus-pg psql -U nexus -d nexuschain -c \
  "TRUNCATE transaction_index, transaction, header, account, incubator_state, payment_channels, stablecoin_positions, bridge_transactions; DROP TABLE IF EXISTS validators_synced;" >/dev/null 2>&1 || true

echo "[2/3] 启动 node-A (19585)..."
"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=19585 --p2p.address=nexus://localhost:9235 --p2p.enable-discovery=true --nexus.cache-dir=./data-a --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$PRIVA --nexus.consensus.min-validators-to-mine=2" \
  --console=plain > "$LOG_DIR/nodeA.log" 2>&1 &

echo "[2/3] 启动 node-B (19586, bootstrap→A)..."
# 等待 A 的 P2P 地址就绪
for i in $(seq 1 40); do
  PEER_A=$(grep -oE "provide address to your peers to connect nexus://[0-9a-f]+@localhost:9235" "$LOG_DIR/nodeA.log" 2>/dev/null | head -1 | sed 's/.*connect nexus:\/\///')
  [ -n "$PEER_A" ] && break
  sleep 2
done
if [ -z "$PEER_A" ]; then echo "❌ node-A P2P 地址未就绪"; exit 1; fi
echo "   PEER_A=nexus://$PEER_A"

"$GRADLE_BIN" :nexus-core:nexus-core:run \
  --args="--spring.profiles.active=local --server.port=19586 --p2p.address=nexus://localhost:9236 --p2p.bootstraps=nexus://$PEER_A --p2p.enable-discovery=true --nexus.cache-dir=./data-b --nexus.consensus.proposer-strategy=round-robin --nexus.consensus.validator-private-key=$PRIVB --nexus.consensus.min-validators-to-mine=2" \
  --console=plain > "$LOG_DIR/nodeB.log" 2>&1 &

echo "[3/3] 等待双节点就绪..."
for i in $(seq 1 60); do
  A_OK=$(grep -c "Started Start" "$LOG_DIR/nodeA.log" 2>/dev/null || echo 0)
  B_OK=$(grep -c "Started Start" "$LOG_DIR/nodeB.log" 2>/dev/null || echo 0)
  [ "$A_OK" -ge 1 ] && [ "$B_OK" -ge 1 ] && break
  sleep 3
done

echo "✅ 集群已启动。查看收敛: tail -f nodeA.log nodeB.log"
echo "   验证: bash scripts/dev-cluster-verify.sh"
