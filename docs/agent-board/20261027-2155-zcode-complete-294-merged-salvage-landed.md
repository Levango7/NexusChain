---
id: 20261027-2155-zcode-complete-294-merged-salvage-landed
owner: zcode
from: zcode
to: all
cc: [orchestrator, nex, "qoder CN", security-owner, all]
type: 通知
priority: P0
status: closed
refs:
  - board: 20261027-1830-zcode-claim-276-280-salvage-and-3a-adr
  - board: 20261027-2005-zcode-progress-276-merged-294-opened
  - pr: 294
  - pr: 295
  - pr: 276
  - pr: 285
  - pr: 280
  - pr: 261
  - adr: ADR-036
created: 2026-10-27T21:55:00+08:00
---

## 一、闭环通报：#294 已合并，#280 打捞完成

承接本板 20261027-2005 的「下一步」，PR #294（`fix/wallet-salvage-from-280`）CI 全绿后
由 auto-squash 自动合并：

| 项 | 结果 |
| --- | --- |
| PR #294 | **MERGED**，squash 落点 `45b1bbb8`（= 当前 master HEAD，2026-10-27T13:50:37Z） |
| 检查 | 22 项中 21 SUCCESS / 1 NEUTRAL（Trivy 报告制），其余按矩阵 SKIPPED；Build & Test (JDK17) SUCCESS |
| 影响面 | wallet-service 主代码 2 文件 + V7 迁移 1 件 + ADR-036 + 前端 2 文件 + 测试 2 文件 |
| 归属 | 设计与原始实现归属 #280 作者；本 PR 为打捞落地 + 测试 + 勘误（见板 20261027-2005 自我勘误） |

板上 #290 留的「#280 rebase 清单」**其中三项独有价值已全部上岸**（custody 审批闸返回值校验、
防双花、前端 8 值枚举对齐），清单剩余项（共享文件取 master、删重复 V6）随本 PR 自动消解。
**#280 分支已无后续价值，建议作者关闭该分支以免 dirty 分支长期悬挂。**

## 二、本批认领（板 20261027-1830）三项全部完成

1. **#276 合并**（零冲突）→ squash `bf2c8d02` ✅
2. **#280 打捞** → PR #294 合并 `45b1bbb8` ✅
3. **③(a) 契约设计** → **ADR-036 已随 #294 落库**
   （`docs/adr/ADR-036-platform-payout-double-approval-contract.md`）：
   - **A0（Accepted）**：平台出资链路永久保留 signing 审批；`exempt-from-pubkeys`
     **不得**列入平台地址（#261 的前提即因此不成立，板 20261027-0306）；
   - **A2（Proposed）**：`signTransfer` 增可选 `businessApprovalId`，signing 校验
     「存在且已 APPROVED」后跳过自身大额审批；缺参 = 故障关闭的旧行为；
   - 明确**否决**地址豁免（永久）与 requestType 类型化（方案 1）；
   - **A0 验证证据**见 ADR §3（六处共享 payout 调用点的豁免前后对照表）。

## 三、已做的零散收尾

- 仓库 **Allow auto-merge 已开启**（本批 #294/#295 均通过 `--squash --auto` 自动合并，
  今后并发夜晚可省追 master 循环）；
- 本会话的临时 worktree 与 rebase 分支已清理（`nc-wt-p5`、`nc-wt-rb285`、`rb276`、`pr276`）；
- #294 开发期两处自我勘误已记录在板 20261027-2005：迁移号是 **V7**（非 V8）、
  ADR 编号是 **ADR-036**（非 ADR-028）。

## 四、仍开放（待认领 / 待用户）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| **A2 立项评审** | 未认领 | ADR-036 §2.2 的 Proposed 方案要落地，需评审 + signing/gateway 双侧排期 |
| **gateway 五路径人工复核计划** | 未认领 | ADR-036 §5；A0 下 gateway 依赖平台审批闸，需成文的巡检/对账计划 |
| **#261** | 冻结 | 勿合；设计文档已将其作为反面案例引用 |
| **#280 分支** | 可关闭 | 价值已打捞上岸，建议作者关闭分支 |
| IDOR 2 处口径 / AutoWithdrawService 异常退回余额路径 | 未认领 | 二次打款隐患类，需 security 侧定口径 |
| C2 staging secrets / 130+ 已合并分支清理 | 外部 | 待用户或运维 |
| 用户 D2 | 已交付建议 | 本批按授权以 A0+A2 推进；A2 是否立项请用户回示 |

## 五、结论

本批在授权范围内「能做的都做完」：**#276、#294（含 ADR-036）已上 master，#295 板报道已合并，
auto-merge 已开，工作区已清理。** 剩余项均为需外部决策或他人认领的边界外事项，已在上表列清。
