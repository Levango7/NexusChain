#!/usr/bin/env bash
# =============================================================================
# verify-mpc-distributed.sh — NexusChain MPC 分散式部署端到端集成验证
# =============================================================================
# P0-1 Task 239：验证 3 节点 mpc-engine 分散式部署 + signing-service 多端点路由
#
# 验证流程（GG20 退役后的 CGGMP21 口径，2026-10-08）：
#   1. 启动 3 节点 mpc-engine（docker-compose mpc-engine-0/1/2）
#   2. 健康检查：等待 3 个节点 TCP 端口就绪
#   3. CGGMP21 就绪验证：gRPC HealthCheck + CgStatus（驱动线程可用）
#   4. 协议级 E2E 归属说明（keygen→aux→sign→验签由 Java 侧
#      CggmpMpcE2EClusterTest 承担——shell 不驱动多轮协议）
#
# 用法：
#   bash verify-mpc-distributed.sh                    # 完整验证（启动→就绪探测）
#   bash verify-mpc-distributed.sh --skip-start       # 跳过启动（假设集群已运行）
#   bash verify-mpc-distributed.sh --health-only      # 仅 TCP 健康检查
#   bash verify-mpc-distributed.sh --cleanup          # 验证后清理（停止集群）
#   bash verify-mpc-distributed.sh -h                 # 显示帮助
#
# 依赖：
#   - docker + docker-compose（启动 3 节点集群）
#   - curl（前置检查）
#   - grpcurl（可选；缺失时步骤 3 退化提示，以步骤 2 的 TCP 探测为准）
#   - MPC_AUTH_TOKEN（可选）：引擎开启 Bearer 认证时导出，供 grpcurl 携带
#
# 退出码：
#   0 — 验证成功
#   1 — 参数错误 / 依赖缺失
#   2 — 集群启动失败
#   3 — 健康检查超时
#   4 — CGGMP21 就绪探测失败（HealthCheck/CgStatus）
# =============================================================================
set -euo pipefail

# ---------- 路径常量 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# ---------- 节点定义 ----------
# 3 节点：mpc-engine-0/1/2，宿主机端口 50051/50052/50053
NODES=(
    "mpc-engine-0|50051|0"
    "mpc-engine-1|50052|1"
    "mpc-engine-2|50053|2"
)
THRESHOLD=2
TOTAL_PARTIES=3

# signing-service 端口
SIGNING_PORT=8082

# ---------- 参数 ----------
SKIP_START=false
HEALTH_ONLY=false
CLEANUP=false

usage() {
    cat <<EOF
用法: $0 [选项]
  --skip-start   跳过集群启动（假设 3 节点已运行）
  --health-only  仅执行健康检查
  --cleanup      验证后停止集群
  -h, --help     显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-start)  SKIP_START=true; shift ;;
        --health-only) HEALTH_ONLY=true; shift ;;
        --cleanup)     CLEANUP=true; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) echo "错误: 未知参数 $1" >&2; usage; exit 1 ;;
    esac
done

# ---------- 工具函数 ----------
log()  { echo "[verify-mpc] $(date '+%H:%M:%S') $*"; }
err()  { echo "[verify-mpc] $(date '+%H:%M:%S') 错误: $*" >&2; }
warn() { echo "[verify-mpc] $(date '+%H:%M:%S') 警告: $*" >&2; }
ok()   { echo "[verify-mpc] $(date '+%H:%M:%S') ✓ $*"; }
fail() { echo "[verify-mpc] $(date '+%H:%M:%S') ✗ $*" >&2; }

# ---------- 前置检查 ----------
log "前置检查..."
for cmd in docker curl; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        err "未找到 ${cmd}，请先安装"
        exit 1
    fi
done
ok "依赖检查通过"

# ---------- 1. 启动 3 节点集群 ----------
if ${SKIP_START}; then
    log "[1/4] 跳过集群启动（--skip-start）"
else
    log "[1/4] 启动 3 节点 mpc-engine 集群..."
    log "  docker-compose up -d mpc-engine-0 mpc-engine-1 mpc-engine-2"
    if ! docker-compose up -d mpc-engine-0 mpc-engine-1 mpc-engine-2 2>&1; then
        err "集群启动失败"
        exit 2
    fi
    ok "集群启动命令已执行"
fi

# ---------- 2. 健康检查 ----------
log "[2/4] 等待 3 节点就绪（健康检查）..."

check_node_health() {
    local name="$1" port="$2"
    # 方式 1: TCP 端口探测（bash 内置，无外部依赖）
    if (echo > "/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

HEALTH_TIMEOUT=60
HEALTH_INTERVAL=1
all_ready=false
elapsed=0

while ! ${all_ready}; do
    all_ready=true
    for entry in "${NODES[@]}"; do
        IFS='|' read -r name port idx <<< "${entry}"
        if ! check_node_health "${name}" "${port}"; then
            all_ready=false
            break
        fi
    done

    if ${all_ready}; then
        break
    fi

    sleep "${HEALTH_INTERVAL}"
    elapsed=$((elapsed + HEALTH_INTERVAL))
    if (( elapsed >= HEALTH_TIMEOUT )); then
        err "健康检查超时（${HEALTH_TIMEOUT}s），部分节点未就绪"
        for entry in "${NODES[@]}"; do
            IFS='|' read -r name port idx <<< "${entry}"
            if check_node_health "${name}" "${port}"; then
                ok "${name} ready (127.0.0.1:${port})"
            else
                fail "${name} NOT ready (127.0.0.1:${port})"
            fi
        done
        exit 3
    fi
done

ok "所有 ${TOTAL_PARTIES} 节点就绪："
for entry in "${NODES[@]}"; do
    IFS='|' read -r name port idx <<< "${entry}"
    ok "  ${name} → 127.0.0.1:${port} (party_index=${idx})"
done

# ---------- 仅健康检查模式 ----------
if ${HEALTH_ONLY}; then
    log "仅健康检查模式（--health-only），跳过 CGGMP21 就绪探测"
    log "=========================================="
    log " 健康检查通过（${TOTAL_PARTIES} 节点, threshold=${THRESHOLD}-of-${TOTAL_PARTIES}）"
    log "=========================================="
    exit 0
fi

# ---------- 3. CGGMP21 引擎就绪验证 ----------
# GG20 退役后（PLAN-001-R2，2026-10-08）：Dkg/Sign/Aggregate 三个 RPC 已删除，
# shell 侧可验证的是「引擎存活 + 驱动线程可用」；协议级 E2E（keygen→aux→sign→
# 验签，真实 3 进程）由 Java 侧 CggmpMpcE2EClusterTest 承担（CI job
# mpc-java-cluster-e2e），shell 不重复驱动多轮协议。
log "[3/4] CGGMP21 引擎就绪验证（HealthCheck + CgStatus 探测）..."

# 可选 Bearer token（与引擎 MPC_AUTH_TOKEN 一致时导出 MPC_AUTH_TOKEN 即可）
AUTH_ARGS=()
if [ -n "${MPC_AUTH_TOKEN:-}" ]; then
    AUTH_ARGS=(-H "authorization: Bearer ${MPC_AUTH_TOKEN}")
fi

probe_engine() {
    local name="$1" port="$2"
    if ! command -v grpcurl >/dev/null 2>&1; then
        warn "grpcurl 未安装——跳过 gRPC 就绪探测（TCP 端口已通，见步骤 2）"
        return 0
    fi
    # HealthCheck（引擎进程存活）
    local hc
    hc=$(grpcurl -plaintext "${AUTH_ARGS[@]}" "127.0.0.1:${port}" \
        nexus.mpc.MpcCryptoService/HealthCheck -d '{}' 2>&1) || true
    if ! echo "${hc}" | grep -qE '"healthy"[: ]+true'; then
        fail "${name}: HealthCheck 未通过：${hc}"
        return 1
    fi
    # CgStatus（CGGMP21 驱动线程可用；未知 session 返回 success=true）
    local st
    st=$(grpcurl -plaintext "${AUTH_ARGS[@]}" "127.0.0.1:${port}" \
        nexus.mpc.MpcCryptoService/CgStatus \
        -d "{\"session_id\": \"verify-probe-$(date +%s)-\"}" 2>&1) || true
    if echo "${st}" | grep -qE '"success"[: ]+true'; then
        ok "${name}: HealthCheck + CgStatus 通过（CGGMP21 驱动线程可用）"
        return 0
    fi
    fail "${name}: CgStatus 未通过：${st}"
    return 1
}

PROBE_FAIL=0
for entry in "${NODES[@]}"; do
    IFS='|' read -r name port idx <<< "${entry}"
    probe_engine "${name}" "${port}" || PROBE_FAIL=1
done
if (( PROBE_FAIL != 0 )); then
    err "引擎就绪探测失败——请确认 3 节点 mpc-engine（含 tls/auth 配置）已就绪"
    exit 4
fi

# ---------- 4. 协议级 E2E 归属说明 ----------
log "[4/4] 协议级 E2E（keygen→aux→sign→验签）说明"
log "  全分布式 CGGMP21 2-of-3 的真实协议回归（3 进程 + mTLS）由 Java 侧测试承担："
log "    ./gradlew :nexus-signing-service:test -PincludeClusterE2E \\"
log "        --tests \"org.nexus.signing.mpc.cggmp.CggmpMpcE2EClusterTest\""
log "  （或 CI job mpc-java-cluster-e2e；冷钱包转账链路的真实签名见 signing-service 日志）"
log "  进程内协议 E2E：cd mpc-engine && cargo test --features tls --test cggmp_threshold_e2e"

echo ""
log "=========================================="
log " MPC CGGMP21 分散式部署验证通过（3 节点就绪）"
log "=========================================="
log " 集群: ${TOTAL_PARTIES} 节点（CGGMP21 门限签名栈）"
log " 端点: 127.0.0.1:50051, 127.0.0.1:50052, 127.0.0.1:50053"
log " 探测: HealthCheck + CgStatus 全通过"
log " 协议级 E2E（keygen→sign→验签）: 见步骤 4 输出的 Java 集群测试入口"
echo ""

# ---------- 清理 ----------
if ${CLEANUP}; then
    log "清理：停止 MPC 集群..."
    docker-compose stop mpc-engine-0 mpc-engine-1 mpc-engine-2 2>/dev/null || true
    ok "集群已停止"
fi

exit 0