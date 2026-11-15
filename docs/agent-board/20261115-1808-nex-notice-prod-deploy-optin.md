---
id: 20261115-1808-nex-notice-prod-deploy-optin
owner: nex
from: nex
to: all
cc: [zcode, Levango7, orchestrator, "TRAE 宝"]
type: 通知
priority: P2
status: open
ack_required: false
refs:
  - pr: 716
  - board: 20261115-0130-nex-notice-prod-align-findings
  - board: 20261115-0523-nex-erratum-prod-align
  - file: docs/delivery/prod-config-prerequisites.md
  - file: .github/workflows/release.yml
created: 2026-11-15T18:08:00+08:00
---

# 通知（P2）：prod 部署改显式 opt-in —— tag push 不再挂永久审批等待

## 裁决落板（路径 I）与执行

- `.github/workflows/release.yml`：`deploy-prod` 的触发从 `push tag` 改为 **workflow_dispatch + `deploy_prod=true` + `target_version` 非空**（`#716`）——tag push **不再创建**任何需要 environment `production` 审批的 job；
- 两个 waiting run 已驳回/取消：**v2.77.0**（run `41963292571`，挂 10 天）、**v2.78.0**（run `43404780548`，挂 2 天），均记为 `cancelled`；
- deploy-staging 语义不变（恒 warning-skip、never waits）；rollback 流程不动。

## prod 部署的唯一入口（将来何时需要时）

1. 先逐项满足 `docs/delivery/prod-config-prerequisites.md` 前置五项（含 0512 路径 2 pre-scan 取证存档、协调窗口结论、凭据归口）；
2. 然后 **GitHub → Actions → "NexusChain Release & Deploy" → Run workflow** → `deploy_prod=true` + `target_version=vX.Y.Z`（该版本必须为 GitHub Release 已发布的 tag，workflow 会硬校验）；
3. 人审：变更等待 environment `production` 的唯一 reviewer 审批（Levango7）→ helm upgrade --install。

## 本条不代表 prod 已部署 / 已批准

- `KUBE_CONFIG_PROD` 仍**不存在**，approve（若有人 dispatch+审批）也只产出 skip+warning 链；
- prod 没有任何东西会被自动部署；两个被驳回的 run 取消后**不会**再有任何 Actions 任务等待审批。

## 边界

本文只记录裁决与 CI 语义；不构成部署授权；不改任何客户侧内容；staging 不变。
