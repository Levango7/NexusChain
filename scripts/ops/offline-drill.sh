#!/usr/bin/env bash
# ============================================================================
# offline-drill.sh — 离线交付整链演练（只用交付包里的资产）
# ============================================================================
# 目的：证明「**交付包 = 客户机上不需要源码、不需要 Gradle、不需要联网**」也能
#       起服务、自证、跑完端到端验收（并可选做升级/回滚演练）。
#
# 它演练的是交付包里的三条自证链路：
#   ① 校验和比对（SHA256SUMS）——包没被改过；
#   ② doctor.sh（包内 scripts）——仓库自检；
#   ③ acceptance-e2e.sh（包内 scripts）——端到端四步；
#   ④ upgrade-rollback-drill.sh（包内 scripts）——可选，需另给一个旧版本 jar。
#
# 用法：
#   bash scripts/ops/offline-drill.sh <交付包目录> [--old-jar <旧.jar>] [--port <端口>]
#
# 退出码：0=演练全绿，1=有失败项，2=环境不满足（缺 jar / 缺 java / 缺 curl）
#
# 边界（诚实声明）：
#   · 本演练**不启动 Docker**，走「预构建 jar + 裸进程」安装路径
#     （compose 路径的 nexus-* 服务是 `build:` 从源码构建，离线机装不出 ⇒ 必须走 jar 路径）；
#   · 仍为 sandbox profile + 文件型 H2 + mock 连接器 ⇒ 无真实链上转账 / 真实 L1；
#   · 「真断网」未模拟（脚本本身不联网，但宿主机网络未断）——这一条由交付方在真断网环境复测。
# ============================================================================

set -uo pipefail

BUNDLE=""
OLD_JAR=""
PORT=18097
while [ $# -gt 0 ]; do
  case "$1" in
    --old-jar) shift; OLD_JAR="$1" ;;
    --port) shift; PORT="$1" ;;
    *) BUNDLE="$1" ;;
  esac
  shift
done

[ -n "$BUNDLE" ] || { echo "用法：offline-drill.sh <交付包目录> [--old-jar <旧.jar>] [--port <端口>]" >&2; exit 2; }
[ -d "$BUNDLE" ] || { echo "交付包目录不存在：$BUNDLE" >&2; exit 2; }

BUNDLE="$(cd "$BUNDLE" && pwd)"
WORK="${TMPDIR:-/tmp}/offline-drill-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$WORK" || exit 2
SUMMARY="$WORK/summary.tsv"; : > "$SUMMARY"
N_PASS=0; N_FAIL=0; N_SKIP=0; APP_PID=""

record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$SUMMARY"; case "$3" in PASS) N_PASS=$((N_PASS + 1));; FAIL) N_FAIL=$((N_FAIL + 1));; *) N_SKIP=$((N_SKIP + 1));; esac; }
stop_app() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null; sleep 2; kill -9 "$APP_PID" 2>/dev/null; APP_PID=""; }
trap stop_app EXIT INT TERM

command -v java >/dev/null 2>&1 || { echo "缺 java" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "缺 curl" >&2; exit 2; }
JAVA_BIN="${NEX_DRILL_JAVA:-java}"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && [ -z "${NEX_DRILL_JAVA:-}" ] && JAVA_BIN="$JAVA_HOME/bin/java"

echo "== 离线交付整链演练 =="
echo "交付包: $BUNDLE"
echo "工作目录: $WORK"
echo "端口: $PORT"

# --- ① 校验和 ---------------------------------------------------------
if [ -f "$BUNDLE/SHA256SUMS" ]; then
  (cd "$BUNDLE" && sha256sum -c SHA256SUMS >"$WORK/sha.log" 2>&1)
  rc=$?
  if [ "$rc" -eq 0 ]; then record "pack" "SHA256SUMS 校验" PASS "逐文件比对全绿"
  else record "pack" "SHA256SUMS 校验" FAIL "见 $WORK/sha.log"; fi
else
  record "pack" "SHA256SUMS 校验" SKIP "包内无 SHA256SUMS"
fi

# --- ② doctor（包内脚本） ----------------------------------------------
if [ -f "$BUNDLE/scripts/doctor.sh" ]; then
  (cd "$BUNDLE" && bash scripts/doctor.sh >"$WORK/doctor.log" 2>&1)
  rc=$?
  dline="$(grep -E '^PASS=' "$WORK/doctor.log" | tail -1)"
  if [ "$rc" -eq 0 ]; then record "pack" "包内 doctor.sh 体检" PASS "${dline:-rc=0}"
  else record "pack" "包内 doctor.sh 体检" FAIL "rc=$rc ${dline:-}"; fi
else
  record "pack" "包内 doctor.sh 体检" SKIP "包内无 scripts/doctor.sh"
fi

# --- ③ 起服务（包内 jar）+ 端到端验收（包内脚本） ----------------------
GATE_JAR="$(ls -1 "$BUNDLE/jars/"nexus-gateway-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | head -1)"
if [ -z "$GATE_JAR" ]; then
  record "run" "起 gateway（包内 jar）" SKIP "包内无 jars/nexus-gateway-*.jar（生成包时加 --with-jars）"
  echo; echo "== 结果 =="; cat "$SUMMARY"; echo "PASS=$N_PASS FAIL=$N_FAIL SKIP=$N_SKIP"; exit 0
fi

DB_DIR="$WORK/db"; mkdir -p "$DB_DIR"
JWT_SECRET="${NEX_DRILL_JWT_SECRET:-offline-drill-jwt-secret-0123456789abcdef}"
SIGNING_SECRET="${NEX_DRILL_SIGNING_SECRET:-sandbox-signing-secret-do-not-use-in-prod}"
TOKEN="offline-drill-metrics-token"

JWT_SECRET="$JWT_SECRET" NEXUS_REQUEST_SIGNING_SECRET="$SIGNING_SECRET" \
"$JAVA_BIN" -Xmx512m -jar "$GATE_JAR" \
  --spring.profiles.active=sandbox \
  --server.port="$PORT" --server.address=127.0.0.1 \
  --nexus.settlement.enabled=false \
  --spring.jpa.hibernate.ddl-auto=update \
  --spring.datasource.url="jdbc:h2:file:$DB_DIR/nexus_gateway;MODE=MySQL" \
  >"$WORK/app.log" 2>&1 &
APP_PID=$!
# 注意：JWT_SECRET 必须传给网关（缺省会随机生成 ⇒ 自签 ADMIN token 403）；
# 签名密钥同理会传（sandbox 自带默认值，显式传更稳）。

t0="$(date +%s)"; up=0
while [ $(( $(date +%s) - t0 )) -lt 300 ]; do
  code="$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null)"
  if [ -n "$code" ] && [ "$code" != "000" ]; then up=1; break; fi
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 2
done
if [ "$up" -eq 1 ]; then record "run" "起 gateway（包内 jar）" PASS "已监听（health HTTP $code，$(( $(date +%s) - t0 ))s）"
else record "run" "起 gateway（包内 jar）" FAIL "300s 内未就绪（见 $WORK/app.log）"; fi

if [ "$up" -eq 1 ] && [ -f "$BUNDLE/scripts/acceptance-e2e.sh" ]; then
  # 商户准备（公开注册 + 自签 ADMIN + 签发 Key）
  b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
  now="$(date +%s)"; exp=$((now + 3600))
  h="$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)"
  p="$(printf '{"sub":"drill-admin","iat":%s,"exp":%s,"roles":"ADMIN"}' "$now" "$exp" | b64url)"
  sg="$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$JWT_SECRET" -binary | b64url)"
  JWT="$h.$p.$sg"
  reg="$(curl -sS -X POST "http://127.0.0.1:$PORT/api/v1/merchants/register" -H 'Content-Type: application/json' \
    -d '{"merchantName":"Offline Drill","email":"offline@example.test","settlementAddress":"1SandboxPlatformPubkey0000000000000000000000000"}' 2>/dev/null)"
  mid="$(printf '%s' "$reg" | sed -n 's/.*"id":\([0-9]\+\).*/\1/p' | head -1)"
  if [ -z "$mid" ]; then
    record "run" "商户准备" FAIL "注册失败：$reg"
  else
    vcode="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/api/v1/merchants/$mid/verify" \
      -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{"status":"VERIFIED"}')"
    k="$(curl -sS -X POST "http://127.0.0.1:$PORT/api/v1/merchants/$mid/api-keys" -H "Authorization: Bearer $JWT" 2>/dev/null)"
    key="$(printf '%s' "$k" | sed -n 's/.*"apiKey":"\([^"]*\)".*/\1/p')"
    if [ "$vcode" = "200" ] && [ -n "$key" ]; then
      record "run" "商户准备" PASS "merchantId=$mid"
      report="$WORK/acceptance.md"
      (cd "$BUNDLE" && NEX_GATEWAY_URL="http://127.0.0.1:$PORT" NEX_API_KEY="$key" NEX_MERCHANT_ID="$mid" \
        NEX_ACCEPTANCE_REPORT="$report" bash scripts/acceptance-e2e.sh >"$WORK/acceptance.log" 2>&1)
      arc=$?
      res="$(sed -n 's/.*结果：\*\*\(PASS [0-9]* \/ FAIL [0-9]*\)\*\*.*/\1/p' "$report" 2>/dev/null | head -1)"
      if [ "$arc" -eq 0 ]; then record "run" "包内端到端验收" PASS "${res:-rc=0}"
      else record "run" "包内端到端验收" FAIL "rc=$arc ${res:-}"; fi
    else
      record "run" "商户准备" FAIL "verify=$vcode key=${#key}"
    fi
  fi
else
  record "run" "包内端到端验收" SKIP "网关未就绪或包内无 acceptance-e2e.sh"
fi
stop_app

# --- ④ 升级/回滚演练（可选，需旧版本 jar） --------------------------------
if [ -n "$OLD_JAR" ] && [ -f "$OLD_JAR" ] && [ -f "$BUNDLE/scripts/upgrade-rollback-drill.sh" ]; then
  (cd "$BUNDLE" && bash scripts/upgrade-rollback-drill.sh "$OLD_JAR" "$GATE_JAR" >"$WORK/drill.log" 2>&1)
  rc=$?
  dsum="$(grep -E '^PASS=[0-9]+' "$WORK/drill.log" | tail -1)"
  if [ "$rc" -eq 0 ]; then record "run" "包内升级/回滚演练" PASS "${dsum:-rc=0}"
  else record "run" "包内升级/回滚演练" FAIL "rc=$rc ${dsum:-}"; fi
else
  record "run" "包内升级/回滚演练" SKIP "未提供 --old-jar（或包内无演练脚本）"
fi

echo
echo "== 结果 =="
while IFS="$(printf '\t')" read -r phase item st note; do
  [ -z "$phase" ] && continue
  printf '%-6s %-24s %-5s %s\n' "$phase" "$item" "$st" "$note"
done < "$SUMMARY"
echo
echo "PASS=$N_PASS  FAIL=$N_FAIL  SKIP=$N_SKIP"
echo "明细: $WORK"
if [ "$N_FAIL" -gt 0 ]; then exit 1; fi
exit 0
