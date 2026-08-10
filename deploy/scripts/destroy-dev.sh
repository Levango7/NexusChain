#!/bin/bash
# ============================================================================
# NexusChain dev 环境清理脚本
# ============================================================================
# 用途：卸载 NexusChain dev 环境 Helm release 与 namespace
# 行为：
#   1. 检查 kubectl / helm
#   2. helm uninstall nexus-chain -n nexus-dev
#   3. kubectl delete namespace nexus-dev
#   4. 可选：销毁 kind 集群（--full 参数）
#   5. 可选：移除本地 registry 容器（--full 参数）
# 不修改任何 Helm Chart 文件
# ============================================================================
set -euo pipefail

# ---------- 常量 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLUSTER_NAME="nexus-chain"
NAMESPACE="nexus-dev"
RELEASE="nexus-chain"

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

# ---------- 参数 ----------
FULL=0
if [ "${1:-}" = "--full" ]; then
  FULL=1
fi

# ---------- 前置检查 ----------
check_prereqs() {
  local missing=0
  for cmd in kubectl helm; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      err "$cmd 未安装或不在 PATH"
      missing=1
    fi
  done
  if [ $missing -ne 0 ]; then
    exit 1
  fi
}

# ---------- 卸载 Helm release ----------
uninstall_release() {
  step "卸载 Helm release '$RELEASE'..."
  if helm list -n "$NAMESPACE" 2>/dev/null | grep -qw "$RELEASE"; then
    helm uninstall "$RELEASE" -n "$NAMESPACE"
    log "Helm release 已卸载。"
  else
    warn "namespace '$NAMESPACE' 下不存在 release '$RELEASE'，跳过。"
  fi
}

# ---------- 删除 namespace ----------
delete_namespace() {
  step "删除 namespace '$NAMESPACE'..."
  if kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
    kubectl delete namespace "$NAMESPACE" --ignore-not-found
    log "namespace 已删除。"
  else
    warn "namespace '$NAMESPACE' 不存在，跳过。"
  fi
}

# ---------- 销毁 kind 集群 ----------
destroy_kind_cluster() {
  if ! command -v kind >/dev/null 2>&1; then
    warn "kind 未安装，跳过集群销毁。"
    return
  fi
  step "销毁 kind 集群 '$CLUSTER_NAME'..."
  if kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
    kind delete cluster --name "$CLUSTER_NAME"
    log "kind 集群已销毁。"
  else
    warn "kind 集群 '$CLUSTER_NAME' 不存在，跳过。"
  fi
}

# ---------- 移除本地 registry ----------
remove_registry() {
  step "移除本地 registry 容器 'kind-registry'..."
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "kind-registry"; then
    docker rm -f kind-registry
    log "registry 容器已移除。"
  else
    warn "registry 容器 'kind-registry' 不存在，跳过。"
  fi
}

# ---------- 主流程 ----------
main() {
  echo "========================================"
  echo " NexusChain dev 环境清理"
  echo "========================================"
  check_prereqs
  uninstall_release
  delete_namespace

  if [ $FULL -eq 1 ]; then
    destroy_kind_cluster
    remove_registry
  else
    echo
    echo "如需同时销毁 kind 集群与本地 registry，请执行："
    echo "  $0 --full"
  fi

  log "完成。"
}

main "$@"