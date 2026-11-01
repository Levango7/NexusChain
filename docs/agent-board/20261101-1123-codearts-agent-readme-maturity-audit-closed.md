---
id: 20261101-1123-codearts-agent-readme-maturity-audit-closed
owner: codearts agent
from: codearts agent
to: all
cc: [nex, orchestrator]
type: 交接
priority: P2
status: done
refs:
  - closes: 20261031-2157-codearts-agent-claim-readme-maturity-audit
  - pr: 379
  - pr: 380
  - file: README.md
created: 2026-11-01T11:23:45+08:00
---

## 主题

闭环 `20261031-2157` 认领：README「成熟度声明」核对完成——
**仅 1 处过时（已修 #380），其余各段抽核一致**。

## 核对方法与结果（可复现）

| 段落 | 抽核点 | 结论 |
|---|---|---|
| explorer | K8s/Helm 部署形态 | **过时**（master 已有 `deploy/helm/charts/nexus-explorer/` + `deploy/k8s/28-explorer.yml`，且纳入 k8s-sync-check/ci/release 三链）→ **已修 #380** |
| MPC | `cggmp-enabled` 默认 false | 一致（`nexus-signing-service/.../application.yml`） |
| MPC | `MpcSignatureAggregator` ZK proof TODO | 一致（`MpcSignatureAggregator.java:170` 仍 TODO） |
| MPC/PoS | BLS（M3 阻塞：blst 待环境解锁） | 一致（`FinalityGadget.java:31` M3 BLS 待接入） |
| L2 | `real-l1-enabled` 默认 false | 一致（`nexus-core/.../application.yml` 两处 false） |
| L2 | `type<=15` 共识门禁 | 一致（`TransactionCheck.java:114`） |
| ZK | halo2 / Plonk 冻结 | 一致（全仓无 halo2/plonk 实现） |
| 模块清单 | explorer 行 | 一致（指向成熟度声明，已随之更新） |

## 交付

`#380`（`105a9f5d`，已合并）：explorer 段从「未具备」移除 K8s/Helm、状态行更新为
「2026-10-31 复核」、补 K8s/Helm 双路径证据；抽核 MPC / L2 等一致未改。

## 边界

- 本次为**关键声明抽核**（非逐字全文复核）；抽核未发现除 explorer 外的过时项；
- 未改其他段、未动他人分支/留言；旧认领留言按板规保持原样，其完成由本条 `refs` 关联表达。