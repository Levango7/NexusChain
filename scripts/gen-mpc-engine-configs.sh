#!/usr/bin/env bash
# 生成 mpc-engine PartyConfig；compose-prod 使用 env 主钥且不写明文密钥。
set -euo pipefail
umask 077

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
  sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'
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
    -h|--help) usage ;;
    *) echo "错误: 未知参数 $1（-h 看用法）" >&2; exit 1 ;;
  esac
done

case "${LAYOUT}" in
  compose|compose-prod|native) ;;
  *) echo "错误: --layout 只支持 compose|compose-prod|native" >&2; exit 1 ;;
esac
case "${CERT_LAYOUT}" in
  letters|digits) ;;
  *) echo "错误: --cert-layout 只支持 letters|digits" >&2; exit 1 ;;
esac
if ! [[ "$N" =~ ^[1-9][0-9]*$ ]] || ! [[ "$T" =~ ^[1-9][0-9]*$ ]] || [ "$T" -gt "$N" ]; then
  echo "错误: 参与方数/阈值无效" >&2
  exit 1
fi
if [ "${LAYOUT}" = "compose-prod" ] && { [ "$N" -ne 3 ] || [ "$T" -ne 2 ] || [ "${CERT_LAYOUT}" != "letters" ]; }; then
  echo "错误: compose-prod 固定为 3 方 2-of-3、node-A/B/C 证书布局" >&2
  exit 1
fi

if [ -z "${OUT_DIR}" ]; then
  case "${LAYOUT}" in
    compose) OUT_DIR="./mpc-certs/config" ;;
    compose-prod) OUT_DIR="./mpc-certs/config-prod" ;;
    native) OUT_DIR="./mpc-sessions-test/config" ;;
  esac
fi

if [ "${LAYOUT}" != "compose-prod" ]; then
  if ! printf '%s' "${STORAGE_KEY}" | grep -Eq '^[0-9a-fA-F]{64}$'; then
    echo "错误: 非生产布局要求 MPC_STORAGE_KEY 为 64 位 hex" >&2
    exit 1
  fi
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

for i in $(seq 0 $((N - 1))); do
  cert_name="$(cert_name_for "${i}")"
  for name in cert.pem key.pem; do
    if [ ! -f "${CERTS_ABS}/${cert_name}/${name}" ]; then
      echo "错误: 缺 ${CERTS_ABS}/${cert_name}/${name}（--cert-layout ${CERT_LAYOUT} 推断）" >&2
      exit 2
    fi
  done
done

mkdir -p "${OUT_DIR}"
chmod 700 "${OUT_DIR}"

write_config() {
  local path="$1"
  local content="$2"
  local tmp
  tmp="$(mktemp "${OUT_DIR}/.node.XXXXXX")"
  printf '%s\n' "$content" > "$tmp"
  chmod 600 "$tmp"
  if [ -f "$path" ]; then
    if cmp -s "$tmp" "$path"; then
      rm -f "$tmp"
      echo "  ${path##*/} 已存在且一致（跳过）"
      return 0
    fi
    rm -f "$tmp"
    echo "错误: ${path} 已存在且内容不同；为避免静默轮换，请先移走旧文件" >&2
    exit 3
  fi
  mv "$tmp" "$path"
}

echo "[gen-mpc-configs] 布局=${LAYOUT} 参与方=${N} 阈值=${T} 输出=${OUT_DIR}"
for i in $(seq 0 $((N - 1))); do
  cert_name="$(cert_name_for "${i}")"
  if [ "${LAYOUT}" = "compose" ]; then
    tls_cert="/etc/mpc/tls/node/cert.pem"
    tls_key="/etc/mpc/tls/node/key.pem"
    tls_ca="/etc/mpc/tls/ca/CA.pem"
    listen_addr="0.0.0.0:50051"
    peer_host() { echo "mpc-engine-$1:50051"; }
  elif [ "${LAYOUT}" = "compose-prod" ]; then
    tls_cert="/etc/mpc/certs/${cert_name}/cert.pem"
    tls_key="/etc/mpc/certs/${cert_name}/key.pem"
    tls_ca="/etc/mpc/certs/ca/CA.pem"
    listen_addr="0.0.0.0:50051"
    peer_host() { echo "mpc-engine-$1:50051"; }
  else
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

  if [ "${LAYOUT}" = "compose-prod" ]; then
    storage_key=""
    storage_source="env"
  else
    storage_key="${STORAGE_KEY}"
    storage_source="plain"
  fi

  content="$(cat <<JSON
{
  "party_index": ${i},
  "party_id": "party-${i}",
  "listen_addr": "${listen_addr}",
  "peers": ${peers_json},
  "storage_key": "${storage_key}",
  "storage_key_version": 1,
  "storage_key_source": "${storage_source}",
  "tls_cert": "${tls_cert}",
  "tls_key": "${tls_key}",
  "tls_ca": "${tls_ca}"
}
JSON
)"
  write_config "${OUT_DIR}/node${i}.json" "$content"
  echo "  node${i}.json  party_index=${i} cert=${cert_name} listen=${listen_addr}"
done

echo "[PASS] PartyConfig 生成完成（阈值 ${T}-of-${N}）"
if [ "${LAYOUT}" = "compose-prod" ]; then
  echo "  生产配置不含明文主钥；引擎从 NEXUS_MPC_STORAGE_KEY 读取。"
else
  echo "  注意：非生产配置含 storage_key，输出目录必须保持本地且不可提交。"
fi
