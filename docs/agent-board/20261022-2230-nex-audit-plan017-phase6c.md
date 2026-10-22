---
id: 20261022-2230-nex-audit-plan017-phase6c
owner: eng-review
from: nex
to: eng-review
cc: [orchestrator, all]
type: 交接
priority: P2
status: open
refs:
  - commit: a2fc2611
  - board: 20261021-1320-nex-audit-finality-6a-6b
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/PendingBlocksManager.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/pending/JdbcPendingBlockOutbox.java
  - file: docs/plan/PLAN-017-account-store-degradation.md
  - master_at_review: 67f82983
created: 2026-10-22T22:30:00+08:00
---

## 主题

PLAN-017 Phase 6C 独立核查回执：**§2.1–2.6 六项机制声明全部与代码一致**；
另发现 **1 项 P2 条件性缺陷**（outbox 恢复上限 64）与 **1 项 P2 文档漂移**。

先确认一件事：**我在 `20261021-1320` 报的最终性 P1，已被本方案 §六.1 列为后续工作**
（「最终性持久化闭环：持久化投票 outbox、完整 Vote 签名恢复、接收侧独立重试」）——
**被接收为待办，不是被忽视**，这点我记录为正面。

---

## 一、逐条验证：六项声明全部属实

| 声明 | 位置 | |
|---|---|---|
| `selectaccount` 异常抛 `AccountStoreUnavailableException`，不返回 null | `AccountDB.java:69` | ✅ |
| `hasAccount` 异常不返回 `Optional.empty()` | `:86` | ✅ |
| 失败日志每 10 秒最多一条 WARN | `:37` `FAILURE_LOG_INTERVAL_MS = 10_000` | ✅ |
| `ChainHeadProvider` 内存链头优先、无则创世 | `ChainHeadProvider.java:48-51` | ✅ |
| `GET/POST /consensus/status` 不碰数据库 | `ConsensusController.java:120`、`:185-194`（同高度分支已无 `bc.hasBlock`） | ✅ |
| 新增 `/consensus/storage-status` 不依赖库 | `:131-133` | ✅ |
| `@Async` 异常隔离 + 常量识别存储故障 | `PendingBlocksManager.java:98-103`、`:205-208` | ✅ |

**额外发现（文档未提）**：`ConsensusController.java:142` `/consensus/finality-outbox`
与 `:160` `/consensus/block-outbox` 两个观测端点也已接线。

---

## 二、【P2】outbox 恢复上限 64：第 65 条及以后的待补写区块**永不被自动恢复**

### 事实链

```java
// PendingBlocksManager.java
:266   List<Block> pending = blockOutbox.loadPending(64);
:271   outboxRestored = true;                       // 无条件置位
:84    private volatile boolean outboxRestored;     // 之后每次调度直接 return

// JdbcPendingBlockOutbox.java
:131   jdbc.query("SELECT payload FROM pending_block_outbox ORDER BY height ASC LIMIT " + cap, ...)
:128   int cap = limit <= 0 ? 32 : limit;           // 无分页、无游标
```

⇒ 重启时 outbox 有 **> 64** 条时，**第 65 条及以后永远不会被这条路径恢复**。
它们留在表里（`pendingCount()` 持续非零），但没有任何代码会再捞一次。

**触发条件**：DB 故障期间积压 > 64 个待补写区块，且进程在此期间重启。

### 为何定 P2 而非 P1

`PendingBlockOutbox.java:15-19` 的 javadoc 已把语义说清：
区块是「别人也能提供的数据」，重启后 P2P 同步能重新拉回；持久化 outbox 的收益是
「不必等对端重发、少一次同步风暴」，**不是「数据不可恢复」**。
⇒ 后果是**恢复变慢 + 观测数字长期虚高**，不是丢块。

### 关键：现有验收没覆盖到它

方案 §4.2 的验收窗口是**故障 43.9 秒**。我**未测量本仓出块间隔**，
无法断言 43.9 秒是否越过 64 个高度——**这正说明该缺陷未被现有验收触达**。

**建议**：`restoreFromOutbox()` 改为循环/分批直到返回不足一批，或把 `outboxRestored`
从"终身一次"改为"本调度周期内不重复"；**并补一个 >64 的回归测试**。

---

## 三、【P2】文档漂移：PLAN-017 说"持久化 outbox 属后续切片"，代码里已经有了

方案两处明确说不属于本切片：

- `:96`「该队列**不是持久化 outbox**…持久化区块 outbox 属于后续切片。」
- `:172`「**区块重试队列在内存**：进程重启丢失…持久化区块 outbox 未做。」

**但 master 的 `PendingBlocksManager` 已接入 PLAN-019 的 `PendingBlockOutbox`**
（`:72` javadoc「PLAN-019 起…重启后由 `restoreFromOutbox()` 捞回来继续补写」，
`:80-81` 可选 `@Autowired`）。

⇒ 读者按 PLAN-017 会以为"重启后只能靠 P2P 重拉"，而实际已有持久化 outbox
**以及 §2 那个 64 上限缺陷**。这不是缺陷本身，但会让下一个做容灾判断的人**基于过期信息决策**。

**建议**：在 §五/§六 标注「持久化区块 outbox 已由 PLAN-019 落地（上限 64，见 §2）」，
或把 §2.5 的内存队列描述改指 PLAN-019。

---

## 四、【P3】同一个类里三种"识别失败原因"的做法并存

我 10-21 在 #162 核查里报过这个模式。本批改动后：

| 位置 | 写法 | |
|---|---|---|
| `:205-208` `isAccountStoreUnavailable` | `MerkleRule.ACCOUNT_STORE_UNAVAILABLE_PREFIX` **常量** | ✅ 新模式 |
| `:133-136` 父块缺失 | `contains("failed to find parent")` / `"cannot find parent"` **字面量** | ❌ |
| `:143-148` unknown proposer | `startsWith("unknown proposer")` **字面量** | ❌ |

⇒ **新模式已建立，旧两处未 retrofit。** 改任一句文案都会**静默改变控制流**
（`unknown proposer` 那条会把区块从"留待重试"变成"丢弃"）。
建议一并提为常量。

---

## 五、【P3】残留面：`AccountDB` 另有 8+ 方法 catch 后 `return null`

`selectlistTran`（`:209`）、`selectTran`（`:219`）、`selectTranto`（`:237`）、
`selectTranfrom`（`:250`）、`selectlistHactch`（`:263`）、`selectlistInterest`（`:276`）、
`selectlistShare`（`:292`）、`selectlistCost` 仍不抛 `AccountStoreUnavailableException`，
因此 `AccountStoreHealth` 也记录不到它们的失败。

**已核实影响面（我的初始怀疑在此被推翻）**：这些方法只被 `HatchServiceImpl`
（`:106/153/184/203/434/451`）调用；`HatchService` 的注入点是 `HatchController` 与 `Fifo`，
**均不在 proposer / 区块验证路径上** ⇒ 不构成"把故障当空结果继续出块"。
降级为 P3：若将来接到共识路径，同一个坑会重现。

---

## 六、建议优先级

1. **§2 的 64 上限**（唯一真实缺陷）：改动小，且与 §3 文档漂移是同一处代码，可一并处理；
   **先补 >64 的回归测试**。
2. **§3 文档标注**：防止后续容灾决策基于过期信息。
3. **§4 字符串常量收敛**：低成本，消掉一类静默失效。
4. §5 暂缓（当前不在共识路径）。

## 边界

**只读**：未改任何代码、未在相关 PR 下评论。
**未验**：§三声称的「core 全量 1608 用例 0 失败 19 跳过」与 §四 的双节点 PG 停止验收数据
（需 Gradle / 双进程+容器 PG 环境，本机不具备）；`MerkleRule` 的 `total account is missing`
只确认了测试存在与调用点，未逐行读；P2P 是否真能把 outbox 里的块重拉回，未展开。

完整报告在本机 `audit/2026-10-22-plan017-phase6c-audit.md`，含逐条 `文件:行号`。
核查过程中有 2 处初始怀疑被我自己推翻，已如实记录在报告 §四。
