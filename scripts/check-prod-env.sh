#!/usr/bin/env bash
# 校验 .env.prod.example 覆盖 docker-compose.prod.yml 的全部 :? 强制变量。
#
# 背景（2026-11-07）：清单漏了 NEXUS_METRICS_TOKEN，而 compose 有 6 处 `:?` 强制，
# 照清单执行 `docker compose config` 会在插值阶段直接失败 —— 交付物自带的自检命令不可用。
# 本脚本把「清单是否覆盖 compose 强制变量」变成可执行断言，防止再次漂移。
#
# 用法：scripts/check-prod-env.sh [compose文件] [清单文件]
# 退出码：0 = 覆盖完整；1 = 有缺失；2 = 前置文件缺失
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="${1:-$REPO_ROOT/docker-compose.prod.yml}"
EXAMPLE="${2:-$REPO_ROOT/.env.prod.example}"

for f in "$COMPOSE" "$EXAMPLE"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: 缺少文件 $f" >&2
    exit 2
  fi
done

# compose 里所有 ${VAR:?} 形式的强制变量
required="$(grep -oE '\$\{[A-Z_][A-Z0-9_]*:\?' "$COMPOSE" \
  | sed -E 's/^\$\{//; s/:\?$//' | sort -u)"

# 清单里声明的变量（KEY=VALUE 行）
declared="$(grep -E '^[A-Z_][A-Z0-9_]*=' "$EXAMPLE" | cut -d= -f1 | sort -u)"

missing="$(comm -23 <(printf '%s\n' "$required") <(printf '%s\n' "$declared"))"

if [ -n "$missing" ]; then
  echo "FAIL: .env.prod.example 缺少以下强制变量（compose 用 :? 声明）：" >&2
  printf '  - %s\n' $missing >&2
  echo "" >&2
  echo "修复：把这些键补进 $EXAMPLE，或从 compose 移除 :? 并给出安全默认值。" >&2
  exit 1
fi

count="$(printf '%s\n' "$required" | wc -l | tr -d ' ')"
echo "OK: .env.prod.example 覆盖全部 ${count} 个强制变量"
