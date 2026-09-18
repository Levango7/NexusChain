#!/usr/bin/env bash
# ============================================================================
# 版权头合规检查（License header compliance）
# ============================================================================
# 背景（2026-09-17 交付前审计发现）：
#   nexus-core 为上游 `java-nexuscore` 的 LGPL-3.0 派生代码（166 个 Java 文件
#   带 LGPL 头 + nexus-core/LICENSE 为 LGPL 全文），但根 LICENSE / README 此前
#   仅声明 Apache-2.0，属"整仓许可声明与实际内容不符"的法律风险缺口。
#
# 本脚本是**声明一致性门禁**（不是法律审查）：
#   1. 断言 LGPL 许可文本存在（nexus-core/LICENSE）；
#   2. 断言 README.md / NOTICE 含 LGPL-3.0 分模块声明（防止声明被误删）；
#   3. 扫描全部 tracked *.java，带 GPL/LGPL/AGPL 头的文件必须落在白名单前缀内，
#      否则失败并打印文件清单；
#   4. 打印白名单前缀的命中计数，便于人工与 docs/licensing.md 比对。
#
# 新增 GPL/LGPL/AGPL 代码时：更新 NOTICE §2、docs/licensing.md §3 与本脚本 ALLOWED。
#
# 运行：bash scripts/check-license-headers.sh
# 实现说明：用 `git grep -l` 一次扫描（单进程、只遍历 tracked 文件），
#           避免逐文件 spawn grep（Windows 本地实测逐文件方式会超时）。
# 兼容性：POSIX sh（不用 bash 数组 / mapfile / 进程替换），dash/ash 亦可执行。
# 行结束符：须为 LF（.gitattributes 的 `*.sh text eol=lf` 保证）。若在 Windows
#           工作区以 CRLF 直接执行，会报 "set: Illegal option" 类错误——那是行
#           结束符问题，不是脚本逻辑问题；请在 LF 副本或 WSL/Git-Bash 中验证。
# ============================================================================
set -eu

cd "$(dirname "$0")/.."

# 允许携带 GNU 系许可头的路径前缀（与 NOTICE §2 / docs/licensing.md §3 严格一致）
ALLOWED="nexus-core/nexus-core/src/ nexus-consortium/consortium/src/"

# GNU 系许可头标识（LGPL 文本含 "Lesser General"，故单条正则同时覆盖 GPL/LGPL/AGPL）
GNU_HEADER_RE='GNU (Lesser )?General Public License|GNU Affero General Public License|AGPL'

LICENSE_FILE="nexus-core/LICENSE"
DECLARATION_FILES="README.md NOTICE"

fail=0

echo "=== 版权头合规检查 ==="

# --- 1. LGPL 许可文本存在 ---
if [ ! -f "$LICENSE_FILE" ]; then
  echo "[FAIL] 缺少 LGPL 许可文本：$LICENSE_FILE"
  fail=1
elif ! grep -q "GNU LESSER GENERAL PUBLIC LICENSE" "$LICENSE_FILE"; then
  echo "[FAIL] $LICENSE_FILE 不是 LGPL 文本（未找到 'GNU LESSER GENERAL PUBLIC LICENSE'）"
  fail=1
else
  echo "[ OK ] $LICENSE_FILE 为 LGPL 许可文本"
fi

# --- 2. 分模块许可声明存在 ---
for f in $DECLARATION_FILES; do
  if [ ! -f "$f" ]; then
    echo "[FAIL] 缺少声明文件：$f"
    fail=1
    continue
  fi
  if grep -q "LGPL-3.0" "$f"; then
    echo "[ OK ] $f 含 LGPL-3.0 分模块声明"
  else
    echo "[FAIL] $f 未声明 LGPL-3.0（nexus-core 为 LGPL 派生，必须披露；见 docs/licensing.md）"
    fail=1
  fi
done

# --- 3. tracked Java 文件枚举与一次扫描 ---
if ! command -v git >/dev/null 2>&1; then
  echo "[FAIL] 需要 git 以枚举 tracked 文件"
  exit 1
fi

MATCHED="$(mktemp)"
COUNTS="$(mktemp)"
VIOLATIONS="$(mktemp)"
trap 'rm -f "$MATCHED" "$COUNTS" "$VIOLATIONS"' EXIT INT TERM

TOTAL="$(git ls-files '*.java' | wc -l | tr -d ' ')"
echo "[INFO] tracked Java 文件数：$TOTAL"

# git grep 无命中时返回 1，属合法情况（例如 LGPL 部分已 clean-room 重写并清空）
git grep -lE "$GNU_HEADER_RE" -- '*.java' > "$MATCHED" 2>/dev/null || true
MATCH_TOTAL="$(grep -c . "$MATCHED" || true)"
echo "[INFO] 带 GNU 系许可头的 tracked Java 文件数：$MATCH_TOTAL"

# --- 4. 白名单前缀命中计数（供人工与 docs/licensing.md §1 比对）---
for prefix in $ALLOWED; do
  n=0
  while IFS= read -r f; do
    case "$f" in
      "$prefix"*) n=$((n + 1)) ;;
    esac
  done < "$MATCHED"
  echo "$prefix 命中 $n 个文件" >> "$COUNTS"
done

# --- 5. 越界扫描：白名单前缀之外出现 GNU 系许可头 = 失败 ---
while IFS= read -r f; do
  if [ -z "$f" ]; then
    continue
  fi
  allowed=0
  for prefix in $ALLOWED; do
    case "$f" in
      "$prefix"*) allowed=1; break ;;
    esac
  done
  if [ "$allowed" -eq 0 ]; then
    echo "$f" >> "$VIOLATIONS"
  fi
done < "$MATCHED"

if [ -s "$VIOLATIONS" ]; then
  echo "[FAIL] 以下文件带 GPL/LGPL/AGPL 头，但不在白名单前缀内："
  sed 's/^/  - /' "$VIOLATIONS"
  echo "       请二选一：(a) 更新 NOTICE §2 / docs/licensing.md §3 / 本脚本 ALLOWED；"
  echo "                 (b) 移除该文件的 GNU 系头部依赖（clean-room 重写）。"
  fail=1
else
  echo "[ OK ] 无越界 GNU 许可头文件"
fi

echo "[INFO] 白名单内 GNU 许可头文件计数："
sed 's/^/         /' "$COUNTS"
echo "[INFO] 期望值（docs/licensing.md §1）：nexus-core=162，nexus-consortium=4（clean-room 重写后应同步下调）"

if [ "$fail" -ne 0 ]; then
  echo "=== 版权头合规检查失败 ==="
  exit 1
fi

echo "=== 版权头合规检查通过 ==="
