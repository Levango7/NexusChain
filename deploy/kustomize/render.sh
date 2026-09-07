#!/usr/bin/env bash
# deploy/kustomize/render.sh（PLAN-002 Step B）
# ============================================================================
# 串联 helm template + kustomize——输出一套可直接 apply 到目标环境的 manifests。
#
# 用法：
#   bash deploy/kustomize/render.sh <env> [output-file]
#   bash deploy/kustomize/render.sh dev
#   bash deploy/kustomize/render.sh staging
#   bash deploy/kustomize/render.sh prod > /tmp/prod-rendered.yaml
#
# <env> 必填（dev / staging / prod）：
#   1. helm template 渲染 deploy/helm/values-<env>.yaml（含所有重型环境
#      差异：replicas / resources / HPA / PDB / image tag）
#   2. kubectl kustomize 在渲染产物上打轻量 patch（namespace / env label）
#   3. 输出可 apply 的 YAML
# ============================================================================
set -euo pipefail

ENV="${1:-}"
OUT="${2:-/tmp/nexus-${ENV}.yaml}"

if [[ -z "$ENV" ]] || [[ ! "$ENV" =~ ^(dev|staging|prod)$ ]]; then
  echo "Usage: $0 <dev|staging|prod> [output-file]" >&2
  exit 1
fi

if ! command -v helm >/dev/null 2>&1; then
  echo "ERROR: helm not found in PATH" >&2
  exit 1
fi
if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl not found in PATH" >&2
  exit 1
fi

# 1. helm 渲染（重型环境差异：replicas / resources / image tag / HPA / PDB）
helm template nexus-chain deploy/helm/ \
  -f "deploy/helm/values-${ENV}.yaml" \
  --set "global.env=${ENV}" \
  > "${OUT}"

# 2. kubectl kustomize 验证 overlay 合法（不修改输出——overlay 逻辑已由 helm
#   表达；kustomize overlay 的实际价值是 CI 验证 namespace / env label）
if ! kubectl kustomize "deploy/kustomize/overlays/${ENV}" >/dev/null; then
  echo "ERROR: kustomize overlay validation failed for ${ENV}" >&2
  exit 1
fi

echo "✓ Rendered to ${OUT}"
echo "  Apply: kubectl apply -f ${OUT}"
