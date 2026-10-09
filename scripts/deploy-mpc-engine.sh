#!/usr/bin/env bash
# ============================================================
# MPC 引擎本地多进程启动器（CGGMP21 语义；2026-10-08 随 GG20 退役改写）
#
# 拓扑: **每个引擎进程 = 一个 MPC 参与方**（GG20 时代的"单进程承载 N 份
#       额协调器模型"已随该路径退役）。n 个进程构成 t-of-n 门限集群：
#         · 各自独立 MPC_ENGINE_SESSION_DIR ⇒ 份额隔离 + 落盘持久化；
#         · 协议消息经协调器字节管道中转（谁当协调器由调用方指定，见仪式脚本）；
#         · 份额供给 = 先跑一次 DKG 仪式（scripts/mpc-wallet-ceremony.py）。
#
# ⚠️ 本脚本是**明文**开发启动器（MPC_REQUIRE_TLS=false，env 模式）——
#    适合本地快速起进程。要真 mTLS + PartyConfig，用：
#      · 沙箱：docker compose up -d mpc-engine-0 mpc-engine-1 mpc-engine-2
#        （PartyConfig 由 scripts/gen-mpc-engine-configs.sh 生成）
#      · 集群：deploy/helm（mpc-engine chart 自带 mTLS/PVC/Secret 装配）
#
# 用法: bash scripts/deploy-mpc-engine.sh [engine-count] [start-port] [session-dir]
#   默认 2 个进程（50051, 50052）；CGGMP21 门限集群建议 COUNT=3（2-of-3）
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

echo "=== 后续步骤（CGGMP21）==="
echo "1) 为钱包供给份额（keygen→aux→assemble）："
echo "   python3 scripts/mpc-wallet-ceremony.py --wallet <walletId> --plaintext \\"
echo "       --endpoints 127.0.0.1:${START_PORT},... --sign-probe"
echo "2) 签名服务多端点配置（MpcEngineRouter）："
echo "   NEX_MPC_ENGINE_ENDPOINTS=127.0.0.1:${START_PORT},127.0.0.1:$((START_PORT + 1))"
echo "   NEX_MPC_ENGINE_CGGMP_ENABLED=true / CGGMP_SIGNERS=0,1 / DISTRIBUTED=true"
echo "（真实部署请用 docker-compose 或 helm——本脚本为明文开发形态）"
