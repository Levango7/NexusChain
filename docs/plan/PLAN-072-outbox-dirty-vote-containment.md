# 方案 PLAN-072：`finality_vote_outbox` 脏行传播性确认（结论：无需清理）

- **状态**：已交付（2026-11-03）
- **前置**：PLAN-067（32 字节强校验，#407）、PLAN-069（votes 隔离，#418）、
  PLAN-071（checkpoints 隔离，#428）
- **范围**：outbox 脏行传播性的运行时取证 + 固化为回归门禁 + 文档
- **不包含**：outbox 清理脚本（**经取证确认不需要**）、retention、真实集群、物理多机

---

## 一、这是 PLAN-071 留下的最后一条遗留项

PLAN-071 交付留言 §"后续" 原文：

> `finality_vote_outbox` 的脏行传播性确认（若确认不传播，可正式登记为非目标）

本切片就是把它确认掉。**结论：不传播，无需清理**——这是一条「不做」的结论，
所以本切片交付的是**证据与门禁**，不是清理脚本。

## 二、取证（两个问题，都有实测）

方法：H2 + 真实 `JdbcFinalityStateStore` + 真实 `FinalityGadget` +
真实 `FinalityOutboxManager`，用一条**签名完全有效、仅哈希长度非法（3 字节）**的票
——这样才能证明是「长度闸」在起作用，而不是"签名坏了"这种无关原因。
临时探针跑完已删除，结论固化进 `FinalityOutboxDirtyVoteContainmentTest`。

### 2.1 脏票能进 outbox 吗？**能**

`FinalityOutboxManager.enqueue()` 只挡 `vote == null`，**不校验 checkpoint 哈希长度**：

```java
private synchronized void enqueue(Vote vote, FinalityVoteOrigin origin, Throwable error) {
    if (vote == null) {
        return;   // ← 唯一的校验
    }
    ...
    persistToOutbox(pending);
}
```

实测：`enqueueReceived(3字节哈希的有效签名票)` ⇒ 内存 `pendingCount = 1`，
`finality_vote_outbox` 表 1 行。

### 2.2 重放会把脏票怎么样？**正常拒绝并移除，不污染、不变毒丸**

重放路径 `flush() → tryFlush() → gadget.submitVote(vote)`：

- `submitVote` 内部先走 `validateVoteBinding`（含 PLAN-067 的 32 字节校验），
  脏票被**正常拒绝**，返回 `record(...)`（`finalized=false`），**不抛异常**；
- `tryFlush` 只在 **catch 到异常**时才把票留在队列重试：

```java
try {
    gadget.submitVote(pending.vote);
    queue.remove(...);        // ← 正常返回 ⇒ 移除
    removeFromOutbox(pending);
} catch (RuntimeException e) {
    pending.error = describe(e);
    persistToOutbox(pending); // ← 只有抛异常才留着重试
}
```

实测连续 3 轮 `flush()`：

| 轮次 | 内存 pending | outbox 表 | finality_votes | finality_checkpoints |
|---|---|---|---|---|
| 入队后 | 1 | 1 | 0 | 0 |
| flush #1 | **0** | **0** | 0 | 0 |
| flush #2 | 0 | 0 | 0 | 0 |
| flush #3 | 0 | 0 | 0 | 0 |

并且 `isFinalized(5, dirty) = false`、内存 voter 集合为空。

### 2.3 三张表的行为对比（这是本切片的核心结论）

| 表 | 写入侧校验 | 脏行是否沉淀 | 是否需要清理 |
|---|---|---|---|
| `finality_votes` | 无（`recordVote` 不校验长度） | **是**，且每 5s 被收敛路径重扫 | ✅ PLAN-069 已隔离 |
| `finality_checkpoints` | 无（`markFinalized` 不校验） | **是**，经 restore 已终局分支进 weights，造成 DB/内存视图不一致 | ✅ PLAN-071 已隔离 |
| `finality_vote_outbox` | 无 | **否**——重放时被 `submitVote` 正常拒绝并移除 | ❌ **不需要** |

⇒ 这解释了为什么 PLAN-069/071 只处理那两张表：**outbox 是"写库意图"的暂存，
它的出口（`submitVote`）本身就是 fail-closed 的**。脏票在这里是"一次失败的尝试"，
不是"沉淀的事实"。

> **一个必须说清的边界**：如果将来 `submitVote` 对非法哈希改成**抛异常**（而不是
> 正常拒绝），脏票就会变成**毒丸**——每 5 秒重试一次、永远留在 outbox 里。
> 反向对照 A 正是模拟这个改动，门禁会红。所以本门禁同时守着两条边界：
> ① outbox 写入侧不校验（前提）；② 重放侧是"正常拒绝"而非"抛异常"。
> 后者是当前设计的**隐含依赖**，本切片把它显式化。

## 三、交付的门禁

`FinalityOutboxDirtyVoteContainmentTest`（2 例）：

| 用例 | 钉住什么 |
|---|---|
| `dirtyVoteIsRejectedOnReplayAndLeavesNoTrace` | ① 脏票可入队（**前提断言**，防止将来给 enqueue 加校验后用例假绿）；② `flush()` 后内存队列与 outbox 表都归零（非毒丸）；③ `finality_votes` / `finality_checkpoints` 均为 0 行；④ `isFinalized()` 为 false；⑤ 内存 voter 集合为空 |
| `repeatedFlushStaysClean` | 连续 3 轮 flush 后仍为空（不能"越跑越多"或残留） |

两个用例都用**签名有效、仅长度非法**的票，确保测的是长度闸而不是签名校验。

## 四、反向对照（2 组，全部精确命中）

| 组 | 变异 | 实测 |
|---|---|---|
| A | 让 `submitVote` 对非法绑定**抛异常**（模拟"硬失败"） | 两例都红：`第 1 轮后内存队列必须为空 ==> expected: <0> but was: <1>`（这就是毒丸行为） |
| B | 给 `enqueue()` 加长度校验（模拟"写入侧加闸"） | 前提断言红：`前提：outbox 写入侧不校验哈希长度，脏票可入队 ==> expected: <1> but was: <0>` |

还原后两文件 SHA-256 与变异前逐字一致：

```
gadget  babf1de2748d0ef5870fa1164abfea9d9e7e7fea80bbfed33c3264a07c62c78f
outbox  7a22f0f2b9de68954a2ea4f906e33dd7dee5a289679f0bc3abf898dbec677053
```

还原后重跑 2/2 绿。

## 五、边界（勿引用为更强结论）

1. **这是"不需要做"的结论，不是"已修复"**：`finality_vote_outbox` 从未有过
   需要清理的脏行沉淀；本切片只确认这一点并守住它；
2. **前提依赖 `submitVote` 的正常拒绝语义**：若将来改为抛异常，需要同时处理
   outbox 毒丸问题（门禁会红，但修复需要新代码）；
3. **同内核单机 + H2**：取证在 H2 上完成（存储语义与 PG 一致，均由同一
   `JdbcFinalityStateStore` 驱动）；**不是物理多机**，不构成分布式结论；
4. **未在生产/预发库验证**：不需要——本结论是"不沉淀"，不涉及数据操作；
5. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 六、后续

- 若将来要动 `submitVote` 的拒绝语义（正常返回 → 抛异常），**必须**同时实现
  outbox 毒丸处理（例如按 `attempts` 上限丢弃并计数），否则会引入新的无限重试；
- roadmap #27 其余项：epoch 窗口/规范检查点语义（阻塞于链接口）、
  真实集群告警演练、Prometheus 服务端成本基准、物理多机矩阵、历史 retention。
