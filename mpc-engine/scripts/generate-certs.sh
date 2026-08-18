#!/usr/bin/env bash
# =============================================================================
# generate-certs.sh — NexusChain MPC 集群 mTLS 证书生成脚本
# =============================================================================
# 功能：
#   1. 使用 openssl 生成自签名 CA 证书（ca.crt / ca.key）
#   2. 为 3 个 MPC 节点（node1/node2/node3）各签发节点证书
#      证书包含 SAN（Subject Alternative Name）= localhost + 127.0.0.1，
#      允许节点间通过 localhost 互相 mTLS 验证
#   3. 输出目录：mpc-engine/certs/
#      - ca.crt / ca.key                 （CA 证书与私钥）
#      - node1.crt / node1.key           （node1 证书与私钥）
#      - node2.crt / node2.key           （node2 证书与私钥）
#      - node3.crt / node3.key           （node3 证书与私钥）
#   4. 幂等：若 ca.crt 与所有节点证书均已存在，则直接跳过生成
#
# 用法：
#   bash generate-certs.sh                # 生成证书到默认目录 ../certs
#   bash generate-certs.sh -o /tmp/certs  # 生成到指定目录
#   bash generate-certs.sh -f             # 强制重新生成（覆盖已有）
#
# 依赖：openssl >= 1.1.1
#
# 退出码：
#   0 — 成功（生成或跳过）
#   1 — openssl 未安装
#   2 — 目录创建失败
# =============================================================================
set -euo pipefail

# ---------- 默认参数 ----------
# 脚本所在目录的父目录即为 mpc-engine/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MPC_ENGINE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 证书输出目录（默认 mpc-engine/certs/）
CERT_DIR="${MPC_ENGINE_DIR}/certs"
# 是否强制重新生成
FORCE=false

# ---------- 参数解析 ----------
usage() {
    cat <<EOF
用法: $0 [-o CERT_DIR] [-f] [-h]
  -o CERT_DIR  证书输出目录（默认: ${MPC_ENGINE_DIR}/certs）
  -f           强制重新生成（覆盖已有证书）
  -h           显示帮助
EOF
}

while getopts ":o:fh" opt; do
    case "${opt}" in
        o) CERT_DIR="$(cd "${OPTARG}" 2>/dev/null && pwd || (mkdir -p "${OPTARG}" && cd "${OPTARG}" && pwd))" ;;
        f) FORCE=true ;;
        h) usage; exit 0 ;;
        \?) echo "错误: 未知选项 -${OPTARG}" >&2; usage; exit 1 ;;
        :)  echo "错误: 选项 -${OPTARG} 需要参数" >&2; usage; exit 1 ;;
    esac
done

# ---------- 前置检查 ----------
if ! command -v openssl >/dev/null 2>&1; then
    echo "错误: 未找到 openssl，请先安装 openssl >= 1.1.1" >&2
    exit 1
fi

OPENSSL_VER="$(openssl version)"
echo "[generate-certs] openssl 版本: ${OPENSSL_VER}"

# ---------- 创建输出目录 ----------
if ! mkdir -p "${CERT_DIR}"; then
    echo "错误: 无法创建证书目录 ${CERT_DIR}" >&2
    exit 2
fi
echo "[generate-certs] 证书输出目录: ${CERT_DIR}"

# ---------- 节点列表 ----------
NODES=("node1" "node2" "node3")
# SAN 扩展：localhost + 127.0.0.1（节点间通过本地回环互通）
SAN_DNS="DNS:localhost"
SAN_IP="IP:127.0.0.1"

# ---------- 幂等检查 ----------
all_exist() {
    [[ -f "${CERT_DIR}/ca.crt" && -f "${CERT_DIR}/ca.key" ]] || return 1
    for n in "${NODES[@]}"; do
        [[ -f "${CERT_DIR}/${n}.crt" && -f "${CERT_DIR}/${n}.key" ]] || return 1
    done
    return 0
}

if ! ${FORCE} && all_exist; then
    echo "[generate-certs] 所有证书已存在，跳过生成（使用 -f 强制重新生成）"
    exit 0
fi

# ---------- 1. 生成 CA 证书 ----------
echo "[generate-certs] [1/4] 生成 CA 证书..."
# CA 私钥（4096 位 RSA）
openssl genrsa -out "${CERT_DIR}/ca.key" 4096 2>/dev/null
# CA 自签名证书（10 年有效期，CN=NexusChain-MPC-CA）
openssl req -new -x509 -days 3650 \
    -key "${CERT_DIR}/ca.key" \
    -out "${CERT_DIR}/ca.crt" \
    -subj "/C=CN/O=NexusChain/OU=MPC/CN=NexusChain-MPC-CA" \
    -sha256

# ---------- 2. 为每个节点签发证书 ----------
# 节点证书签发需要：节点私钥 → 节点 CSR → CA 签发（带 SAN 扩展）
for i in "${!NODES[@]}"; do
    n="${NODES[$i]}"
    idx=$((i + 1))
    echo "[generate-certs] [$((idx + 1))/4] 生成节点 ${n} 证书..."

    # 节点私钥（2048 位 RSA，足够 mTLS 安全且性能优）
    openssl genrsa -out "${CERT_DIR}/${n}.key" 2048 2>/dev/null

    # 节点 CSR（Certificate Signing Request）
    openssl req -new \
        -key "${CERT_DIR}/${n}.key" \
        -out "${CERT_DIR}/${n}.csr" \
        -subj "/C=CN/O=NexusChain/OU=MPC/CN=${n}" \
        -sha256

    # 节点证书扩展配置（含 SAN）
    # 注：openssl 1.1.1+ 支持 -addext，避免单独的 ext 文件
    # 兼容性回退：若 -addext 不支持，则使用 -extfile
    if ! openssl x509 -req -days 825 \
        -in "${CERT_DIR}/${n}.csr" \
        -CA "${CERT_DIR}/ca.crt" \
        -CAkey "${CERT_DIR}/ca.key" \
        -CAcreateserial \
        -out "${CERT_DIR}/${n}.crt" \
        -sha256 \
        -addext "subjectAltName = ${SAN_DNS}, ${SAN_IP}" 2>/dev/null; then
        # 回退：使用 -extfile（兼容 openssl 1.0.x）
        EXT_FILE="$(mktemp)"
        printf "subjectAltName = %s, %s\n" "${SAN_DNS}" "${SAN_IP}" > "${EXT_FILE}"
        openssl x509 -req -days 825 \
            -in "${CERT_DIR}/${n}.csr" \
            -CA "${CERT_DIR}/ca.crt" \
            -CAkey "${CERT_DIR}/ca.key" \
            -CAcreateserial \
            -out "${CERT_DIR}/${n}.crt" \
            -sha256 \
            -extfile "${EXT_FILE}"
        rm -f "${EXT_FILE}"
    fi

    # 清理 CSR（中间文件，不需要保留）
    rm -f "${CERT_DIR}/${n}.csr"
done

# ---------- 3. 验证证书 ----------
echo "[generate-certs] [4/4] 验证证书..."
for n in "${NODES[@]}"; do
    if openssl verify -CAfile "${CERT_DIR}/ca.crt" "${CERT_DIR}/${n}.crt" >/dev/null 2>&1; then
        echo "  ✓ ${n}.crt 验证通过"
    else
        echo "  ✗ ${n}.crt 验证失败" >&2
        exit 1
    fi
done

# ---------- 4. 输出摘要 ----------
echo ""
echo "[generate-certs] 证书生成完成："
echo "  CA:    ${CERT_DIR}/ca.crt"
for n in "${NODES[@]}"; do
    echo "  ${n}: ${CERT_DIR}/${n}.crt / ${n}.key"
done
echo ""
echo "提示: 私钥文件权限建议设置为 600（chmod 600 ${CERT_DIR}/*.key）"

exit 0