#!/usr/bin/env bash
# ============================================================================
# nc-task.sh — 「被运维平台纳管」统一任务入口
# ============================================================================
# 目的：让 NexusChain 能被运维控制面（OpsMesh 或同类平台）**用一条 shell 任务**
#       驱动，并拿到**机器可解析**的回执（stdout 单行 JSON + 退出码）。
#       本脚本不依赖任何特定平台，只约定三件事：
#         ① 命令行：nc-task.sh <action> [args...]
#         ② stdout：**最后一行**是单行 JSON 回执（其余输出为人类可读日志）
#         ③ 退出码：0=动作成功；1=动作失败（回执里有原因）；2=用法/环境不满足
#
# 平台侧怎么用（以 OpsMesh 为例，接口形状来自其公开 API 文档，非本仓实现）：
#   POST /api/v1/tasks
#   { "agentID": "a-xxx", "type": "shell", "command": "bash scripts/ops/nc-task.sh doctor" }
#
# 动作清单：
#   doctor        仓库自检（scripts/doctor.sh）——交付后第一道
#   acceptance    端到端验收（scripts/acceptance-e2e.sh）——需 NEX_API_KEY / NEX_MERCHANT_ID
#   upgrade-drill 升级/回滚演练（scripts/upgrade-rollback-drill.sh）——需两个 jar
#   env-check     prod env 强制变量清单自检（scripts/check-prod-env.sh）
#   version       版本口径自检（scripts/check-version-consistency.sh）
#   status        轻量状态：健康端点 + 版本 + 关键环境变量是否就位（不改任何状态）
#
# 约定（跨平台中立）：
#   · 只读动作不改任何状态；写动作（验收/演练）只作用于沙箱与本地数据目录；
#   · 回执字段固定：{"action","ok","exitCode","reason","ts","elapsedMs","detail":{...}}；
#   · 日志走 stdout（平台侧按需采集），**不打印任何凭据**。
#
# 退出码：0=PASS，1=FAIL，2=用法/环境不满足（与 scripts/doctor.sh 一致）
# ============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

ACTION="${1:-}"
[ $# -gt 0 ] && shift

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
T0="$(date +%s)"

emit() { # emit <ok:true|false> <exitCode> <reason> [detail-json]
  ok="$1"; code="$2"; reason="$3"; detail="${4:-{\}}"
  printf '{"action":"%s","ok":%s,"exitCode":%s,"reason":"%s","ts":"%s","elapsedMs":%s,"detail":%s}\n' \
    "$ACTION" "$ok" "$code" "$reason" "$TS" "$(( $(date +%s) - T0 ))" "$detail"
}

usage() {
  sed -n '20,45p' "${BASH_SOURCE[0]}"
  exit 2
}

case "$ACTION" in
  doctor|env-check|version)
    case "$ACTION" in
      doctor)       cmd=(bash scripts/doctor.sh) ;;
      env-check)    cmd=(bash scripts/check-prod-env.sh) ;;
      version)      cmd=(bash scripts/check-version-consistency.sh) ;;
    esac
    echo "== nc-task: $ACTION =="
    "${cmd[@]}" 2>&1 | tail -n 40
    rc="${PIPESTATUS[0]}"
    if [ "$rc" -eq 0 ]; then emit true 0 "PASS"; exit 0; fi
    emit false "$rc" "自检未通过（详见上方输出）"; exit 1
    ;;

  acceptance)
    if [ -z "${NEX_API_KEY:-}" ]; then
      emit false 2 "缺 NEX_API_KEY（需 VERIFIED 商户 API Key；见 docs/delivery/demo-runbook.md §2）"
      exit 2
    fi
    echo "== nc-task: acceptance =="
    bash scripts/acceptance-e2e.sh 2>&1 | tail -n 40
    rc="${PIPESTATUS[0]}"
    if [ "$rc" -eq 0 ]; then emit true 0 "PASS"; exit 0; fi
    emit false "$rc" "端到端验收未全绿（FAIL 计数见报告）"; exit 1
    ;;

  upgrade-drill)
    if [ $# -lt 2 ]; then
      emit false 2 "用法：nc-task.sh upgrade-drill <旧.jar> <新.jar>"
      exit 2
    fi
    echo "== nc-task: upgrade-drill =="
    bash scripts/upgrade-rollback-drill.sh "$1" "$2" 2>&1 | tail -n 60
    rc="${PIPESTATUS[0]}"
    if [ "$rc" -eq 0 ]; then emit true 0 "PASS"; exit 0; fi
    emit false "$rc" "升级/回滚演练未全绿"; exit 1
    ;;

  status)
    detail='{}'
    code=""
    health="unknown"
    if command -v curl >/dev/null 2>&1; then
      code="$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "${NEX_GATEWAY_URL:-http://127.0.0.1:8080}/actuator/health" 2>/dev/null)"
      health="${code:-000}"
    fi
    ver="$(grep -E "^\s*version\s*=\s*'" build.gradle 2>/dev/null | head -1 | sed "s/.*'\([^']*\)'.*/\1/")"
    [ -z "$ver" ] && ver="$(sed -n "s/^versionNumber='\([^']*\)'.*/\1/p" nexus-core/nexus-core/src/main/resources/version.properties 2>/dev/null | head -1)"
    sha="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    detail="$(printf '{"healthHttp":"%s","version":"%s","gitSha":"%s"}' "$health" "${ver:-unknown}" "$sha")"
    echo "== nc-task: status =="
    echo "  health=$health  version=${ver:-unknown}  sha=$sha"
    case "$code" in
      000|"") emit false 1 "服务不可达" "$detail"; exit 1 ;;
      *) emit true 0 "服务可达（health HTTP $code）" "$detail"; exit 0 ;;
    esac
    ;;

  ""|-h|--help) usage ;;
  *) echo "未知动作：$ACTION" >&2; usage ;;
esac
