#!/usr/bin/env bash
# =============================================================================
# PostgreSQL 恢复脚本（P2-T8：数据库生产化）
# =============================================================================
#
# 功能：
#   1. 从 S3 下载指定日期的备份文件
#   2. pg_restore --clean --if-exists 恢复（先删后建，幂等）
#   3. 支持指定库（nexus_gateway / nexus_wallet / all）
#   4. 恢复后可选运行 flyway validate 校验 schema 一致性
#
# 用法：
#   ./restore-postgres.sh --date 2026-08-08 --database nexus_gateway
#   ./restore-postgres.sh --date 2026-08-08 --database all
#   ./restore-postgres.sh --date 2026-08-08 --database nexus_gateway --target-host localhost:5433
#
# 环境变量：
#   S3_BUCKET            S3 备份桶（默认 nexus-backups）
#   S3_PREFIX            S3 路径前缀（默认 pg）
#   AWS_REGION           AWS 区域（默认 us-east-1）
#   TARGET_HOST          目标 PG 主机（默认 postgres，可被 --target-host 覆盖）
#   TARGET_PORT          目标 PG 端口（默认 5432）
#   TARGET_USER          目标 PG 用户（默认 postgres）
#   TARGET_PASSWORD      目标 PG 密码（必填）
#   FLYWAY_PATH          Flyway 可执行文件路径（可选，设置后恢复后自动 validate）
#
# 安全提示：
#   - 恢复操作会覆盖目标库现有数据（pg_restore --clean --if-exists）
#   - 生产环境恢复前务必先在测试实例演练
#   - 恢复前建议对目标库做一次快照备份
# =============================================================================

set -euo pipefail

# === 默认参数 ===
S3_BUCKET="${S3_BUCKET:-nexus-backups}"
S3_PREFIX="${S3_PREFIX:-pg}"
AWS_REGION="${AWS_REGION:-us-east-1}"
TARGET_HOST="${TARGET_HOST:-postgres}"
TARGET_PORT="${TARGET_PORT:-5432}"
TARGET_USER="${TARGET_USER:-postgres}"

RESTORE_DATE=""
DATABASE=""
SKIP_FLYWAY_VALIDATE=false

# === 参数解析 ===
while [[ $# -gt 0 ]]; do
    case "$1" in
        --date)
            RESTORE_DATE="$2"
            shift 2
            ;;
        --database)
            DATABASE="$2"
            shift 2
            ;;
        --target-host)
            TARGET_HOST="$2"
            shift 2
            ;;
        --target-port)
            TARGET_PORT="$2"
            shift 2
            ;;
        --target-user)
            TARGET_USER="$2"
            shift 2
            ;;
        --target-password)
            TARGET_PASSWORD="$2"
            shift 2
            ;;
        --skip-flyway-validate)
            SKIP_FLYWAY_VALIDATE=true
            shift
            ;;
        --help|-h)
            sed -n '2,35p' "$0"
            exit 0
            ;;
        *)
            echo "ERROR: 未知参数 $1" >&2
            exit 1
            ;;
    esac
done

# === 参数校验 ===
if [[ -z "$RESTORE_DATE" ]]; then
    echo "ERROR: --date 参数必填（格式 YYYY-MM-DD）" >&2
    exit 1
fi

if [[ -z "$DATABASE" ]]; then
    echo "ERROR: --database 参数必填（nexus_gateway / nexus_wallet / all）" >&2
    exit 1
fi

if [[ -z "${TARGET_PASSWORD:-}" ]]; then
    echo "ERROR: TARGET_PASSWORD 环境变量或 --target-password 参数必填" >&2
    exit 1
fi

# 确定要恢复的库列表
if [[ "$DATABASE" == "all" ]]; then
    DATABASES=("nexus_gateway" "nexus_wallet")
else
    DATABASES=("$DATABASE")
fi

# === 前置检查 ===
for cmd in pg_restore aws; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: $cmd 未安装" >&2
        exit 1
    fi
done

export PGPASSWORD="$TARGET_PASSWORD"

# === 安全确认 ===
echo "============================================================"
echo "  PostgreSQL 恢复操作 - 高风险操作"
echo "============================================================"
echo "  备份日期:     $RESTORE_DATE"
echo "  目标库:       ${DATABASES[*]}"
echo "  目标实例:     $TARGET_HOST:$TARGET_PORT"
echo "  目标用户:     $TARGET_USER"
echo "  Flyway 校验:  $([ "$SKIP_FLYWAY_VALIDATE" == false ] && echo "启用" || echo "跳过")"
echo "============================================================"
echo ""
echo "警告: 恢复将覆盖目标库现有数据（pg_restore --clean --if-exists）！"
read -p "确认恢复？输入 YES 继续: " CONFIRM
if [[ "$CONFIRM" != "YES" ]]; then
    echo "已取消"
    exit 0
fi

# === 恢复执行 ===
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

echo ""
echo "=== PostgreSQL 恢复开始 $(date) ==="

START_EPOCH=$(date +%s)
SUCCESS_COUNT=0
FAIL_COUNT=0

for DB in "${DATABASES[@]}"; do
    echo ""
    echo "--- 恢复库: $DB ---"

    # 1. 列出 S3 中该日期该库的备份文件（取最新一个）
    S3_LIST_KEY="$S3_PREFIX/$RESTORE_DATE/${DB}_"
    S3_KEY=$(aws s3 ls "s3://$S3_BUCKET/$S3_PREFIX/$RESTORE_DATE/" \
        --region "$AWS_REGION" | grep "${DB}_" | awk '{print $4}' | tail -1)

    if [[ -z "$S3_KEY" ]]; then
        echo "  ERROR: S3 中未找到 $RESTORE_DATE 的 $DB 备份" >&2
        FAIL_COUNT=$((FAIL_COUNT + 1))
        continue
    fi

    BACKUP_FILE="$TMP_DIR/$S3_KEY"
    echo "  S3 备份文件: s3://$S3_BUCKET/$S3_PREFIX/$RESTORE_DATE/$S3_KEY"

    # 2. 从 S3 下载
    if aws s3 cp "s3://$S3_BUCKET/$S3_PREFIX/$RESTORE_DATE/$S3_KEY" "$BACKUP_FILE" \
        --region "$AWS_REGION" --only-show-errors; then
        BACKUP_SIZE=$(stat -c %s "$BACKUP_FILE" 2>/dev/null || stat -f %z "$BACKUP_FILE")
        echo "  下载完成: $(numfmt --to=iec $BACKUP_SIZE 2>/dev/null || echo ${BACKUP_SIZE}B)"
    else
        echo "  ERROR: S3 下载失败" >&2
        FAIL_COUNT=$((FAIL_COUNT + 1))
        continue
    fi

    # 3. pg_restore 恢复
    #    --clean --if-exists：先 DROP 再 CREATE（幂等，可重复执行）
    #    --no-owner --no-privileges：不恢复 owner（跨环境恢复友好）
    #    --jobs=4：并行恢复（加速大库恢复）
    if pg_restore \
        --host="$TARGET_HOST" \
        --port="$TARGET_PORT" \
        --username="$TARGET_USER" \
        --dbname="$DB" \
        --clean \
        --if-exists \
        --no-owner \
        --no-privileges \
        --jobs=4 \
        --verbose \
        "$BACKUP_FILE" 2>&1 | tail -20; then
        echo "  恢复完成: $DB"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    else
        echo "  ERROR: pg_restore 失败: $DB" >&2
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi

    # 4. Flyway validate（可选）
    if [[ "$SKIP_FLYWAY_VALIDATE" == false && -n "${FLYWAY_PATH:-}" ]]; then
        echo "  运行 Flyway validate..."
        # Flyway locations 根据库选择对应的迁移目录
        case "$DB" in
            nexus_gateway)
                FLYWAY_LOCATIONS="classpath:db/migration/postgresql"
                FLYWAY_SCRIPT_DIR="nexus-gateway/src/main/resources/db/migration/postgresql"
                ;;
            nexus_wallet)
                FLYWAY_LOCATIONS="classpath:db/migration/postgresql"
                FLYWAY_SCRIPT_DIR="nexus-wallet-service/src/main/resources/db/migration/postgresql"
                ;;
        esac

        if "$FLYWAY_PATH" \
            -url="jdbc:postgresql://$TARGET_HOST:$TARGET_PORT/$DB" \
            -user="$TARGET_USER" \
            -password="$TARGET_PASSWORD" \
            -locations="filesystem:$FLYWAY_SCRIPT_DIR" \
            validate; then
            echo "  Flyway validate 通过: $DB"
        else
            echo "  WARNING: Flyway validate 失败: $DB（schema 可能不一致）" >&2
        fi
    fi
done

# === 数据完整性快速校验 ===
echo ""
echo "--- 数据完整性校验 ---"
for DB in "${DATABASES[@]}"; do
    TABLE_COUNT=$(psql \
        --host="$TARGET_HOST" \
        --port="$TARGET_PORT" \
        --username="$TARGET_USER" \
        --dbname="$DB" \
        --tuples-only \
        --command="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")
    echo "  $DB: $(echo $TABLE_COUNT | tr -d ' ') 张表"
done

# === 计时结束 ===
END_EPOCH=$(date +%s)
DURATION=$((END_EPOCH - START_EPOCH))

echo ""
echo "=== PostgreSQL 恢复结束 $(date) ==="
echo "成功: $SUCCESS_COUNT, 失败: $FAIL_COUNT, 耗时: ${DURATION}s"

if [[ $FAIL_COUNT -gt 0 ]]; then
    exit 1
fi