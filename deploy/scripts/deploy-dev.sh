#!/bin/bash
# ============================================================================
# NexusChain dev 环境一键部署脚本
# ============================================================================
# 用途：在 kind 集群（或其他 K8s 集群）上部署 NexusChain dev 环境
# 前置：kind / kubectl / helm 已安装且在 PATH
# 行为：
#   1. 检查前置工具
#   2. 确认 kind 集群存在（不存在则按 deploy/kind/kind-config.yaml 创建）
#   3. 切换 kubectl context 到 kind-nexus-chain
#   4. helm dependency update
#   5. helm upgrade --install nexus-chain（dev values）
#   6. 等待 Pod 就绪
#   7. 打印验证命令
# 不修改任何 Helm Chart 文件，仅调用 helm 命令
# ============================================================================
set -euo pipefail

# ---------- 常量 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLUSTER_NAME="nexus-chain"
KIND_CONFIG="$REPO_ROOT/deploy/kind/kind-config.yaml"
HELM_CHART="$REPO_ROOT/deploy/helm"
VALUES_DEV="$REPO_ROOT/deploy/helm/values-dev.yaml"
NAMESPACE="nexus-dev"
RELEASE="nexus-chain"
TIMEOUT="300s"

# ---------- 颜色 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*" >&2; }
step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# ---------- 前置检查 ----------
check_prereqs() {
  step "检查前置工具..."
  local missing=0
  for cmd in kubectl helm; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      err "$cmd 未安装或不在 PATH"
      missing=1
    fi
  done
  if [ $missing -ne 0 ]; then
    err "缺少必需工具，请先安装：kubectl / helm"
    exit 1
  fi
  log "kubectl: $(kubectl version --client --short 2>/dev/null || kubectl version --client | head -1)"
  log "helm:     $(helm version --short)"
}

# ---------- kind 集群 ----------
ensure_kind_cluster() {
  if ! command -v kind >/dev/null 2>&1; then
    warn "kind 未安装，跳过集群创建。假设已配置 kubectl context。"
    return
  fi

  if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
    log "kind 集群 '$CLUSTER_NAME' 已存在，复用。"
  else
    step "创建 kind 集群 '$CLUSTER_NAME'..."
    if [ ! -f "$KIND_CONFIG" ]; then
      err "kind 配置不存在: $KIND_CONFIG"
      exit 1
    fi
    kind create cluster --name "$CLUSTER_NAME" --config "$KIND_CONFIG"
  fi

  step "切换 kubectl context 到 kind-$CLUSTER_NAME..."
  kubectl config use-context "kind-$CLUSTER_NAME"
  kubectl cluster-info
}

# ---------- Helm 依赖 ----------
update_helm_deps() {
  step "更新 Helm 子 chart 依赖..."
  helm dependency update "$HELM_CHART"
}

# ---------- 渲染检查 ----------
lint_and_template() {
  step "helm lint 检查..."
  if ! helm lint "$HELM_CHART"; then
    err "helm lint 失败"
    exit 1
  fi

  step "helm template 渲染检查（dev）..."
  if ! helm template "$RELEASE" "$HELM_CHART" -f "$VALUES_DEV" -n "$NAMESPACE" >/dev/null; then
    err "helm template 渲染失败"
    exit 1
  fi
  log "渲染检查通过。"
}

# ---------- 部署 ----------
deploy() {
  step "部署 Helm release '$RELEASE' 到 namespace '$NAMESPACE'..."
  helm upgrade --install "$RELEASE" "$HELM_CHART" \
    -f "$VALUES_DEV" \
    -n "$NAMESPACE" --create-namespace \
    --timeout "$TIMEOUT" \
    --wait

  log "Helm 部署完成。"
}

# ---------- 等待就绪 ----------
wait_for_pods() {
  step "等待所有 NexusChain Pod 就绪（超时 $TIMEOUT）..."
  if ! kubectl wait --for=condition=Ready pod \
        -n "$NAMESPACE" \
        -l app.kubernetes.io/part-of=nexus \
        --timeout="$TIMEOUT"; then
    warn "部分 Pod 未在超时内就绪，请手动检查：kubectl get pods -n $NAMESPACE"
  fi
}

# ---------- 验证输出 ----------
print_summary() {
  step "部署完成。当前状态："
  echo
  echo "=== Pods ==="
  kubectl get pods -n "$NAMESPACE" -o wide
  echo
  echo "=== Services ==="
  kubectl get svc -n "$NAMESPACE"
  echo
  echo "=== Helm release ==="
  helm list -n "$NAMESPACE"
  echo
  echo "=== 后续验证命令 ==="
  echo "  kubectl get pods -n $NAMESPACE -w"
  echo "  kubectl logs -f -n $NAMESPACE -l app.kubernetes.io/name=nexus-gateway"
  echo "  kubectl port-forward svc/nexus-gateway -n $NAMESPACE 8080:8080"
  echo "  curl http://localhost:8080/actuator/health"
  echo
  echo "=== 清理 ==="
  echo "  $SCRIPT_DIR/destroy-dev.sh"
}

# ---------- 主流程 ----------
main() {
  echo "========================================"
  echo " NexusChain dev 环境部署"
  echo "========================================"
  check_prereqs
  ensure_kind_cluster
  update_helm_deps
  lint_and_template
  deploy
  wait_for_pods
  print_summary
  log "完成。"
}

main "$@"