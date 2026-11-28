#!/usr/bin/env bash
# J4 独立运行验收证据收集器（#786）。
#
# 用法：
#   bash scripts/ops/j4-evidence.sh <证据目录> [--no-start] [--min-ram-mb <N>]
#
# 模式：
#   默认     资源门禁 → production 预检 → compose-up → doctor →
#           acceptance → status → compose-down → 端口/容器释放检查
#   --no-start  只跑只读段：环境快照、资源门禁、production 预检。
#               不启动任何服务，用于起栈前的安全预取证。
#
# 约定：
#   · 每一步保存原始 stdout/stderr（.out）与 nc-task 回执（.json）。
#   · SKIP 不计为 PASS：summary.json 中 skip 单独列出，必检项缺失即整体 FAIL。
#   · 不打印任何凭据；日志只含回执与状态。
#   · 未启动生产服务、不触碰钱路径（acceptance 使用沙箱商户）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "[FAIL] 缺少 python3/python" >&2
  exit 2
fi

if [ $# -lt 1 ]; then
  echo "用法：bash scripts/ops/j4-evidence.sh <证据目录> [--no-start] [--min-ram-mb <N>]" >&2
  exit 2
fi
EVIDENCE_DIR="$1"; shift
NO_START=0
MIN_RAM_MB=12288
while [ $# -gt 0 ]; do
  case "$1" in
    --no-start) NO_START=1; shift ;;
    --min-ram-mb) MIN_RAM_MB="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

mkdir -p "$EVIDENCE_DIR"

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GATEWAY_URL="${NEX_GATEWAY_URL:-http://127.0.0.1:8080}"
ENV_FILE="${NEX_ENV_FILE:-$ROOT/.env.prod}"
COMPOSE_FILE="$ROOT/docker-compose.prod.yml"

# 步骤执行器：run_step <序号> <名称> <命令...>
# 回执（stdout 最后一行 JSON）写入 <序号>-<名称>.json，全量输出写入 .out
run_step() {
  local seq="$1" name="$2"; shift 2
  local out="$EVIDENCE_DIR/${seq}-${name}.out"
  "$@" >"$out" 2>&1
  local rc=$?
  local last
  last="$(tail -n 1 "$out" 2>/dev/null || true)"
  if printf '%s' "$last" | grep -q '^{"action"'; then
    printf '%s\n' "$last" >"$EVIDENCE_DIR/${seq}-${name}.json"
  else
    printf '{"action":"%s","ok":%s,"exitCode":%s,"reason":"no machine receipt captured","ts":"%s","elapsedMs":0,"detail":{}}\n' \
      "$name" "$([ "$rc" -eq 0 ] && echo true || echo false)" "$rc" "$TS" >"$EVIDENCE_DIR/${seq}-${name}.json"
  fi
  echo "$seq $name $rc"
  return "$rc"
}

# 1. 环境快照（只读）
{
  printf '{"ts":"%s","root":"%s","gitSha":"%s","gatewayUrl":"%s","envFile":"%s"}\n' \
    "$TS" "$ROOT" "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)" \
    "$GATEWAY_URL" "$ENV_FILE"
  "$PY" scripts/start_gate.py --json
} > "$EVIDENCE_DIR/env-snapshot.json" 2>&1

# 2. 资源门禁
run_step 02 resource-gate "$PY" scripts/start_gate.py --json --min-mb "$MIN_RAM_MB" || {
  echo "资源门禁未满足，停止取证（未起栈）"
  exit 2
}

# 3. production 预检（只读）
run_step 03 prod-check bash scripts/ops/nc-task.sh prod-check || {
  echo "production 预检未通过，停止取证（未起栈）"
  exit 1
}

if [ "$NO_START" -eq 1 ]; then
  : > "$EVIDENCE_DIR/no-start.flag"
  echo "[PASS] --no-start：只读取证完成，未启动任何服务"
  "$PY" scripts/ops/j4_summary.py "$EVIDENCE_DIR" --no-start
  exit $?
fi

# 4. 起栈（先过 nc-task 自己的资源门禁 + production 预检）
run_step 04 compose-up bash scripts/ops/nc-task.sh compose-up || {
  echo "compose-up 失败，进入停机清理"
  run_step 08 compose-down bash scripts/ops/nc-task.sh compose-down || true
  exit 1
}

# 5. doctor
run_step 05 doctor bash scripts/ops/nc-task.sh doctor
DOCTOR_RC=$?

# 6. acceptance（需 NEX_API_KEY；缺 Key 记 SKIP，不计 PASS）
if [ -z "${NEX_API_KEY:-}" ]; then
  echo "SKIP acceptance: NEX_API_KEY 未设置" > 06-acceptance.out
  printf '{"action":"acceptance","ok":false,"exitCode":2,"reason":"SKIP: NEX_API_KEY 未设置","ts":"%s","elapsedMs":0,"detail":{}}\n' "$TS" > 06-acceptance.json
  ACCEPTANCE_RC=2
else
  run_step 06 acceptance bash scripts/ops/nc-task.sh acceptance
  ACCEPTANCE_RC=$?
fi

# 7. status
run_step 07 status bash scripts/ops/nc-task.sh status
STATUS_RC=$?

# 8. 独立停机
run_step 08 compose-down bash scripts/ops/nc-task.sh compose-down
DOWN_RC=$?

# 9. 释放检查：compose 容器全部移除，且网关端口已释放
{
  echo "== compose ps --all =="
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps --all 2>&1 || true
  echo "== 网关端口探测 =="
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep -E '(:8080|:19585|:8082|:8083|:8084|:8085|:3000)\s' || echo "no-listening"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E 'LISTENING\s' | grep -E ':(8080|19585|8082|8083|8084|8085|3000)\s' || echo "no-listening"
  else
    echo "no-ss-or-netstat"
  fi
} > 09-port-release.out 2>&1
PORT_RC=$?

"$PY" scripts/ops/j4_summary.py "$EVIDENCE_DIR"
exit $?
