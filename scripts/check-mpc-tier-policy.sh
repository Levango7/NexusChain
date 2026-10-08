#!/usr/bin/env bash
# ============================================================================
# MPC 分层口径门禁（MPC tier policy gate）
# ============================================================================
# 背景（2026-10-02 决策，依据 docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md §2；
#       2026-10-08 随 GG20 退役（PLAN-001-R2）更新措辞）：
#   MPC「头牌能力默认关闭」经核实为**有意分层**，三级阶梯：
#     · dev/默认（application.yml）：real-grpc-enabled=false + cggmp-enabled=false
#       —— InMemoryMpcTransport + **无真实引擎（FROZEN skeleton 记账）**，零外部依赖；
#     · staging：NEX_MPC_TRANSPORT_GRPC=true + distributed + cggmp-enabled 全开
#       —— 2026-10-08 起与 prod 同为**全分布式 CGGMP21**（此前只开传输层）；
#     · prod：transport + distributed-mode + cggmp-enabled 全开
#       —— 全分布式 CGGMP21 2-of-3。
#   本门禁把该口径固化为断言：staging 不得静默退回进程内传输；prod 不得
#   静默退回非分布式/进程内（支付系统的阈值签名路径不允许无声降级）。
#   dev 默认值（application.yml）不在此门禁范围——那是代码与单测的领地。
#   **GG20 退役后**不存在"退回 GG20"状态（代码已删）——cggmp-enabled=false
#   即无真实引擎，故 staging 缺 cggmp-enabled 时下方打 WARN 而非 FAIL（属待升级项）。
#
#   （2026-10-08 已完成 staging 升级：硬断言见文件末；前置清单见 values-staging.yaml）
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
# 2026-10-08：staging 已升级为全分布式 CGGMP21（与 prod 同口径）——
# 本项由 WARN **升级为硬断言**：staging 不得再无声退回 skeleton 记账。
# 前置清单（部署前须齐备，见 values-staging.yaml 同批注释）：
#   mpc-engine 3 副本（PVC 落盘）+ mpc-engine-secret（storage-key/auth-token）
#   + mpc-engine-tls（每 Pod 证书）+ nexus-mpc-certs（signing-service 客户端证书）
#   + 每钱包 DKG 仪式（scripts/mpc-wallet-ceremony.py，keyshare 供给）。
assert_env deploy/helm/values-staging.yaml NEX_MPC_ENGINE_CGGMP_ENABLED true "staging"
assert_env deploy/helm/values-staging.yaml NEX_MPC_ENGINE_CGGMP_SIGNERS '"0,1"' "staging"
assert_env deploy/helm/values-staging.yaml NEX_MPC_ENGINE_DISTRIBUTED true "staging"

echo "OK: MPC 分层口径一致（dev=进程内+无真实引擎（skeleton 记账）/ staging=全分布式 CGGMP21 / prod=全分布式 CGGMP21）"
