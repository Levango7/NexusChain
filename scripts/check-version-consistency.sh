#!/usr/bin/env bash
# ============================================================================
# 版本口径一致性检查（Version consistency gate）
# ============================================================================
# 背景：版本号漂移已发生三次——
#   ① README 版本口径头注停在 2.50.0（实际 2.50.2，2026-09-28 发现）；
#   ② v2.50.2 发版时 version.properties 仍是 2.50.0，导致 jar 名错位
#      （见 version.properties 内 2026-09-22 注释）；
#   ③ 2026-09-17 审计发现 README 硬编码 v2.40.0 与构建/CHANGELOG 不一致。
#   ④ v2.51.3 发版准备（6d71d48）对历史遗留的第二个 `## [Unreleased]` 头做全局
#      替换，产生重复的 `## [2.51.3]` 条目（2026-10-06 发现，本次修复）。
#
# 本门禁断言（CHANGELOG 允许滞后于 tag —— v2.50.1 无条目先例，不做强等校验）：
#   1. 根 build.gradle 的 version == nexus-core version.properties 的 versionNumber
#      （发版时两处都要改，见 version.properties 内注释）；
#   2. README.md 的版本口径头注包含当前版本号（防止头注再次停在旧版本）；
#   3. README.md 正文不得出现与当前版本不一致的「当前 vX.Y.Z」式硬编码声明
#      （上一条断言只保证头注含当前版本，对正文里的第二处硬编码完全无感——
#       2026-09-29 实测：头注已是 2.51.0，正文历史摘要段仍写「当前 v2.40.0」）；
#   4. CHANGELOG 的版本条目 `## [x]`（含 [Unreleased]）不得重复——重复即意味着
#      发版替换或人工编辑误伤了历史条目（先例见背景④）。
#   5. CHANGELOG 不得**超前**于构建版本（滞后允许——v2.50.1 先例；超前不允许——
#      [2.74.0] 条目已合并而构建仍 2.73.0，2026-10-18 发现，旧四断言全绿）；
#   6. README 头注若复述 CHANGELOG 最新条目版本（`最新条目 `[X.Y.Z]``），
#      必须与 CHANGELOG 实际一致（2026-10-18 先例：复述停在旧版本）。
#   7. nexus-core application.properties 的 `nexus.version=vX.Y.Z` 必须等于当前
#      版本（2026-10-18 发现该字段陈旧至 v2.50.0——bump 脚本会写它，但门禁
#      从不校验，属"写而不测"）。
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

# 断言 3：正文的「当前 vX.Y.Z」式声明必须等于当前版本。
# 只写"引用头注/CHANGELOG"而不落数值，才是能长期成立的口径；此断言负责把
# 重新写死数值的做法拦下来。
STALE_CLAIMS=$(grep -nE "当前 ?v?[0-9]+\.[0-9]+\.[0-9]+" README.md | grep -v "$GRADLE_VERSION" || true)
if [ -n "$STALE_CLAIMS" ]; then
    echo "FAIL: README 正文存在与当前版本 $GRADLE_VERSION 不一致的「当前 vX.Y.Z」硬编码声明：" >&2
    echo "$STALE_CLAIMS" >&2
    echo "处置：改为引用文件开头「版本口径」头注或 CHANGELOG，不要在正文写死数值。" >&2
    exit 1
fi

# 断言 4：CHANGELOG 版本条目（## [x]）唯一性。
CHANGELOG_DUP=$(grep -oE '^## \[[^]]+\]' CHANGELOG.md | sort | uniq -d || true)
if [ -n "$CHANGELOG_DUP" ]; then
    echo "FAIL: CHANGELOG 存在重复版本条目：" >&2
    echo "$CHANGELOG_DUP" >&2
    echo "处置：把重复段归并进其实际所属的版本节（内容不得删除），每个版本号恰好一条。" >&2
    exit 1
fi
echo "CHANGELOG 版本条目 = $(grep -cE '^## \[' CHANGELOG.md) 条（无重复）"

# 断言 5（2026-10-18 补）：CHANGELOG 不得超前于构建版本。
# 旧四断言在「批次先写入 CHANGELOG、归版提交后补」的空窗期全绿——
# 2.73.0（构建）vs 2.74.0（CHANGELOG）的漂移即如此逃过门禁。
CHANGELOG_LATEST=$(grep -oE '^## \[[^]]+\]' CHANGELOG.md \
    | sed 's/^## \[\(.*\)\]$/\1/' | grep -vxE 'Unreleased' | head -1 || true)
if [ -n "$CHANGELOG_LATEST" ]; then
    if [ "$(printf '%s\n%s\n' "$GRADLE_VERSION" "$CHANGELOG_LATEST" | sort -V | tail -1)" != "$GRADLE_VERSION" ]; then
        fail "CHANGELOG 超前于构建版本：最新条目 [$CHANGELOG_LATEST] 高于 $GRADLE_VERSION —— 批次已写入 CHANGELOG 但未归版（先例 2026-10-18）。处置：bash scripts/bump-version.sh $CHANGELOG_LATEST 并同步 README 头注"
    fi
fi
echo "CHANGELOG 最新版本条目 = ${CHANGELOG_LATEST:-（无）}（不超前于构建版本）"

# 断言 6（2026-10-18 补）：README 若复述「最新条目 `[X.Y.Z]`」，必须与 CHANGELOG 实际一致。
README_QUOTED=$(grep -oE '最新条目 *`+\[[0-9]+\.[0-9]+\.[0-9]+\]' README.md \
    | head -1 | sed 's/.*\[\([0-9]*\.[0-9]*\.[0-9]*\)\]$/\1/' || true)
if [ -n "$README_QUOTED" ] && [ "$README_QUOTED" != "${CHANGELOG_LATEST:-}" ]; then
    fail "README 头注复述的 CHANGELOG 最新条目 [$README_QUOTED] 与 CHANGELOG 实际 [$CHANGELOG_LATEST] 不一致 —— 复述必漂移（先例 2026-10-18）。处置：删除复述或对齐"
fi

# 断言 7（2026-10-18 补）：application.properties 的 nexus.version 必须与当前版本一致。
# 该字段由 bump-version.sh 第 7 步维护，但此前无任何门禁——实测陈旧至 v2.50.0
# （跨 23 个版本无人测出），属"写而不测"。
APPPROPS_FILE="nexus-core/nexus-core/src/main/resources/application.properties"
# tr -d '\r'：Windows 工作树（core.autocrlf）下文件为 CRLF，不剥离尾部 \r 会误判失配。
APP_VERSION=$(grep -E '^nexus\.version=' "$APPPROPS_FILE" | head -1 | sed 's/^nexus\.version=//' | tr -d '\r' || true)
if [ "$APP_VERSION" != "v$GRADLE_VERSION" ]; then
    fail "application.properties nexus.version($APP_VERSION) != v$GRADLE_VERSION —— 跑 bash scripts/bump-version.sh 归版（该字段此前陈旧至 v2.50.0，2026-10-18 发现）"
fi
echo "application.properties nexus.version = $APP_VERSION"

echo "OK: 版本口径一致 ($GRADLE_VERSION)"
