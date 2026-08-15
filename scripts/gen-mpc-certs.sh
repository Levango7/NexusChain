#!/usr/bin/env bash
# ============================================================
# MPC 多主机部署：mTLS 证书发放（自签 CA + 每节点证书）
#
# 生成:
#   mpc-certs/ca/CA.pem, CA.key          —— 根 CA（信任锚）
#   mpc-certs/node-<name>/cert.pem, key.pem —— 各节点证书（引擎 + Java 客户端共用）
#
# 用法: bash scripts/gen-mpc-certs.sh <node-name>...
#   例: bash scripts/gen-mpc-certs.sh node-A node-B node-C
# ============================================================
set -e
cd "$(dirname "$0")/.."
CERT_DIR="${MPC_CERT_DIR:-mpc-certs}"

NODES=("$@")
if [ ${#NODES[@]} -eq 0 ]; then
    NODES=("node-A" "node-B" "node-C")
fi

echo "=== 生成根 CA（信任锚）==="
mkdir -p "$CERT_DIR/ca"
if [ ! -f "$CERT_DIR/ca/CA.pem" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
        -keyout "$CERT_DIR/ca/CA.key" -out "$CERT_DIR/ca/CA.pem" \
        -subj "/CN=NexusChain MPC Root CA" 2>/dev/null; 
    echo "  CA 已生成: $CERT_DIR/ca/"
else
    echo "  CA 已存在（复用）"
fi

for NODE in "${NODES[@]}"; do
    echo "=== 节点 $NODE 证书 ==="
    mkdir -p "$CERT_DIR/$NODE"
    if [ -f "$CERT_DIR/$NODE/cert.pem" ]; then
        echo "  $NODE 证书已存在（跳过）"
        continue
    fi
    # 私钥 + CSR + 签发（SAN 含 localhost/127.0.0.1）
    openssl req -newkey rsa:2048 -nodes \
        -keyout "$CERT_DIR/$NODE/key.pem" \
        -out "$CERT_DIR/$NODE/csr.pem" \
        -subj "/CN=$NODE" 2>/dev/null
    openssl x509 -req -days 3650 \
        -in "$CERT_DIR/$NODE/csr.pem" \
        -CA "$CERT_DIR/ca/CA.pem" -CAkey "$CERT_DIR/ca/CA.key" -CAcreateserial \
        -out "$CERT_DIR/$NODE/cert.pem" \
        -extfile mpc-certs/san.cnf 2>/dev/null
    rm -f "$CERT_DIR/$NODE/csr.pem"
    # 私钥权限 600
    chmod 600 "$CERT_DIR/$NODE/key.pem"
    echo "  $NODE 证书已签发（SAN: localhost, 127.0.0.1）"
done

echo ""
echo "=== 证书体系就绪 ==="
echo "  信任锚（所有节点配置）: $CERT_DIR/ca/CA.pem"
for NODE in "${NODES[@]}"; do
    echo "  $NODE: $CERT_DIR/$NODE/cert.pem + key.pem"
done
echo ""
echo "=== 引擎启动（示例，节点 $NODES）==="
echo "  MPC_REQUIRE_TLS=true MPC_TLS_CERT_PATH=$CERT_DIR/$NODES/cert.pem MPC_TLS_KEY_PATH=$CERT_DIR/$NODES/key.pem ./mpc-engine"
echo "=== Java 客户端配置（示例）==="
echo "  mpc.engine.use-plaintext=false"
echo "  mpc.engine.tls.trust-cert-path=$CERT_DIR/ca/CA.pem"
echo "  mpc.engine.tls.client-cert-path=$CERT_DIR/<client>/cert.pem"
echo "  mpc.engine.tls.client-key-path=$CERT_DIR/<client>/key.pem"
