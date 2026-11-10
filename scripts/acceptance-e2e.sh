#!/usr/bin/env bash
# NexusChain 端到端验收：下单 → 查询 → 结算确认 → 对账差异
#
# 契约来源（2026-11-07 逐字核对 origin/master）：
#   - 签名头 X-NexusChain-{Timestamp,Nonce,Signature,ApiKey}
#     nexus-gateway/src/main/java/org/nexus/gateway/security/RequestSignatureInterceptor.java:48-50
#   - v2 canonical "NXC2|len:val|..." 同文件 computeSignatureV2/canonicalV2/appendField
#   - 商户身份 ApiKeyInterceptor.java:25,35,61（X-NexusChain-ApiKey → nexus.merchantId）
#   - 创建支付 PaymentOrchestrationController.java:37,77（POST /api/v1/payments）
#   - 结算确认 ChainSettlementConfirmationController.java:27,45（GET /api/v1/settlement-confirmations/{paymentId}）
#   - 对账差异 DiscrepancyResolutionController.java:46,101（GET /api/v1/reconciliation/discrepancies/{merchantId}）
#
# 前置：
#   1) gateway 已启动（sandbox：./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"）
#   2) 一个 verification_status=VERIFIED 商户的 API Key（sandbox 无预置商户，需自行创建/授权）
#
# 用法：
#   NEX_API_KEY=xxx bash scripts/acceptance-e2e.sh
#   NEX_GATEWAY_URL=http://localhost:8080 NEX_SIGNING_SECRET=xxx \
#     NEX_API_KEY=xxx NEX_MERCHANT_ID=1 bash scripts/acceptance-e2e.sh
#
# 注：NEX_MERCHANT_ID 未传时会尝试从响应回填 merchantId；支付响应实际不含该字段，
#     因此对账步骤需要显式传入，否则记 SKIP（SKIP 不等于通过）。
#
# 退出码：0 全通过；1 有步骤失败；2 前置缺失
set -uo pipefail

BASE_URL="${NEX_GATEWAY_URL:-http://localhost:8080}"
API_KEY="${NEX_API_KEY:-}"
SECRET="${NEX_SIGNING_SECRET:-sandbox-signing-secret-do-not-use-in-prod}"
AMOUNT="${NEX_AMOUNT:-100}"
CURRENCY="${NEX_CURRENCY:-NEX}"
PAYER="${NEX_PAYER_ADDRESS:-}"
PAYEE="${NEX_PAYEE_ADDRESS:-}"
MERCHANT_ID="${NEX_MERCHANT_ID:-}"
REPORT="${NEX_ACCEPTANCE_REPORT:-acceptance-report.md}"

if [ -z "$API_KEY" ]; then
  echo "ERROR: NEX_API_KEY 未设置。需要一个 VERIFIED 商户的 API Key（sandbox 无预置商户）。" >&2
  exit 2
fi
for bin in curl openssl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "ERROR: 需要 $bin" >&2; exit 2; }
done

# ---------- 签名（与 Java canonicalV2 逐字节对齐） ----------
_len() { printf '%s' "$1" | wc -c | tr -d ' '; }
canonical_v2() {
  printf 'NXC2|%s:%s|%s:%s|%s:%s|%s:%s|%s:%s|' \
    "$(_len "$1")" "$1" "$(_len "$2")" "$2" \
    "$(_len "$3")" "$3" "$(_len "$4")" "$4" \
    "$(_len "$5")" "$5"
}
sign_v2() {
  canonical_v2 "$1" "$2" "$3" "$4" "$5" \
    | openssl dgst -sha256 -hmac "$SECRET" -hex 2>/dev/null \
    | awk -F'= ' '{print $NF}'
}

# ---------- 请求 ----------
RESP_CODE=""; RESP_BODY=""
call() {
  local method="$1" path="$2" body="${3:-}" ts nonce sig tmp
  ts="$(( $(date +%s) * 1000 ))"
  nonce="$(openssl rand -hex 16)"
  sig="v2:$(sign_v2 "$ts" "$nonce" "$method" "$path" "$body")"
  tmp="$(mktemp)"
  if [ -n "$body" ]; then
    RESP_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
      -H "X-NexusChain-ApiKey: $API_KEY" \
      -H "X-NexusChain-Timestamp: $ts" \
      -H "X-NexusChain-Nonce: $nonce" \
      -H "X-NexusChain-Signature: $sig" \
      -H 'Content-Type: application/json' --data-binary "$body" 2>/dev/null)"
  else
    RESP_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE_URL$path" \
      -H "X-NexusChain-ApiKey: $API_KEY" \
      -H "X-NexusChain-Timestamp: $ts" \
      -H "X-NexusChain-Nonce: $nonce" \
      -H "X-NexusChain-Signature: $sig" 2>/dev/null)"
  fi
  RESP_BODY="$(cat "$tmp" 2>/dev/null || true)"
}

json_get() {
  python -c 'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1:]:
    if isinstance(d,dict) and k in d and d[k] not in (None,""):
        print(d[k]); break' "$@" <<<"$RESP_BODY" 2>/dev/null
}

PASS=0; FAIL=0
RESULTS=()
record() {
  local name="$1" code="$2" verdict="$3" detail="$4"
  RESULTS+=("| $name | $code | $verdict | $detail |")
  if [ "$verdict" = "PASS" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
}

echo "== NexusChain acceptance @ $BASE_URL =="

# 1) 创建支付
BODY="$(printf '{"amount":%s,"currency":"%s","description":"acceptance-e2e","request_id":"acc-%s"%s%s}' \
  "$AMOUNT" "$CURRENCY" "$(date +%s)" \
  "${PAYER:+,\"payer_address\":\"$PAYER\"}" "${PAYEE:+,\"payee_address\":\"$PAYEE\"}")"
call POST /api/v1/payments "$BODY"
PAYMENT_ID="$(json_get paymentId id | head -1)"
if [ "$RESP_CODE" = "201" ] && [ -n "$PAYMENT_ID" ]; then
  record "create payment" "$RESP_CODE" PASS "id=$PAYMENT_ID"
else
  record "create payment" "$RESP_CODE" FAIL "$(printf '%s' "$RESP_BODY" | head -c 200)"
fi

# 2) 查询支付
if [ -n "$PAYMENT_ID" ]; then
  call GET "/api/v1/payments/$PAYMENT_ID"
  if [ "$RESP_CODE" = "200" ]; then
    record "query payment" "$RESP_CODE" PASS "id=$PAYMENT_ID"
  else
    record "query payment" "$RESP_CODE" FAIL "$(printf '%s' "$RESP_BODY" | head -c 200)"
  fi
fi

# 3) 结算确认
if [ -n "$PAYMENT_ID" ]; then
  call GET "/api/v1/settlement-confirmations/$PAYMENT_ID"
  case "$RESP_CODE" in
    200) record "settlement confirmation" "$RESP_CODE" PASS "paymentId=$PAYMENT_ID" ;;
    404) record "settlement confirmation" "$RESP_CODE" FAIL "无确认记录：initiateConfirmation 无生产调用（写侧未接线），非支付未到结算腿" ;;
    *)   record "settlement confirmation" "$RESP_CODE" FAIL "$(printf '%s' "$RESP_BODY" | head -c 160)" ;;
  esac
fi

# 4) 对账差异
# 支付响应不含 merchantId（PaymentOrchestrationController.toResponse() 的键集合里没有），
# 因此优先用调用方显式传入的 NEX_MERCHANT_ID；未传才尝试从响应回填。
if [ -z "$MERCHANT_ID" ]; then
  MERCHANT_ID="$(json_get merchantId merchant_id | head -1)"
fi
if [ -n "$MERCHANT_ID" ]; then
  call GET "/api/v1/reconciliation/discrepancies/$MERCHANT_ID"
  if [ "$RESP_CODE" = "200" ]; then
    record "reconciliation query" "$RESP_CODE" PASS "merchantId=$MERCHANT_ID"
  else
    record "reconciliation query" "$RESP_CODE" FAIL "$(printf '%s' "$RESP_BODY" | head -c 160)"
  fi
else
  record "reconciliation query" "-" SKIP "未提供 NEX_MERCHANT_ID，且响应不含 merchantId"
fi

# ---------- 报告 ----------
{
  echo "# NexusChain 端到端验收报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- 目标：$BASE_URL"
  echo "- 币种/金额：$CURRENCY / $AMOUNT"
  echo "- 结果：**PASS $PASS / FAIL $FAIL**"
  echo
  echo "| 步骤 | HTTP | 判定 | 说明 |"
  echo "|---|---|---|---|"
  printf '%s\n' "${RESULTS[@]}"
  echo
  echo "> 本报告由 scripts/acceptance-e2e.sh 生成。SKIP 不等于通过。"
} > "$REPORT"

echo "report: $REPORT (PASS=$PASS FAIL=$FAIL)"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
