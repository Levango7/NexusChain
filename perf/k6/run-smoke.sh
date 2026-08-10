#!/usr/bin/env bash
# ============================================================================
# NexusChain k6 冒烟测试 — CI / 本地快速验证
# P2-T6: 性能压测与调优
# ----------------------------------------------------------------------------
# 用途：低负载（5 VU × 30s）快速验证 4 个场景的连通性与基本正确性。
#       不强制 P99 阈值，仅要求错误率 < 5% / 检查通过率 > 95%。
#       退出码 0=通过，非 0=失败，可直接用于 CI gating。
#
# 用法：
#   bash perf/k6/run-smoke.sh                       # 全部 4 个场景
#   bash perf/k6/run-smoke.sh payment-create        # 仅单个场景
#
# 环境变量（必填）：
#   API_KEY, SIGNING_SECRET
# 环境变量（可选，带默认）：
#   BASE_URL_GATEWAY (http://localhost:8080)
#   BASE_URL_BRIDGE  (http://localhost:8084)
#   K6_BIN           (k6；未安装时自动用 docker)
#   SMOKE_VUS        (5)
#   SMOKE_DURATION   (30s)
#   RESULTS_DIR      (./perf/k6/results)
# ============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# 颜色 & 日志
# ---------------------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "[$(date +%H:%M:%S)] $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

API_KEY="${API_KEY:?缺少 API_KEY 环境变量}"
SIGNING_SECRET="${SIGNING_SECRET:?缺少 SIGNING_SECRET 环境变量}"
BASE_URL_GATEWAY="${BASE_URL_GATEWAY:-http://localhost:8080}"
BASE_URL_BRIDGE="${BASE_URL_BRIDGE:-http://localhost:8084}"
SMOKE_VUS="${SMOKE_VUS:-5}"
SMOKE_DURATION="${SMOKE_DURATION:-30s}"
RESULTS_DIR="${RESULTS_DIR:-$SCRIPT_DIR/results}"
mkdir -p "$RESULTS_DIR"

# k6 二进制：优先 $K6_BIN，其次 which k6，最后 docker
if [[ -n "${K6_BIN:-}" ]]; then
  K6="$K6_BIN"
elif command -v k6 >/dev/null 2>&1; then
  K6="$(command -v k6)"
elif command -v docker >/dev/null 2>&1; then
  warn "未找到 k6，使用 docker grafana/k6:latest"
  K6="docker run --rm -i -v \"$SCRIPT_DIR:/scripts\" grafana/k6:latest"
else
  fail "未找到 k6 或 docker，请先安装 k6（>=0.43）"
  exit 2
fi

# 通用环境参数
COMMON_ENV=(
  -e "API_KEY=$API_KEY"
  -e "SIGNING_SECRET=$SIGNING_SECRET"
  -e "BASE_URL_GATEWAY=$BASE_URL_GATEWAY"
  -e "BASE_URL_BRIDGE=$BASE_URL_BRIDGE"
  -e "MERCHANT_ID=${MERCHANT_ID:-1}"
  -e "PREFERRED_CONNECTOR=${PREFERRED_CONNECTOR:-mock}"
  -e "SEED_POOL_SIZE=${SEED_POOL_SIZE:-20}"
  -e "TLS_SKIP_VERIFY=${TLS_SKIP_VERIFY:-false}"
)

# 冒烟覆盖：低 VU + 短时长 + 放宽阈值
SMOKE_OPTS=(
  --vus "$SMOKE_VUS"
  --duration "$SMOKE_DURATION"
  # 放宽阈值：冒烟只看连通性，不卡 P99
  --summary-trend-stats "avg,p(95),p(99)"
)

# 场景列表
SCENARIOS=(
  "payment-create:payment-create.js"
  "payment-query:payment-query.js"
  "bridge-lock:bridge-lock.js"
  "webhook-delivery:webhook-delivery.js"
)

# 命令行参数过滤
if [[ $# -gt 0 ]]; then
  FILTER="$1"
  SCENARIOS=($(printf '%s\n' "${SCENARIOS[@]}" | grep "^$FILTER:"))
  if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
    fail "未知场景: $FILTER（可选: payment-create, payment-query, bridge-lock, webhook-delivery）"
    exit 2
  fi
fi

# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------
log "NexusChain k6 冒烟测试"
log "  k6: $K6"
log "  gateway: $BASE_URL_GATEWAY"
log "  bridge:  $BASE_URL_BRIDGE"
log "  vus: $SMOKE_VUS, duration: $SMOKE_DURATION"
log "  场景: ${SCENARIOS[*]}"
echo "----------------------------------------"

OVERALL=0
for entry in "${SCENARIOS[@]}"; do
  name="${entry%%:*}"
  script="${entry##*:}"
  scriptPath="$SCRIPT_DIR/$script"
  resultFile="$RESULTS_DIR/smoke-$name.json"

  if [[ ! -f "$scriptPath" ]]; then
    fail "$name: 脚本不存在 $scriptPath"
    OVERALL=1
    continue
  fi

  log "▶ $name"
  # shellcheck disable=SC2086
  if $K6 run "${SMOKE_OPTS[@]}" --out "json=$resultFile" \
        "${COMMON_ENV[@]}" "$scriptPath" 2>&1 | sed 's/^/    /'; then
    ok "$name"
  else
    fail "$name（详见 $resultFile）"
    OVERALL=1
  fi
  echo "----------------------------------------"
done

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
if [[ $OVERALL -eq 0 ]]; then
  ok "全部冒烟测试通过"
else
  fail "存在失败场景，请检查上方日志与 $RESULTS_DIR"
fi

exit $OVERALL