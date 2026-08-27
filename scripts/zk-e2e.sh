#!/usr/bin/env bash
# ============================================================================
# zk-e2e.sh —— zk-groth16-service HTTP 端到端回归（ZK-A1-R5）
#
# 依据 .codeartsdoer/specs/zk-groth16-realization/plan.md ZK-A1-R5：
#   (a) cargo test（bridge 动态电路单测）→ 见 CI job（本脚本专注 HTTP 层）
#   (b) HTTP E2E：setup → prove → verify 全链路 curl 断言
#   (c) bench 输出阈值断言：verify_avg_ms < 100ms
#
# 用法：
#   SERVICE_URL=http://localhost:50062 scripts/zk-e2e.sh          # 服务已运行
#   scripts/zk-e2e.sh --start [二进制路径]                          # 脚本自启服务并清理
#   scripts/zk-e2e.sh --skip-bench                                # 跳过 bench 阈值
#
# 环境变量：
#   SERVICE_URL  服务地址（默认 http://localhost:50062）
#   BENCH_MAX_VERIFY_MS  bench verify 阈值（默认 100.0）
#   WAIT_SECS    启动等待上限（默认 30）
#
# 退出码：0 全部通过；1 断言失败；2 服务不可用；3 用法错误
# ============================================================================
set -euo pipefail

SERVICE_URL="${SERVICE_URL:-http://localhost:50062}"
BENCH_MAX_VERIFY_MS="${BENCH_MAX_VERIFY_MS:-100.0}"
WAIT_SECS="${WAIT_SECS:-30}"
START_MODE=0
SKIP_BENCH=0
BIN_PATH=""
for a in "$@"; do
    case "$a" in
        --start) START_MODE=1 ;;
        --skip-bench) SKIP_BENCH=1 ;;
        --start=*) START_MODE=1; BIN_PATH="${a#--start=}" ;;
        *) echo "未知参数: $a" >&2; exit 3 ;;
    esac
done

fail() { echo "❌ FAIL: $*" >&2; exit 1; }
ok() { echo "✅ $*"; }

# 演示电路（x^3 + x + 5 = 35，与 bridge.rs 测试一致）
CIRCUIT_JSON='{"num_public":1,"num_private":3,"witness":[1,35,3,9,27],"constraints":[{"a":{"2":1},"b":{"2":1},"c":{"3":1}},{"a":{"3":1},"b":{"2":1},"c":{"4":1}},{"a":{"4":1,"2":1,"0":5},"b":{"0":1},"c":{"1":1}}]}'

wait_health() {
    for i in $(seq 1 "$WAIT_SECS"); do
        if curl -sf --max-time 2 "$SERVICE_URL/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

cleanup() {
    [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null || true
}
trap cleanup EXIT

if [ "$START_MODE" = "1" ]; then
    if [ -z "$BIN_PATH" ]; then
        BIN_PATH="$(pwd)/zk-groth16-service/target/release/zk-groth16-service"
        [ -f "$BIN_PATH" ] || BIN_PATH="$(pwd)/target/release/zk-groth16-service"
    fi
    [ -x "$BIN_PATH" ] || fail "服务二进制不存在: $BIN_PATH（先 cargo build --release）"
    echo "[e2e] 启动服务: $BIN_PATH"
    "$BIN_PATH" >/tmp/zk-e2e-service.log 2>&1 &
    SRV_PID=$!
fi

echo "[e2e] 等待服务就绪: $SERVICE_URL ..."
wait_health || fail "服务 $WAIT_SECS 秒内未就绪（${BIN_PATH:-外部服务}）"
ok "服务就绪"

# ---- (b) setup → prove → verify 全链路 ----
echo "[e2e] 1/3 /v1/setup（生成真实 Groth16 参数）..."
SETUP_RESP=$(curl -sf --max-time 60 -H "Content-Type: application/json" \
    -d "{\"circuit_json\":$CIRCUIT_JSON}" "$SERVICE_URL/v1/setup") \
    || fail "POST /v1/setup 失败"
FP=$(echo "$SETUP_RESP" | sed -n 's/.*"fingerprint":"\([^"]*\)".*/\1/p')
VK_HEX=$(echo "$SETUP_RESP" | sed -n 's/.*"vk_hex":"\([^"]*\)".*/\1/p')
[ -n "$FP" ] || fail "setup 响应无 fingerprint: $SETUP_RESP"
[ -n "$VK_HEX" ] || fail "setup 响应无 vk_hex"
ok "setup OK: fingerprint=$FP（vk_hex ${#VK_HEX} 字符）"

echo "[e2e] 2/3 /v1/prove（真实证明生成）..."
PROVE_RESP=$(curl -sf --max-time 60 -H "Content-Type: application/json" \
    -d "{\"circuit_json\":$CIRCUIT_JSON}" "$SERVICE_URL/v1/prove") \
    || fail "POST /v1/prove 失败"
PROOF_HEX=$(echo "$PROVE_RESP" | sed -n 's/.*"proof_hex":"\([^"]*\)".*/\1/p')
[ -n "$PROOF_HEX" ] || fail "prove 响应无 proof_hex: $PROVE_RESP"
ok "prove OK: proof_hex ${#PROOF_HEX} 字符"

echo "[e2e] 3/3 /v1/verify（真实 BN254 配对验证）..."
VERIFY_RESP=$(curl -sf --max-time 60 -H "Content-Type: application/json" \
    -d "{\"circuit_json\":$CIRCUIT_JSON,\"public_inputs_hex\":[\"35\"]}" \
    "$SERVICE_URL/v1/verify") || fail "POST /v1/verify 失败"
VALID=$(echo "$VERIFY_RESP" | sed -n 's/.*"valid":\([a-z]*\).*/\1/p')
[ "$VALID" = "true" ] || fail "verify 应为 valid=true: $VERIFY_RESP"
ok "verify OK: valid=true（真实配对通过）"

# 反例：错误公共输入应验证失败
NEG_RESP=$(curl -sf --max-time 60 -H "Content-Type: application/json" \
    -d "{\"circuit_json\":$CIRCUIT_JSON,\"public_inputs_hex\":[\"36\"]}" \
    "$SERVICE_URL/v1/verify") || fail "POST /v1/verify（反例）失败"
NEG_VALID=$(echo "$NEG_RESP" | sed -n 's/.*"valid":\([a-z]*\).*/\1/p')
[ "$NEG_VALID" = "false" ] || fail "错误公共输入应 valid=false: $NEG_RESP"
ok "反例校验 OK: 错误输入 valid=false"

# ---- (c) bench 阈值断言 ----
if [ "$SKIP_BENCH" = "1" ]; then
    echo "[e2e] bench 已跳过（--skip-bench）"
else
    echo "[e2e] bench: GET /v1/bench（阈值 verify_avg_ms < $BENCH_MAX_VERIFY_MS）..."
    BENCH_RESP=$(curl -sf --max-time 120 "$SERVICE_URL/v1/bench") || fail "GET /v1/bench 失败"
    PROVE_MS=$(echo "$BENCH_RESP" | sed -n 's/.*"prove_avg_ms":\([0-9.]*\).*/\1/p')
    VERIFY_MS=$(echo "$BENCH_RESP" | sed -n 's/.*"verify_avg_ms":\([0-9.]*\).*/\1/p')
    ITERS=$(echo "$BENCH_RESP" | sed -n 's/.*"iterations":\([0-9]*\).*/\1/p')
    [ -n "$VERIFY_MS" ] || fail "bench 响应无 verify_avg_ms: $BENCH_RESP"
    ok "bench OK: iterations=$ITERS prove=${PROVE_MS}ms verify=${VERIFY_MS}ms"
    # 阈值断言（浮点比较用 awk）
    if awk "BEGIN{exit !($VERIFY_MS < $BENCH_MAX_VERIFY_MS)}"; then
        ok "bench 阈值通过: verify ${VERIFY_MS}ms < ${BENCH_MAX_VERIFY_MS}ms"
    else
        fail "bench 阈值超限: verify ${VERIFY_MS}ms >= ${BENCH_MAX_VERIFY_MS}ms"
    fi
fi

echo ""
echo "════════════════════════════════════════════"
echo "✅ zk-e2e 全部通过（setup→prove→verify + bench）"
echo "════════════════════════════════════════════"
