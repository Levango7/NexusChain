#!/usr/bin/env bash
# ============================================================================
# MPC 分层口径门禁（MPC tier policy gate）
# ============================================================================
# 背景（2026-10-02 决策，依据 docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md §2；
#       2026-10-08 随 GG20 退役（PLAN-001-R2）更新措辞）：
#   MPC「头牌能力默认关闭」经核实为**有意分层**，三级阶梯：
#     · dev/默认（application.yml）：real-grpc-enabled=false + cggmp-enabled=false
#       —— InMemoryMpcTransport + **无真实引擎（FROZEN skeleton 记账）**，零外部依赖；
#     · staging：NEX_MPC_TRANSPORT_GRPC=true（真实 gRPC+mTLS 传输，拓扑同 prod）
#       —— CGGMP21 未开（staging keyshare 供给未配），**同样没有真实签名引擎**；
#     · prod：transport + distributed-mode + cggmp-enabled 全开
#       —— 全分布式 CGGMP21 2-of-3。
#   本门禁把该口径固化为断言：staging 不得静默退回进程内传输；prod 不得
#   静默退回非分布式/进程内（支付系统的阈值签名路径不允许无声降级）。
#   dev 默认值（application.yml）不在此门禁范围——那是代码与单测的领地。
#   **GG20 退役后**不存在"退回 GG20"状态（代码已删）——cggmp-enabled=false
#   即无真实引擎，故 staging 缺 cggmp-enabled 时下方打 WARN 而非 FAIL（属待升级项）。
#
#   若 staging 要升级到 CGGMP21：先配齐 staging 的 keyshare 供给，
#   置 NEX_MPC_ENGINE_CGGMP_ENABLED=true，并把下方 WARN 升级为 assert_env 断言。
#
# 运行：bash scripts/check-mpc-tier-policy.sh（由 k8s-sync-check.yml 调用）
# ============================================================================
set -euo pipefail

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# 断言 helm values 中的环境变量值：file name expected context
assert_env() {
    file="$1"; name="$2"; expected="$3"; context="$4"
    actual=$(grep -E "^[[:space:]]*${name}:" "$file" | head -1 | sed "s/.*:[[:space:]]*//")
    if [ "$actual" != "\"$expected\"" ] && [ "$actual" != "$expected" ]; then
        fail "$context: $name 期望 \"$expected\"，实际 '$actual' —— MPC 分层口径（README『MPC 多方签名』/ 审计 2026-09-29 §2）不允许该降级"
    fi
    echo "OK  $context: $name = $expected"
}

assert_env deploy/helm/values-staging.yaml NEX_MPC_TRANSPORT_GRPC true "staging"
assert_env deploy/helm/values-prod.yaml NEX_MPC_TRANSPORT_GRPC true "prod"
assert_env deploy/helm/values-prod.yaml NEX_MPC_ENGINE_CGGMP_ENABLED true "prod"

# K8s 静态清单（deploy/k8s/25-signing.yml）：env 数组里 name 行的下一行是 value
k8s_value=$(grep -A1 "name: NEX_MPC_TRANSPORT_GRPC" deploy/k8s/25-signing.yml \
    | grep "value:" | head -1 | sed "s/.*value:[[:space:]]*//")
if [ "$k8s_value" != "\"true\"" ] && [ "$k8s_value" != "true" ]; then
    fail "deploy/k8s/25-signing.yml: NEX_MPC_TRANSPORT_GRPC 期望 \"true\"，实际 '$k8s_value'"
fi
echo "OK  k8s 静态清单: NEX_MPC_TRANSPORT_GRPC = true"

# staging 的 CGGMP21 开关：GG20 退役后这是"是否有真实引擎"的开关。
# 未开 = staging 走 FROZEN skeleton 记账（非真实签名）——属已记录待升级项，
# 打 WARN 不 FAIL（升级前置=staging keyshare 供给，见文件头注释）。
staging_cggmp=$(grep -E "^[[:space:]]*NEX_MPC_ENGINE_CGGMP_ENABLED:" deploy/helm/values-staging.yaml \
    | head -1 | sed "s/.*:[[:space:]]*//" || true)
if [ "$staging_cggmp" != "\"true\"" ] && [ "$staging_cggmp" != "true" ]; then
    echo "WARN staging: NEX_MPC_ENGINE_CGGMP_ENABLED 未置 true（当前='${staging_cggmp:-<未设置>}'）——staging 无真实 MPC 引擎（FROZEN skeleton 记账）。升级前置=配齐 staging keyshare 供给后置 true 并升级本 WARN 为断言（PLAN-001-R2 退役后口径）" >&2
else
    echo "OK  staging: NEX_MPC_ENGINE_CGGMP_ENABLED = true"
fi

echo "OK: MPC 分层口径一致（dev=进程内+无真实引擎 / staging=真实 gRPC+无真实引擎（待升级 CGGMP21）/ prod=全分布式 CGGMP21）"
