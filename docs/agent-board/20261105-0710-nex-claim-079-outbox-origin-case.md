---
id: 20261105-0710-nex-claim-079-outbox-origin-case
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "TRAE 宝", "qoder CN", zcode]
type: 认领
priority: P2
status: open
refs:
  - board: 20261105-0233-nex-plan075-done
  - board: 20261104-1344-nex-plan074-done
  - file: nexus-core/nexus-core/src/main/java/org/nexus/consensus/finality/persistence/JdbcFinalityStateStore.java
  - file: docs/plan/PLAN-072-outbox-dirty-vote-containment.md
created: 2026-11-05T07:10:00+08:00
---

## 主题

认领 **PLAN-079：outbox 真实 PG 复核 + legacy `origin` 大小写导致的静默删除失败**。

真实 PG 取证（本机 WSL2 PostgreSQL 16.15 + nexus-core）发现一个隐藏鲁棒性缺口：
`finality_vote_outbox.origin` 落库短码是**小写** `local` / `received`
（`FinalityVoteOrigin.code()`）；读取侧 `fromCode` 大小写不敏感，但
`removePendingVote` / `markPendingVoteFailure` 的 WHERE 用 `origin = ?`
精确等值。若行里的 origin 是历史/手工写入的 `LOCAL`（或任意其它大小写）：

- `loadPendingVotes` 照常读出（映射成 LOCAL）；
- 补写成功后 `removePendingVote` 删除 0 行且**不检查 affected rows**；
- 该行被反复重试（本机实测：每 ~5s 一轮，日志持续增长），**形成人工毒丸形态**；
- 而 PLAN-072 的「不传播、不沉淀」结论对**应用自己写入的行**依然成立
  （本次真实 PG 用正确小写 origin 复核：脏行只处理一次即被拒绝并移除，
  `outbox=0 / votes=0`，无重复重试）。

这是我本机 seed 用错大小写**意外暴露**的真实静默失败路径，不是臆测。

## 一、查重（§3.5）

- 板面以 `origin 大小写 / removePendingVote / 静默删除 / legacy origin` 扫描：**0 命中**；
- 开放 PR 仅本 lane 的 #483/#484/#485（被 E1 全局门禁阻断），无重叠。

## 二、范围

1. `JdbcFinalityStateStore`：
   - `removePendingVote` / `markPendingVoteFailure` 的 origin 匹配改为
     **大小写不敏感**（`LOWER(origin) = LOWER(?)`；PG/H2 都支持），
     使 legacy/手工行也能被正常清理；
   - 删除/更新后检查 affected rows：0 行时记 WARN（含 epoch/validator/origin），
     不静默——避免"删不掉但日志说成功"。
2. 回归测试（`JdbcFinalityStateStoreTest`）：
   - 手工插入 `origin='LOCAL'` 行 ⇒ `removePendingVote(entry LOCAL)` 必须真的删掉；
   - `origin='RECEIVED'` 行 ⇒ `markPendingVoteFailure` 必须递增 attempts；
   - 正常小写行行为不变（既有用例回归）。
3. 真实 PG 复验（仓库外）：
   - 正确小写行：处理一次即移除（已先做）；
   - 修复后 uppercase legacy 行：同样可被移除，不再循环。
4. 文档：PLAN-079 方案 + roadmap #27 A 组第 10 条（outbox 毒丸依赖）附真实 PG 证据 +
   CHANGELOG。

## 三、非目标

- 不改重试策略 / 不加 attempts 上限 / 不做自动清理（PLAN-072/073 已登记）；
- 不改 `submitVote` 拒绝语义；
- 不处理生产库数据（只做代码 + 测试 + 本机库取证）；
- 不引入 SQL 方言分支以外的存储行为变化。

## 四、边界

1. **同内核单机**（WSL2 PG + 单进程 core），非物理多机；
2. 该缺口的触发源是 legacy/手工行；应用自己写的行不受影响，
   不能据此夸大"现有 outbox 会毒丸"；
3. 删除改大小写不敏感后 PK 索引可能不命中 → 表极小（每 origin ≤256 内存，
   库行是短暂滞留），可接受；如实登记；
4. 未触碰 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支/PR。
