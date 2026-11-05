#!/usr/bin/env bash
# ============================================================
# drill-scenarios.sh — 多机演练场景执行器（docs/drill/multi-node-drill-plan.md §3）
#
# 用法:
#   bash scripts/drill/drill-scenarios.sh <场景> [选项]
#   场景: s1 | s2 | s3 | s4 | s5 | all
#
# 常用环境覆盖（继承 drill-common.sh，另见下）：
#   DRILL_TIER=0|1|2     DRILL_DRY_RUN=1（注入只打印不执行）
#   DRILL_OUTAGE_SEC=60  S1 停机窗（默认 60）
#   DRILL_WINDOW=12      证据采样窗数（默认 12）
#   DRILL_INTERVAL=5     采样间隔秒（默认 5）
#   CATCHUP_TIMEOUT=600  追平超时秒（候选口径 10min，见 plan §4 F3）
#   DRILL_KILL_MODE=stop|kill  S1 注入形态（默认 stop=SIGSTOP/CONT；kill=杀进程重启）
#   DRILL_NET=docker网络名（Tier-1） / DRILL_IPTABLES_IF=网卡名（Tier-2）
#   DRILL_CONTAINER_A/B/C（Tier-1 容器名）/ DRILL_PG_SSH（Tier-2 PG 主机）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/drill/drill-common.sh

SCENARIO="${1:-}"
[ -z "$SCENARIO" ] && { sed -n '2,20p' "$0" >&2; exit 2; }

DRILL_OUTAGE_SEC="${DRILL_OUTAGE_SEC:-60}"
DRILL_WINDOW="${DRILL_WINDOW:-12}"
DRILL_INTERVAL="${DRILL_INTERVAL:-5}"
CATCHUP_TIMEOUT="${CATCHUP_TIMEOUT:-600}"
DRILL_KILL_MODE="${DRILL_KILL_MODE:-stop}"
LOG_DIR="${LOG_DIR:-$(pwd)}"
WAIT_READY_TIMEOUT="${WAIT_READY_TIMEOUT:-120}"   # Tier-2 慢启动可调大

# --- 前置工具 ---

# wait_ready <node...> —— 轮询直到所有节点应答 /height（未起节点返回 -1 也算应答失败）
wait_ready() {
  local timeout="${1:-120}"; shift
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    local all=1 n h
    for n in "$@"; do
      h=$(height_of "$n")
      [ "$h" = "-1" ] && all=0
    done
    [ "$all" = "1" ] && return 0
    sleep 5
  done
  echo "[WARN] wait_ready 超时（${timeout}s）：$* 未全部应答" >&2
  return 1
}

node_pid() { cat ".drill-pids/node-$1.pid" 2>/dev/null || echo ""; }

# gate_ready —— dry-run 下放行（plan §6：无资源时 dry-run 用于验证编排，不要求真节点）
gate_ready() {
  if [ "$DRILL_DRY_RUN" = "1" ]; then
    echo "[DRY-RUN] 跳过节点就绪检查"
    return 0
  fi
  wait_ready "$WAIT_READY_TIMEOUT" a b c
}

# report_header <场景dir> —— report.md 表头（只写一次）
report_header() {
  local d="$1"
  [ -f "$d/report.md" ] || printf '| 判据 | 结论 | 证据/说明 |\n|------|------|----------|\n' > "$d/report.md"
}

# timeline 窗 <场景dir> <窗数> <间隔s>
sample_window() {
  local d="$1" n="$2" iv="$3" i row
  for i in $(seq 1 "$n"); do
    row=$(timeline_row "$d")
    echo "  [$(date '+%H:%M:%S')] A/B/C = $row"
    # 注意：末次迭代不得以 `[ ... ] && sleep` 收尾——该式在条件为假时返回 1，
    # 作为函数最后一条命令会触发 set -e 整体退出（s2 dry-run smoke 实抓）。
    if [ "$i" -lt "$n" ]; then sleep "$iv"; fi
  done
}

# err_count <node> —— ERROR 行数（扣良性 WARN 白名单）
err_count() {
  local logf="$LOG_DIR/$(node_log "$1")"
  [ -f "$logf" ] || { echo "0"; return; }
  grep -c 'ERROR' "$logf" 2>/dev/null | head -1 || echo "0"
}

# catchup_wait <滞后节点> <基准节点> —— 高度追平（超时返回1）
catchup_wait() {
  local laggard="$1" ref="$2" deadline=$((SECONDS + CATCHUP_TIMEOUT)) hl hr
  while [ "$SECONDS" -lt "$deadline" ]; do
    hl=$(height_of "$laggard"); hr=$(height_of "$ref")
    echo "  [CATCHUP] $laggard=$hl / $ref=$hr"
    if [ "$hl" != "-1" ] && [ "$hl" = "$hr" ]; then return 0; fi
    sleep 10
  done
  return 1
}

# ============================================================
# S1 停机-恢复闭环重跑（Tier-0 可端到端跑；收 #30）
# ============================================================
run_s1() {
  echo "=== S1 停机-恢复（mode=$DRILL_KILL_MODE, outage=${DRILL_OUTAGE_SEC}s）==="
  local D
  D=$(evidence_dir s1-stop-restart)
  run_meta "$D"; report_header "$D"

  gate_ready || { rubric "$D" FAIL S1-0 "三节点未就绪，场景中止"; return 1; }

  echo "--- 基线窗 ---"
  sample_window "$D" 6 "$DRILL_INTERVAL"

  local pid target
  target=c
  if [ "$DRILL_TIER" = "0" ]; then
    pid=$(node_pid "$target")
    if [ -z "$pid" ]; then
      rubric "$D" FAIL S1-1 "Tier-0 缺 pid（.drill-pids/node-$target.pid）— 先用 drill-node-up.sh 拉起"
      return 1
    fi
    if [ "$DRILL_KILL_MODE" = "stop" ]; then
      inject "SIGSTOP node-$target（pid=$pid）" kill -STOP "$pid"
    else
      inject "SIGKILL node-$target（pid=$pid）" kill -9 "$pid"
    fi
  elif [ "$DRILL_TIER" = "1" ]; then
    inject "docker stop ${DRILL_CONTAINER_C:?Tier-1 需 DRILL_CONTAINER_C}" docker stop "${DRILL_CONTAINER_C}"
  else
    inject "ssh 停 node-$target" ssh "${DRILL_NODE_HOST_C:?Tier-2 需 DRILL_NODE_HOST_C}" 'systemctl stop nexus-core'
  fi

  echo "--- 停机观测窗（${DRILL_OUTAGE_SEC}s）---"
  local end=$((SECONDS + DRILL_OUTAGE_SEC))
  while [ "$SECONDS" -lt "$end" ]; do
    timeline_row "$D" >/dev/null
    sleep "$DRILL_INTERVAL"
  done

  echo "--- 恢复 ---"
  if [ "$DRILL_TIER" = "0" ]; then
    if [ "$DRILL_KILL_MODE" = "stop" ]; then
      inject "SIGCONT node-$target（pid=$pid）" kill -CONT "$pid"
    else
      bash scripts/drill/drill-node-up.sh "$target"
    fi
  elif [ "$DRILL_TIER" = "1" ]; then
    inject "docker start $DRILL_CONTAINER_C" docker start "$DRILL_CONTAINER_C"
  else
    inject "ssh 起 node-$target" ssh "$DRILL_NODE_HOST_C" 'systemctl start nexus-core'
  fi

  echo "--- 追平窗（超时 ${CATCHUP_TIMEOUT}s，候选口径 plan §4 F3）---"
  if catchup_wait "$target" a; then
    rubric "$D" PASS S1-2 "node-$target 高度追平 node-a"
  else
    rubric "$D" FAIL S1-2 "超时未追平（F3 候选口径）——首查 ValidatorSetPersistence.replay()（PLAN-020 回归锚点）"
  fi

  local ec
  ec=$(err_count "$target")
  if [ "$ec" = "0" ]; then
    rubric "$D" PASS S1-3 "ERROR 日志 0 行（白名单已扣）"
  else
    rubric "$D" FAIL S1-3 "ERROR $ec 行，见 $LOG_DIR/$(node_log "$target")"
  fi
  echo "[S1] 完成 → $D"
}

# ============================================================
# S2 网络分区（Tier-1 docker / Tier-2 iptables；Tier-0 不支持）
# ============================================================
run_s2() {
  echo "=== S2 网络分区（C 隔离，{A,B}+{C}）==="
  local D
  D=$(evidence_dir s2-partition)
  run_meta "$D"; report_header "$D"

  if [ "$DRILL_TIER" = "0" ]; then
    rubric "$D" PENDING S2-0 "Tier-0 共享网络不支持分区注入——用 Tier-1/2 跑（DRILL_TIER=1|2）"
    echo "[S2] Tier-0 跳过"
    return 0
  fi

  gate_ready || { rubric "$D" FAIL S2-0 "未就绪"; return 1; }
  echo "--- 基线窗 ---"
  sample_window "$D" 6 "$DRILL_INTERVAL"

  if [ "$DRILL_TIER" = "1" ]; then
    inject "docker network disconnect ${DRILL_NET:?Tier-1 需 DRILL_NET} ${DRILL_CONTAINER_C:?Tier-1 需 DRILL_CONTAINER_C}" \
      docker network disconnect "$DRILL_NET" "$DRILL_CONTAINER_C"
  else
    inject "iptables 隔离 node-$DRILL_NODE_HOST_C（${DRILL_IPTABLES_IF:-eth0}）" \
      ssh "${DRILL_NODE_HOST_C:?Tier-2 需 DRILL_NODE_HOST_C}" \
      "iptables -I INPUT -i ${DRILL_IPTABLES_IF:-eth0} -j DROP && iptables -I OUTPUT -o ${DRILL_IPTABLES_IF:-eth0} -j DROP"
  fi

  echo "--- 分区观测窗（${DRILL_OUTAGE_SEC}s）---"
  local end=$((SECONDS + DRILL_OUTAGE_SEC))
  while [ "$SECONDS" -lt "$end" ]; do
    timeline_row "$D" >/dev/null
    sleep "$DRILL_INTERVAL"
  done

  if [ "$DRILL_TIER" = "1" ]; then
    inject "docker network connect $DRILL_NET $DRILL_CONTAINER_C" \
      docker network connect "$DRILL_NET" "$DRILL_CONTAINER_C"
  else
    inject "iptables 解除隔离" \
      ssh "$DRILL_NODE_HOST_C" \
      "iptables -D INPUT -i ${DRILL_IPTABLES_IF:-eth0} -j DROP; iptables -D OUTPUT -o ${DRILL_IPTABLES_IF:-eth0} -j DROP"
  fi

  echo "--- 恢复追平 ---"
  if catchup_wait c a; then
    rubric "$D" PASS S2-4 "分区恢复后 node-c 追平"
  else
    rubric "$D" FAIL S2-4 "恢复后未追平（对照 PLAN-025 补票/Anti-entropy）"
  fi
  # 伪造类 reason 判据（F5）由 sidecar 证据采集后人工/脚本复核，见 drill-evidence.sh
  rubric "$D" PENDING S2-5 "rejected-reason 伪造类零容忍（F5）——复核 $D/rejected-*.txt"
  echo "[S2] 完成 → $D"
}

# ============================================================
# S3 PG 主备切换（Tier-2 + PG-C 流复制备；仓内首次，产出=证据）
# ============================================================
run_s3() {
  echo "=== S3 PG 主备切换（Tier-2）==="
  local D
  D=$(evidence_dir s3-pg-failover)
  run_meta "$D"; report_header "$D"

  if [ "$DRILL_TIER" != "2" ]; then
    rubric "$D" PENDING S3-0 "S3 需 Tier-2（PG-C 流复制备）——当前 tier=$DRILL_TIER"
    echo "[S3] 跳过"
    return 0
  fi

  rubric "$D" PENDING S3-0 "仓内首次场景：执行半边需 ops 在场；本骨架只打注入命令"
  inject "停 PG-A" ssh "${DRILL_PG_SSH:?Tier-2 需 DRILL_PG_SSH}" 'pg_ctl stop -D $PGDATA -m fast'
  inject "提升 PG-C 为主" ssh "${DRILL_NODE_HOST_C:?}" 'pg_ctl promote -D $PGDATA'
  inject "node-A 连刺切到 PG-C（应用侧重启/连接池刷新，部署侧重）" \
    ssh "${DRILL_NODE_HOST_A:?}" 'systemctl restart nexus-core'
  rubric "$D" PENDING S3-1 "切换窗 /consensus/storage-status 降级 + 无双重最终化 ——人工按 plan §3 S3 判据复核"
  echo "[S3] 完成 → $D（执行半边证据）"
}

# ============================================================
# S4 数据丢失重放（Tier-0 可跑；收 #30 物理维度）
# ============================================================
run_s4() {
  echo "=== S4 数据丢失重放（node-c 全毁重建）==="
  local D
  D=$(evidence_dir s4-disk-loss)
  run_meta "$D"; report_header "$D"

  gate_ready || { rubric "$D" FAIL S4-0 "未就绪"; return 1; }

  if [ "$DRILL_TIER" = "0" ] || [ "$DRILL_TIER" = "1" ]; then
    local pid
    pid=$(node_pid c)
    [ -n "$pid" ] && inject "停 node-c（pid=$pid）" kill -9 "$pid"
    # cache-dir 清除脚本化（演练 cache 目录独立，不触碰共享 PG 库）；
    # PG 库 DROP 一律留给执行半边人工执行——自动化 DROP 库的危险面不进骨架。
    inject "清 node-c cache-dir（./$(node_cache c)）" rm -rf "./$(node_cache c)"
  else
    inject "ssh 停 node-c + 清盘" ssh "${DRILL_NODE_HOST_C:?}" 'systemctl stop nexus-core && rm -rf /var/lib/nexus/cache'
  fi
  rubric "$D" PENDING S4-1 "PG 库 DROP + 重启 node-c 由执行半边人工完成（DROP 库不自动化，见 plan §3 S4）"

  echo "--- 重启 node-c（无种子）---"
  if [ "$DRILL_TIER" = "0" ]; then
    bash scripts/drill/drill-node-up.sh c
  else
    inject "ssh 起 node-c（无种子）" ssh "$DRILL_NODE_HOST_C" 'systemctl start nexus-core'
  fi

  if catchup_wait c a; then
    rubric "$D" PASS S4-2 "node-c 自注册+回放后追平（对照 PLAN-042 同主机基线）"
  else
    rubric "$D" FAIL S4-2 "未追平——首查自注册链路（PLAN-042 20s 基线）"
  fi
  echo "[S4] 完成 → $D"
}

# ============================================================
# S5 C7 真机 registered 取证（Tier-0 可跑；收 C7）
# ============================================================
run_s5() {
  echo "=== S5 registered=true 取证 ==="
  local D
  D=$(evidence_dir s5-registered)
  run_meta "$D"; report_header "$D"

  gate_ready || { rubric "$D" FAIL S5-0 "未就绪"; return 1; }

  echo "--- 基线窗（$DRILL_WINDOW × ${DRILL_INTERVAL}s）---"
  sample_window "$D" "$DRILL_WINDOW" "$DRILL_INTERVAL"

  local n logf
  for n in a b c; do
    logf="$LOG_DIR/$(node_log "$n")"
    if [ -f "$logf" ]; then
      {
        echo "# node-$n Validator-set add registered= 分布"
        grep -oE 'Validator-set add .*registered=(true|false)' "$logf" \
          | grep -oE 'registered=(true|false)' | sort | uniq -c || echo "0 行命中"
      } > "$D/registered-$n.txt"
    else
      echo "日志缺失: $logf" > "$D/registered-$n.txt"
    fi
  done

  local false_total=0
  for n in a b c; do
    local c
    c=$(grep -c 'registered=false' "$D/registered-$n.txt" 2>/dev/null || echo 0)
    false_total=$((false_total + c))
  done
  if [ "$false_total" = "0" ]; then
    rubric "$D" PASS S5-1 "三节点 registered 全 true 对照 dev-cluster 口径"
  else
    rubric "$D" FAIL S5-1 "出现 registered=false（$false_total 节点行）——落证据升级 nex（共识语义）"
  fi
  echo "[S5] 完成 → $D"
}

# ============================================================
case "$SCENARIO" in
  s1) run_s1 ;;
  s2) run_s2 ;;
  s3) run_s3 ;;
  s4) run_s4 ;;
  s5) run_s5 ;;
  all) run_s1; run_s2; run_s3; run_s4; run_s5
      echo "[ALL] 汇总 → $DRILL_EVIDENCE_ROOT/$RUN_ID/summary.md（人工按 plan §5 写）"
      ;;
  *) echo "未知场景: $SCENARIO（s1|s2|s3|s4|s5|all）" >&2; exit 2 ;;
esac
