#!/usr/bin/env bash
# ============================================================
# NexusChain 版本号统一升级脚本
#
# 用法: bash scripts/bump-version.sh <new-version>
# 示例: bash scripts/bump-version.sh 2.2.0
#
# 统一更新以下位置的版本号：
#   1. build.gradle（根）         - project.version（单一真源）
#   2. deploy/k8s/*.yml           - 镜像 tag
#   3. deploy/helm/Chart.yaml     - version + appVersion + dependencies
#   4. deploy/helm/charts/*/Chart.yaml - 子 chart version + appVersion
#   5. deploy/helm/values-prod.yaml    - image tag
#   6. deploy/k8s/SECRET-MANAGEMENT.md - 标题版本
#   7. nexus-core application.properties - nexus.version
#
# 前置：在项目根目录执行
# ============================================================
set -e

if [ $# -ne 1 ]; then
  echo "用法: bash scripts/bump-version.sh <new-version>"
  echo "示例: bash scripts/bump-version.sh 2.2.0"
  exit 1
fi

NEW_VERSION="$1"
# 简单校验 semver 格式（X.Y.Z）
if ! echo "$NEW_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "❌ 版本号格式错误，应为 X.Y.Z（semver），如 2.1.0"
  exit 1
fi

cd "$(dirname "$0")/.."
ROOT_DIR="$(pwd)"

echo "============================================================"
echo "NexusChain 版本号统一升级 → v${NEW_VERSION}"
echo "============================================================"

# 1. build.gradle（根）
echo "[1/7] 更新 build.gradle project.version ..."
sed -i.bak "s/^    version = '[0-9]*\.[0-9]*\.[0-9]*'/    version = '${NEW_VERSION}'/" build.gradle
sed -i.bak "s/^        nexusVersion = '[0-9]*\.[0-9]*\.[0-9]*'/        nexusVersion = '${NEW_VERSION}'/" build.gradle
rm -f build.gradle.bak

# 2. deploy/k8s/*.yml 镜像 tag
echo "[2/7] 更新 deploy/k8s/*.yml 镜像 tag ..."
for f in deploy/k8s/*.yml; do
  if [ -f "$f" ]; then
    sed -i.bak "s|ghcr.io/nexus/\([a-z-]*\):[0-9]*\.[0-9]*\.[0-9]*|ghcr.io/nexus/\1:${NEW_VERSION}|g" "$f"
    rm -f "$f.bak"
  fi
done

# 3. deploy/helm/Chart.yaml
echo "[3/7] 更新 deploy/helm/Chart.yaml ..."
if [ -f deploy/helm/Chart.yaml ]; then
  sed -i.bak "s/^version: [0-9]*\.[0-9]*\.[0-9]*$/version: ${NEW_VERSION}/" deploy/helm/Chart.yaml
  sed -i.bak 's/^appVersion: "[0-9]*\.[0-9]*\.[0-9]*"$/appVersion: "'"${NEW_VERSION}"'"/' deploy/helm/Chart.yaml
  sed -i.bak "s/^    version: [0-9]*\.[0-9]*\.[0-9]*$/    version: ${NEW_VERSION}/" deploy/helm/Chart.yaml
  rm -f deploy/helm/Chart.yaml.bak
fi

# 4. deploy/helm/charts/*/Chart.yaml
echo "[4/7] 更新 deploy/helm/charts/*/Chart.yaml ..."
for chart_dir in deploy/helm/charts/*/; do
  chart_file="${chart_dir}Chart.yaml"
  if [ -f "$chart_file" ]; then
    sed -i.bak "s/^version: [0-9]*\.[0-9]*\.[0-9]*$/version: ${NEW_VERSION}/" "$chart_file"
    sed -i.bak 's/^appVersion: "[0-9]*\.[0-9]*\.[0-9]*"$/appVersion: "'"${NEW_VERSION}"'"/' "$chart_file"
    rm -f "$chart_file.bak"
  fi
done

# 5. deploy/helm/values-prod.yaml
echo "[5/7] 更新 deploy/helm/values-prod.yaml image tag ..."
if [ -f deploy/helm/values-prod.yaml ]; then
  sed -i.bak "s/^    tag: [0-9]*\.[0-9]*\.[0-9]*$/    tag: ${NEW_VERSION}/" deploy/helm/values-prod.yaml
  sed -i.bak "s/^    tag: \"[0-9]*\.[0-9]*\.[0-9]*\"$/    tag: \"${NEW_VERSION}\"/" deploy/helm/values-prod.yaml
  rm -f deploy/helm/values-prod.yaml.bak
fi

# 6. deploy/k8s/SECRET-MANAGEMENT.md
echo "[6/7] 更新 deploy/k8s/SECRET-MANAGEMENT.md 标题版本 ..."
if [ -f deploy/k8s/SECRET-MANAGEMENT.md ]; then
  sed -i.bak "s|# Kubernetes Secret 管理指南（v[0-9]*\.[0-9]*\.[0-9]*）|# Kubernetes Secret 管理指南（v${NEW_VERSION}）|" deploy/k8s/SECRET-MANAGEMENT.md
  rm -f deploy/k8s/SECRET-MANAGEMENT.md.bak
fi

# 7. nexus-core application.properties
echo "[7/7] 更新 nexus-core application.properties nexus.version ..."
PROPS_FILE="nexus-core/nexus-core/src/main/resources/application.properties"
if [ -f "$PROPS_FILE" ]; then
  sed -i.bak "s|^nexus\.version=v[0-9]*\.[0-9]*\.[0-9]*.*|nexus.version=v${NEW_VERSION}|" "$PROPS_FILE"
  rm -f "$PROPS_FILE.bak"
fi

echo "============================================================"
echo "✅ 版本号已统一升级至 v${NEW_VERSION}"
echo "============================================================"
echo ""
echo "请执行以下验证："
echo "  1. grep -rn '${NEW_VERSION}' build.gradle deploy/ nexus-core/nexus-core/src/main/resources/application.properties"
echo "  2. grep -rn '1\.0\.0\|2\.0\.0' build.gradle deploy/ nexus-core/nexus-core/src/main/resources/application.properties  # 应无残留"
echo "  3. gradle.bat build -x test  # 编译验证"