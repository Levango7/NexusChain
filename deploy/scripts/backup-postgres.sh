#!/usr/bin/env bash
# =============================================================================
# PostgreSQL 备份脚本（P2-T8：数据库生产化）
# =============================================================================
#
# 功能：
#   1. pg_dump --format=custom --compress=9 压缩备份（支持并行恢复和选择性恢复）
#   2. 上传 S3（AWS CLI，启用服务器端加密 SSE-KMS）
#   3. 保留 N 天，超期自动删除
#   4. 备份完成写入 metrics 文件供监控
#
# 用法：
#   ./backup-postgres.sh                                  # 备份所有库
#   ./backup-postgres.sh --database nexus_gateway         # 仅备份指定库
#   ./backup-postgres.sh --retention 30                   # 保留 30 天
#
# 环境变量：
#   PG_HOST              PostgreSQL 主机（默认 postgres）
#   PG_PORT              PostgreSQL 端口（默认 5432）
#   PG_USER              超级用户（默认 postgres）
#   PG_PASSWORD          超级用户密码（必填）
#   S3_BUCKET            S3 备份桶（默认 nexus-backups）
#   S3_PREFIX            S3 路径前缀（默认 pg）
#   AWS_REGION           AWS 区域（默认 us-east-1）
#   BACKUP_RETENTION_DAYS 保留天数（默认 30）
#
# 部署：crontab 每日 02:00 执行
#   0 2 * * * /opt/nexus/deploy/scripts/backup-postgres.sh >> /var/log/nexus-backup.log 2>&1
# =============================================================================

set -euo pipefail

# === 默认参数 ===
PG_HOST="${PG_HOST:-postgres}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
S3_BUCKET="${S3_BUCKET:-nexus-backups}"
S3_PREFIX="${S3_PREFIX:-pg}"
AWS_REGION="${AWS_REGION:-us-east-1}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
DATABASES=("nexus_gateway" "nexus_wallet")
METRICS_FILE="${METRICS_FILE:-/var/lib/nexus/backup-metrics}"

# === 参数解析 ===
while [[ $# -gt 0 ]]; do
    case "$1" in
        --database)
            DATABASES=("$2")
            shift 2
            ;;
        --retention)
            BACKUP_RETENTION_DAYS="$2"
            shift 2
            ;;
        --help|-h)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "ERROR: 未知参数 $1" >&2
            exit 1
            ;;
    esac
done

# === 前置检查 ===
if [[ -z "${PG_PASSWORD:-}" ]]; then
    echo "ERROR: PG_PASSWORD 环境变量未设置" >&2
    exit 1
fi

for cmd in pg_dump aws; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: $cmd 未安装" >&2
        exit 1
    fi
done

# === 导出 PG 密码（pg_dump 使用） ===
export PGPASSWORD="$PG_PASSWORD"

# === 备份执行 ===
BACKUP_DATE=$(date +%Y-%m-%d)
BACKUP_TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

echo "=== PostgreSQL 备份开始 $(date) ==="
echo "备份日期: $BACKUP_DATE"
echo "目标库: ${DATABASES[*]}"
echo "S3 路径: s3://$S3_BUCKET/$S3_PREFIX/$BACKUP_DATE/"

# 记时开始
START_EPOCH=$(date +%s)
TOTAL_SIZE=0
SUCCESS_COUNT=0
FAIL_COUNT=0

for DB in "${DATABASES[@]}"; do
    echo ""
    echo "--- 备份库: $DB ---"

    BACKUP_FILE="$TMP_DIR/${DB}_${BACKUP_TIMESTAMP}.dump"
    S3_KEY="$S3_PREFIX/$BACKUP_DATE/${DB}_${BACKUP_TIMESTAMP}.dump"

    # 1. pg_dump 压缩备份（custom 格式 + 压缩级别 9）
    #    --format=custom：支持 pg_restore 并行恢复和选择性恢复
    #    --compress=9：最大压缩比
    #    --no-owner --no-privileges：恢复时不依赖原 owner（跨环境恢复友好）
    if pg_dump \
        --host="$PG_HOST" \
        --port="$PG_PORT" \
        --username="$PG_USER" \
        --format=custom \
        --compress=9 \
        --no-owner \
        --no-privileges \
        --dbname="$DB" \
        --file="$BACKUP_FILE"; then

        BACKUP_SIZE=$(stat -c %s "$BACKUP_FILE" 2>/dev/null || stat -f %z "$BACKUP_FILE")
        TOTAL_SIZE=$((TOTAL_SIZE + BACKUP_SIZE))
        echo "  本地备份完成: $BACKUP_FILE ($(numfmt --to=iec $BACKUP_SIZE 2>/dev/null || echo ${BACKUP_SIZE}B))"

        # 2. 上传 S3（SSE-KMS 加密）
        if aws s3 cp "$BACKUP_FILE" "s3://$S3_BUCKET/$S3_KEY" \
            --region "$AWS_REGION" \
            --sse aws:kms \
            --only-show-errors; then
            echo "  S3 上传完成: s3://$S3_BUCKET/$S3_KEY"
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        else
            echo "  ERROR: S3 上传失败: $DB" >&2
            FAIL_COUNT=$((FAIL_COUNT + 1))
        fi
    else
        echo "  ERROR: pg_dump 失败: $DB" >&2
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
done

# === 清理过期备份 ===
echo ""
echo "--- 清理 $BACKUP_RETENTION_DAYS 天前的备份 ---"
CUTOFF_DATE=$(date -d "-${BACKUP_RETENTION_DAYS} days" +%Y-%m-%d 2>/dev/null || date -v-${BACKUP_RETENTION_DAYS}d +%Y-%m-%d)
aws s3 ls "s3://$S3_BUCKET/$S3_PREFIX/" --region "$AWS_REGION" | awk '{print $2}' | while read -r dir; do
    dir_date=$(echo "$dir" | tr -d '/')
    if [[ "$dir_date" < "$CUTOFF_DATE" ]]; then
        echo "  删除过期备份: s3://$S3_BUCKET/$S3_PREFIX/$dir"
        aws s3 rm "s3://$S3_BUCKET/$S3_PREFIX/$dir" --recursive --region "$AWS_REGION" --only-show-errors
    fi
done

# === 计时结束 ===
END_EPOCH=$(date +%s)
DURATION=$((END_EPOCH - START_EPOCH))

# === 写入 metrics 文件（供 Prometheus node_exporter textfile collector 抓取） ===
mkdir -p "$(dirname "$METRICS_FILE")"
cat > "$METRICS_FILE" <<EOF
# HELP nexus_pg_backup_success_count 备份成功的库数量
# TYPE nexus_pg_backup_success_count gauge
nexus_pg_backup_success_count $SUCCESS_COUNT
# HELP nexus_pg_backup_fail_count 备份失败的库数量
# TYPE nexus_pg_backup_fail_count gauge
nexus_pg_backup_fail_count $FAIL_COUNT
# HELP nexus_pg_backup_total_bytes 备份总字节数
# TYPE nexus_pg_backup_total_bytes gauge
nexus_pg_backup_total_bytes $TOTAL_SIZE
# HELP nexus_pg_backup_duration_seconds 备份耗时（秒）
# TYPE nexus_pg_backup_duration_seconds gauge
nexus_pg_backup_duration_seconds $DURATION
# HELP nexus_pg_backup_last_success_timestamp 最后一次成功备份时间戳
# TYPE nexus_pg_backup_last_success_timestamp gauge
nexus_pg_backup_last_success_timestamp $(date +%s)
EOF

echo ""
echo "=== PostgreSQL 备份结束 $(date) ==="
echo "成功: $SUCCESS_COUNT, 失败: $FAIL_COUNT, 总大小: $(numfmt --to=iec $TOTAL_SIZE 2>/dev/null || echo ${TOTAL_SIZE}B), 耗时: ${DURATION}s"

# 失败则退出非零（供 cron / 监控告警）
if [[ $FAIL_COUNT -gt 0 ]]; then
    exit 1
fi