#!/usr/bin/env bash
# ============================================================================
# canary-gate.sh — 滚动/灰度后的三秒判断
# ============================================================================
# 用法：
#   bash scripts/ops/canary-gate.sh --url http://host:18091/actuator/health
#   bash scripts/ops/canary-gate.sh --url https://prod.example.com --token <bearer>
#   bash scripts/ops/canary-gate.sh --prom http://prom:9090 --query 'up{namespace="nexus-prod",service="nexus-gateway"}'
#
# 判读：
#   rc=0  → 一切正常；可走下一格（或收尾本次发布）
#   rc=1  → 发生硬故障 → 人取消/回滚
#   rc=2  → 用法 / 参数缺失 / 缺 curl
# ============================================================================

set -uo pipefail

URL=""
PROM=""
QUERY=""
TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url)   shift; URL="$1" ;;
    --prom)  shift; PROM="$1" ;;
    --query) shift; QUERY="$1" ;;
    --token) shift; TOKEN="$1" ;;
    -h|--help) sed -n '10,28p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
  shift
done

command -v curl >/dev/null 2>&1 || { echo "缺 curl" >&2; exit 2; }

H=(-s -m 30)
[ -n "$TOKEN" ] && H+=(-H "Authorization: Bearer $TOKEN")

echo "== Canary Gate =="

# Step 1 — health
if [ -z "$URL" ]; then
  echo "（跳过 health 探测：未给 --url）"
else
  body="$(curl "${H[@]}" "$URL" 2>&1)"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "FAIL:health → curl rc=$rc"
    exit 1
  fi
  status="$(printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("status","unknown"))' 2>/dev/null)" || status=""
  echo "health body 截取: $(printf '%s' "$body" | head -c 160)"
  if [ "$status" = "UP" ]; then
    echo "PASS:health(UP)"
  else
    echo "FAIL:health(status=$status) body=$body"
    exit 1
  fi
fi

# Step 2 — Prometheus（可选）
if [ -n "$PROM" ] && [ -n "$QUERY" ]; then
  result="$(curl -s -m 15 "$PROM/api/v1/query" --data-urlencode "query=$QUERY" 2>&1)"
  if printf '%s' "$result" | grep -q '"status":"success"'; then
    echo "PASS:prom($QUERY)"
  else
    echo "FAIL:prom($QUERY) → $result"
    exit 1
  fi
fi

echo "rc=0"
