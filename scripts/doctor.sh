#!/usr/bin/env bash
# ============================================================================
# doctor.sh — 交付自证：一条命令体检（Delivery Self-Check）
# ============================================================================
# 用途：私有化交付方/客户拿到交付包后，先跑本脚本，得到一份**可复现的现状结论**，
#       而不是「照 README 猜能不能跑」。
#
# 设计原则（对齐本仓既有纪律）：
#   1. **缺前置 ≠ 失败**：Docker/网络/主树/Gradle 缺失时，对应项记 SKIP 并写明缺什么，
#      绝不用「环境不全」把结论染成 FAIL（否则交付方拿到一个红字就以为包坏了）。
#   2. **不掩盖真失败**：真红一律 FAIL 并保留完整日志路径。
#   3. **只读**：本脚本不改仓库、不写工作区、不启动服务（重项需显式开关，见 --help）。
#   4. **离线优先**：默认全部检查都不需要网络（依赖扫描类只在有本地产物时跑）。
#
# 退出码：
#   0 = 无 FAIL（有 PASS 或全部 SKIP 也算通过）
#   1 = 至少一项 FAIL
#   2 = 环境不满足（连 git / python 都没有，无法做体检）
#
# 用法：
#   bash scripts/doctor.sh                 # 默认体检（离线、无副作用）
#   bash scripts/doctor.sh --quick         # 只跑秒级项（跳过往返/大扫描）
#   bash scripts/doctor.sh --all           # 含重项（boot / acceptance / compose）
#   bash scripts/doctor.sh --only <id>     # 只跑指定项（可重复；--list 看 id）
#   bash scripts/doctor.sh --list          # 列出全部检查项
#   bash scripts/doctor.sh --with-boot     # 追加 dev 档启动验收（需 Gradle + 2GB 内存）
#   bash scripts/doctor.sh --with-acceptance  # 追加端到端验收（需已起 gateway + API Key）
#   bash scripts/doctor.sh --with-compose  # 追加 docker compose 配置校验（需 Docker）
#   bash scripts/doctor.sh --json          # 额外输出机器可读汇总（JSON）
#
# 日志：默认写到系统临时目录（NEX_DOCTOR_LOG_DIR 可覆盖），不落仓库。
# ============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

QUICK=0
WITH_BOOT=0
WITH_ACCEPTANCE=0
WITH_COMPOSE=0
JSON_OUT=0
LIST_ONLY=0
ONLY_IDS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1 ;;
    --all) WITH_BOOT=1; WITH_ACCEPTANCE=1; WITH_COMPOSE=1 ;;
    --with-boot) WITH_BOOT=1 ;;
    --with-acceptance) WITH_ACCEPTANCE=1 ;;
    --with-compose) WITH_COMPOSE=1 ;;
    --json) JSON_OUT=1 ;;
    --list) LIST_ONLY=1 ;;
    --only) shift; ONLY_IDS="$ONLY_IDS $1" ;;
    -h|--help) sed -n '20,60p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
  shift
done

TS="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="${NEX_DOCTOR_LOG_DIR:-${TMPDIR:-/tmp}/nexus-doctor-$TS}"
mkdir -p "$LOG_DIR" || { echo "无法创建日志目录 $LOG_DIR" >&2; exit 2; }
SUMMARY="$LOG_DIR/summary.tsv"
: > "$SUMMARY"

N_PASS=0; N_FAIL=0; N_SKIP=0; FAILED_IDS=""; SKIPPED_IDS=""; PASSED_IDS=""

have() { command -v "$1" >/dev/null 2>&1; }

PY=""
if have python3; then PY="python3"; elif have python; then PY="python"; fi

want() { # want <id>：无 --only 则一律要；有 --only 则只跑列出的
  [ -z "$ONLY_IDS" ] && return 0
  case " $ONLY_IDS " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

record() { # record <id> <status> <label> <note> <logfile> <seconds>
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" >> "$SUMMARY"
  case "$2" in
    PASS) N_PASS=$((N_PASS + 1)); PASSED_IDS="$PASSED_IDS $1" ;;
    FAIL) N_FAIL=$((N_FAIL + 1)); FAILED_IDS="$FAILED_IDS $1" ;;
    *)    N_SKIP=$((N_SKIP + 1)); SKIPPED_IDS="$SKIPPED_IDS $1" ;;
  esac
}

run() { # run <id> <label> <cmd...>
  id="$1"; label="$2"; shift 2
  want "$id" || return 0
  log="$LOG_DIR/$id.log"
  t0="$(date +%s)"
  "$@" >"$log" 2>&1
  rc=$?
  t1="$(date +%s)"
  if [ "$rc" -eq 0 ]; then
    record "$id" PASS "$label" "rc=0" "$log" "$((t1 - t0))s"
  else
    record "$id" FAIL "$label" "rc=$rc" "$log" "$((t1 - t0))s"
  fi
}

skip() { # skip <id> <label> <reason>
  id="$1"; label="$2"; reason="$3"
  want "$id" || return 0
  record "$id" SKIP "$label" "$reason" "-" "0s"
}

section() { printf '\n== %s ==\n' "$1"; }

# ---------------------------------------------------------------------------
# 0. 前置环境
# ---------------------------------------------------------------------------
section "前置环境"

if ! have git; then
  echo "FAIL: 找不到 git，无法做交付体检（环境不满足）" >&2
  exit 2
fi
if [ -z "$PY" ]; then
  echo "FAIL: 找不到 python/python3，多数自检无法运行（环境不满足）" >&2
  exit 2
fi

if want env; then
  echo "git   : $(git --version)"
  echo "python: $($PY --version 2>&1)"
  if have java; then
    echo "java  : $(java -version 2>&1 | head -n1)"
  else
    echo "java  : (缺失)"
  fi
  if have docker; then
    echo "docker: $(docker --version 2>&1 | head -n1)"
  else
    echo "docker: (缺失 ⇒ 容器类检查记 SKIP)"
  fi
  echo "仓库  : $ROOT @ $(git rev-parse --short HEAD 2>/dev/null) ($(git rev-parse --abbrev-ref HEAD 2>/dev/null))"
  dirty="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$dirty" != "0" ]; then
    echo "工作区: 有 $dirty 处未提交改动（不判 FAIL，仅提示；体检只读）"
  else
    echo "工作区: 干净"
  fi
fi

# ---------------------------------------------------------------------------
# 1. 离线自检（默认全跑）
# ---------------------------------------------------------------------------
section "仓库自检（离线）"

if want version-consistency; then
  run version-consistency "版本口径一致性（build.gradle/version.properties/README/CHANGELOG）" \
    bash scripts/check-version-consistency.sh
else
  :; fi

if want prod-env; then
  run prod-env "prod env 强制变量清单（.env.prod.example ↔ docker-compose.prod.yml）" \
    bash scripts/check-prod-env.sh
fi

if want license-headers; then
  run license-headers "版权头与许可声明一致性" bash scripts/check-license-headers.sh
fi

if want code-hygiene; then
  run code-hygiene "代码卫生棘轮" $PY scripts/check-code-hygiene.py
fi

if want packaging; then
  run packaging "打包一致性" $PY scripts/check-packaging-consistency.py
fi

if want scheduler-lock; then
  run scheduler-lock "调度锁（ShedLock）签名守护" $PY scripts/check-scheduler-lock.py
fi

if want event-wiring; then
  run event-wiring "事件接线（发布↔监听双向闭合 + 豁免账）" $PY scripts/check-event-wiring.py
fi

if want ddl-auto-gate; then
  if [ -f scripts/check-ddl-auto-gate.py ]; then
    run ddl-auto-gate "ddl-auto 白名单门禁（生产档禁止 update）" $PY scripts/check-ddl-auto-gate.py
  else
    skip ddl-auto-gate "ddl-auto 白名单门禁" "本版本无 scripts/check-ddl-auto-gate.py"
  fi
fi

if want license-policy; then
  if [ -f Cargo.lock ]; then
    run license-policy "Rust 依赖许可策略" $PY scripts/check-license-policy.py
  else
    skip license-policy "Rust 依赖许可策略" "无 Cargo.lock（未取 Rust 子工程）"
  fi
fi

if want trivyignore-staleness; then
  # 该脚本必须喂 Trivy 扫描产物（--scan LABEL=JSON），否则「复核没跑成」；
  # 本地无产物时记 SKIP（不判 FAIL），产物可用 NEX_TRIVY_SCANS="label=path label2=path2" 指定。
  scans="${NEX_TRIVY_SCANS:-}"
  if [ -z "$scans" ]; then
    for cand in trivy-staleness-*.json build/trivy-staleness-*.json; do
      if [ -f "$cand" ]; then
        scans="local=$(cd "$(dirname "$cand")" && pwd)/$(basename "$cand")"
        break
      fi
    done
  fi
  if [ -n "$scans" ]; then
    set -- ; for pair in $scans; do set -- "$@" --scan "$pair"; done
    run trivyignore-staleness ".trivyignore 豁免时效复核" $PY scripts/check-trivyignore-staleness.py --ignore .trivyignore "$@"
  else
    skip trivyignore-staleness ".trivyignore 豁免时效复核" "缺 Trivy 扫描产物（需 --scan LABEL=JSON；可用 NEX_TRIVY_SCANS 指定）"
  fi
fi

if want migration-ratchet; then
  base=""
  for cand in origin/master FETCH_HEAD master; do
    if git rev-parse --verify "$cand" >/dev/null 2>&1; then base="$cand"; break; fi
  done
  if [ -n "$base" ]; then
    run migration-ratchet "迁移号段棘轮（对照 $base）" $PY scripts/check-migration-version-ratchet.py --base "$base"
  else
    skip migration-ratchet "迁移号段棘轮" "无可对照的 git 引用（无 origin/master/FETCH_HEAD/master）"
  fi
fi

if want board-inbox; then
  run board-inbox "留言板签收门禁（无逾期未签收必读项）" $PY scripts/check-agent-board-inbox.py
fi

if want main-tree-hygiene; then
  main_tree="${NEX_MAIN_TREE:-F:/Nexus/NexusChain}"
  if [ -d "$main_tree/.git" ] || [ -f "$main_tree/.git" ]; then
    log="$LOG_DIR/main-tree-hygiene.log"
    t0="$(date +%s)"
    bash scripts/check-main-tree-board-hygiene.sh "$main_tree" >"$log" 2>&1
    rc=$?; t1="$(date +%s)"
    if [ "$rc" -eq 0 ]; then
      record main-tree-hygiene PASS "共享主树板面卫生（无未提交 board 文件）" "rc=0" "$log" "$((t1 - t0))s"
    elif [ "$rc" -eq 2 ]; then
      # 脚本约定：2 = 主树路径不可用（环境问题），不是卫生问题 ⇒ 记 SKIP
      record main-tree-hygiene SKIP "共享主树板面卫生（无未提交 board 文件）" "主树不可用（rc=2）" "$log" "$((t1 - t0))s"
    else
      record main-tree-hygiene FAIL "共享主树板面卫生（无未提交 board 文件）" "rc=$rc" "$log" "$((t1 - t0))s"
    fi
  else
    skip main-tree-hygiene "共享主树板面卫生" "主树路径不可用：$main_tree（可用 NEX_MAIN_TREE 指定）"
  fi
fi

if want mpc-tier-policy; then
  run mpc-tier-policy "MPC 分层策略" bash scripts/check-mpc-tier-policy.sh
fi

# ---------------------------------------------------------------------------
# 2. 重项（显式开关才跑）
# ---------------------------------------------------------------------------
if [ "$WITH_BOOT" -eq 1 ]; then
  section "重项：dev 档启动验收（需 Gradle + 内存）"
  if want smoke-dev-boot; then
    if have java && [ -x ./gradlew ] || [ -f ./gradlew ]; then
      run smoke-dev-boot "dev 档裸启动验收（bootRun + health UP）" bash scripts/smoke-dev-boot.sh
    else
      skip smoke-dev-boot "dev 档裸启动验收" "缺 java 或 gradlew"
    fi
  fi
fi

if [ "$WITH_ACCEPTANCE" -eq 1 ]; then
  section "重项：端到端验收（需已起 gateway + 商户 API Key）"
  if want acceptance-e2e; then
    if [ -n "${NEX_API_KEY:-}" ]; then
      run acceptance-e2e "端到端验收（下单→查询→结算确认→对账）" bash scripts/acceptance-e2e.sh
    else
      skip acceptance-e2e "端到端验收" "未设 NEX_API_KEY（需 VERIFIED 商户 API Key；见 docs/runbooks/acceptance-e2e.md §1.3）"
    fi
  fi
fi

if [ "$WITH_COMPOSE" -eq 1 ]; then
  section "重项：compose 配置校验（需 Docker）"
  if want compose-config; then
    if have docker; then
      run compose-config "docker compose 配置渲染校验" docker compose -f docker-compose.yml config
    else
      skip compose-config "docker compose 配置渲染校验" "缺 docker"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 3. 汇总
# ---------------------------------------------------------------------------
section "汇总"

printf '%-22s %-6s %-8s %s\n' "检查项" "结果" "耗时" "备注"
printf '%-22s %-6s %-8s %s\n' "----------------------" "------" "--------" "------------------------"
while IFS="$(printf '\t')" read -r id st label note log dur; do
  [ -z "$id" ] && continue
  printf '%-22s %-6s %-8s %s\n' "$id" "$st" "$dur" "$note"
done < "$SUMMARY"

echo
echo "PASS=$N_PASS  FAIL=$N_FAIL  SKIP=$N_SKIP"
[ -n "$FAILED_IDS" ] && echo "失败项:$FAILED_IDS"
[ -n "$SKIPPED_IDS" ] && echo "跳过项:$SKIPPED_IDS（缺前置，不等于通过）"
echo "明细日志: $LOG_DIR"

if [ "$JSON_OUT" -eq 1 ]; then
  {
    printf '{"generated":"%s","repo":"%s","head":"%s","pass":%s,"fail":%s,"skip":%s,"items":[' \
      "$TS" "$ROOT" "$(git rev-parse HEAD 2>/dev/null)" "$N_PASS" "$N_FAIL" "$N_SKIP"
    first=1
    while IFS="$(printf '\t')" read -r id st label note log dur; do
      [ -z "$id" ] && continue
      [ $first -eq 0 ] && printf ','
      first=0
      printf '{"id":"%s","status":"%s","label":"%s","note":"%s","log":"%s","seconds":"%s"}' \
        "$id" "$st" "$label" "$note" "$log" "$dur"
    done < "$SUMMARY"
    printf ']}\n'
  } > "$LOG_DIR/summary.json"
  echo "JSON: $LOG_DIR/summary.json"
fi

if [ "$N_FAIL" -gt 0 ]; then exit 1; fi
exit 0
