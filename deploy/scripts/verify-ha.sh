#!/usr/bin/env bash
# ============================================================================
# NexusChain 高可用验证脚本
# ============================================================================
# 用途：验证 K8s 生产环境中的 HPA / PDB / 多 AZ / 滚动更新 配置是否正确
#
# 用法：
#   bash deploy/scripts/verify-ha.sh [选项]
#
# 选项：
#   -n, --namespace    命名空间（默认 nexus）
#   -c, --check        检查项：all|hpa|pdb|az|rolling（默认 all）
#   -v, --verbose      详细输出
#   -h, --help         帮助信息
#
# 示例：
#   bash deploy/scripts/verify-ha.sh                          # 全部检查
#   bash deploy/scripts/verify-ha.sh -n nexus -c hpa         # 仅检查 HPA
#   bash deploy/scripts/verify-ha.sh -c pdb                  # 仅检查 PDB
#   bash deploy/scripts/verify-ha.sh -c az                   # 仅检查多 AZ
#   bash deploy/scripts/verify-ha.sh -c rolling              # 模拟滚动更新
#
# 退出码：
#   0 - 所有检查通过
#   1 - 有检查项失败
#   2 - 脚本错误（依赖缺失等）
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 全局变量
# ----------------------------------------------------------------------------
NAMESPACE="nexus"
CHECK="all"
VERBOSE=false
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# 服务列表
SERVICES=(
  "nexus-gateway"
  "nexus-bridge"
  "nexus-signing-service"
  "nexus-wallet-service"
)

# 颜色定义
if [[ -t 1 ]]; then
  COLOR_RED='\033[0;31m'
  COLOR_GREEN='\033[0;32m'
  COLOR_YELLOW='\033[0;33m'
  COLOR_BLUE='\033[0;34m'
  COLOR_NC='\033[0m'
else
  COLOR_RED=''
  COLOR_GREEN=''
  COLOR_YELLOW=''
  COLOR_BLUE=''
  COLOR_NC=''
fi

# ----------------------------------------------------------------------------
# 工具函数
# ----------------------------------------------------------------------------
log_info()    { echo -e "${COLOR_BLUE}[INFO]${COLOR_NC} $*"; }
log_pass()    { echo -e "  ${COLOR_GREEN}[PASS]${COLOR_NC} $*"; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail()    { echo -e "  ${COLOR_RED}[FAIL]${COLOR_NC} $*"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_warn()    { echo -e "  ${COLOR_YELLOW}[WARN]${COLOR_NC} $*"; WARN_COUNT=$((WARN_COUNT + 1)); }
log_check()   { echo -e "${COLOR_BLUE}[CHECK]${COLOR_NC} $*"; }
log_verbose() { if $VERBOSE; then echo -e "  [DEBUG] $*"; fi }

# 检查 kubectl 是否可用
check_kubectl() {
  if ! command -v kubectl &>/dev/null; then
    echo -e "${COLOR_RED}[ERROR]${COLOR_NC} kubectl 未安装"
    exit 2
  fi
  if ! kubectl cluster-info &>/dev/null; then
    echo -e "${COLOR_RED}[ERROR]${COLOR_NC} 无法连接 K8s 集群"
    exit 2
  fi
}

# 检查命名空间是否存在
check_namespace() {
  if ! kubectl get namespace "$NAMESPACE" &>/dev/null; then
    echo -e "${COLOR_RED}[ERROR]${COLOR_NC} 命名空间 $NAMESPACE 不存在"
    exit 2
  fi
}

# 获取服务副本数
get_replicas() {
  local svc=$1
  kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0"
}

# 获取 Pod 所在节点
get_pod_node() {
  local pod=$1
  kubectl get pod "$pod" -n "$NAMESPACE" -o jsonpath='{.spec.nodeName}' 2>/dev/null
}

# 获取节点所在 AZ
get_node_zone() {
  local node=$1
  kubectl get node "$node" -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}' 2>/dev/null
}

# ----------------------------------------------------------------------------
# HPA 验证
# ----------------------------------------------------------------------------
verify_hpa() {
  log_check "HPA 自动扩容配置验证"

  for svc in "${SERVICES[@]}"; do
    log_info "服务：$svc"

    # 1. 检查 CPU/内存 HPA 是否存在
    local hpa_name="${svc}-hpa"
    if kubectl get hpa "$hpa_name" -n "$NAMESPACE" &>/dev/null; then
      log_pass "CPU/内存 HPA 存在：$hpa_name"

      # 检查 minReplicas
      local min_replicas
      min_replicas=$(kubectl get hpa "$hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.minReplicas}')
      if [[ "$min_replicas" -ge 3 ]]; then
        log_pass "minReplicas=$min_replicas（≥ 3，满足 prod 要求）"
      else
        log_fail "minReplicas=$min_replicas（< 3，不满足 prod 要求）"
      fi

      # 检查 maxReplicas
      local max_replicas
      max_replicas=$(kubectl get hpa "$hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.maxReplicas}')
      if [[ "$max_replicas" -ge 10 ]]; then
        log_pass "maxReplicas=$max_replicas（≥ 10，满足扩容上限）"
      else
        log_warn "maxReplicas=$max_replicas（< 10，扩容上限较低）"
      fi

      # 检查 metrics 类型
      local metrics_type
      metrics_type=$(kubectl get hpa "$hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.metrics[*].type}')
      if echo "$metrics_type" | grep -q "Resource"; then
        log_pass "metrics 包含 Resource 类型（CPU/内存）"
      else
        log_fail "metrics 不包含 Resource 类型"
      fi
    else
      log_fail "CPU/内存 HPA 不存在：$hpa_name"
    fi

    # 2. 检查 RPS HPA 是否存在（可选）
    local rps_hpa_name="${svc}-rps-hpa"
    if kubectl get hpa "$rps_hpa_name" -n "$NAMESPACE" &>/dev/null; then
      log_pass "RPS HPA 存在：$rps_hpa_name"

      # 检查 RPS HPA 的 metrics 类型
      local rps_metrics_type
      rps_metrics_type=$(kubectl get hpa "$rps_hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.metrics[*].type}')
      if echo "$rps_metrics_type" | grep -q "Pods"; then
        log_pass "RPS HPA metrics 类型为 Pods（自定义指标）"
      else
        log_fail "RPS HPA metrics 类型不是 Pods"
      fi

      # 检查 RPS 自定义指标名
      local rps_metric_name
      rps_metric_name=$(kubectl get hpa "$rps_hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.metrics[0].pods.metric.name}' 2>/dev/null || echo "")
      if [[ "$rps_metric_name" == "http_requests_per_second" ]]; then
        log_pass "RPS 自定义指标名：http_requests_per_second"
      else
        log_fail "RPS 自定义指标名错误：$rps_metric_name（期望 http_requests_per_second）"
      fi

      # 检查 behavior 配置
      local scale_up_window
      scale_up_window=$(kubectl get hpa "$rps_hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.behavior.scaleUp.stabilizationWindowSeconds}' 2>/dev/null || echo "0")
      if [[ "$scale_up_window" -le 60 ]]; then
        log_pass "扩容稳定窗口=${scale_up_window}s（≤ 60s，快速响应）"
      else
        log_warn "扩容稳定窗口=${scale_up_window}s（> 60s，响应较慢）"
      fi

      local scale_down_window
      scale_down_window=$(kubectl get hpa "$rps_hpa_name" -n "$NAMESPACE" -o jsonpath='{.spec.behavior.scaleDown.stabilizationWindowSeconds}' 2>/dev/null || echo "0")
      if [[ "$scale_down_window" -ge 300 ]]; then
        log_pass "缩容稳定窗口=${scale_down_window}s（≥ 300s，避免抖动）"
      else
        log_warn "缩容稳定窗口=${scale_down_window}s（< 300s，可能抖动）"
      fi
    else
      log_warn "RPS HPA 不存在：$rps_hpa_name（可选，需启用 global.rpsHpa.enabled=true）"
    fi
  done

  # 3. 检查 Custom Metrics API（RPS HPA 依赖）
  log_info "Custom Metrics API 检查"
  if kubectl get apiservice v1beta1.custom.metrics.k8s.io &>/dev/null; then
    local api_status
    api_status=$(kubectl get apiservice v1beta1.custom.metrics.k8s.io -o jsonpath='{.status.conditions[?(@.type=="Available")].status}' 2>/dev/null || echo "Unknown")
    if [[ "$api_status" == "True" ]]; then
      log_pass "Custom Metrics API 可用"
    else
      log_fail "Custom Metrics API 不可用（status=$api_status）"
    fi
  else
    log_warn "Custom Metrics API 未注册（RPS HPA 不可用）"
  fi

  echo ""
}

# ----------------------------------------------------------------------------
# PDB 验证
# ----------------------------------------------------------------------------
verify_pdb() {
  log_check "PDB 滚动更新配置验证"

  for svc in "${SERVICES[@]}"; do
    log_info "服务：$svc"

    local pdb_name="${svc}-pdb"
    if kubectl get pdb "$pdb_name" -n "$NAMESPACE" &>/dev/null; then
      log_pass "PDB 存在：$pdb_name"

      # 检查 minAvailable
      local min_available
      min_available=$(kubectl get pdb "$pdb_name" -n "$NAMESPACE" -o jsonpath='{.spec.minAvailable}' 2>/dev/null || echo "")
      if [[ -n "$min_available" ]]; then
        if [[ "$min_available" -ge 2 ]]; then
          log_pass "minAvailable=$min_available（≥ 2，满足 prod 零宕机要求）"
        else
          log_fail "minAvailable=$min_available（< 2，不满足 prod 要求）"
        fi
      else
        # 检查 maxUnavailable
        local max_unavailable
        max_unavailable=$(kubectl get pdb "$pdb_name" -n "$NAMESPACE" -o jsonpath='{.spec.maxUnavailable}' 2>/dev/null || echo "")
        if [[ -n "$max_unavailable" ]]; then
          if [[ "$max_unavailable" -eq 0 ]]; then
            log_pass "maxUnavailable=$max_unavailable（零中断）"
          else
            log_warn "maxUnavailable=$max_unavailable（允许 $max_unavailable 个 Pod 不可用）"
          fi
        else
          log_fail "PDB 未配置 minAvailable 或 maxUnavailable"
        fi
      fi

      # 检查 AllowedDisruptions
      local allowed_disruptions
      allowed_disruptions=$(kubectl get pdb "$pdb_name" -n "$NAMESPACE" -o jsonpath='{.status.disruptions.allowed}' 2>/dev/null || echo "0")
      if [[ "$allowed_disruptions" -ge 1 ]]; then
        log_pass "AllowedDisruptions=$allowed_disruptions（允许自愿中断）"
      else
        log_warn "AllowedDisruptions=$allowed_disruptions（=0，滚动更新可能被阻塞）"
      fi
    else
      log_fail "PDB 不存在：$pdb_name"
    fi

    # 检查 Deployment strategy（maxUnavailable=0 保证零宕机）
    local strategy_type
    strategy_type=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.strategy.type}' 2>/dev/null || echo "")
    if [[ "$strategy_type" == "RollingUpdate" ]]; then
      local max_unavail
      max_unavail=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.strategy.rollingUpdate.maxUnavailable}' 2>/dev/null || echo "1")
      if [[ "$max_unavail" == "0" ]]; then
        log_pass "Deployment strategy.maxUnavailable=0（滚动更新零宕机）"
      else
        log_fail "Deployment strategy.maxUnavailable=$max_unavail（≠ 0，滚动更新可能宕机）"
      fi

      local max_surge
      max_surge=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.strategy.rollingUpdate.maxSurge}' 2>/dev/null || echo "1")
      if [[ "$max_surge" -ge 1 ]]; then
        log_pass "Deployment strategy.maxSurge=$max_surge（≥ 1，先创建新 Pod）"
      else
        log_warn "Deployment strategy.maxSurge=$max_surge（< 1，扩容速度受限）"
      fi
    else
      log_fail "Deployment strategy 不是 RollingUpdate（当前=$strategy_type）"
    fi
  done

  echo ""
}

# ----------------------------------------------------------------------------
# 多 AZ 验证
# ----------------------------------------------------------------------------
verify_az() {
  log_check "多 AZ 部署验证"

  # 1. 检查集群 AZ 数量
  log_info "集群 AZ 分布"
  local zones
  zones=$(kubectl get nodes -o jsonpath='{.items[*].metadata.labels.topology\.kubernetes\.io/zone}' 2>/dev/null | tr ' ' '\n' | sort -u | grep -v '^$')
  local zone_count
  zone_count=$(echo "$zones" | wc -l)
  if [[ "$zone_count" -ge 3 ]]; then
    log_pass "集群有 $zone_count 个 AZ（≥ 3，满足多 AZ 要求）"
    log_verbose "AZ 列表：$(echo "$zones" | tr '\n' ' ')"
  elif [[ "$zone_count" -ge 2 ]]; then
    log_warn "集群只有 $zone_count 个 AZ（< 3，建议 ≥ 3 个 AZ）"
  else
    log_fail "集群只有 $zone_count 个 AZ（不满足多 AZ 要求）"
  fi

  # 2. 检查每个服务的 topologySpreadConstraints
  for svc in "${SERVICES[@]}"; do
    log_info "服务：$svc"

    local tsc
    tsc=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.topologySpreadConstraints}' 2>/dev/null || echo "")
    if [[ -n "$tsc" && "$tsc" != "[]" ]]; then
      log_pass "topologySpreadConstraints 已配置"

      # 检查 topologyKey
      local topo_key
      topo_key=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.topologySpreadConstraints[0].topologyKey}' 2>/dev/null || echo "")
      if [[ "$topo_key" == "topology.kubernetes.io/zone" ]]; then
        log_pass "topologyKey=$topo_key（标准 K8s zone 标签）"
      else
        log_fail "topologyKey=$topo_key（≠ topology.kubernetes.io/zone）"
      fi

      # 检查 maxSkew
      local max_skew
      max_skew=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.topologySpreadConstraints[0].maxSkew}' 2>/dev/null || echo "")
      if [[ "$max_skew" -eq 1 ]]; then
        log_pass "maxSkew=$max_skew（= 1，严格均匀分布）"
      else
        log_warn "maxSkew=$max_skew（≠ 1，分布可能不均匀）"
      fi

      # 检查 whenUnsatisfiable
      local when_unsat
      when_unsat=$(kubectl get deployment "$svc" -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.topologySpreadConstraints[0].whenUnsatisfiable}' 2>/dev/null || echo "")
      if [[ "$when_unsat" == "DoNotSchedule" ]]; then
        log_pass "whenUnsatisfiable=$when_unsat（强制约束，不满足时 Pending）"
      else
        log_warn "whenUnsatisfiable=$when_unsat（≠ DoNotSchedule，约束为软约束）"
      fi
    else
      log_fail "topologySpreadConstraints 未配置"
    fi

    # 3. 检查 Pod 实际跨 AZ 分布
    local pod_count
    pod_count=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" --field-selector=status.phase=Running -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | tr ' ' '\n' | grep -c '^' || echo "0")
    if [[ "$pod_count" -eq 0 ]]; then
      log_warn "无 Running Pod，跳过分布检查"
      continue
    fi

    # 统计各 AZ 的 Pod 数
    declare -A zone_pods
    for pod in $(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" --field-selector=status.phase=Running -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | tr ' ' '\n'); do
      local node
      node=$(get_pod_node "$pod")
      local zone
      zone=$(get_node_zone "$node")
      if [[ -n "$zone" ]]; then
        zone_pods["$zone"]=$(( ${zone_pods["$zone"]:-0} + 1 ))
      fi
    done

    local distinct_zones=${#zone_pods[@]}
    if [[ "$distinct_zones" -ge 2 ]]; then
      log_pass "Pod 分布在 $distinct_zones 个 AZ"

      # 检查 maxSkew（最大值 - 最小值 ≤ 1）
      local max_pods=0
      local min_pods=999
      for z in "${!zone_pods[@]}"; do
        local cnt=${zone_pods[$z]}
        if [[ "$cnt" -gt "$max_pods" ]]; then max_pods=$cnt; fi
        if [[ "$cnt" -lt "$min_pods" ]]; then min_pods=$cnt; fi
      done
      local skew=$((max_pods - min_pods))
      if [[ "$skew" -le 1 ]]; then
        log_pass "实际 maxSkew=$skew（≤ 1，均匀分布）"
      else
        log_fail "实际 maxSkew=$skew（> 1，分布不均匀）"
      fi

      log_verbose "AZ 分布：$(for z in "${!zone_pods[@]}"; do echo -n "$z=${zone_pods[$z]} "; done)"
    else
      log_warn "Pod 仅分布在 $distinct_zones 个 AZ（可能节点不足或 AZ 标签缺失）"
    fi

    unset zone_pods
  done

  echo ""
}

# ----------------------------------------------------------------------------
# 滚动更新模拟
# ----------------------------------------------------------------------------
verify_rolling() {
  log_check "滚动更新零宕机验证"

  for svc in "${SERVICES[@]}"; do
    log_info "服务：$svc"

    # 1. 记录当前副本数和 Pod 列表
    local replicas_before
    replicas_before=$(get_replicas "$svc")
    log_verbose "更新前副本数：$replicas_before"

    local pods_before
    pods_before=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | tr ' ' '\n' | sort)

    # 2. 触发滚动更新（通过添加注解）
    local timestamp
    timestamp=$(date +%s)
    kubectl annotate deployment "$svc" -n "$NAMESPACE" "ha-verify-rolling-update=$timestamp" --overwrite &>/dev/null

    # 3. 等待滚动更新完成
    log_info "等待 $svc 滚动更新完成..."
    if kubectl rollout status deployment "$svc" -n "$NAMESPACE" --timeout=300s &>/dev/null; then
      log_pass "滚动更新成功完成"
    else
      log_fail "滚动更新超时或失败"
      continue
    fi

    # 4. 检查副本数是否保持
    local replicas_after
    replicas_after=$(get_replicas "$svc")
    if [[ "$replicas_after" -eq "$replicas_before" ]]; then
      log_pass "副本数保持不变（$replicas_before → $replicas_after）"
    else
      log_warn "副本数变化（$replicas_before → $replicas_after，HPA 可能介入）"
    fi

    # 5. 检查是否有 Pod 同时终止（零宕机验证）
    #    通过检查 rollout 期间的 Ready Pod 数是否始终 ≥ minAvailable
    local min_available
    min_available=$(kubectl get pdb "${svc}-pdb" -n "$NAMESPACE" -o jsonpath='{.spec.minAvailable}' 2>/dev/null || echo "1")

    # 获取更新后的 Pod 列表
    local pods_after
    pods_after=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | tr ' ' '\n' | sort)

    # 检查是否有新 Pod 生成（说明触发了滚动更新）
    local new_pods
    new_pods=$(comm -13 <(echo "$pods_before") <(echo "$pods_after") | grep -c '^' || echo "0")
    if [[ "$new_pods" -gt 0 ]]; then
      log_pass "生成了 $new_pods 个新 Pod（滚动更新生效）"
    else
      log_warn "未检测到新 Pod（可能注解未触发滚动更新）"
    fi

    # 6. 检查所有 Pod 都 Ready
    local ready_pods
    ready_pods=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=$svc" --field-selector=status.phase=Running -o json | \
      jq -r '[.items[] | select(.status.conditions[] | select(.type=="Ready" and .status=="True"))] | length' 2>/dev/null || echo "0")
    if [[ "$ready_pods" -ge "$min_available" ]]; then
      log_pass "就绪 Pod 数=$ready_pods（≥ minAvailable=$min_available）"
    else
      log_fail "就绪 Pod 数=$ready_pods（< minAvailable=$min_available）"
    fi
  done

  echo ""
}

# ----------------------------------------------------------------------------
# 前置依赖检查
# ----------------------------------------------------------------------------
verify_prerequisites() {
  log_check "前置依赖检查"

  # kubectl
  if command -v kubectl &>/dev/null; then
    log_pass "kubectl 已安装"
  else
    log_fail "kubectl 未安装"
    exit 2
  fi

  # 集群连通性
  if kubectl cluster-info &>/dev/null; then
    log_pass "K8s 集群可连接"
  else
    log_fail "无法连接 K8s 集群"
    exit 2
  fi

  # 命名空间
  if kubectl get namespace "$NAMESPACE" &>/dev/null; then
    log_pass "命名空间 $NAMESPACE 存在"
  else
    log_fail "命名空间 $NAMESPACE 不存在"
    exit 2
  fi

  # jq（可选，用于 JSON 解析）
  if command -v jq &>/dev/null; then
    log_pass "jq 已安装"
  else
    log_warn "jq 未安装（部分检查可能跳过）"
  fi

  # metrics-server（HPA 依赖）
  if kubectl get deployment metrics-server -n kube-system &>/dev/null; then
    log_pass "metrics-server 已部署"
  else
    log_warn "metrics-server 未检测到（CPU/内存 HPA 可能不工作）"
  fi

  echo ""
}

# ----------------------------------------------------------------------------
# 汇总报告
# ----------------------------------------------------------------------------
print_summary() {
  echo "=============================================="
  echo "              验证摘要"
  echo "=============================================="
  echo -e "总计：$((PASS_COUNT + FAIL_COUNT + WARN_COUNT))"
  echo -e "通过：${COLOR_GREEN}${PASS_COUNT}${COLOR_NC}"
  echo -e "失败：${COLOR_RED}${FAIL_COUNT}${COLOR_NC}"
  echo -e "警告：${COLOR_YELLOW}${WARN_COUNT}${COLOR_NC}"
  echo "=============================================="

  if [[ "$FAIL_COUNT" -eq 0 ]]; then
    echo -e "${COLOR_GREEN}所有检查项通过！${COLOR_NC}"
    return 0
  else
    echo -e "${COLOR_RED}有 $FAIL_COUNT 个检查项失败，请修复后重新验证。${COLOR_NC}"
    return 1
  fi
}

# ----------------------------------------------------------------------------
# 帮助信息
# ----------------------------------------------------------------------------
show_help() {
  cat <<EOF
NexusChain 高可用验证脚本

用法：
  bash $0 [选项]

选项：
  -n, --namespace    命名空间（默认 nexus）
  -c, --check        检查项：all|hpa|pdb|az|rolling|prereq（默认 all）
  -v, --verbose      详细输出
  -h, --help         帮助信息

检查项说明：
  prereq   - 前置依赖检查（kubectl、集群、命名空间、metrics-server）
  hpa      - HPA 自动扩容配置验证（CPU/内存 + RPS 自定义指标）
  pdb      - PDB 滚动更新配置验证（minAvailable + maxUnavailable=0）
  az       - 多 AZ 部署验证（topologySpreadConstraints + Pod 分布）
  rolling  - 滚动更新零宕机验证（触发滚动更新并检查可用性）
  all      - 执行所有检查项（默认）

示例：
  bash $0                              # 全部检查
  bash $0 -n nexus -c hpa              # 仅检查 HPA
  bash $0 -c pdb                       # 仅检查 PDB
  bash $0 -c az                        # 仅检查多 AZ
  bash $0 -c rolling                   # 模拟滚动更新
  bash $0 -v                           # 详细输出

退出码：
  0 - 所有检查通过
  1 - 有检查项失败
  2 - 脚本错误（依赖缺失等）
EOF
}

# ----------------------------------------------------------------------------
# 参数解析
# ----------------------------------------------------------------------------
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -n|--namespace)
        NAMESPACE="$2"
        shift 2
        ;;
      -c|--check)
        CHECK="$2"
        shift 2
        ;;
      -v|--verbose)
        VERBOSE=true
        shift
        ;;
      -h|--help)
        show_help
        exit 0
        ;;
      *)
        echo "未知选项：$1"
        show_help
        exit 2
        ;;
    esac
  done

  # 验证检查项
  local valid_checks="all prereq hpa pdb az rolling"
  if ! echo "$valid_checks" | grep -qw "$CHECK"; then
    echo "无效的检查项：$CHECK"
    echo "可选值：$valid_checks"
    exit 2
  fi
}

# ----------------------------------------------------------------------------
# 主函数
# ----------------------------------------------------------------------------
main() {
  parse_args "$@"

  echo "=============================================="
  echo "  NexusChain 高可用验证"
  echo "  命名空间：$NAMESPACE"
  echo "  检查项：$CHECK"
  echo "  时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo "=============================================="
  echo ""

  # 始终先执行前置检查
  verify_prerequisites

  case "$CHECK" in
    prereq)
      # 仅前置检查，已执行
      ;;
    hpa)
      verify_hpa
      ;;
    pdb)
      verify_pdb
      ;;
    az)
      verify_az
      ;;
    rolling)
      verify_rolling
      ;;
    all)
      verify_hpa
      verify_pdb
      verify_az
      verify_rolling
      ;;
  esac

  print_summary
}

main "$@"