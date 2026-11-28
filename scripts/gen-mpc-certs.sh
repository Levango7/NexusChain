#!/usr/bin/env bash
# 生成或复用 NexusChain MPC mTLS CA、节点与签名客户端证书；不覆盖已有身份。
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."
CERT_DIR="${MPC_CERT_DIR:-mpc-certs}"
NODES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --cert-dir|-o) CERT_DIR="$2"; shift 2 ;;
    -h|--help)
      echo "用法：bash scripts/gen-mpc-certs.sh [--cert-dir <目录>] [node-A node-B node-C client-signing]"
      exit 0
      ;;
    *) NODES+=("$1"); shift ;;
  esac
done
if [ ${#NODES[@]} -eq 0 ]; then
  NODES=("node-A" "node-B" "node-C" "client-signing")
fi

for NODE in "${NODES[@]}"; do
  if [[ ! "$NODE" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "错误: 非法节点名 $NODE" >&2
    exit 1
  fi
done

mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"
TMP_ROOT="$(mktemp -d "$CERT_DIR/.issue.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT

if [ ! -f "$CERT_DIR/ca/CA.pem" ]; then
  if [ -e "$CERT_DIR/ca/CA.key" ]; then
    echo "错误: CA 私钥已存在但证书缺失，拒绝覆盖" >&2
    exit 1
  fi
  mkdir -p "$TMP_ROOT/ca" "$CERT_DIR/ca"
  chmod 700 "$CERT_DIR/ca"
  MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
    -keyout "$TMP_ROOT/ca/CA.key" -out "$TMP_ROOT/ca/CA.pem" \
    -subj "/CN=NexusChain MPC Root CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" >/dev/null 2>&1
  chmod 600 "$TMP_ROOT/ca/CA.key"
  chmod 644 "$TMP_ROOT/ca/CA.pem"
  mv "$TMP_ROOT/ca/CA.key" "$CERT_DIR/ca/CA.key"
  mv "$TMP_ROOT/ca/CA.pem" "$CERT_DIR/ca/CA.pem"
  echo "[PASS] 已生成 MPC 根 CA"
else
  if [ ! -f "$CERT_DIR/ca/CA.key" ]; then
    echo "错误: 复用 CA 证书需要对应 CA.key 才能签发节点证书" >&2
    exit 1
  fi
  chmod 600 "$CERT_DIR/ca/CA.key"
  CA_CERT_PUB="$(openssl x509 -in "$CERT_DIR/ca/CA.pem" -pubkey -noout)"
  CA_KEY_PUB="$(openssl pkey -in "$CERT_DIR/ca/CA.key" -pubout)"
  if [ "$CA_CERT_PUB" != "$CA_KEY_PUB" ]; then
    echo "错误: 现有 CA 证书与 CA.key 不匹配" >&2
    exit 1
  fi
  openssl verify -CAfile "$CERT_DIR/ca/CA.pem" "$CERT_DIR/ca/CA.pem" >/dev/null
  echo "[PASS] 复用现有 MPC 根 CA"
fi

PENDING=()
for NODE in "${NODES[@]}"; do
  NODE_DIR="$CERT_DIR/$NODE"
  if [ -f "$NODE_DIR/cert.pem" ] || [ -f "$NODE_DIR/key.pem" ]; then
    if [ ! -f "$NODE_DIR/cert.pem" ] || [ ! -f "$NODE_DIR/key.pem" ]; then
      echo "错误: $NODE 证书或私钥缺失，拒绝覆盖半成品" >&2
      exit 1
    fi
    chmod 600 "$NODE_DIR/key.pem"
    CERT_PUB="$(openssl x509 -in "$NODE_DIR/cert.pem" -pubkey -noout)"
    KEY_PUB="$(openssl pkey -in "$NODE_DIR/key.pem" -pubout)"
    if [ "$CERT_PUB" != "$KEY_PUB" ]; then
      echo "错误: $NODE 现有证书与私钥不匹配" >&2
      exit 1
    fi
    openssl verify -CAfile "$CERT_DIR/ca/CA.pem" "$NODE_DIR/cert.pem" >/dev/null
    echo "[PASS] 复用现有 $NODE 证书"
    continue
  fi

  WORK="$TMP_ROOT/$NODE"
  mkdir -p "$WORK"
  MSYS_NO_PATHCONV=1 openssl req -newkey rsa:3072 -nodes -sha256 \
    -keyout "$WORK/key.pem" -out "$WORK/csr.pem" \
    -subj "/CN=$NODE" >/dev/null 2>&1
  cat > "$WORK/san.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
EOF
  openssl x509 -req -sha256 -days 3650 \
    -in "$WORK/csr.pem" \
    -CA "$CERT_DIR/ca/CA.pem" -CAkey "$CERT_DIR/ca/CA.key" -CAcreateserial \
    -out "$WORK/cert.pem" -extfile "$WORK/san.cnf" >/dev/null 2>&1
  chmod 600 "$WORK/key.pem"
  chmod 644 "$WORK/cert.pem"
  CERT_PUB="$(openssl x509 -in "$WORK/cert.pem" -pubkey -noout)"
  KEY_PUB="$(openssl pkey -in "$WORK/key.pem" -pubout)"
  if [ "$CERT_PUB" != "$KEY_PUB" ]; then
    echo "错误: $NODE 新证书与私钥不匹配" >&2
    exit 1
  fi
  openssl verify -CAfile "$CERT_DIR/ca/CA.pem" -purpose sslserver "$WORK/cert.pem" >/dev/null
  openssl verify -CAfile "$CERT_DIR/ca/CA.pem" -purpose sslclient "$WORK/cert.pem" >/dev/null
  PENDING+=("$NODE")
done

for NODE in "${PENDING[@]}"; do
  NODE_DIR="$CERT_DIR/$NODE"
  mkdir -p "$NODE_DIR"
  chmod 700 "$NODE_DIR"
  mv "$TMP_ROOT/$NODE/key.pem" "$NODE_DIR/key.pem"
  mv "$TMP_ROOT/$NODE/cert.pem" "$NODE_DIR/cert.pem"
  echo "[PASS] 已签发 $NODE 证书（SAN: localhost, 127.0.0.1；EKU: serverAuth,clientAuth）"
done

echo "[PASS] MPC 证书体系就绪：$CERT_DIR"
