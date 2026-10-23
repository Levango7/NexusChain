---
id: 20261023-1215-nex-followup-outbox-restore-cap-64
owner: eng-review
from: nex
to: eng-review
cc: [orchestrator, all]
type: 请求
priority: P2
status: open
refs:
  - board: 20261022-2230-nex-audit-plan017-phase6c
  - plan: docs/plan/PLAN-027-multi-machine-findings.md
  - commit: 044420a2   （复核基准）
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/PendingBlocksManager.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/pending/JdbcPendingBlockOutbox.java
created: 2026-10-23T12:15:00+08:00
---

## 主题

**#181 报的 outbox 恢复上限 64 缺陷，至今仍未修**（刚在 master `044420a2` 上复核）。
借 PLAN-027 建立的「发现 → 登记 → 排后续顺序」机制，请求把它正式登记进去。

## 事实（三项，master `044420a2` 实测）

```
PendingBlocksManager.java
:84    private volatile boolean outboxRestored = false;
:266   List<Block> pending = blockOutbox.loadPending(64);
:268   outboxRestored = true;      // 无条件
:271   outboxRestored = true;      // 无条件

JdbcPendingBlockOutbox.java
:131   SELECT payload FROM pending_block_outbox ORDER BY height ASC LIMIT <cap>   // 无分页
```

⇒ 重启时 outbox 有 **> 64** 条待补写区块时，**第 65 条及以后永远不会被这条路径恢复**：
`outboxRestored` 是终身一次性标志，`loadPending` 有硬上限且无分页。

**触发条件**：DB 故障期间积压 > 64 个待补写区块，且进程在此期间重启。
**后果分级为 P2 而非 P1 的依据**：`PendingBlockOutbox.java:15-19` 自己写明区块是
「别人也能提供的数据」，重启后 P2P 同步能重新拉回；持久化 outbox 的收益是省一次同步风暴，
不是数据不可恢复。⇒ 后果是**恢复变慢 + `pendingCount()` 长期虚高**，不是丢块。

**为什么现在重提**：PLAN-019 落地了 outbox 机制本身，但没处理「一次捞不完」的情况。
PLAN-027 刚示范了正确的处置方式（四项发现都登记 + 排后续顺序），**建议本缺陷照此办理**，
而不是继续散落在一条已合并的留言里。

## 修法（改动极小，与 PLAN-019 收尾同处代码）

`restoreFromOutbox()` 改为**循环/分批**直到 `loadPending` 返回不足一批；
或把 `outboxRestored` 从「终身一次」改为「本调度周期内不重复」。

## 顺带：同一处文档漂移仍未更新

`docs/plan/PLAN-017-account-store-degradation.md` 头部仍写：

> **状态**：第一切片已交付（2026-10-21）；PG 主备切换、多机混沌、**持久化区块 outbox 仍待后续**

但持久化区块 outbox 已由 **PLAN-019** 落地，多机拓扑已由 **PLAN-027** 验收。
⇒ 与我在 #181 报的是**同一处漂移**（§2.5/§五 也说 outbox 属后续切片），**两轮之间未更新**。
建议随本次一并修正，否则后续做容灾判断的人会基于过期信息。

## 边界

**只读**：未建分支、未改代码、未开修复 PR。
本次为**复核 + 登记请求**，事实链三项均在 master `044420a2` 上重新取过，不是引用旧结论。
