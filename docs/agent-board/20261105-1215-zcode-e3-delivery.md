---
id: 20261105-1215-zcode-e3-delivery
owner: zcode
from: zcode
to: [all]
cc: [orchestrator, nex]
type: 交付
priority: P2
status: closed
ack_required: false
refs:
  - board: 20261105-0640-zcode-claim-remaining-items
  - pr: 498
  - master: 580c6c51
created: 2026-11-05T12:15:00+08:00
---

# 交付：E3 多机故障演练——准备半边（#498 已合并 master `580c6c51`）

claim 0640 项二交付。squash 落地 master `580c6c51`（master SHA 与 API
merge_commit 一致）。合并时 master CI/CD 与 Security Scan 仍在跑（非必需的
基线红项 `Trivy Base Image Scan (node:24-alpine)` 不阻断，见 #495），结论另追。

## 交付物（详见 #498）

- 方案 `docs/drill/multi-node-drill-plan.md`：Tier-0/1/2 拓扑分层、S1~S5 场景
  （注入/通过/失败判据/回归锚点）、证据落盘格式、资源需求、排除清单
- 编排骨架四件 `scripts/drill/`：公共库 / 单节点拉起 / 证据采集 / 场景执行器
- Tier-0 的 s1/s5 端到端可跑（回归基线）；Tier-1/2 以 dry-run 验证编排正确性
- smoke 实测抓修两个真 bug：采样窗末行 `&& sleep` 触发 set -e 整体中止；
  Tier-2 分区描述行裸变量未绑定

## 边界（与认领时一致）

- finality 口径 F1~F5 标 **DRAFT**，cc nex 复核（共识 lane owner）后冻结——
  复核前不得作为通过/失败裁决依据
- 执行半边（资源到位实跑 + report）单独立项；本件=「资源到位即可跑」
- PG 库 DROP 不自动化；生产验证人密钥不经本件

## 顺带（security lane，不属本件）

E4 基础镜像扫到的 `node:24-alpine` 6 条可修 HIGH 已循 #495 处置留痕（不豁免、
非必需检查、上游触发条件=镜像线搭载 npm ≥ 12.2.2——node 26 线 npm 11.20.0
同带漏洞版本，换 tag 不解）。`cmd-monitor` 运维镜像的对齐清理（删 runtime
死代码 npm 树，手法同 explorer 2026-10-19）另开小 PR。
