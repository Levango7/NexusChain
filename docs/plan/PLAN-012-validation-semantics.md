# 方案 PLAN-012：区块验证语义拆分——同步轻校验 + 状态重放

- **状态**：Approved（2026-08-14 审核通过）
- **日期**：2026-08-14
- **前置**：PLAN-011 根因定位（同步路径对接收块做完整状态 merkle 校验 → 跨节点同步必失败）
- **目标**：同步节点（接收方）能写入对端链（高度认知一致），且不牺牲共识安全

## 审核决策（2026-08-14）

| 决策点 | 结论 |
|---|---|
| 方案选型 | **A. 拆分验证路径 + 状态重放** |
| 状态重放粒度 | **仅新增区块**（已有链状态持久化，增量更新） |
| 同步者信任 | **信任签名 + 重放后校验**（写链后重放状态再校验 merkle） |
| 失败处理 | **回滚 + 拉黑**（复用 ReorgManager + 拉黑对端） |

---

## 一、问题本质（PLAN-011 实证）

```
B 收到 A 的块 1（blocks received 1→1）→ PendingBlocksManager 写链
→ merkleRule.validateBlock: validateMerkle(block.body, nHeight) 基于 B 的
   本地账户状态（genesis 初始态，未执行 A 链交易）→ merkle state 不一致
→ 静默 writeBlockToCache + continue（无日志，掩盖失败）
→ B 的 getBestBlock 停 genesis → 高度认知不一致 → 双方都不出块死锁
```

**根因**：`PendingBlocksManager` 对**接收块**复用了**出块方完整校验**语义。
验证者（出块）需要完整状态校验；同步者（接收）状态未重放，无法做状态校验。

---

## 二、标准区块链实践参照

| 角色 | 校验强度 | 状态处理 |
|---|---|---|
| 出块方（validator） | 完整（签名+结构+状态） | 执行交易更新状态 |
| 同步方（sync peer） | 轻（签名+结构+父链） | 写链 → **状态重放**（重执行交易）→ 事后校验 |

**当前实现**：同步方也做完整状态校验 → 与"状态未重放"矛盾 → 必失败。

---

## 三、方案设计

### 方案 A：拆分验证路径（推荐）

```
PendingBlocksManager 写链路径改为：
  1. 轻校验（保留）: BasicRule（结构）+ ConsensusRule（PoS 签名/轮次）
     —— 已验证可通过（PLAN-002/003 适配后）
  2. merkle 状态校验（拆分）: 不在写链时执行
     —— 移入"链确认后状态重放"阶段
  3. 状态重放（新增）: 写链后重执行区块交易 → 更新账户状态 →
     MerkleTreeManager 重建状态树 → 事后校验 merkle state
  4. 校验失败 → 触发回滚（复用 PLAN-003 ReorgManager）
```

### 方案 B：同步节点跳过状态校验（最小）

```
接收块时跳过 merkleRule（仅签名+结构），直接写链。
代价：接受恶意链风险（状态错误不即时发现，靠后续对账）。
```

### 方案 C：仅修复"静默吞没"（止血，不解决本质）

```
merkle 失败分支加明确日志 + 写入缓存（不再静默），
让链先同步、高度认知恢复——但状态不一致风险仍在。
```

**推荐**：A（拆分验证路径 + 状态重放）。B 作为过渡，C 作为止血第一优先。

---

## 四、核心改动点

| 文件 | 改动 |
|---|---|
| `PendingBlocksManager` | 写链循环移除 merkleRule 校验（改轻校验） |
| `StateDB` | 写链后触发状态重放（重执行交易）——复用 `PaymentTransactionProcessor` |
| 新增 `StateReplayManager` | 区块交易重放 → 账户状态更新 → merkle state 重建与校验 |
| `MerkleTreeManager` | 暴露"按链重建状态树"能力 |
| `ReorgManager` | 状态校验失败 → 触发回滚（复用 PLAN-003） |

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 状态重放性能（同步全链重执行） | 仅对新增区块重放；已有链状态持久化 |
| 重放与出块并发竞争 | 状态重放持写锁（复用 StateDB readWriteLock） |
| 恶意链状态错误延迟发现 | 重放后校验 merkle state；失败回滚 + 拉黑对端 |
| 止血前静默吞没持续存在 | 先做 C（明确日志），再 A（本质修复） |

## 六、实施顺序

```
0. 止血（C）: merkle 失败明确日志（立即，1 小时）
1. 轻校验拆分（A-1）: PendingBlocksManager 移除 merkle（1 天）
2. 状态重放（A-2）: StateReplayManager（2-3 天）
3. 事后校验 + 回滚（A-3）: 重放后校验 + ReorgManager 接线（1 天）
4. 真机双节点验证（1 天）
预估: 5-6 天（含止血与验证）
```

## 七、待审核决策点

1. **方案选型**：A（拆分+重放，推荐）vs B（跳过校验）vs C 止血先行？
2. **状态重放粒度**：仅新增区块重放 vs 全链重放重建？
3. **同步者信任模型**：信任对端签名（重放后校验）vs 必须即时校验？
4. **失败处理**：状态校验失败回滚拉黑（推荐）vs 告警继续？

请审核并给出决策，通过后按第六节实施。

---

## 实施记录（2026-08-14）

**A-1 止血（已提交 5a6e7fa）**：
- merkle 状态校验失败明确 WARN 日志（不再静默吞没）
- 真机实证：B 日志出现 "merkle state validate deferred (sync path): height=1"

**A-1 局限（诚实）**：merkle 失败走 writeBlockToCache（仅 merkle 缓存），
未走 stateDB.writeBlock 完整写链 → B 的 best 不更新 → 高度认知死锁仍在。
真正闭环需 A-2 状态重放：PaymentTransactionProcessor.processTransaction
**无外部调用者**（交易处理链路触发点缺失）——需补"写链→重放交易→更新
账户状态→merkle 重建校验"完整链路，属 core 状态执行引擎专项改造
（约 3-4 天），后续实施。

**多节点验证最终结论**：机制层（PLAN-001~010）全部闭环；
区块状态执行链路（PLAN-011/012）为共识安全核心，需完整实施 A-2 后
才能真机完全收敛。

## A-2 深挖修正（2026-08-14，重要）

**修正**：交易执行引擎**不是缺失**——`StateDB.applyTransactions`/
`getAccountUnsafe`（按需重放）完整存在。真正机制：
- merkle 状态树经 `MerkleMessageEvent` → `MerkleHandler` **P2P 传播**
- 同步节点需先收到对端 merkle 状态才能通过状态校验
- 同步路径的 merkle 校验失败是**状态同步时序**（状态树未到达即校验）

**A-1 止血已让失败可见 + 块入缓存 + 状态事件发布**；best 更新与
merkle 状态 P2P 到达链路是后续专项（需查 MerkleHandler 同步协议）。
核心结论：同步轻校验 + 状态重放（审核方案 A）方向正确，实施需
MerkleHandler 状态同步链路完整理解。

## 最终收敛验证（2026-08-15，诚实记录）

**已确认（真机）**：
- 双向验证人同步（A/B 各收对方广播 registered=true）
- **单 proposer 交替精确**（A 出奇数 1,3 / B 出偶数 2——同高度全网唯一 proposer）
- 状态对账触发（merkle-debug dialing，peers=2）

**遗留（同步最后一环）**：链推进在低高度停止（A=3/B=2，PG 仅 genesis）——
B 未持续同步 A 的更高块（高度认知同步，与 PLAN-011 同源）。
落库需 3 块确认差但链停 → 无法确认。机制层全部实证，
"持续同步+确认"为同步层最后专项（PLAN-013 候选）。

**环境备注**：Docker Desktop 空闲退出需手动恢复（nexus-pg 重建）。
