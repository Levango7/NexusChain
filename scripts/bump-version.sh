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
#   6. nexus-core application.properties - nexus.version
#   7. nexus-core version.properties    - versionNumber（2026-09-07 补——
#      此前脚本遗漏导致与 CHANGELOG 漂移的历史病根）
#   8. README.md                        - 版本口径头注「当前均为 `X.Y.Z`」
#      （2026-10-18 补：此前靠人工同步，是历次漂移的最后一环）
#   （原第 6 步「deploy/k8s/SECRET-MANAGEMENT.md 标题版本」于 2026-10-18
#   批次④移除：该文档标题已去版本化，跨版本通用文档不随归版修改。）
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
echo "[1/8] 更新 build.gradle project.version ..."
sed -i.bak "s/^    version = '[0-9]*\.[0-9]*\.[0-9]*'/    version = '${NEW_VERSION}'/" build.gradle
# 注（2026-10-18）：此处原还有一条针对 `nexusVersion = '...'` 的 sed——该字段
# 已不存在于 build.gradle（死代码，空匹配静默通过），移除以免误导。
rm -f build.gradle.bak

# 2. deploy/k8s/*.yml 镜像 tag
echo "[2/8] 更新 deploy/k8s/*.yml 镜像 tag ..."
for f in deploy/k8s/*.yml; do
  if [ -f "$f" ]; then
    # 2026-10-18 修复：实际镜像前缀为 ghcr.io/levango7/nexuschain/<module>（GHCR 命名
    # 空间 = GitHub 用户 Levango7）——原 `ghcr.io/nexus/` 从未匹配任何行，本步一直是 no-op。
    # 2026-10-18 批次②：发布产物 tag 为 vX.Y.Z（release.yml 推 ${{ github.ref_name }}），
    # 静态清单同步写 v 前缀；正则兼容无 v 的存量形态，归版一次后全链统一。
    sed -i.bak "s|ghcr.io/levango7/nexuschain/\([a-z-]*\):v\?[0-9]*\.[0-9]*\.[0-9]*|ghcr.io/levango7/nexuschain/\1:v${NEW_VERSION}|g" "$f"
    rm -f "$f.bak"
  fi
done

# 3. deploy/helm/Chart.yaml
echo "[3/8] 更新 deploy/helm/Chart.yaml ..."
if [ -f deploy/helm/Chart.yaml ]; then
  sed -i.bak "s/^version: [0-9]*\.[0-9]*\.[0-9]*$/version: ${NEW_VERSION}/" deploy/helm/Chart.yaml
  sed -i.bak 's/^appVersion: "[0-9]*\.[0-9]*\.[0-9]*"$/appVersion: "'"${NEW_VERSION}"'"/' deploy/helm/Chart.yaml
  sed -i.bak "s/^    version: [0-9]*\.[0-9]*\.[0-9]*$/    version: ${NEW_VERSION}/" deploy/helm/Chart.yaml
  rm -f deploy/helm/Chart.yaml.bak
fi

# 4. deploy/helm/charts/*/Chart.yaml
echo "[4/8] 更新 deploy/helm/charts/*/Chart.yaml ..."
for chart_dir in deploy/helm/charts/*/; do
  chart_file="${chart_dir}Chart.yaml"
  if [ -f "$chart_file" ]; then
    sed -i.bak "s/^version: [0-9]*\.[0-9]*\.[0-9]*$/version: ${NEW_VERSION}/" "$chart_file"
    sed -i.bak 's/^appVersion: "[0-9]*\.[0-9]*\.[0-9]*"$/appVersion: "'"${NEW_VERSION}"'"/' "$chart_file"
    rm -f "$chart_file.bak"
  fi
done

# 5. deploy/helm/values-prod.yaml
echo "[5/8] 更新 deploy/helm/values-prod.yaml image tag ..."
if [ -f deploy/helm/values-prod.yaml ]; then
  # 2026-10-18 批次②：image tag 与发布产物同口径（vX.Y.Z）。chart 元数据
  # （步骤 3/4）维持纯 semver——release.yml `helm package --version
  # "${CHART_VERSION#v}"` 显式去 v，属既定设计，v 政策只适用于镜像引用。
  sed -i.bak "s/^    tag: v\?[0-9]*\.[0-9]*\.[0-9]*$/    tag: v${NEW_VERSION}/" deploy/helm/values-prod.yaml
  sed -i.bak "s/^    tag: \"v\?[0-9]*\.[0-9]*\.[0-9]*\"$/    tag: \"v${NEW_VERSION}\"/" deploy/helm/values-prod.yaml
  rm -f deploy/helm/values-prod.yaml.bak
fi

# === 已移除步骤（2026-10-18 批次④）===
# 原第 6 步「deploy/k8s/SECRET-MANAGEMENT.md 标题版本」：该文档标题去版本化后
# 此步失去同步目标——移除以免留下"永远不匹配的 sed"死代码（同第 1 步
# nexusVersion sed 的移除先例）。

# 6. nexus-core application.properties
echo "[6/8] 更新 nexus-core application.properties nexus.version ..."
PROPS_FILE="nexus-core/nexus-core/src/main/resources/application.properties"
if [ -f "$PROPS_FILE" ]; then
  sed -i.bak "s|^nexus\.version=v[0-9]*\.[0-9]*\.[0-9]*.*|nexus.version=v${NEW_VERSION}|" "$PROPS_FILE"
  rm -f "$PROPS_FILE.bak"
fi

# 7. nexus-core version.properties（versionNumber='X.Y.Z'——单引号 Java properties 格式）
#    2026-09-07 完善批补：此前脚本不覆盖此文件，导致它与 CHANGELOG 漂移
#    （2.30 vs 2.40 的历史漂移即此单点遗漏；治根）
echo "[7/8] 更新 nexus-core version.properties versionNumber ..."
VERSION_PROPS="nexus-core/nexus-core/src/main/resources/version.properties"
if [ -f "$VERSION_PROPS" ]; then
  sed -i.bak "s|^versionNumber='[0-9]*\.[0-9]*\.[0-9]*'|versionNumber='${NEW_VERSION}'|" "$VERSION_PROPS"
  rm -f "$VERSION_PROPS.bak"
fi

# 8. README.md 版本口径头注（「当前均为 `X.Y.Z`」）
#    2026-10-18 补：此前该处全靠人工同步——门禁断言 2 只保证"头注含当前版本"，
#    归版时漏改该处要等下一次 CI 才暴露；纳入脚本后归版流程一次到位。
echo "[8/8] 更新 README.md 版本口径头注 ..."
if [ -f README.md ]; then
  sed -i.bak "s/当前均为 \`[0-9]*\.[0-9]*\.[0-9]*\`/当前均为 \`${NEW_VERSION}\`/" README.md
  rm -f README.md.bak
fi

echo "============================================================"
echo "✅ 版本号已统一升级至 v${NEW_VERSION}"
echo "============================================================"
echo ""
echo "请执行以下验证："
echo "  0. bash scripts/check-version-consistency.sh  # 版本口径门禁（含 CHANGELOG 超前检测）"
echo "  1. grep -rn '${NEW_VERSION}' build.gradle deploy/ nexus-core/nexus-core/src/main/resources/"
echo "  2. grep -rn 'versionNumber' nexus-core/nexus-core/src/main/resources/version.properties  # 应为 ${NEW_VERSION}"
echo "  3. gradle.bat build -x test  # 编译验证"