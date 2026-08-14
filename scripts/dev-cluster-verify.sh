#!/usr/bin/env bash
# ============================================================
# NexFinality 双节点收敛验证脚本
# 用法: bash scripts/dev-cluster-verify.sh
# ============================================================
cd "$(dirname "$0")/.."

echo "=== 高度对比 ==="
echo -n "A: " && grep -oE "Block proposed at height [0-9]+" nodeA.log 2>/dev/null | tail -1
echo -n "B: " && grep -oE "Block proposed at height [0-9]+" nodeB.log 2>/dev/null | tail -1

echo "=== 验证人广播（跨节点同步）==="
echo "A 收到 B 广播:" && grep -cE "Validator-set add.*registered=true" nodeA.log 2>/dev/null
echo "B 收到 A 广播:" && grep -cE "Validator-set add.*registered=true" nodeB.log 2>/dev/null

echo "=== 同高度 proposer 一致性（round-robin 确定性）==="
H=$(grep -oE "at height [0-9]+" nodeA.log 2>/dev/null | tail -1 | grep -oE "[0-9]+")
echo "取最近高度 $H 对比:"
grep -oE "Selected proposer [A-Za-z0-9]+ at height $H" nodeA.log nodeB.log 2>/dev/null | sort -u

echo "=== PG 共享链高度 ==="
docker exec nexus-pg psql -U nexus -d nexuschain -t -c "SELECT max(height) FROM header;" 2>/dev/null

echo "=== 最终性 ==="
curl -s http://localhost:19585/rpc/v1/finality/epoch/1 2>/dev/null | head -c 300
echo ""
