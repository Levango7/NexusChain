#!/usr/bin/env bash
# ============================================================
# MPC 引擎高可用部署（方案 A 多引擎拓扑）
#
# 拓扑: 引擎进程 = 协调器模型（单进程承载 N 参与者份额，gg20.rs）
#       多引擎 = 高可用/横向扩展（故障转移，非安全模型必需）
#       签名服务 (signing-service) 经 gRPC mTLS 连接任一引擎
#
# 用法: bash scripts/deploy-mpc-engine.sh [engine-count]
#   默认 2 个引擎进程 (50051, 50052)
# ============================================================
set -e
cd "$(dirname "$0")/.."

ENGINE_BIN="mpc-engine/target/release/mpc-engine"
COUNT="${1:-2}"
START_PORT="${2:-50051}"
SESSION_DIR="${3:-./mpc-sessions}"

if [ ! -f "$ENGINE_BIN" ]; then
    echo "❌ 引擎二进制不存在: $ENGINE_BIN（先跑 bash scripts/build-mpc-engine.sh）"
    exit 1
fi

echo "=== 启动 $COUNT 个引擎进程（端口 $START_PORT+）==="
for i in $(seq 0 $((COUNT - 1))); do
    PORT=$((START_PORT + i))
    INSTANCE_DIR="${SESSION_DIR}/engine-${PORT}"
    mkdir -p "$INSTANCE_DIR"
    echo "[$i] 引擎 $PORT 启动（会话目录 $INSTANCE_DIR）..."
    MPC_ENGINE_PORT="$PORT" \
    MPC_ENGINE_SESSION_DIR="$INSTANCE_DIR" \
    MPC_REQUIRE_TLS=false \
        "$ENGINE_BIN" > "mpc-engine-engine-${PORT}.log" 2>&1 &
    echo "    PID $! → mpc-engine-engine-${PORT}.log"
done

sleep 2
echo "=== 就绪检查 ==="
for i in $(seq 0 $((COUNT - 1))); do
    PORT=$((START_PORT + i))
    (timeout 2 bash -c "echo > /dev/tcp/localhost/${PORT}" 2>/dev/null \
        && echo "引擎 ${PORT} ✅ 可达" || echo "引擎 ${PORT} ❌ 不可达")
done

echo "=== 签名服务连接配置（任一引擎）==="
echo "mpc.engine.host=localhost"
echo "mpc.engine.port=${START_PORT}"
echo "（故障转移：指向另一引擎或由外层 LB 路由）"
