#!/usr/bin/env bash
# NexusChain k6 压测凭证自助签发（10g）——seed-perf-credentials.ps1 的 bash 等价实现
# 用法：./seed-perf-credentials.sh [GATEWAY_BASE] [JWT_SECRET]
# 仅限本地 dev / 演练环境（密钥默认值与 docker-compose.yml 10g 批一致）
set -euo pipefail

GATEWAY_BASE="${1:-http://localhost:8080}"
JWT_SECRET="${2:-nexus-dev-only-jwt-secret-0123456789abcdef}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# 1. 离线签发 ADMIN JWT（契约对齐 JwtTokenProvider.generateToken：HS256，
#    sub/iat/exp/roles 逗号串）
now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | openssl base64 -A | tr '+/' '-_' | tr -d '=')
payload=$(printf '{"sub":"perf-admin","iat":%s,"exp":%s,"roles":"ADMIN"}' "$now" "$((now + 900))" | openssl base64 -A | tr '+/' '-_' | tr -d '=')
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
JWT="${header}.${payload}.${sig}"
echo "[1/4] ADMIN JWT 离线签发完成（15 分钟有效）"

# 2. 注册压测商户（公开端点）
stamp=$(date +%Y%m%d%H%M%S)
resp=$(curl -sf -X POST "${GATEWAY_BASE}/api/v1/merchants/register" \
  -H "Content-Type: application/json" \
  -d "{\"merchantName\":\"perf-k6-${stamp}\",\"email\":\"perf-k6-${stamp}@invalid\",\"settlementAddress\":\"0x0000000000000000000000000000000000000000\"}")
id=$(printf '%s' "$resp" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "${id}" ]; then echo "[ERROR] register 响应缺少 id: ${resp}"; exit 1; fi
echo "[2/4] 商户已注册 id=${id}"

# 3. ADMIN 核验
curl -sf -X POST "${GATEWAY_BASE}/api/v1/merchants/${id}/verify" \
  -H "Content-Type: application/json" -H "Authorization: Bearer ${JWT}" \
  -d '{"status":"VERIFIED"}' >/dev/null
echo "[3/4] 商户已核验 VERIFIED"

# 4. 签发 API key + 签名密钥（仅此一次返回）
pair=$(curl -sf -X POST "${GATEWAY_BASE}/api/v1/merchants/${id}/api-keys" \
  -H "Authorization: Bearer ${JWT}")
api_key=$(printf '%s' "$pair" | grep -o '"apiKey":"[^"]*"' | cut -d'"' -f4)
secret=$(printf '%s' "$pair" | grep -o '"secret":"[^"]*"' | cut -d'"' -f4)
if [ -z "${api_key}" ] || [ -z "${secret}" ]; then echo "[ERROR] api-keys 响应缺少 apiKey/secret: ${pair}"; exit 1; fi
echo "[4/4] API 凭证签发完成（merchantId=${id}）"
echo
echo "==================== k6 凭证（妥善保管，仅此一次） ===================="
echo "API_KEY        : ${api_key}"
echo "SIGNING_SECRET : ${secret}"
echo
echo "k6 即用命令："
echo "  k6 run -e BASE_URL_GATEWAY=${GATEWAY_BASE} -e API_KEY=${api_key} -e SIGNING_SECRET=${secret} perf/k6/payment-create.js"
echo "======================================================================"
