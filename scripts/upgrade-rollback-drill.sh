#!/usr/bin/env bash
# ============================================================================
# upgrade-rollback-drill.sh — 升级/回滚演练（应用层，本机可复现）
# ============================================================================
# 目的：给 `docs/runbooks/upgrade-rollback.md` 第七节（应用层回滚）补一条**实跑记录**。
#       演练形态：单进程 gateway + **文件型 H2**（数据跨重启留存），做一次
#       「旧版本 → 新版本 → 回旧版本」的往返，验证三件事：
#         1) 升级后服务能起来且健康；
#         2) 升级**不丢数据**（升级前创建的商户/API Key/支付仍可查）；
#         3) 回滚后同样能起来、同样不丢数据。
#
# 边界（诚实声明）：
#   · 这是**应用层**演练，不涉及 helm / kubectl / 快照恢复（那些需要集群，本机没有）；
#   · 数据库迁移**不回滚**（runbook §7.2 的结论：加性 DDL，无需回退）；
#   · 共识变更**不在本演练范围**（runbook §一：共识变更不能靠回滚补救）；
#   · 生产真实地址/凭据/多副本**不在本演练范围**。
#
# 用法：
#   bash scripts/upgrade-rollback-drill.sh <旧版本.jar> <新版本.jar>
#   NEX_DRILL_PORT=18090 bash scripts/upgrade-rollback-drill.sh old.jar new.jar
#
# 怎么产出「旧版本 jar」：
#   git worktree add ../wt-old v2.76.0
#   cd ../wt-old && ./gradlew :nexus-gateway:bootJar -x test
#   # 产物：../wt-old/nexus-gateway/build/libs/nexus-gateway-2.76.0.jar
#
# 退出码：0=演练全绿，1=有失败项，2=环境不满足（缺 java / 缺 jar）
# ============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

OLD_JAR="${1:-}"
NEW_JAR="${2:-}"
PORT="${NEX_DRILL_PORT:-18090}"
TIMEOUT="${NEX_DRILL_TIMEOUT:-300}"
TS="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="${NEX_DRILL_LOG_DIR:-${TMPDIR:-/tmp}/upgrade-rollback-drill-$TS}"
DB_DIR="${NEX_DRILL_DB_DIR:-$LOG_DIR/db}"
JWT_SECRET="${NEX_DRILL_JWT_SECRET:-drill-jwt-secret-0123456789abcdef0123456789}"
# 与 scripts/acceptance-e2e.sh 的默认签名密钥一致（sandbox 口径），避免脚本另传 secret
SIGNING_SECRET="${NEX_DRILL_SIGNING_SECRET:-sandbox-signing-secret-do-not-use-in-prod}"

[ -n "$OLD_JAR" ] && [ -n "$NEW_JAR" ] || { echo "用法：bash scripts/upgrade-rollback-drill.sh <旧.jar> <新.jar>" >&2; exit 2; }
[ -f "$OLD_JAR" ] || { echo "找不到旧版本 jar：$OLD_JAR" >&2; exit 2; }
[ -f "$NEW_JAR" ] || { echo "找不到新版本 jar：$NEW_JAR" >&2; exit 2; }
command -v java >/dev/null 2>&1 || [ -n "${JAVA_HOME:-}" ] || { echo "找不到 java" >&2; exit 2; }

# 选 JDK：NEX_DRILL_JAVA > JAVA_HOME/bin/java > PATH 上的 java；
# 并强制主版本 ≥17（Spring Boot 4 的 class 文件版本 61，Java 8/11 会直接 UnsupportedClassVersionError）。
if [ -n "${NEX_DRILL_JAVA:-}" ]; then
  JAVA_BIN="$NEX_DRILL_JAVA"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -n1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
case "$JAVA_MAJOR" in
  1[7-9]|[2-9][0-9]) : ;;
  *) echo "需要 JDK 17+，当前 $JAVA_BIN 是 Java $JAVA_MAJOR（可用 NEX_DRILL_JAVA 指定）" >&2; exit 2 ;;
esac

mkdir -p "$LOG_DIR" "$DB_DIR" || exit 2
SUMMARY="$LOG_DIR/summary.tsv"; : > "$SUMMARY"
N_PASS=0; N_FAIL=0; APP_PID=""; HEALTH_CODE=""

record() { # record <阶段> <项> <PASS|FAIL> <说明>
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$SUMMARY"
  case "$3" in
    PASS) N_PASS=$((N_PASS + 1)) ;;
    FAIL) N_FAIL=$((N_FAIL + 1)) ;;
  esac
}

stop_app() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}
trap stop_app EXIT INT TERM

start_app() { # start_app <标签> <jar> → 起来返回 0
  label="$1"; jar="$2"
  log="$LOG_DIR/app-$label.log"
  # JVM 认的路径：Git Bash/WSL 下的 /tmp/... 不是 Windows 路径，需转换（MSYS 提供 pwd -W）
  db_dir_jvm="$DB_DIR"
  if command -v pwd >/dev/null 2>&1; then
    case "$(uname -s 2>/dev/null)" in
      MINGW*|MSYS*|CYGWIN*) db_dir_jvm="$(cd "$DB_DIR" && pwd -W 2>/dev/null || echo "$DB_DIR")" ;;
    esac
  fi
  db_dir_jvm="$(printf '%s' "$db_dir_jvm" | tr '\\' '/')"
  # JWT_SECRET 必须显式传给网关：缺省时网关启动期随机生成，外部自签 ADMIN token 直接 403。
  NEXUS_REQUEST_SIGNING_SECRET="$SIGNING_SECRET" JWT_SECRET="$JWT_SECRET" \
  "$JAVA_BIN" -Xmx512m -jar "$jar" \
    --spring.profiles.active=sandbox \
    --server.port="$PORT" \
    --server.address=127.0.0.1 \
    --nexus.settlement.enabled=false \
    --spring.jpa.hibernate.ddl-auto=update \
    --spring.datasource.url="jdbc:h2:file:$db_dir_jvm/nexus_gateway;MODE=MySQL" \
    >"$log" 2>&1 &
  APP_PID=$!
  t0="$(date +%s)"
  while :; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "  [$label] 进程提前退出，日志尾："
      tail -n 15 "$log" | sed 's/^/    /'
      return 1
    fi
    # 就绪判据：**有 HTTP 响应**即视为已监听。
    # 注意：sandbox 档的 /actuator/health 会因下游指标（Redis 等）报 503 DOWN——那是
    # 「沙箱没起依赖」的已知口径，不代表进程没起来（dev 档才禁用下游指标）。
    code="$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null)"
    if [ -n "$code" ] && [ "$code" != "000" ]; then
      echo "  [$label] 已监听（health HTTP $code，$(( $(date +%s) - t0 ))s）"
      HEALTH_CODE="$code"
      return 0
    fi
    if [ $(( $(date +%s) - t0 )) -gt "$TIMEOUT" ]; then
      echo "  [$label] 等待 ${TIMEOUT}s 仍未 UP"
      tail -n 15 "$log" | sed 's/^/    /'
      return 1
    fi
    sleep 2
  done
}

# 自签 ADMIN token（HS256，与网关共用 JWT_SECRET）
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s); exp=$((now + 3600))
h=$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
p=$(printf '{"sub":"drill-admin","iat":%s,"exp":%s,"roles":"ADMIN"}' "$now" "$exp" | b64url)
s=$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)
JWT="$h.$p.$s"

# --- 签名 GET（与 scripts/acceptance-e2e.sh 的 canonicalV2 逐字节对齐）---
# 用途：直接查「升级前/回滚前」创建的支付 id 与商户 id，验证跨版本数据仍在。
_len() { printf '%s' "$1" | wc -c | tr -d ' '; }
canonical_v2() {
  printf 'NXC2|%s:%s|%s:%s|%s:%s|%s:%s|%s:%s|' \
    "$(_len "$1")" "$1" "$(_len "$2")" "$2" \
    "$(_len "$3")" "$3" "$(_len "$4")" "$4" \
    "$(_len "$5")" "$5"
}
http_code_get() { # http_code_get <path> → 打印 HTTP 状态码
  path="$1"
  ts="$(( $(date +%s) * 1000 ))"; nonce="$(openssl rand -hex 16)"
  sig="v2:$(canonical_v2 "$ts" "$nonce" "GET" "$path" "" \
    | openssl dgst -sha256 -hmac "$SIGNING_SECRET" -hex 2>/dev/null | awk -F'= ' '{print $NF}')"
  curl -sS -o /dev/null -w '%{http_code}' -X "GET" "$GW$path" \
    -H "X-NexusChain-ApiKey: $API_KEY" \
    -H "X-NexusChain-Timestamp: $ts" \
    -H "X-NexusChain-Nonce: $nonce" \
    -H "X-NexusChain-Signature: $sig" 2>/dev/null || echo 000
}

GW="http://127.0.0.1:$PORT"

prepare_merchant() {
  resp="$(curl -sS -X POST "$GW/api/v1/merchants/register" -H 'Content-Type: application/json' \
    -d '{"merchantName":"Rollback Drill","email":"rollback@example.test","settlementAddress":"1SandboxPlatformPubkey0000000000000000000000000"}' 2>/dev/null)"
  mid="$(printf '%s' "$resp" | sed -n 's/.*"id":\([0-9]\+\).*/\1/p' | head -1)"
  [ -z "$mid" ] && { echo "  注册商户失败：$resp"; return 1; }
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/merchants/$mid/verify" \
    -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{"status":"VERIFIED"}')"
  [ "$code" = "200" ] || { echo "  verify 返回 $code"; return 1; }
  k="$(curl -sS -X POST "$GW/api/v1/merchants/$mid/api-keys" -H "Authorization: Bearer $JWT" 2>/dev/null)"
  key="$(printf '%s' "$k" | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')"
  [ -z "$key" ] && { echo "  签发 API Key 失败：$k"; return 1; }
  MERCHANT_ID="$mid"; API_KEY="$key"
  echo "  商户就绪：merchantId=$mid key=${#key} 字符"
  return 0
}

# 跑一次端到端，返回报告路径；只断言 create/query 两步（旧版本可能没有结算确认）
run_acceptance() { # run_acceptance <标签>
  tag="$1"
  report="$LOG_DIR/acceptance-$tag.md"
  NEX_GATEWAY_URL="$GW" NEX_API_KEY="$API_KEY" NEX_MERCHANT_ID="$MERCHANT_ID" \
  NEX_ACCEPTANCE_REPORT="$report" bash scripts/acceptance-e2e.sh >"$LOG_DIR/acceptance-$tag.log" 2>&1
  echo "$report"
}

payment_id_of() { sed -n 's/.*| create payment | [0-9]* | PASS | id=\([^ |][^ |]*\).*/\1/p' "$1" | head -1; }
query_ok() { grep -q '| query payment | 200 | PASS |' "$1"; }

echo "== 升级/回滚演练（应用层，本机） =="
echo "旧版本 jar: $OLD_JAR"
echo "新版本 jar: $NEW_JAR"
echo "端口: $PORT   数据目录: $DB_DIR   日志: $LOG_DIR"

# ---------------------------------------------------------------- 阶段 1：旧版本
echo
echo "[阶段 1] 旧版本启动 + 造数"
if start_app old "$OLD_JAR"; then
  record phase1 "旧版本启动" PASS "已监听（health HTTP $HEALTH_CODE）"
else
  record phase1 "旧版本启动" FAIL "未在 ${TIMEOUT}s 内就绪"
  echo "== 结果 =="; echo "PASS=$N_PASS FAIL=$N_FAIL"; echo "明细：$SUMMARY"; exit 1
fi
if prepare_merchant; then
  record phase1 "商户准备" PASS "merchantId=$MERCHANT_ID"
else
  record phase1 "商户准备" FAIL "注册/verify/api-key 失败"
  exit 1
fi
R1="$(run_acceptance old)"
PID_OLD="$(payment_id_of "$R1")"
if query_ok "$R1" && [ -n "$PID_OLD" ]; then
  record phase1 "旧版本下单→查询" PASS "paymentId=$PID_OLD"
else
  record phase1 "旧版本下单→查询" FAIL "见 $R1"
  echo "  报告：$R1"; exit 1
fi

# ---------------------------------------------------------------- 阶段 2：升级
echo
echo "[阶段 2] 升级到新版本（同一数据目录）"
stop_app
if start_app new "$NEW_JAR"; then
  record phase2 "新版本启动" PASS "已监听（health HTTP $HEALTH_CODE）"
else
  record phase2 "新版本启动" FAIL "未在 ${TIMEOUT}s 内就绪"
  exit 1
fi
R2="$(run_acceptance new)"
PID_NEW="$(payment_id_of "$R2")"
if query_ok "$R2"; then
  record phase2 "新版本下单→查询" PASS "paymentId=$PID_NEW"
else
  record phase2 "新版本下单→查询" FAIL "见 $R2"
fi
if [ -n "$PID_OLD" ]; then
  code_pay="$(http_code_get "/api/v1/payments/$PID_OLD")"
  code_mer="$(http_code_get "/api/v1/merchants/$MERCHANT_ID")"
  if [ "$code_pay" = "200" ]; then
    record phase2 "升级后旧支付仍可查" PASS "GET /api/v1/payments/$PID_OLD ⇒ 200"
  else
    record phase2 "升级后旧支付仍可查" FAIL "GET /api/v1/payments/$PID_OLD ⇒ $code_pay"
  fi
  if [ "$code_mer" = "200" ]; then
    record phase2 "升级后旧商户仍可查" PASS "GET /api/v1/merchants/$MERCHANT_ID ⇒ 200"
  else
    record phase2 "升级后旧商户仍可查" FAIL "GET /api/v1/merchants/$MERCHANT_ID ⇒ $code_mer"
  fi
else
  record phase2 "升级后旧数据仍可查" SKIP "阶段 1 未拿到 paymentId"
fi

# ---------------------------------------------------------------- 阶段 3：回滚
echo
echo "[阶段 3] 回滚到旧版本（同一数据目录）"
stop_app
if start_app old-rollback "$OLD_JAR"; then
  record phase3 "旧版本重启（回滚）" PASS "已监听（health HTTP $HEALTH_CODE）"
else
  record phase3 "旧版本重启（回滚）" FAIL "未在 ${TIMEOUT}s 内就绪"
  exit 1
fi
R3="$(run_acceptance old-rollback)"
if query_ok "$R3"; then
  record phase3 "回滚后下单→查询" PASS "paymentId=$(payment_id_of "$R3")"
else
  record phase3 "回滚后下单→查询" FAIL "见 $R3"
fi
if [ -n "$PID_OLD" ]; then
  code_pay="$(http_code_get "/api/v1/payments/$PID_OLD")"
  code_mer="$(http_code_get "/api/v1/merchants/$MERCHANT_ID")"
  if [ "$code_pay" = "200" ]; then
    record phase3 "回滚后旧支付仍可查" PASS "GET /api/v1/payments/$PID_OLD ⇒ 200"
  else
    record phase3 "回滚后旧支付仍可查" FAIL "GET /api/v1/payments/$PID_OLD ⇒ $code_pay"
  fi
  if [ "$code_mer" = "200" ]; then
    record phase3 "回滚后旧商户仍可查" PASS "GET /api/v1/merchants/$MERCHANT_ID ⇒ 200"
  else
    record phase3 "回滚后旧商户仍可查" FAIL "GET /api/v1/merchants/$MERCHANT_ID ⇒ $code_mer"
  fi
else
  record phase3 "回滚后旧数据仍可查" SKIP "阶段 1 未拿到 paymentId"
fi
stop_app

# ---------------------------------------------------------------- 汇总
echo
echo "== 结果 =="
while IFS="$(printf '\t')" read -r phase item st note; do
  [ -z "$phase" ] && continue
  printf '%-8s %-22s %-5s %s\n' "$phase" "$item" "$st" "$note"
done < "$SUMMARY"
echo
echo "PASS=$N_PASS  FAIL=$N_FAIL"
echo "报告与日志：$LOG_DIR"
if [ "$N_FAIL" -gt 0 ]; then exit 1; fi
exit 0
