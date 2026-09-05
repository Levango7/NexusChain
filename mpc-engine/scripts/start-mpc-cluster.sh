#!/usr/bin/env bash
# =============================================================================
# start-mpc-cluster.sh — NexusChain MPC 3 节点集群启动脚本
# =============================================================================
# 功能：
#   1. 启动 3 个 mpc-engine 节点（node1/node2/node3），分别监听
#      127.0.0.1:50051 / 50052 / 50053
#   2. 每个节点使用独立的配置文件（config/nodeN.json）与数据目录（data/nodeN/）
#   3. 启动前检查 mTLS 证书，不存在则调用 generate-certs.sh 生成
#   4. 健康检查：等待 3 个节点 gRPC HealthCheck 全部 ready
#   5. 优雅停止：trap SIGINT/SIGTERM，停止所有节点子进程
#   6. 日志输出到 logs/node{1,2,3}.log
#
# 用法：
#   bash start-mpc-cluster.sh                # 前台启动，Ctrl+C 停止
#   bash start-mpc-cluster.sh -d             # 后台启动（daemon 模式）
#   bash start-mpc-cluster.sh -k             # 停止已运行的集群
#   bash start-mpc-cluster.sh -b <binary>    # 指定 mpc-engine 二进制路径
#   bash start-mpc-cluster.sh --no-health    # 跳过健康检查
#   bash start-mpc-cluster.sh -h             # 显示帮助
#
# 依赖：
#   - cargo / rustc（编译 mpc-engine）
#   - gcc / dlltool（链接 secp256k1 / kzen-paillier 原生库，仅 Linux/WSL）
#   - openssl（生成证书）
#   - python3（TOML→JSON 配置转换，可选；若直接提供 JSON 配置则不需要）
#   - grpcurl 或 grpc_cli（健康检查，可选；缺失时退化为 TCP 端口探测）
#
# 退出码：
#   0 — 成功
#   1 — 参数错误 / 依赖缺失
#   2 — 编译失败
#   3 — 证书生成失败
#   4 — 健康检查超时
# =============================================================================
set -euo pipefail

# ---------- 路径常量 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MPC_ENGINE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_DIR="${MPC_ENGINE_DIR}/config"
CERT_DIR="${MPC_ENGINE_DIR}/certs"
LOG_DIR="${MPC_ENGINE_DIR}/logs"
DATA_DIR="${MPC_ENGINE_DIR}/data"
PID_DIR="${MPC_ENGINE_DIR}/.run"

# 节点定义：name | port | party_index | party_id
NODES=(
    "node1|50051|0|party-0"
    "node2|50052|1|party-1"
    "node3|50053|2|party-2"
)
# 阈值签名参数：2-of-3（threshold=1, total_parties=3；GG20 中 threshold=t 意味着 t+1 方签名）
THRESHOLD=1
TOTAL_PARTIES=3

# ---------- 参数 ----------
DAEMON=false
KILL_ONLY=false
SKIP_HEALTH=false
SETUP_ONLY=false
MPC_BINARY=""
BUILD_FEATURES="tls"

usage() {
    cat <<EOF
用法: $0 [选项]
  -d              后台启动（daemon 模式，日志写文件）
  -k              停止已运行的集群
  -b <binary>     指定 mpc-engine 二进制路径（跳过编译）
  -f <features>   cargo 编译 features（默认: tls）
  --no-health     跳过启动后健康检查
  --no-build      跳过编译（使用已有二进制或 -b 指定）
  --setup-only    仅生成 mTLS 证书与节点 JSON 配置后退出（不编译/不启动），
                  供 CI 集群 E2E（CggmpMpcE2EClusterTest 自行拉起引擎）预备环境
  -h              显示帮助
EOF
}

NO_BUILD=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        -d) DAEMON=true; shift ;;
        -k) KILL_ONLY=true; shift ;;
        -b) MPC_BINARY="$2"; shift 2 ;;
        -f) BUILD_FEATURES="$2"; shift 2 ;;
        --no-health) SKIP_HEALTH=true; shift ;;
        --no-build) NO_BUILD=true; shift ;;
        --setup-only) SETUP_ONLY=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "错误: 未知参数 $1" >&2; usage; exit 1 ;;
    esac
done

# ---------- 工具函数 ----------
log()  { echo "[start-mpc] $(date '+%H:%M:%S') $*"; }
err()  { echo "[start-mpc] $(date '+%H:%M:%S') 错误: $*" >&2; }
warn() { echo "[start-mpc] $(date '+%H:%M:%S') 警告: $*" >&2; }

mkdir -p "${LOG_DIR}" "${DATA_DIR}" "${PID_DIR}" "${CONFIG_DIR}"

# ---------- 停止集群 ----------
stop_cluster() {
    log "停止 MPC 集群..."
    for entry in "${NODES[@]}"; do
        IFS='|' read -r name port idx pid_id <<< "${entry}"
        pid_file="${PID_DIR}/${name}.pid"
        if [[ -f "${pid_file}" ]]; then
            pid="$(cat "${pid_file}" 2>/dev/null || true)"
            if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
                log "  停止 ${name} (pid=${pid})"
                kill -TERM "${pid}" 2>/dev/null || true
                # 等待最多 10 秒优雅退出
                for _ in $(seq 1 100); do
                    kill -0 "${pid}" 2>/dev/null || break
                    sleep 0.1
                done
                # 仍未退出则强制 kill
                if kill -0 "${pid}" 2>/dev/null; then
                    warn "${name} 未在 10 秒内退出，强制 kill"
                    kill -KILL "${pid}" 2>/dev/null || true
                fi
            fi
            rm -f "${pid_file}"
        fi
    done
    log "集群已停止"
}

if ${KILL_ONLY}; then
    stop_cluster
    exit 0
fi

# ---------- 1. 检查 / 生成证书 ----------
log "[1/5] 检查 mTLS 证书..."
CERT_OK=true
[[ -f "${CERT_DIR}/ca.crt" && -f "${CERT_DIR}/ca.key" ]] || CERT_OK=false
for entry in "${NODES[@]}"; do
    IFS='|' read -r name _ _ _ <<< "${entry}"
    [[ -f "${CERT_DIR}/${name}.crt" && -f "${CERT_DIR}/${name}.key" ]] || CERT_OK=false
done

if ! ${CERT_OK}; then
    log "  证书缺失，调用 generate-certs.sh 生成..."
    if ! bash "${SCRIPT_DIR}/generate-certs.sh" -o "${CERT_DIR}"; then
        err "证书生成失败"
        exit 3
    fi
else
    log "  证书已存在，跳过生成"
fi

# ---------- 2. 编译 mpc-engine（若未指定二进制） ----------
log "[2/5] 准备 mpc-engine 二进制..."
if ${SETUP_ONLY}; then
    log "  --setup-only：仅生成证书与配置，跳过二进制准备"
elif [[ -n "${MPC_BINARY}" ]]; then
    if [[ ! -x "${MPC_BINARY}" ]]; then
        err "指定的二进制不存在或不可执行: ${MPC_BINARY}"
        exit 1
    fi
    log "  使用指定二进制: ${MPC_BINARY}"
elif ${NO_BUILD}; then
    # 尝试 target/release/mpc-engine 或 target/debug/mpc-engine
    if [[ -x "${MPC_ENGINE_DIR}/target/release/mpc-engine" ]]; then
        MPC_BINARY="${MPC_ENGINE_DIR}/target/release/mpc-engine"
    elif [[ -x "${MPC_ENGINE_DIR}/target/debug/mpc-engine" ]]; then
        MPC_BINARY="${MPC_ENGINE_DIR}/target/debug/mpc-engine"
    else
        err "--no-build 但未找到已编译的二进制（请先 cargo build --features tls）"
        exit 2
    fi
    log "  使用已编译二进制: ${MPC_BINARY}"
else
    log "  编译 mpc-engine (features: ${BUILD_FEATURES})..."
    if ! cargo build --features "${BUILD_FEATURES}" --release 2>&1 | tee "${LOG_DIR}/build.log"; then
        err "编译失败（详见 ${LOG_DIR}/build.log）"
        exit 2
    fi
    MPC_BINARY="${MPC_ENGINE_DIR}/target/release/mpc-engine"
    log "  编译完成: ${MPC_BINARY}"
fi

# ---------- 3. 生成 / 检查节点 JSON 配置 ----------
# 注：mpc-engine 的 PartyConfig 由 serde_json 反序列化（JSON 格式）。
#     config/nodeN.toml 为人类可读的配置模板，此处转换为 JSON 供运行时加载。
log "[3/5] 准备节点配置文件..."

# storage_key：AES-256-GCM 密钥（32 字节 hex = 64 字符）
# S4 修复（2026-08-31 交付前审计）：优先 MPC_STORAGE_KEY 环境变量；
# 未设置时生成一次性随机密钥（每节点独立加密落盘会话数据）。
# 此前三方共用硬编码 "4242...42"（随源码公开）——任何拿到仓库的人
# 可解密节点落盘的 MPC 会话数据；且轮换是假的（版本号被忽略）。
STORAGE_KEY="${MPC_STORAGE_KEY:-$(openssl rand -hex 32)}"

generate_node_json() {
    local name="$1" port="$2" idx="$3" pid_id="$4"
    local json_file="${CONFIG_DIR}/${name}.json"
    local data_node_dir="${DATA_DIR}/${name}"

    mkdir -p "${data_node_dir}"

    # 构造 peers 数组（排除本节点）
    local peers_json="["
    local first=true
    for entry in "${NODES[@]}"; do
        IFS='|' read -r pname pport pidx ppid <<< "${entry}"
        [[ "${pname}" == "${name}" ]] && continue
        if ! ${first}; then peers_json+=","; fi
        first=false
        peers_json+="{\"party_index\":${pidx},\"party_id\":\"${ppid}\",\"endpoint\":\"https://127.0.0.1:${pport}\"}"
    done
    peers_json+="]"

    # 写入 JSON 配置（与 PartyConfig 结构对齐）
    cat > "${json_file}" <<EOF
{
  "party_index": ${idx},
  "party_id": "${pid_id}",
  "listen_addr": "127.0.0.1:${port}",
  "peers": ${peers_json},
  "storage_key": "${STORAGE_KEY}",
  "storage_key_version": 1,
  "storage_key_source": "plain",
  "tls_cert": "${CERT_DIR}/${name}.crt",
  "tls_key": "${CERT_DIR}/${name}.key",
  "tls_ca": "${CERT_DIR}/ca.crt"
}
EOF
    log "  生成 ${name}.json (listen=127.0.0.1:${port}, party_index=${idx})"
}

for entry in "${NODES[@]}"; do
    IFS='|' read -r name port idx pid_id <<< "${entry}"
    # 优先使用人工编辑的 JSON；不存在则从 TOML 模板转换或自动生成
    if [[ -f "${CONFIG_DIR}/${name}.json" ]]; then
        log "  ${name}.json 已存在，跳过生成"
    else
        generate_node_json "${name}" "${port}" "${idx}" "${pid_id}"
    fi
done

# ---------- --setup-only：证书 + 配置就绪即退出 ----------
if ${SETUP_ONLY}; then
    log "--setup-only 完成：证书与节点配置已就绪（未编译/未启动）"
    log "  配置: ${CONFIG_DIR}/node{1,2,3}.json"
    log "  证书: ${CERT_DIR}/ca.crt + node{1,2,3}.{crt,key}"
    exit 0
fi

# ---------- 4. 启动节点 ----------
log "[4/5] 启动 MPC 节点..."

# 全局 PIDs 数组（用于 trap）
declare -a RUNNING_PIDS=()

cleanup() {
    echo ""
    log "收到停止信号，清理子进程..."
    stop_cluster
    exit 0
}
trap cleanup SIGINT SIGTERM

for entry in "${NODES[@]}"; do
    IFS='|' read -r name port idx pid_id <<< "${entry}"
    json_file="${CONFIG_DIR}/${name}.json"
    log_file="${LOG_DIR}/${name}.log"
    pid_file="${PID_DIR}/${name}.pid"

    # 若该节点已在运行，先停止
    if [[ -f "${pid_file}" ]] && kill -0 "$(cat "${pid_file}" 2>/dev/null)" 2>/dev/null; then
        warn "${name} 已在运行 (pid=$(cat "${pid_file}"))，先停止..."
        kill -TERM "$(cat "${pid_file}")" 2>/dev/null || true
        sleep 1
        rm -f "${pid_file}"
    fi

    log "  启动 ${name} (listen=127.0.0.1:${port})..."

    # 设置节点专属环境变量
    export MPC_CONFIG_PATH="${json_file}"
    export MPC_ENGINE_SESSION_DIR="${DATA_DIR}/${name}/sessions"
    export MPC_REQUIRE_TLS=true
    export MPC_AUTH_TOKEN="nexus-mpc-test-token"
    export RUST_LOG="info"

    # 启动子进程
    if ${DAEMON}; then
        # daemon 模式：nohup + 后台
        nohup "${MPC_BINARY}" --config "${json_file}" > "${log_file}" 2>&1 &
        node_pid=$!
    else
        # 前台模式：仍后台启动，但日志实时 tail（由 trap 处理 Ctrl+C）
        "${MPC_BINARY}" --config "${json_file}" > "${log_file}" 2>&1 &
        node_pid=$!
    fi

    echo "${node_pid}" > "${pid_file}"
    RUNNING_PIDS+=("${node_pid}")
    log "    ${name} pid=${node_pid}, 日志=${log_file}"

    # 节点间稍微错开启动，避免端口竞争
    sleep 0.5
done

# ---------- 5. 健康检查 ----------
if ${SKIP_HEALTH}; then
    log "[5/5] 跳过健康检查（--no-health）"
else
    log "[5/5] 等待节点就绪（健康检查）..."

    # 健康检查函数：优先 grpcurl，回退到 TCP 端口探测
    check_node_health() {
        local name="$1" port="$2"
        # 方式 1: grpcurl（若安装）
        if command -v grpcurl >/dev/null 2>&1; then
            grpcurl -plaintext "127.0.0.1:${port}" \
                nexus.mpc.MpcCryptoService/HealthCheck >/dev/null 2>&1 && return 0
        fi
        # 方式 2: TCP 端口探测（仅验证端口监听，不验证 gRPC 协议）
        if command -v nc >/dev/null 2>&1; then
            nc -z 127.0.0.1 "${port}" >/dev/null 2>&1 && return 0
        fi
        # 方式 3: bash /dev/tcp（内置，无外部依赖）
        (echo > "/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1 && return 0
        return 1
    }

    HEALTH_TIMEOUT=30  # 秒
    HEALTH_INTERVAL=0.5
    all_ready=false
    elapsed=0

    while ! ${all_ready}; do
        all_ready=true
        for entry in "${NODES[@]}"; do
            IFS='|' read -r name port _ _ <<< "${entry}"
            if ! check_node_health "${name}" "${port}"; then
                all_ready=false
                break
            fi
        done

        if ${all_ready}; then
            break
        fi

        sleep "${HEALTH_INTERVAL}"
        elapsed=$(awk "BEGIN{print ${elapsed} + ${HEALTH_INTERVAL}}")
        if (( $(awk "BEGIN{print (${elapsed} >= ${HEALTH_TIMEOUT})}") )); then
            err "健康检查超时（${HEALTH_TIMEOUT}s），部分节点未就绪"
            for entry in "${NODES[@]}"; do
                IFS='|' read -r name port _ _ <<< "${entry}"
                if check_node_health "${name}" "${port}"; then
                    echo "  ✓ ${name} ready" >&2
                else
                    echo "  ✗ ${name} NOT ready (127.0.0.1:${port})" >&2
                fi
            done
            err "查看日志: ${LOG_DIR}/node{1,2,3}.log"
            stop_cluster
            exit 4
        fi
    done

    log "  所有节点就绪："
    for entry in "${NODES[@]}"; do
        IFS='|' read -r name port _ _ <<< "${entry}"
        log "    ✓ ${name} → 127.0.0.1:${port}"
    done
fi

# ---------- 启动完成 ----------
echo ""
log "=========================================="
log " MPC 集群已启动（${TOTAL_PARTIES} 节点, $((THRESHOLD+1))-of-${TOTAL_PARTIES} 签名）"
log "=========================================="
for entry in "${NODES[@]}"; do
    IFS='|' read -r name port idx pid_id <<< "${entry}"
    pid="$(cat "${PID_DIR}/${name}.pid" 2>/dev/null || echo '?')"
    log " ${name}  pid=${pid}  127.0.0.1:${port}  party_index=${idx}"
done
echo ""
log "日志: tail -f ${LOG_DIR}/node{1,2,3}.log"
log "停止: bash $0 -k  或  Ctrl+C"

if ${DAEMON}; then
    log "（daemon 模式，脚本退出，节点继续运行）"
    exit 0
else
    log "（前台模式，Ctrl+C 停止集群）"
    # 等待任一子进程退出（或被信号中断）
    wait "${RUNNING_PIDS[@]}" 2>/dev/null || true
    stop_cluster
    exit 0
fi