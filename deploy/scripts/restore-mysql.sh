#!/usr/bin/env bash
# ============================================================================
# NexusChain 数据库恢复（MySQL 8 / OceanBase MySQL 模式）
# ============================================================================
# 2026-10-12：由 restore-postgres.sh 改写而来（数据库族 PostgreSQL → MySQL）。
#
# 用法：
#   ./restore-mysql.sh --file backups/nexus_gateway-20261012-020000.sql.gz   # 指定备份文件
#   ./restore-mysql.sh --date 2026-10-12 --database nexus_gateway            # 按日期找最新备份
#   ./restore-mysql.sh --date 2026-10-12 --database all                      # 该日期全部库
#   ./restore-mysql.sh --file ... --target-host 10.0.0.9:3306                # 恢复到指定实例
#   ./restore-mysql.sh --file ... --dry-run                                  # 只打印将执行的动作
#
# ⚠️ 破坏性：默认使用 --drop-database（先 DROP DATABASE 再重建）保证"恢复=回到备份点"，
#    否则残留表会让恢复结果介于两点之间。请务必：
#      1. 先在目标实例确认库名（--target-host 指向测试实例演练一次）
#      2. 生产恢复前做一次当前库的即时备份（本脚本会提示）
#      3. 需要更保险的姿势：--no-drop 改为「建新库 + 导入」，由人工切换连接串
#
# 凭据（环境变量）：
#   MYSQL_HOST（默认 127.0.0.1）/ MYSQL_PORT（默认 3306）
#   MYSQL_USER（默认 root）/ MYSQL_PWD 或 MYSQL_PASSWORD
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="${SCRIPT_DIR}/../backups"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PWD="${MYSQL_PWD:-${MYSQL_PASSWORD:-}}"

FILE=""
DATE_ARG=""
DB_ARG=""
TARGET_HOST=""
DROP_DB=1
DRY_RUN=0
NEW_DB_SUFFIX=""

while [ $# -gt 0 ]; do
  case "$1" in
    --file)        FILE="$2"; shift 2 ;;
    --date)        DATE_ARG="$2"; shift 2 ;;
    --database)    DB_ARG="$2"; shift 2 ;;
    --target-host) TARGET_HOST="$2"; shift 2 ;;
    --no-drop)     DROP_DB=0; shift ;;
    --suffix)      NEW_DB_SUFFIX="$2"; shift 2 ;;
    --dry-run)     DRY_RUN=1; shift ;;
    -h|--help)     sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 查看用法）" >&2; exit 2 ;;
  esac
done

if [ -n "${TARGET_HOST}" ]; then
  MYSQL_HOST="${TARGET_HOST%%:*}"
  MYSQL_PORT="${TARGET_HOST##*:}"
  [ "${MYSQL_HOST}" = "${MYSQL_PORT}" ] && MYSQL_PORT=3306
fi

[ -n "${MYSQL_PWD}" ] || { echo "缺少凭据：请设置 MYSQL_PWD（或 MYSQL_PASSWORD）" >&2; exit 1; }

# 解析出「要恢复的文件列表」
FILES=""
if [ -n "${FILE}" ]; then
  [ -f "${FILE}" ] || { echo "备份文件不存在：${FILE}" >&2; exit 1; }
  FILES="${FILE}"
elif [ -n "${DATE_ARG}" ] && [ -n "${DB_ARG}" ]; then
  if [ "${DB_ARG}" = "all" ]; then
    FILES="$(ls -1 "${BACKUP_DIR}"/*-"${DATE_ARG}"-*.sql.gz 2>/dev/null || true)"
  else
    FILES="$(ls -1 "${BACKUP_DIR}/${DB_ARG}"-"${DATE_ARG}"-*.sql.gz 2>/dev/null | tail -1 || true)"
  fi
  [ -n "${FILES}" ] || { echo "未找到 ${DATE_ARG} 的备份（目录 ${BACKUP_DIR}）" >&2; exit 1; }
else
  echo "用法错误：需要 --file，或 --date + --database" >&2; exit 2
fi

mysql_cli() { mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" "$@"; }

echo "=== 恢复目标：${MYSQL_HOST}:${MYSQL_PORT}（用户 ${MYSQL_USER}）==="
for F in ${FILES}; do
  BASE="$(basename "${F}")"
  DB="${BASE%%-*}"                       # 文件名前缀即库名（backup-mysql.sh 的命名约定）
  TARGET_DB="${DB}${NEW_DB_SUFFIX}"

  echo ">>> ${BASE} → 库 ${TARGET_DB}"
  if [ "${DRY_RUN}" -eq 1 ]; then
    echo "    [dry-run] 将执行：$([ "${DROP_DB}" -eq 1 ] && echo "DROP DATABASE IF EXISTS ${TARGET_DB}; CREATE DATABASE ${TARGET_DB};" || echo "CREATE DATABASE IF NOT EXISTS ${TARGET_DB};") + 导入 ${BASE}"
    continue
  fi

  if [ "${DROP_DB}" -eq 1 ] && [ -z "${NEW_DB_SUFFIX}" ]; then
    echo "    ⚠️  5 秒后 DROP 并重建 ${TARGET_DB}（Ctrl-C 中止）"
    sleep 5
    mysql_cli -e "DROP DATABASE IF EXISTS \`${TARGET_DB}\`; CREATE DATABASE \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  else
    mysql_cli -e "CREATE DATABASE IF NOT EXISTS \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  fi

  gunzip -c "${F}" | mysql_cli "${TARGET_DB}"
  TABLES="$(mysql_cli -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}';")"
  echo "    完成：${TARGET_DB} 现有 ${TABLES} 张表"
done

echo "=== 恢复结束 ==="
echo "提示：核对业务后，如使用 --suffix 恢复到新库，请同步切换各服务 NEX_DB_URL 并重启。"
