#!/usr/bin/env bash
# ============================================================================
# NexusChain 数据库逻辑备份（MySQL 8 / OceanBase MySQL 模式）
# ============================================================================
# 2026-10-12：由 backup-postgres.sh 改写而来（数据库族 PostgreSQL → MySQL）。
#
# 用法：
#   ./backup-mysql.sh                              # 备份 backup-databases.txt 里的全部库
#   ./backup-mysql.sh --database nexus_gateway     # 仅备份指定库
#   ./backup-mysql.sh --retention 30               # 保留 30 天（默认 7）
#   ./backup-mysql.sh --out /data/backup           # 指定输出目录（默认 ./backups）
#
# 凭据（环境变量，勿写死在脚本里）：
#   MYSQL_HOST（默认 127.0.0.1）/ MYSQL_PORT（默认 3306）
#   MYSQL_USER（默认 root）/ MYSQL_PWD 或 MYSQL_PASSWORD
#
# 定时任务示例（每日 02:00）：
#   0 2 * * * /opt/nexus/deploy/scripts/backup-mysql.sh >> /var/log/nexus-backup.log 2>&1
#
# 说明：逻辑备份（mysqldump --single-transaction），非 PITR；需要时间点恢复请额外配置
#       binlog 归档。库列表与 deploy/config/backup-databases.txt、
#       deploy/k8s/50-backup.yml 的 for DB in ... 三处必须一致。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DB_LIST_FILE="${SCRIPT_DIR}/../config/backup-databases.txt"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PWD="${MYSQL_PWD:-${MYSQL_PASSWORD:-}}"
RETENTION_DAYS=7
OUT_DIR="${SCRIPT_DIR}/../backups"
SINGLE_DB=""

while [ $# -gt 0 ]; do
  case "$1" in
    --database)  SINGLE_DB="$2"; shift 2 ;;
    --retention) RETENTION_DAYS="$2"; shift 2 ;;
    --out)       OUT_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 查看用法）" >&2; exit 2 ;;
  esac
done

[ -n "${MYSQL_PWD}" ] || { echo "缺少凭据：请设置 MYSQL_PWD（或 MYSQL_PASSWORD）" >&2; exit 1; }

# 库列表：单库 → 命令行；否则读共享清单（去掉注释与空行）
if [ -n "${SINGLE_DB}" ]; then
  DATABASES="${SINGLE_DB}"
else
  [ -f "${DB_LIST_FILE}" ] || { echo "未找到库列表：${DB_LIST_FILE}" >&2; exit 1; }
  DATABASES="$(grep -vE '^[[:space:]]*(#|$)' "${DB_LIST_FILE}" | tr -d '\r' | tr '\n' ' ')"
fi
[ -n "${DATABASES}" ] || { echo "库列表为空" >&2; exit 1; }

mkdir -p "${OUT_DIR}"
DATE="$(date +%Y%m%d-%H%M%S)"
echo ">>> [${DATE}] 开始备份（${MYSQL_HOST}:${MYSQL_PORT}）：${DATABASES}"

FAILED=0
for DB in ${DATABASES}; do
  FILE="${OUT_DIR}/${DB}-${DATE}.sql.gz"
  # --single-transaction：InnoDB 一致性快照，不锁表（业务可继续写入）
  # --routines/--triggers：存储过程与触发器一并备份
  if mysqldump --single-transaction --routines --triggers --events \
      -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" "${DB}" 2>/dev/null | gzip > "${FILE}"; then
    echo "    ${DB} -> ${FILE}（$(du -h "${FILE}" | cut -f1)）"
  else
    echo "    ${DB} 备份失败" >&2
    FAILED=$((FAILED + 1))
  fi
done

echo ">>> 清理 ${RETENTION_DAYS} 天前的备份"
find "${OUT_DIR}" -name '*.sql.gz' -mtime +"${RETENTION_DAYS}" -delete

if [ "${FAILED}" -gt 0 ]; then
  echo ">>> 结束：${FAILED} 个库备份失败（退出码 1，便于监控告警）" >&2
  exit 1
fi
echo ">>> 备份完成，当前留存："
ls -lh "${OUT_DIR}" | tail -20
