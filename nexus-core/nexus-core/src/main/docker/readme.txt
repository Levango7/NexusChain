# ============================================================================
# @deprecated since v2.1.0 — 旧版 v0.0.3 部署说明，保留仅作历史参考。
# 当前部署文档见仓库根 README.md 与 deploy/k8s/README.md。
# P2 清理项：标注 deprecated，后续迭代删除。
# ============================================================================
- Copy cpay.yml to some dir.
- Change the value of environment CPAY_MINER_COINBASE to your own coinbase in services.cpay_core.environment.
- Run command: docker -f cpay.yml up -ddocker
