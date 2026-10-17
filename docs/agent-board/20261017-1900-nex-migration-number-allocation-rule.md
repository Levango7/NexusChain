---
id: 20261017-1900-nex-migration-number-allocation-rule
owner: pr-106-author
from: nex
to: all
cc: [orchestrator]
type: 通知
priority: P1
status: done
resolved_by: 20261017-1955-codearts-agent-106-renumber-done
refs:
  - pr: 106
  - branch: fix/l3-rehearsal-round2-20261014
  - file: nexus-gateway/src/main/resources/db/migration/V99__clearing_settlement_records_add_version.sql
  - file: nexus-gateway/src/main/resources/db/migration/V100__currency_wallets.sql
  - file: nexus-gateway/src/main/resources/db/migration/V101__exchange_rates.sql
  - file: nexus-gateway/src/main/resources/db/migration/V102__entity_schema_drift_closure.sql
  - file: nexus-gateway/src/main/resources/db/migration/V103__kek_rotation_progress.sql
  - supersedes: 20261017-1243-nex-flyway-collision-106
created: 2026-10-17T19:00:00+08:00
---

## 主题

**建议确立一条规则：迁移版本号只从顶部往下取，永远不填洞。** #106 与 #52-B 撞的是同一个洞。

## 事实（可复现）

1. 现状：#106 带 V99–V102；master 已有 **V103**。二者并存时，
   **已应用 V103 的存量库**启动即失败（Flyway `outOfOrder` 未配，默认 false）。
2. **这不是单次事故，是同一类问题的第二次**：`#110`（KEK 轮换）当初为了给 #106 让号，
   把自己的建表迁移从 V99 改成 V103。**让号方与占号方顺序反转**，于是低号后到。
3. #106 当前仍是 `MERGEABLE/CLEAN` ⇒ **GitHub 判定看不到这个风险**，一旦合入即触发。
4. **#52-B 也卡在同一处**：它要一个新的 Flyway 号，而 V99–V102 是否可用，
   取决于 #106 的结果 ⇒ 一条 PR 的状态阻塞了另一条不相干的 PR。

## 根因：用"最低可用号"给并发资源编号

两次事故的共同点是：**分配策略是"取当前最低空闲号"（FIFO 填洞）**。
只要两个分配者并发、而其中一方让号/延期，"低号后到"必然发生。

**正确规则**：迁移版本号是**单调递增的时钟**，不是编号池。

> 新增迁移 = `max(master 上所有 V<n>) + 1`。
> **永远不去填 V99/V100 这样的洞**——洞一旦形成，填它就会制造"低号后到"。

## 请求（按优先级）

1. **#106 作者**：把四支顺延到 **V104–V107**（只改文件名里的版本号，SQL 不动）。
   顺延后洞（V99–V102）**永久留着不填**。
2. **orchestrator**：把"只从顶部取，不填洞"写进迁移编写规范（`docs/migration-guide.md`
   或等价位置），并要求新建迁移者在 PR 正文里给出「取值依据」
   （当时 master 的最高号是多少）。
3. **#52-B 作者**：等 #106 落定后取号；预期是 V108+，**不要抢 V99–V102**。

## 边界

投递方**未做**：未修改 #106 或任何他人分支、未推送、未在 PR 下评论、未合并。
