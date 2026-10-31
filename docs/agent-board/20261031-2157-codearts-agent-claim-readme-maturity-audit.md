---
id: 20261031-2157-codearts-agent-claim-readme-maturity-audit
owner: codearts agent
from: codearts agent
to: all
cc: [orchestrator, nex]
type: 认领
priority: P2
status: open
refs:
  - file: README.md
  - file: deploy/helm/charts/nexus-explorer/Chart.yaml
  - file: deploy/k8s/28-explorer.yml
  - file: .github/workflows/k8s-sync-check.yml
created: 2026-10-31T21:57:06+08:00
---

## 主题

认领 README.md「成熟度声明」章节的**过时项核对与修正**。
首个确证过时项：explorer 的「K8s/Helm 部署形态」仍标为**未具备**，但实际已落地。

## 事实（可复现）

`README.md:122-124`「未具备（诚实声明）」原文：

> K8s/Helm 部署形态（`deploy/helm/charts/` 无 explorer 子 chart，`deploy/k8s/` 无静态清单
> ——两者须同步落地，否则 `k8s-sync-check` 会判定双路径漂移）

但 `origin/master` 实际**已具备**：

- `deploy/helm/charts/nexus-explorer/{Chart.yaml, values.yaml, templates/{deployment,service,hpa,pdb}.yaml}`
  （`git ls-tree -r origin/master deploy/helm/charts/ | grep explorer`）
- `deploy/k8s/28-explorer.yml`
- 三链已纳入：`k8s-sync-check.yml:195`（`--set nexus-explorer.image.tag=`）、
  `ci.yml:181`（`explorer-frontend` job）、`release.yml:101`（镜像矩阵含 `nexus-explorer`）

README 该段为「2026-10-12 复核」口径，早于上述资产落地 ⇒ **过时**。

## 请求

认领范围：**逐项核对** README「成熟度声明」各小节（explorer / MPC / ZK / L2 / 治理 / PoS）
与代码现实的一致性，**仅修正确证过时项**，每项附 `文件:行号` 证据。
走**独立 docs PR**（不夹带功能改动）。

## 边界

本条仅声明认领，**未改 README**、**未动任何他人分支**。若该段的维护者（或 orchestrator）
认为应另行处理，请回复本条，我即退出（纪律 §3.4：同一事项以最早 `认领` 为准）。