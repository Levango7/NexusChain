# 方案 PLAN-014：状态执行接线专项（同步状态重放 A-2/A-3 + 支付扩展执行接线 + 欺诈证明锚定状态）

- **状态**：Approved（2026-10-19 拍板：批次⑥ 路线文档 §8 结论——合并立项、一次设计、分期交付）
- **日期**：2026-10-19
- **前置**：
  - PLAN-012 审核方案 A（拆分验证路径 + 状态重放，2026-08-14 审核通过）——A-1 止血已上线，A-2/A-3 未实施
  - 批次⑥ 路线文档 `docs/audit/2026-10-19-nexus-core-narrative-reality-routes.md` §4（欺诈证明锚定未完成）、§5（支付扩展执行未接线）
  - 批次⑥ 第 7 项 DB schema 版本化基建（本专项 Phase 0，已交付）
- **目标**：把三条彼此依赖的"叙事-现实"缺口收敛为一条链路——**区块接受 → 状态执行（重放）→ merkle 重建校验 → 失败回滚/拉黑 → 持久化与对账**，使同步节点可持续同步与确认、支付扩展交易端到端生效、欺诈证明状态重启不丢失。

## 一、范围合并论证（为什么一次设计）

| 来源 | 缺口 | 依赖的公共链路 |
|---|---|---|
| PLAN-012 A-2/A-3 | 同步节点"写链 → 状态重放 → merkle 校验 → 回滚"未接线 | 写链后的状态执行与校验 |
| 批次⑥ §5 | `0x10–0x1a` 支付扩展"验证在链、执行未接线"（`processTransaction` 生产无调用点） | 区块接受后的交易执行入口 |
| 批次⑥ §4 | 欺诈证明"机制真实、锚定未完成"（状态内存态 + L1 交互默认模拟） | 执行结果的状态持久化与外部锚定 |

三者共同点是**同一条状态执行链路的三个消费方**：A-2/A-3 需要"按区块重放交易"的执行入口；支付扩展需要"区块接受路径调用执行器"的接线；欺诈证明需要"执行结果/批次状态的持久化"。分开实施会三次改动同一路径（`PendingBlocksManager` / `StateDB` / `MerkleTreeManager`），存在反复回归风险——**合并立项、一次设计、分期交付**。

## 二、现状证据（file:line）

- **同步写链路径**：`PendingBlocksManager` 接收块轻校验（A-1 止血已上线：merkle 校验失败明确 WARN + 块入缓存，PLAN-012 实施记录 5a6e7fa）。
- **状态执行引擎存在**（PLAN-012 A-2 深挖修正）：`StateDB.applyTransactions` / `getAccountUnsafe`（按需重放）完整存在——缺口不是"引擎缺失"，而是**接线缺失**。
- **merkle 状态树同步**：经 `MerkleMessageEvent` → `MerkleHandler` P2P 传播；同步路径 merkle 校验失败的根因之一是**状态同步时序**（状态树未到达即校验）。
- **支付扩展**：类型定义齐备（`core/account/Transaction.java:130-141`，CHANNEL_OPEN 0x10 … SUBSCRIPTION_AUTH 0x1a）；验证规则在链（`CompositeBlockRule` 支付扩展 `TransactionRule` 列表 + `BasicRule:139`）；处理器实现齐备（`PaymentTransactionProcessor.processTransaction` 分发 0x10–0x18 有逻辑、0x19/0x1a 仅日志 :156-159）——**但 `processTransaction` 生产无任何调用点**（全仓 prod 引用仅注释 + 测试；`AccountRule` 只调用验证不调用执行）。
- **欺诈证明**：单步二分证明 + 挑战窗 + challenge bond + 罚没奖励 + 多挑战者冲突解决（first-valid-wins）实现完整；**但** `FraudProofVerifier` 的批次/挑战状态为进程内存（`batchSubmitTime`/`batchStore`/`submitterStore`/`challengeBonds` 等 ConcurrentHashMap，:68-81）；L1 侧 `DefaultL2BridgeContract` 默认注入内存模拟客户端（`real-l1-enabled=false`，:26）。

## 三、目标架构

```
区块接受（PendingBlocksManager 写链，轻校验）
   │
   ├─► 状态执行器 StateReplayManager（新建）
   │     1. 取新增区块（增量，不重放全链）
   │     2. 重放交易 → StateDB.applyTransactions（StateDB 写锁内）
   │     3. 支付扩展交易 → PaymentTransactionProcessor.processTransaction（本节 Phase 3 接线）
   │     4. MerkleTreeManager 重建/更新状态树
   │     5. 事后校验 merkle 状态根 vs 区块声明
   │           ├─ 一致 → 确认推进（best 更新条件）
   │           └─ 不一致 → ReorgManager 回滚 + 拉黑对端（Phase 2）
   │
   └─► 欺诈证明（Phase 4）
         批次/挑战状态持久化（复用 nexus_schema_meta / LevelDB meta 基建）
         + real-l1-enabled=true 真机全链路
```

**并发与幂等**：
- 状态重放持 `StateDB` 写锁（复用既有 readWriteLock）；出块与重放串行化。
- 重放以"区块高度 + 区块哈希"幂等键防重复执行；已确认高度之前不重放。
- merkle 校验失败的块保持"已入缓存、未确认"状态，待状态树到达或重放完成后复核——**不静默吞没**（A-1 语义延续）。

## 四、分期交付

| 阶段 | 内容 | 预估 | 验收 |
|---|---|---|---|
| **Phase 0**（已交付） | DB schema 版本化基建（`SchemaMeta` + LevelDB `__meta/schema_version` + `docs/db-schema-versioning.md`）——支撑后续阶段的状态持久化迁移 | 1–2d | 单测三分支 + 政策文档 |
| **Phase 1** | StateReplayManager 核心（A-2）：写链后按需重放 → 账户状态更新 → merkle 重建；`MerkleTreeManager` 暴露"按链重建状态树"能力 | 2–3d | 单测/仿真：重放后状态根与出块方一致；PLAN-012 死锁场景复现测试通过 |
| **Phase 2** | 事后校验 + 回滚（A-3）：重放后 merkle 校验 + `ReorgManager` 接线（复用 PLAN-003 回滚 + 拉黑） | 1d | 校验失败 → 回滚触发 + 对端拉黑的可重复单测 |
| **Phase 3** | 支付扩展执行接线：区块接受路径调用 `processTransaction`（0x10–0x18 已有逻辑接入；0x19/0x1a 补实现或显式降级为"仅记录"并对外标注） | 2–3d | 支付扩展交易端到端（验证→执行→状态变更→对账）测试；生产调用点存在性检查 |
| **Phase 4** | 欺诈证明锚定：批次/挑战状态持久化（PG/LevelDB）+ `real-l1-enabled=true` 真机链路 | 2–3d | 进程重启后批次/挑战状态不丢失；真机 L1 交互验证 |
| **Phase 5** | 真机双节点全链路验证：持续同步 + 确认不中断（PLAN-011/012 死锁根除）；状态根对账一致 | 1d | A/B 双节点高度持续推进 + finality 正常 + RPC 验证 |

预估总计 9–13 天（含真机验证），按阶段独立 PR 交付。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 状态重放性能（同步全链重执行） | 仅新增区块重放；已有链状态持久化（PLAN-012 决策） |
| 重放与出块并发竞争 | 重放持 StateDB 写锁（复用既有 readWriteLock） |
| 恶意链状态错误延迟发现 | 重放后校验 merkle state；失败回滚 + 拉黑（PLAN-012 决策） |
| 支付扩展接线引入共识回归 | 先以"验证不变、执行旁路"灰度（执行失败不回滚区块，仅告警），稳定后切换 fail-closed |
| 真机环境不稳定（Docker/P2P 时序） | 复用 PLAN-012 环境处置经验；验证脚本化，失败可重放 |

## 六、对外口径（与 README/ARCHITECTURE 注记联动）

本专项交付前，README / ARCHITECTURE 对以下两点的口径为（2026-10 起）：

1. **Optimistic 路线**：欺诈证明机制真实，但运行在 L2 进程内（状态内存态、L1 交互默认内存模拟）——"锚定到真实 L1 状态"未完成。
2. **支付扩展（0x10–0x1a）**：交易类型与验证规则已在链上生效；执行层接线在途/实验性（生产暂无调用点）。

各阶段交付后同步回填本节与 README/ARCHITECTURE 表述（**交付即收敛，不留口径漂移**）。

## 七、其余关联项（已闭环，不属本专项）

- 批次⑥ 第 6 项 nonce 上界——已修（PR #148，`MAX_TX_NONCE` + 回归测试）。
- 批次⑥ 第 1/2/3 项（Merkle A-2 决策 / Groth16 真实路径 / KZG 诚实标注）——维持现状，已收敛。
