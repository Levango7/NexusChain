#!/usr/bin/env bash
# smoke-dev-boot.sh — 交付就绪 D2：dev 档「开箱启动」验收
#
# 断言：在**不设置任何结算相关环境变量**的前提下，
#   ./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=dev"
# 能启动成功，且 /actuator/health 返回 UP。
#
# 依赖：#563（application-dev.yml 显式 nexus.settlement.enabled=false）。
#   若当前 checkout 尚未包含该修复，脚本标 SKIP 并说明，不判 FAIL。
#
# 用法（在仓库根或任意目录均可，脚本自行定位仓库根）：
#   bash scripts/smoke-dev-boot.sh
#   NEX_DEV_PORT=18081 NEX_DEV_BOOT_TIMEOUT=420 bash scripts/smoke-dev-boot.sh
#
# 退出码：0=PASS/SKIP，1=FAIL，2=环境不满足（端口占用 / 找不到 gradlew）
#
# 说明：
# - 子进程显式 `env -u` 掉 NEX_SETTLEMENT_ENABLED / NEX_SETTLEMENT_PLATFORM_ADDRESS /
#   SPRING_PROFILES_ACTIVE，确保测的是 dev 档自身默认值，而不是调用方的 env 兜底；
# - 日志默认写到系统临时目录，不落仓库；
# - 退出时清理 bootRun 进程与端口占用。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${NEX_DEV_PORT:-8080}"
TIMEOUT="${NEX_DEV_BOOT_TIMEOUT:-360}"
LOG="${NEX_DEV_BOOT_LOG:-${TMPDIR:-/tmp}/smoke-dev-boot-$(date +%Y%m%d-%H%M%S).log}"
DEV_YML="$ROOT/nexus-gateway/src/main/resources/application-dev.yml"

GRADLE_PID=""

fail() { echo "FAIL: $*"; exit 1; }
skip() { echo "SKIP: $*"; exit 0; }

port_pid() {
  local port="$1" pid=""
  if command -v lsof >/dev/null 2>&1; then
    pid="$(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null | head -n1)"
  fi
  if [[ -z "$pid" ]] && command -v netstat >/dev/null 2>&1; then
    pid="$(netstat -ano 2>/dev/null \
      | awk -v p=":$port" '$0 ~ /LISTENING/ && $2 ~ p"$" {print $5; exit}')"
  fi
  printf '%s' "$pid"
}

kill_pid() {
  local pid="$1"
  [[ -z "$pid" ]] && return 0
  if command -v taskkill >/dev/null 2>&1; then
    taskkill //F //PID "$pid" >/dev/null 2>&1 || true
  else
    kill -9 "$pid" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ -n "$GRADLE_PID" ]]; then
    kill "$GRADLE_PID" 2>/dev/null || true
  fi
  local pid
  pid="$(port_pid "$PORT")"
  [[ -n "$pid" ]] && kill_pid "$pid"
  return 0
}
trap cleanup EXIT INT TERM

echo "== dev 开箱启动验收（D2） =="
echo "repo    : $ROOT"
echo "port    : $PORT"
echo "timeout : ${TIMEOUT}s"
echo "log     : $LOG"

[[ -f "$ROOT/gradlew" ]] || { echo "FAIL: 找不到 $ROOT/gradlew（不在仓库根？）"; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "FAIL: 需要 curl 探测 /actuator/health"; exit 2; }

# --- 前置断言：dev 档必须显式关闭结算（#563） -------------------------------
if ! awk '
  /^[[:space:]]*settlement:[[:space:]]*$/ { inblk = 1; next }
  inblk && /^[[:space:]]*enabled:[[:space:]]*false[[:space:]]*$/ { found = 1; exit }
  inblk && /^[^[:space:]]/ { inblk = 0 }
  END { exit found ? 0 : 1 }
' "$DEV_YML"; then
  skip "application-dev.yml 未显式设置 nexus.settlement.enabled=false（依赖 #563 未合入）；
      裸 bootRun 会被 SettlementPlatformAddressGuard 以「平台出款地址未配置」拦下。"
fi

# --- 端口必须空闲 ------------------------------------------------------------
existing="$(port_pid "$PORT")"
if [[ -n "$existing" ]]; then
  echo "FAIL: 端口 $PORT 已被 PID $existing 占用；先释放或用 NEX_DEV_PORT 指定其他端口"
  exit 2
fi

# --- 启动（显式清掉调用方可能设置的结算 env） --------------------------------
echo "env     : NEX_SETTLEMENT_ENABLED=${NEX_SETTLEMENT_ENABLED:-<unset>} (子进程将被 unset)"
start_ts="$(date +%s)"
(
  cd "$ROOT" || exit 1
  env -u NEX_SETTLEMENT_ENABLED -u NEX_SETTLEMENT_PLATFORM_ADDRESS -u SPRING_PROFILES_ACTIVE \
    ./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=dev --server.port=$PORT"
) >"$LOG" 2>&1 &
GRADLE_PID=$!

# --- 等待健康端点 ------------------------------------------------------------
health=""
while :; do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "--- log tail ---"
    tail -n 40 "$LOG" 2>/dev/null || true
    fail "bootRun 进程提前退出（见上方日志）"
  fi
  health="$(curl -fsS "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null || true)"
  [[ "$health" == *'"status":"UP"'* ]] && break
  now="$(date +%s)"
  if (( now - start_ts > TIMEOUT )); then
    echo "--- log tail ---"
    tail -n 40 "$LOG" 2>/dev/null || true
    fail "等待 ${TIMEOUT}s 后 /actuator/health 仍未 UP"
  fi
  sleep 2
done
elapsed=$(( $(date +%s) - start_ts ))

# --- 日志断言 ----------------------------------------------------------------
grep -Eq 'The following .* profile.*dev' "$LOG" \
  || fail "日志中未见 dev profile 激活记录"
started_line="$(grep -E 'Started .* in [0-9.]+ seconds' "$LOG" | tail -n1)"
[[ -n "$started_line" ]] || fail "日志中未见 Started 行"
if grep -q '结算已启用，但平台出款地址未配置' "$LOG"; then
  fail "触发 SettlementPlatformAddressGuard（dev 档未关闭结算）"
fi

echo "--- log tail ---"
grep -E 'The following .* profile|Started .* in [0-9.]+ seconds|Tomcat started' "$LOG" | tail -n 5

echo
echo "== 结果 =="
echo "PASS  dev 档裸启动（未设置任何结算 env）"
echo "PASS  /actuator/health = UP"
echo "PASS  未触发 SettlementPlatformAddressGuard"
echo "启动耗时: ${elapsed}s"
echo "日志: $LOG"
exit 0
