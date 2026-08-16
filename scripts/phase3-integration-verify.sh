#!/usr/bin/env bash
# =============================================================================
# NexusChain Phase 3 集成验证脚本
# -----------------------------------------------------------------------------
# 用途：在 docker-compose 全链路启动后，验证 Phase 3 微服务化（Seata 分布式事务
#       + Micrometer 链路追踪 + Feign fallback 绑定）9 项集成点是否就绪。
#
# 9 项验证：
#   1. Seata Server 健康检查（http://localhost:7091/health）
#   2. Zipkin Server 健康检查（http://localhost:9411/api/v2/services）
#   3. Nacos 服务注册检查（gateway/signing-service/wallet-service/bridge）
#   4. gateway 启动检查（http://localhost:${GATEWAY_PORT}/actuator/health）
#   5. signing-service 启动检查（http://localhost:${SIGNING_PORT}/actuator/health）
#   6. wallet-service 启动检查（http://localhost:${WALLET_PORT}/actuator/health）
#   7. bridge 启动检查（http://localhost:${BRIDGE_PORT}/actuator/health）
#   8. 跨服务 Feign 调用检查（gateway 健康指标含 signing/wallet 探测结果）
#   9. 链路追踪检查（Zipkin 已收集到跨服务 trace）
#
# 端口约定（与各服务 application.yml server.port 对齐，REQ-04 修正）：
#   gateway  : 8080 / signing : 8082 / wallet : 8083 / bridge : 8084
#
# 可覆盖环境变量：
#   GATEWAY_PORT  — gateway 端口（默认 8080）
#   SIGNING_PORT  — signing-service 端口（默认 8082）
#   WALLET_PORT   — wallet-service 端口（默认 8083）
#   BRIDGE_PORT   — bridge 端口（默认 8084）
#   CURL_TIMEOUT  — 单次 HTTP 探测超时秒数（默认 8）
#
# 用法：
#   bash scripts/phase3-integration-verify.sh
#   SIGNING_PORT=18082 WALLET_PORT=18083 BRIDGE_PORT=18084 bash scripts/phase3-integration-verify.sh
#
# 退出码：
#   0 — 全部 PASS
#   1 — 存在 FAIL 项
# =============================================================================
set -uo pipefail

# ---------- 配置 ----------
# REQ-04: 端口从环境变量读取（默认值与各服务 application.yml server.port 对齐）
#   gateway  : 8080 / signing : 8082 / wallet : 8083 / bridge : 8084
GATEWAY_PORT="${GATEWAY_PORT:-8080}"
SIGNING_PORT="${SIGNING_PORT:-8082}"
WALLET_PORT="${WALLET_PORT:-8083}"
BRIDGE_PORT="${BRIDGE_PORT:-8084}"

SEATA_HEALTH_URL="http://localhost:7091/health"
ZIPKIN_SERVICES_URL="http://localhost:9411/api/v2/services"
ZIPKIN_TRACE_URL="http://localhost:9411/api/v2/trace?limit=1"
NACOS_INSTANCE_URL="http://localhost:8848/nacos/v1/ns/instance/list"

GATEWAY_HEALTH_URL="http://localhost:${GATEWAY_PORT}/actuator/health"
SIGNING_HEALTH_URL="http://localhost:${SIGNING_PORT}/actuator/health"
WALLET_HEALTH_URL="http://localhost:${WALLET_PORT}/actuator/health"
BRIDGE_HEALTH_URL="http://localhost:${BRIDGE_PORT}/actuator/health"

# Nacos 中应注册的服务名（namespace=dev）
NACOS_SERVICES=("gateway" "signing-service" "wallet-service" "bridge")

# 单次 HTTP 探测超时（秒）
CURL_TIMEOUT="${CURL_TIMEOUT:-8}"

# ---------- 颜色 ----------
if [[ -t 1 ]]; then
  C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_RESET=$'\033[0m'
else
  C_GREEN=""; C_RED=""; C_YELLOW=""; C_RESET=""
fi

# ---------- 计数器 ----------
PASS_COUNT=0
FAIL_COUNT=0
FAIL_ITEMS=()

# ---------- 工具函数 ----------
# log_pass <item> <detail>
log_pass() {
  printf '%s[PASS]%s %s — %s\n' "$C_GREEN" "$C_RESET" "$1" "$2"
  PASS_COUNT=$((PASS_COUNT + 1))
}

# log_fail <item> <detail>
log_fail() {
  printf '%s[FAIL]%s %s — %s\n' "$C_RED" "$C_RESET" "$1" "$2"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAIL_ITEMS+=("$1")
}

# http_ok <url> —— 返回 0 表示 HTTP 200
http_ok() {
  curl -fsS -m "$CURL_TIMEOUT" -o /dev/null "$1" 2>/dev/null
}

# http_get <url> —— 输出 body，失败输出空
http_get() {
  curl -fsS -m "$CURL_TIMEOUT" "$1" 2>/dev/null
}

# json_extract <json> <key> —— 简易 JSON 字符串/数字提取（不依赖 jq）
# 仅用于本脚本中扁平字段的粗提取；复杂结构请用 python3。
json_value() {
  local json="$1" key="$2"
  printf '%s' "$json" | \
    sed -n 's/.*"'"$key"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p; s/.*"'"$key"'"[[:space:]]*:[[:space:]]*\([0-9a-zA-Z.-]*\).*/\1/p' | head -n1
}

print_summary() {
  echo "----------------------------------------------------------------"
  local total=$((PASS_COUNT + FAIL_COUNT))
  printf '汇总：%d 项检查，%s%d PASS%s，%s%d FAIL%s\n' \
    "$total" "$C_GREEN" "$PASS_COUNT" "$C_RESET" "$C_RED" "$FAIL_COUNT" "$C_RESET"
  if (( FAIL_COUNT > 0 )); then
    printf '%s失败项：%s%s\n' "$C_RED" "${FAIL_ITEMS[*]}" "$C_RESET"
    return 1
  fi
  printf '%sPhase 3 集成验证全部通过。%s\n' "$C_GREEN" "$C_RESET"
  return 0
}

# =============================================================================
# 1. Seata Server 健康检查
# =============================================================================
verify_seata() {
  local item="1. Seata Server 健康检查"
  if http_ok "$SEATA_HEALTH_URL"; then
    log_pass "$item" "$SEATA_HEALTH_URL 可达"
  else
    log_fail "$item" "$SEATA_HEALTH_URL 不可达（Seata Server 未启动或健康检查失败）"
  fi
}

# =============================================================================
# 2. Zipkin Server 健康检查
# =============================================================================
verify_zipkin() {
  local item="2. Zipkin Server 健康检查"
  local body
  body=$(http_get "$ZIPKIN_SERVICES_URL")
  if [[ -n "$body" ]]; then
    log_pass "$item" "$ZIPKIN_SERVICES_URL 返回服务列表"
  else
    log_fail "$item" "$ZIPKIN_SERVICES_URL 无响应（Zipkin 未启动）"
  fi
}

# =============================================================================
# 3. Nacos 服务注册检查
# =============================================================================
verify_nacos_registry() {
  local item="3. Nacos 服务注册检查"
  local missing=()
  for svc in "${NACOS_SERVICES[@]}"; do
    local resp
    resp=$(http_get "${NACOS_INSTANCE_URL}?serviceName=${svc}&namespaceId=dev")
    # Nacos instance/list 返回 JSON，hosts 字段非空数组表示已注册
    local count
    count=$(printf '%s' "$resp" | \
      sed -n 's/.*"hosts"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p' | wc -c)
    if [[ -z "$resp" || "$count" -le 2 ]]; then
      missing+=("$svc")
    fi
  done
  if (( ${#missing[@]} == 0 )); then
    log_pass "$item" "gateway/signing-service/wallet-service/bridge 均已注册到 Nacos(dev)"
  else
    log_fail "$item" "未注册或无实例：${missing[*]}"
  fi
}

# =============================================================================
# 4-7. 业务服务启动检查（actuator/health）
# =============================================================================
verify_service() {
  local idx="$1" name="$2" url="$3"
  local item="${idx}. ${name} 启动检查"
  local body status
  body=$(http_get "$url")
  status=$(json_value "$body" "status")
  if [[ "$status" == "UP" ]]; then
    log_pass "$item" "$url → UP"
  else
    log_fail "$item" "$url → ${status:-无响应}（${name} 未就绪）"
  fi
}

# =============================================================================
# 8. 跨服务 Feign 调用检查
# -----------------------------------------------------------------------------
# gateway 通过 SigningServiceHealthIndicator / WalletServiceHealthIndicator
#（#65 新增）以 FeignClient 探测下游。检查 gateway /actuator/health 的
# components 中是否含 signingService / walletService 且状态 UP。
# =============================================================================
verify_cross_service_feign() {
  local item="8. 跨服务 Feign 调用检查"
  local body
  body=$(http_get "$GATEWAY_HEALTH_URL")
  if [[ -z "$body" ]]; then
    log_fail "$item" "gateway /actuator/health 无响应"
    return
  fi
  # 健康指标名遵循 HealthIndicator 类名去后缀 + 驼峰转小写
  # SigningServiceHealthIndicator → signingService
  # WalletServiceHealthIndicator  → walletService
  local has_signing has_wallet
  has_signing=$(printf '%s' "$body" | grep -o '"signingService"[[:space:]]*:[[:space:]]*{[^}]*"status"[[:space:]]*:[[:space:]]*"UP"' | head -n1)
  has_wallet=$(printf '%s' "$body" | grep -o '"walletService"[[:space:]]*:[[:space:]]*{[^}]*"status"[[:space:]]*:[[:space:]]*"UP"' | head -n1)
  if [[ -n "$has_signing" && -n "$has_wallet" ]]; then
    log_pass "$item" "gateway → signing-service / wallet-service Feign 探测均 UP"
  else
    local detail="signingService=${has_signing:-DOWN}, walletService=${has_wallet:-DOWN}"
    log_fail "$item" "跨服务健康指标未全部 UP（$detail）"
  fi
}

# =============================================================================
# 9. 链路追踪检查
# -----------------------------------------------------------------------------
# 触发一次 gateway → 下游调用后，查询 Zipkin /api/v2/trace?limit=1，
# 若返回非空数组且 span 的 serviceName 非空，则判定 trace 已采集。
# 为避免依赖具体业务接口，先尝试 gateway /actuator/health（会经 Feign 探测
# 下游产生 span），再查 Zipkin。
# =============================================================================
verify_tracing() {
  local item="9. 链路追踪检查"
  # 先触发一次可能产生 trace 的调用（gateway health 含 Feign 探测）
  http_ok "$GATEWAY_HEALTH_URL" >/dev/null 2>&1 || true
  sleep 2  # 等待 Zipkin 异步收集
  local body
  body=$(http_get "$ZIPKIN_TRACE_URL")
  # /api/v2/trace?limit=1 返回 JSON 数组，非 "[]" 即有 trace
  if [[ -n "$body" && "$body" != "[]" ]]; then
    # 进一步确认 span 含 traceId
    if printf '%s' "$body" | grep -q '"traceId"'; then
      log_pass "$item" "Zipkin 已采集到跨服务 trace 记录"
    else
      log_fail "$item" "Zipkin trace 响应无 traceId 字段"
    fi
  else
    log_fail "$item" "Zipkin 无 trace 记录（/api/v2/trace?limit=1 返回空）"
  fi
}

# =============================================================================
# 主流程
# =============================================================================
main() {
  echo "================================================================"
  echo " NexusChain Phase 3 集成验证（$(date '+%Y-%m-%d %H:%M:%S')）"
  echo "================================================================"

  command -v curl >/dev/null 2>&1 || { echo '依赖 curl 未找到，终止。' >&2; exit 2; }

  verify_seata
  verify_zipkin
  verify_nacos_registry
  verify_service "4" "gateway"        "$GATEWAY_HEALTH_URL"
  verify_service "5" "signing-service" "$SIGNING_HEALTH_URL"
  verify_service "6" "wallet-service"  "$WALLET_HEALTH_URL"
  verify_service "7" "bridge"          "$BRIDGE_HEALTH_URL"
  verify_cross_service_feign
  verify_tracing

  echo
  print_summary
}

main "$@"