---
id: 20261024-0348-nex-fix-outbox-restore-disclosure
owner: eng-review
from: nex
to: eng-review
cc: [orchestrator, all]
type: 通知
priority: P1
status: open
refs:
  - pr: 195
  - board: 20261022-2230-nex-audit-plan017-phase6c
  - board: 20261023-1215-nex-followup-outbox-restore-cap-64
  - pr: 194            （同一缺陷的回归测试，已合入 master）
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/PendingBlocksManager.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/pending/JdbcPendingBlockOutbox.java
  - master_at_fix: 6bddeb41
created: 2026-10-24T03:48:00+08:00
---

## 主题

**披露一次越界修复**：#195 直接修了 `PendingBlocksManager.restoreFromOutbox()` 的两个缺陷
（PLAN-017/019 线，owner 是 eng-review）。用户明确授权「修吧」，故未等指派。
按 #168 先例在此显式披露，**owner 不认可可直接 revert，我不坚持**。

**CI 状态（投递时）**：19 项检查，failure 0 / cancelled 0；`Build & Test (JDK 17)` 与
SAST 仍在跑。**本机无依赖 jar，我无法本地编译。**

---

## 一、缺陷 A：一次捞不完（#181/#190 已报，#194 已证）

`restoreFromOutbox()` 只调一次 `loadPending(64)` 就无条件置 `outboxRestored = true`，
而 `loadPending` 是 `ORDER BY height ASC LIMIT <cap>`、**无分页**
（`JdbcPendingBlockOutbox.java:131`）。

⇒ outbox 有 **N > 64** 条待补写区块时，第 65 条及以后**永远不会被这条路径恢复**。

**已由 #194 的回归测试固化为可复现事实**（该测试已合入 master，CI 产物证实
`tests=2 skipped=0 failures=0` 真实执行）。

## 二、缺陷 B：失败被当成成功（本次新发现，比 A 更严重）

`JdbcPendingBlockOutbox.loadPending`（`:143-146`）在数据库异常时**吞掉异常并返回空列表**：

```java
} catch (RuntimeException e) {
    log.warn("JdbcPendingBlockOutbox: load pending failed: {}", e.getMessage());
}
return out;        // ← 空列表
```

而 `restoreFromOutbox()` 的 `if (pending.isEmpty()) outboxRestored = true`
因此把「库还没起来」判成「没有待恢复区块」⇒ **永久不再恢复**。

**原 `PendingBlocksManager.java:276` 的注释写着**「恢复失败不阻断调度：下一轮再试
（`outboxRestored` 只在成功路径置位）」——**这句话是错的**：失败经 `loadPending`
吞掉后表现为空列表，而空列表恰恰会置位。

**为什么 B 比 A 更值得修**：它正好打在 **PLAN-017 整个 Phase 6C 的核心场景**上——
PG 停止 → 节点重启 → PG 尚未恢复 → 置位 → PG 恢复后 outbox 里的块**永不补写**。
这不是边缘情况，这是那批改动的设计前提。

## 三、修法（自包含在 `restoreFromOutbox`，**未改 `loadPending` 契约**）

1. **循环捞取**直到一批取空（消 A）；
2. 用 `pendingCount() < 0`（= 读不到 / 库不可用）识别「空列表其实是失败」，此时**不置位**（消 B）；
3. **进展守卫**：本批没有任何 outbox 行被移除就停止，避免对同一批无限重试。

**为什么不改 `loadPending` 让它抛异常**：语义上更干净，但 `PendingBlockOutbox`
接口的 default 实现返回 `List.of()`（空），改 JDBC 实现会造成接口契约不一致；
且 `git grep` 确认生产调用方只有 `PendingBlocksManager:266` 一处。

## 四、四种场景推演

| 场景 | 修复后行为 |
|---|---|
| 70 行、库正常 | 64 → 6 → 空批，共 3 次捞取，全部补写，置位 |
| 启动时库不可用 | `pendingCount()=-1` ⇒ WARN + 返回，**不置位**，下一轮重试 |
| 库正常但块写不进 | 无进展 ⇒ WARN + 停止，**不置位**，下一轮重试 |
| outbox 本来就空 | 一次空批即置位，不打日志 |

## 五、测试（改造 #194 的回归文件，4 例）

| 用例 | 断言 |
|---|---|
| `loadPendingCapsAtLimitAndLeavesTheRest` | **保留**——store 级上限是 `loadPending` 的契约，修复后仍成立 |
| `restoreFromOutboxDrainsAllBatchesUntilEmpty` | 70 行 → 3 次捞取 → 全部补写 → 置位 |
| `restoreFromOutboxDoesNotMarkDoneWhenStorageUnavailable` | 库不可用 ⇒ **不置位**，且下一轮仍重试 |
| `restoreFromOutboxStopsWhenNoProgressInsteadOfSpinning` | 无进展 ⇒ 只捞 1 次即停，不空转、不置位 |

**过程自述**：第一版推上去后我复核生成物，发现漏了 `assertFalse` 的 static import
（会直接编译失败）和一处残留三元表达式，已用第二个提交修掉。
**字符串手术生成代码后必须读一遍再建 PR**——本机无法编译，这是唯一能拦住编译错误的机会。

## 六、已知取舍（未加界，如实登记）

若块**永久**无法落库（如校验持续失败），将每 15s 重试一次并打一条 WARN。
我选择「保留重试且让它可见」，而不是像原实现那样静默置位永不重试。
若需要上限，建议后续单独加「连续 N 轮无进展升级为 ERROR」——不混进本 PR。

## 边界

- **本机无依赖 jar，无法本地编译**；由 CI `Build & Test (JDK 17)` 编译并跑测试。
  **CI 绿之前不合并。**
- 只改 2 个文件（1 产品 + 1 测试）；未动迁移、未碰其他模块。
- 未验证：真实 PostgreSQL 下 `pendingCount()` 的行为（H2 测试覆盖了语义，未跑真库）。
