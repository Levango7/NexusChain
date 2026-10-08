#!/usr/bin/env bash
# =============================================================================
# gen-mpc-engine-configs.sh — 生成 mpc-engine 的 PartyConfig（CGGMP21 分布式）
# =============================================================================
# 为什么需要它（2026-10-08）：
#   GG20 退役后，引擎只有 CGGMP21 一条路径，**每引擎 = 一个 MPC 参与方**，
#   且生产语义要求：
#     · 真 mTLS（服务端验客户端证书）——env 模式只能做单 TLS，mTLS 必须走
#       PartyConfig（`MPC_CONFIG_PATH`）里的 tls_cert/tls_key/tls_ca 三件套；
#     · 份额落盘（keyshare 持久化）——`MPC_ENGINE_SESSION_DIR` 指向可写卷，
#       否则 keygen 产出的份额只在内存，引擎重启即丢（签名直接报
#       "key_share missing"）；
#     · 3 方 2-of-3 拓扑——party_index/peers 显式声明。
#   本脚本把这三件事一次配齐，产出与 docker-compose（容器路径）或本机
#   三进程（主机路径）两种布局兼容的 node{0..n-1}.json。
#
# 用法：
#   bash scripts/gen-mpc-engine-configs.sh                      # compose 布局（默认）
#   bash scripts/gen-mpc-engine-configs.sh --layout native      # 本机三进程布局
#   bash scripts/gen-mpc-engine-configs.sh -o /tmp/cfg -n 3 -t 2
#
# 参数：
#   -o OUT_DIR      输出目录（compose 默认 ./mpc-certs/config；native 默认 ./mpc-sessions-test/config）
#   -n N            参与方数（默认 3）
#   -t T            阈值（默认 2；写入注释供部署方核对，引擎本身从 RPC 取）
#   --base-port P   本机布局的起始端口（默认 50051）
#   --certs-dir D   证书目录（默认 ./mpc-certs；须含 ca/CA.pem 与 node-{A,B,C}/cert.pem+key.pem）
#   --cert-layout L 证书命名布局：letters=node-A/B/C（默认，gen-mpc-certs.sh 产物）
#                                 digits=node-0/1/2（chart/集群脚本产物）
#   --storage-key K 份额落盘 AES-256-GCM 密钥（64 hex）；缺省读 $MPC_STORAGE_KEY，
#                   再缺省用 compose 的 dev 占位值（**仅沙箱**，生产必须经 Secret 注入）
#   -h              帮助
#
# 退出码：0 成功 / 1 参数错误 / 2 证书缺失 / 3 写入失败
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

LAYOUT="compose"
OUT_DIR=""
N=3
T=2
BASE_PORT=50051
CERTS_DIR="./mpc-certs"
CERT_LAYOUT="letters"
DEV_PLACEHOLDER_KEY="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
STORAGE_KEY="${MPC_STORAGE_KEY:-${DEV_PLACEHOLDER_KEY}}"

usage() {
    sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --layout) LAYOUT="$2"; shift 2 ;;
        -o) OUT_DIR="$2"; shift 2 ;;
        -n) N="$2"; shift 2 ;;
        -t) T="$2"; shift 2 ;;
        --base-port) BASE_PORT="$2"; shift 2 ;;
        --certs-dir) CERTS_DIR="$2"; shift 2 ;;
        --cert-layout) CERT_LAYOUT="$2"; shift 2 ;;
        --storage-key) STORAGE_KEY="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "错误: 未知参数 $1（-h 看用法）" >&2; exit 1 ;;
    esac
done

case "${LAYOUT}" in
    compose) ;;        # docker-compose.yml：证书经 ./mpc-certs/{ca,node-X} 挂到 /etc/mpc/tls
    compose-prod) ;;   # docker-compose.prod.yml：整卷 mpc-certs 挂到 /etc/mpc/certs
    native) ;;         # 本机多进程：主机绝对路径
    *) echo "错误: --layout 只支持 compose|compose-prod|native" >&2; exit 1 ;;
esac

if [ -z "${OUT_DIR}" ]; then
    if [ "${LAYOUT}" = "compose" ]; then OUT_DIR="./mpc-certs/config"; else OUT_DIR="./mpc-sessions-test/config"; fi
fi

# storage key 形状校验（与引擎侧一致的 fail-closed：64 hex）
if ! printf '%s' "${STORAGE_KEY}" | grep -Eq '^[0-9a-fA-F]{64}$'; then
    echo "错误: storage key 必须是 64 位 hex（32 字节 AES-256-GCM 密钥）" >&2
    exit 1
fi

# 生产布局禁止开发占位密钥（与 docker-compose.prod.yml 的 ${VAR:?} fail-closed 口径一致）
if [ "${LAYOUT}" = "compose-prod" ] && [ "${STORAGE_KEY}" = "${DEV_PLACEHOLDER_KEY}" ]; then
    echo "错误: --layout compose-prod 拒绝开发占位密钥——请显式 --storage-key <hex> 或导出 MPC_STORAGE_KEY（生产必须经 Secret/KMS）" >&2
    exit 1
fi

CERTS_ABS="$(cd "${CERTS_DIR}" 2>/dev/null && pwd || true)"
if [ -z "${CERTS_ABS}" ] || [ ! -f "${CERTS_ABS}/ca/CA.pem" ]; then
    echo "错误: 证书目录 ${CERTS_DIR} 缺少 ca/CA.pem —— 先跑 bash scripts/gen-mpc-certs.sh" >&2
    exit 2
fi

cert_name_for() {
    local idx="$1"
    if [ "${CERT_LAYOUT}" = "digits" ]; then
        echo "node-${idx}"
    else
        case "${idx}" in
            0) echo "node-A" ;;
            1) echo "node-B" ;;
            2) echo "node-C" ;;
            *) echo "node-${idx}" ;;
        esac
    fi
}

mkdir -p "${OUT_DIR}" || { echo "错误: 无法创建 ${OUT_DIR}" >&2; exit 3; }

echo "[gen-mpc-configs] 布局=${LAYOUT} 参与方=${N} 阈值=${T} 输出=${OUT_DIR}"
for i in $(seq 0 $((N - 1))); do
    cert_name="$(cert_name_for "${i}")"
    if [ ! -f "${CERTS_ABS}/${cert_name}/cert.pem" ]; then
        echo "错误: 缺 ${CERTS_ABS}/${cert_name}/cert.pem（--cert-layout ${CERT_LAYOUT} 推断）" >&2
        exit 2
    fi

    if [ "${LAYOUT}" = "compose" ]; then
        # 容器内路径（docker-compose.yml 挂载约定：./mpc-certs/node-X → /etc/mpc/tls/node）
        tls_cert="/etc/mpc/tls/node/cert.pem"
        tls_key="/etc/mpc/tls/node/key.pem"
        tls_ca="/etc/mpc/tls/ca/CA.pem"
        listen_addr="0.0.0.0:50051"
        peer_host() { echo "mpc-engine-$1:50051"; }
    elif [ "${LAYOUT}" = "compose-prod" ]; then
        # 容器内路径（docker-compose.prod.yml 挂载约定：整卷 mpc-certs → /etc/mpc/certs）
        tls_cert="/etc/mpc/certs/${cert_name}/cert.pem"
        tls_key="/etc/mpc/certs/${cert_name}/key.pem"
        tls_ca="/etc/mpc/certs/ca/CA.pem"
        listen_addr="0.0.0.0:50051"
        peer_host() { echo "mpc-engine-$1:50051"; }
    else
        # 本机三进程：每方独立端口 + 主机绝对路径证书
        port=$((BASE_PORT + i))
        tls_cert="${CERTS_ABS}/${cert_name}/cert.pem"
        tls_key="${CERTS_ABS}/${cert_name}/key.pem"
        tls_ca="${CERTS_ABS}/ca/CA.pem"
        listen_addr="0.0.0.0:${port}"
        peer_host() { echo "127.0.0.1:$((BASE_PORT + $1))"; }
    fi

    peers_json="["
    first=1
    for j in $(seq 0 $((N - 1))); do
        [ "${j}" -eq "${i}" ] && continue
        if [ "${first}" -eq 0 ]; then peers_json="${peers_json},"; fi
        first=0
        peers_json="${peers_json}{\"party_index\":${j},\"party_id\":\"party-${j}\",\"endpoint\":\"https://$(peer_host "${j}")\"}"
    done
    peers_json="${peers_json}]"

    cat > "${OUT_DIR}/node${i}.json" <<JSON
{
  "party_index": ${i},
  "party_id": "party-${i}",
  "listen_addr": "${listen_addr}",
  "peers": ${peers_json},
  "storage_key": "${STORAGE_KEY}",
  "storage_key_version": 1,
  "storage_key_source": "plain",
  "tls_cert": "${tls_cert}",
  "tls_key": "${tls_key}",
  "tls_ca": "${tls_ca}"
}
JSON
    echo "  node${i}.json  party_index=${i} cert=${cert_name} listen=${listen_addr}"
done

cat <<EOF

[gen-mpc-configs] 完成（阈值 ${T}-of-${N}）。
用法（${LAYOUT} 布局）：
EOF
if [ "${LAYOUT}" = "compose" ]; then
    cat <<'EOF'
  docker-compose.yml 中各引擎服务：
    environment:
      - MPC_CONFIG_PATH=/app/config/node.json
      - MPC_ENGINE_SESSION_DIR=/app/data/sessions     # 份额落盘（持久化卷）
      - MPC_REQUIRE_AUTH=true
      - MPC_AUTH_TOKEN=${MPC_AUTH_TOKEN:-dev-mpc-engine-token-change-in-prod}
    volumes:
      - ./mpc-certs/config/node0.json:/app/config/node.json:ro   # 引擎 N 用 nodeN.json
      - ./mpc-certs/ca:/etc/mpc/tls/ca:ro
      - ./mpc-certs/node-A:/etc/mpc/tls/node:ro
  随后用 scripts/mpc-wallet-ceremony.py 为该集群供给钱包份额（keygen→aux→assemble）。
EOF
else
    cat <<EOF
  # 依次启动三方（各自独立 session 目录 ⇒ 份额隔离）
  for i in 0 1 2; do
    MPC_CONFIG_PATH=${OUT_DIR}/node\${i}.json \\
    MPC_ENGINE_SESSION_DIR=./mpc-sessions-test/engine-\${i} \\
    MPC_AUTH_TOKEN=dev-mpc-engine-token-change-in-prod \\
      ./mpc-engine/target/release/mpc-engine &
  done
  随后：python3 scripts/mpc-wallet-ceremony.py --endpoints 127.0.0.1:50051,127.0.0.1:50052,127.0.0.1:50053 \\
        --cacert mpc-certs/ca/CA.pem --client-cert mpc-certs/node-A/cert.pem \\
        --client-key mpc-certs/node-A/key.pem --token dev-mpc-engine-token-change-in-prod --sign-probe
EOF
fi
echo "  注意：config 文件含 storage_key（份额落盘密钥），输出目录应保持 gitignore（mpc-certs/ 已忽略）。"
