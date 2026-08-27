#!/usr/bin/env bash
# ============================================================================
# zk-setup-ceremony.sh —— NexusChain L2 ZK 可信设置仪式（可重复执行）
#
# 依据 .codeartsdoer/specs/zk-groth16-realization/plan.md ZK-A1-R4：
#   可信设置仪式固化：离线生成参数 → 导出 pk/vk hex → setup-external 导入。
#
# 两个阶段（同一脚本，子命令区分）：
#   generate <circuit-json> <bundle-dir>  离线/隔离环境：生成并导出仪式产物
#   import   <circuit-json> <bundle-dir>  生产环境：导入仪式产物并验证
#
# 产物（bundle-dir）：
#   pk.hex / vk.hex      证明/验证密钥 hex（setup-external 载荷）
#   fingerprint          电路指纹（导入后应一致）
#   SHA256SUMS           产物完整性校验（防篡改/传输损坏）
#
# 安全要求（详见 docs/zk-setup-ceremony.md）：
#   * toxic waste（α,β,γ,δ）在 setup 完成后即销毁，不落盘、不导出
#   * generate 阶段必须在离线/物理隔离环境执行，产物经可信通道传输
#   * 产物文件权限 0600，bundle 目录 0700
#   * 导入后必须跑 verify 冒烟验证（--verify 或 /v1/verify-sep）
#
# 依赖：bash >= 4, curl, openssl（sha256sum 亦可）
# 环境变量：SERVICE_URL（默认 http://localhost:50062）
# ============================================================================
set -euo pipefail

SERVICE_URL="${SERVICE_URL:-http://localhost:50062}"
# setup 持久化目录（与 setup_store.rs setup_dir() 一致；可用 GROTH16_SETUP_DIR 覆盖）
SETUP_DIR="${GROTH16_SETUP_DIR:-./groth16-setup}"

usage() {
    echo "用法: $0 {generate|import} <circuit-json-file> <bundle-dir> [--verify]" >&2
    echo "  generate  离线/隔离环境：调 /v1/setup 生成参数，导出 pk/vk hex 到 bundle-dir" >&2
    echo "  import    生产环境：读取 bundle-dir 产物，经 /v1/setup-external 导入并校验" >&2
    echo "  --verify  导入后执行 /v1/verify-sep 冒烟验证（需服务端已持久化 proof）" >&2
    echo "环境变量: SERVICE_URL（默认 http://localhost:50062）" >&2
    exit 1
}

die() { echo "错误: $*" >&2; exit 1; }

[ $# -ge 3 ] || usage
MODE="$1"; CIRCUIT_JSON="$2"; BUNDLE_DIR="$3"; VERIFY=0
for a in "$@"; do [ "$a" = "--verify" ] && VERIFY=1; done

[ -f "$CIRCUIT_JSON" ] || die "电路 JSON 文件不存在: $CIRCUIT_JSON"
mkdir -p "$BUNDLE_DIR" || die "无法创建 bundle 目录: $BUNDLE_DIR"
chmod 700 "$BUNDLE_DIR" 2>/dev/null || true

# ---- generate：离线生成参数并导出 hex 产物 ----
if [ "$MODE" = "generate" ]; then
    echo "[ceremony] 阶段 generate：调用 $SERVICE_URL/v1/setup ..."
    RESP=$(curl -sf --max-time 30 -H "Content-Type: application/json" \
        -d "{\"circuit_json\":$(cat "$CIRCUIT_JSON")}" \
        "$SERVICE_URL/v1/setup") || die "调用 /v1/setup 失败（服务未启动？）"

    FP=$(echo "$RESP" | sed -n 's/.*"fingerprint":"\([^"]*\)".*/\1/p')
    VK_HEX=$(echo "$RESP" | sed -n 's/.*"vk_hex":"\([^"]*\)".*/\1/p')
    ERR=$(echo "$RESP" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')
    [ -n "$FP" ] || die "响应无 fingerprint: $RESP"
    [ -n "$VK_HEX" ] || die "响应无 vk_hex: $RESP"
    [ -z "$ERR" ] || die "setup 失败: $ERR"

    # pk 二进制落盘于 setup_dir/<fp>/pk.bin（/v1/setup 仅返回 vk_hex）
    PK_BIN="$SETUP_DIR/$FP/pk.bin"
    [ -f "$PK_BIN" ] || die "pk 二进制未找到: $PK_BIN（服务 setup 目录与 GROTH16_SETUP_DIR 一致？）"
    PK_HEX=$(xxd -p "$PK_BIN" | tr -d '\n' | tr 'a-f' 'A-F')
    [ -n "$PK_HEX" ] || die "pk hex 导出为空"

    # 写产物（0600）
    echo "$PK_HEX" > "$BUNDLE_DIR/pk.hex"; chmod 600 "$BUNDLE_DIR/pk.hex"
    echo "$VK_HEX" > "$BUNDLE_DIR/vk.hex"; chmod 600 "$BUNDLE_DIR/vk.hex"
    echo "$FP" > "$BUNDLE_DIR/fingerprint"; chmod 600 "$BUNDLE_DIR/fingerprint"
    ( cd "$BUNDLE_DIR" && openssl dgst -sha256 -r pk.hex vk.hex fingerprint > SHA256SUMS )
    chmod 600 "$BUNDLE_DIR/SHA256SUMS"

    echo "[ceremony] generate 完成：fingerprint=$FP"
    echo "[ceremony] 产物: $BUNDLE_DIR/{pk.hex,vk.hex,fingerprint,SHA256SUMS}"
    echo "[ceremony] ⚠️  offline 环境：toxic waste 已销毁（见 docs/zk-setup-ceremony.md）；"
    echo "[ceremony]    产物须经可信通道（加密/离线介质）传输至生产环境。"
    exit 0
fi

# ---- import：生产环境导入仪式产物并验证 ----
if [ "$MODE" = "import" ]; then
    [ -f "$BUNDLE_DIR/pk.hex" ] || die "缺少 $BUNDLE_DIR/pk.hex"
    [ -f "$BUNDLE_DIR/vk.hex" ] || die "缺少 $BUNDLE_DIR/vk.hex"
    [ -f "$BUNDLE_DIR/fingerprint" ] || die "缺少 $BUNDLE_DIR/fingerprint"

    # 完整性校验
    if [ -f "$BUNDLE_DIR/SHA256SUMS" ]; then
        ( cd "$BUNDLE_DIR" && openssl dgst -sha256 -r -verify SHA256SUMS 2>/dev/null ) || true
        # 逐文件比对（openssl -r 校验语法因版本而异，这里用 sha256sum 严格校验）
        ( cd "$BUNDLE_DIR" && sha256sum -c SHA256SUMS >/dev/null 2>&1 ) \
            || die "SHA256 校验失败：产物可能被篡改或损坏"
        echo "[ceremony] SHA256 完整性校验通过"
    else
        echo "[ceremony] 警告：无 SHA256SUMS，跳过完整性校验" >&2
    fi

    PK_HEX=$(cat "$BUNDLE_DIR/pk.hex")
    VK_HEX=$(cat "$BUNDLE_DIR/vk.hex")
    EXPECTED_FP=$(cat "$BUNDLE_DIR/fingerprint")

    echo "[ceremony] 阶段 import：调用 $SERVICE_URL/v1/setup-external ..."
    RESP=$(curl -sf --max-time 60 -H "Content-Type: application/json" \
        -d "{\"circuit_json\":$(cat "$CIRCUIT_JSON"),\"pk_hex\":\"$PK_HEX\",\"vk_hex\":\"$VK_HEX\"}" \
        "$SERVICE_URL/v1/setup-external") || die "调用 /v1/setup-external 失败"

    IMPORTED_FP=$(echo "$RESP" | sed -n 's/.*"fingerprint":"\([^"]*\)".*/\1/p')
    IMPORTED_OK=$(echo "$RESP" | sed -n 's/.*"imported":\([a-z]*\).*/\1/p')
    IMPORT_ERR=$(echo "$RESP" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p')
    [ -n "$IMPORTED_FP" ] || die "导入响应无 fingerprint: $RESP"
    [ "$IMPORTED_OK" = "true" ] || die "导入失败: ${IMPORT_ERR:-未知错误}"
    [ "$IMPORTED_FP" = "$EXPECTED_FP" ] || die "指纹不一致：期望 $EXPECTED_FP，实际 $IMPORTED_FP"

    echo "[ceremony] 导入成功：fingerprint=$IMPORTED_FP（与仪式产物一致）"

    if [ "$VERIFY" = "1" ]; then
        echo "[ceremony] 冒烟验证：$SERVICE_URL/v1/verify-sep ..."
        VRESP=$(curl -sf --max-time 30 -H "Content-Type: application/json" \
            -d "{\"circuit_json\":$(cat "$CIRCUIT_JSON"),\"fingerprint\":\"$IMPORTED_FP\"}" \
            "$SERVICE_URL/v1/verify-sep") || {
            echo "[ceremony] ⚠️  verify-sep 不可用（需先有持久化 proof），尝试 /v1/setup 幂等回归 ..." >&2
            VRESP=$(curl -sf --max-time 30 -H "Content-Type: application/json" \
                -d "{\"circuit_json\":$(cat "$CIRCUIT_JSON")}" "$SERVICE_URL/v1/setup") \
                || die "回归校验失败"
            echo "$VRESP" | grep -q "\"fingerprint\":\"$IMPORTED_FP\"" \
                || die "回归校验失败：setup 指纹与导入不一致"
            echo "[ceremony] setup 幂等回归通过（指纹一致）"
            exit 0
        }
        VALID=$(echo "$VRESP" | sed -n 's/.*"valid":\([a-z]*\).*/\1/p')
        [ "$VALID" = "true" ] || die "verify-sep 验证失败: $VRESP"
        echo "[ceremony] ✅ verify-sep 冒烟验证通过"
    fi
    exit 0
fi

usage
