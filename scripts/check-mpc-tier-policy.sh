#!/usr/bin/env bash
# ============================================================================
# MPC 分层口径门禁（MPC tier policy gate）
# ============================================================================
# 背景（2026-10-02 决策，依据 docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md §2）：
#   MPC「头牌能力默认关闭」经核实为**有意分层**，三级阶梯：
#     · dev/默认（application.yml）：real-grpc-enabled=false + cggmp-enabled=false
#       —— InMemoryMpcTransport + GG20 可信协调器，零外部依赖；
#     · staging：NEX_MPC_TRANSPORT_GRPC=true（真实 gRPC+mTLS 传输，拓扑同 prod）
#       —— 协议层仍走 GG20（CGGMP21 未开，keyshare 供给未配）；
#     · prod：transport + distributed-mode + cggmp-enabled 全开
#       —— 全分布式 CGGMP21 2-of-3。
#   本门禁把该口径固化为断言：staging 不得静默退回进程内传输；prod 不得
#   静默退回 GG20/进程内（支付系统的阈值签名路径不允许无声降级）。
#   dev 默认值（application.yml）不在此门禁范围——那是代码与单测的领地。
#
#   若未来 staging 要升级到 CGGMP21：先配齐 staging 的 keyshare 供给，
#   置 NEX_MPC_ENGINE_CGGMP_ENABLED=true，并在本脚本为 staging 追加断言。
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

echo "OK: MPC 分层口径一致（dev=进程内+GG20 / staging=真实 gRPC+GG20 / prod=全分布式 CGGMP21）"
