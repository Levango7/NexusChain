#!/usr/bin/env bash
# ============================================================================
# 版本口径一致性检查（Version consistency gate）
# ============================================================================
# 背景：版本号漂移已发生三次——
#   ① README 版本口径头注停在 2.50.0（实际 2.50.2，2026-09-28 发现）；
#   ② v2.50.2 发版时 version.properties 仍是 2.50.0，导致 jar 名错位
#      （见 version.properties 内 2026-09-22 注释）；
#   ③ 2026-09-17 审计发现 README 硬编码 v2.40.0 与构建/CHANGELOG 不一致。
#
# 本门禁断言（CHANGELOG 允许滞后于 tag —— v2.50.1 无条目先例，不做强等校验）：
#   1. 根 build.gradle 的 version == nexus-core version.properties 的 versionNumber
#      （发版时两处都要改，见 version.properties 内注释）；
#   2. README.md 的版本口径头注包含当前版本号（防止头注再次停在旧版本）。
#
# 运行：bash scripts/check-version-consistency.sh
# ============================================================================
set -euo pipefail

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

GRADLE_VERSION=$(grep -E "^[[:space:]]*version[[:space:]]*=" build.gradle | head -1 | sed "s/.*version[[:space:]]*=[[:space:]]*'\([^']*\)'.*/\1/")
[ -n "$GRADLE_VERSION" ] || fail "无法从根 build.gradle 解析 version = '...'"

PROPS_VERSION=$(grep -E "^[[:space:]]*versionNumber" nexus-core/nexus-core/src/main/resources/version.properties | head -1 | sed "s/.*versionNumber='\([^']*\)'.*/\1/")
[ -n "$PROPS_VERSION" ] || fail "无法从 version.properties 解析 versionNumber='...'"

echo "build.gradle version       = $GRADLE_VERSION"
echo "version.properties version = $PROPS_VERSION"

if [ "$GRADLE_VERSION" != "$PROPS_VERSION" ]; then
    fail "版本漂移: build.gradle($GRADLE_VERSION) != version.properties($PROPS_VERSION) —— 发版时两处都要改（见 version.properties 注释）"
fi

if ! grep -q "$GRADLE_VERSION" README.md; then
    fail "README.md 版本口径头注未包含当前版本 $GRADLE_VERSION —— 头注漂移（第三次先例：头注停在 2.50.0）"
fi

echo "OK: 版本口径一致 ($GRADLE_VERSION)"
