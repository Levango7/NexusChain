# 方案 PLAN-014：状态执行接线专项（同步状态重放 A-2/A-3 + 支付扩展执行接线 + 欺诈证明锚定状态）

- **状态**：Approved（2026-10-19 拍板：批次⑥ 路线文档 §8 结论——合并立项、一次设计、分期交付）；Phase 0/1 已交付（2026-10-19），Phase 2/3 已交付（2026-10-20，见 §八）
- **日期**：2026-10-19
- **前置**：
  - PLAN-012 审核方案 A（拆分验证路径 + 状态重放，2026-08-14 审核通过）——A-1 止血已上线，A-2/A-3 未实施
  - 批次⑥ 路线文档 `docs/audit/2026-10-19-nexus-core-narrative-reality-routes.md` §4（欺诈证明锚定未完成）、§5（支付扩展链路被门禁拒绝，2026-10-20 修正）
  - 批次⑥ 第 7 项 DB schema 版本化基建（本专项 Phase 0，已交付）
- **目标**：把三条彼此依赖的"叙事-现实"缺口收敛为一条链路——**区块接受 → 状态执行（重放）→ merkle 重建校验 → 失败回滚/拉黑 → 持久化与对账**，使同步节点可持续同步与确认、支付扩展交易端到端生效、欺诈证明状态重启不丢失。

## 一、范围合并论证（为什么一次设计）

| 来源 | 缺口 | 依赖的公共链路 |
|---|---|---|
| PLAN-012 A-2/A-3 | 同步节点"写链 → 状态重放 → merkle 校验 → 回滚"未接线 | 写链后的状态执行与校验 |
| 批次⑥ §5 | `0x10–0x1a` 支付扩展"类型/单规则预检存在、完整链路被门禁拒绝"（`processTransaction` 生产无调用点） | 区块接受后的交易执行入口（需独立共识变更） |
| 批次⑥ §4 | 欺诈证明"机制真实、锚定未完成"（状态内存态 + L1 交互默认模拟） | 执行结果的状态持久化与外部锚定 |

三者共同点是**同一条状态执行链路的三个消费方**：A-2/A-3 需要"按区块重放交易"的执行入口；支付扩展需要"区块接受路径调用执行器"的接线；欺诈证明需要"执行结果/批次状态的持久化"。分开实施会三次改动同一路径（`PendingBlocksManager` / `StateDB` / `MerkleTreeManager`），存在反复回归风险——**合并立项、一次设计、分期交付**。

## 二、现状证据（file:line）

- **同步写链路径**：`PendingBlocksManager` 接收块轻校验（A-1 止血已上线：merkle 校验失败明确 WARN + 块入缓存，PLAN-012 实施记录 5a6e7fa）。
- **状态执行引擎存在**（PLAN-012 A-2 深挖修正）：`StateDB.applyTransactions` / `getAccountUnsafe`（按需重放）完整存在——缺口不是"引擎缺失"，而是**接线缺失**。
- **merkle 对账链路的实现语义（Phase 1 实施期修正）**：`MerkleHandler` 的 GET_TREE_NODES/TREE_NODES 对账对象是**块体交易树**（按 `block.body` 交易哈希构建、按索引替换交易）——不是账户状态树；账户状态链路的真实实现是确认路径 `StateDB.writeBlock → NewBestBlockEvent → StatetreeUpdate`（Phase 1 已收敛到 `StateReplayManager`）。同步路径写链前的 merkle 校验失败（PLAN-011/012 死锁根因）语义不变：本地状态未重放至校验高度时的时序问题（A-1 已改为"延后 + 明确日志 + 继续写链"）。
- **支付扩展（2026-10-20 Phase 3 修正）**：类型定义齐备（`core/account/Transaction.java:130-141`，CHANNEL_OPEN 0x10 … SUBSCRIPTION_AUTH 0x1a）；`BasicRule` 有单规则预检（`BasicRule.java:187-205`）；`PaymentTransactionProcessor.processTransaction` 分发 0x10–0x18 有逻辑、0x19/0x1a 仅日志（`:161-170`），**但 `processTransaction` 生产无任何调用点**。更重要的是：完整区块/RPC 路径先经 `AccountRule` 的 `TransactionFormatCheck`，其 `type<=15` 门禁（`TransactionCheck.java:119-125`）会以 `Type check error` 拒绝 0x10–0x1a——**当前不可上链**，不是"上链后不执行"。
- **欺诈证明**：单步二分证明 + 挑战窗 + challenge bond + 罚没奖励 + 多挑战者冲突解决（first-valid-wins）实现完整；**但** `FraudProofVerifier` 的批次/挑战状态为进程内存（`batchSubmitTime`/`batchStore`/`submitterStore`/`challengeBonds` 等 ConcurrentHashMap，:68-81）；L1 侧 `DefaultL2BridgeContract` 默认注入内存模拟客户端（`real-l1-enabled=false`，:26）。

## 三、目标架构

```
区块接受（PendingBlocksManager 写链，轻校验）
   │
   ├─► 状态执行器 StateReplayManager（新建）
   │     1. 取新增区块（增量，不重放全链）
   │     2. 重放交易 → MerkleRule.validateMerkle（等同 StateDB.applyTransactions 语义，StateDB 写锁内）
   │     3. 支付扩展交易 → 不接线（Phase 3 决定：0x10–0x1a 受 type<=15 门禁拒绝；
   │        执行接线/validate-only 属独立共识变更，不在本专项"无共识变更"范围内）
   │     4. 状态根重算（Block.calculateMerkleState；MerkleTreeManager 仍为块体交易树对账缓存）
   │     5. 事后校验 merkle 状态根 vs 区块声明（MerkleRule.verifyStateRoots）
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
| **Phase 1**（已交付，2026-10-19，见 §八） | `StateReplayManager` 核心（A-2）：确认路径重放 → 账户/孵化状态持久化 → 状态根重算校验（`MerkleRule.verifyStateRoots` 提取）；观察位不阻断 | 2–3d | ✅ 单测：重放后状态根与出块方一致；块体不完整根不一致复现（10/10） |
| **Phase 2**（已交付，2026-10-20，见 §八） | 事后校验 + 回滚（A-3）：`StateRootMismatchHandler` 异步处置；`observe`（默认）/`rollback` 可配；最终化护栏 + 对端溯源拉黑 | 1–2d | ✅ 单测 19/19：observe 不处置 / rollback 回滚+清理+拉黑 / 最终化拒绝 / 幂等 |
| **Phase 3**（已交付，2026-10-20，见 §八） | 支付扩展口径固化（Option A，不改共识）：修正"验证已上链"表述；显式标注 0x10–0x1a 被 `TransactionFormatCheck` 的 `type<=15` 门禁拒绝（`Type check error`）、`processTransaction` 生产无调用点、0x19/0x1a 仅日志；补门禁回归测试。真正的"区块接受但不执行"validate-only 属独立共识变更，不在本期 | 0.5–1d | ✅ 门禁测试：0x19/0x1a → `Type check error`；0x0f 不因类型被拒 |
| **Phase 4** | 欺诈证明锚定：批次/挑战状态持久化（PG/LevelDB）+ `real-l1-enabled=true` 真机链路 | 2–3d | 进程重启后批次/挑战状态不丢失；真机 L1 交互验证 |
| **Phase 5** | 真机双节点全链路验证：持续同步 + 确认不中断（PLAN-011/012 死锁根除）；状态根对账一致 | 1d | A/B 双节点高度持续推进 + finality 正常 + RPC 验证 |

预估总计 9–13 天（含真机验证），按阶段独立 PR 交付。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 状态重放性能（同步全链重执行） | 仅新增区块重放；已有链状态持久化（PLAN-012 决策） |
| 重放与出块并发竞争 | 重放持 StateDB 写锁（复用既有 readWriteLock） |
| 恶意链状态错误延迟发现 | 重放后校验 merkle state；失败回滚 + 拉黑（PLAN-012 决策） |
| 支付扩展"假接线"引入共识回归 | 本期不接 `processTransaction`、不放宽 `type<=15` 门禁（放宽会让未执行交易给 coinbase 加费、且状态根不可见）；只做口径/注释/门禁测试；真正的 validate-only 走独立共识变更 |
| 真机环境不稳定（Docker/P2P 时序） | 复用 PLAN-012 环境处置经验；验证脚本化，失败可重放 |

## 六、对外口径（与 README/ARCHITECTURE 注记联动）

本专项交付前，README / ARCHITECTURE 对以下两点的口径为（2026-10 起）：

1. **Optimistic 路线**：欺诈证明机制真实，但运行在 L2 进程内（状态内存态、L1 交互默认内存模拟）——"锚定到真实 L1 状态"未完成。
2. **支付扩展（0x10–0x1a）**：仅有类型定义与 `BasicRule` 单规则预检；完整区块/RPC 路径被 `TransactionCheck.TransactionFormatCheck` 的 `type<=15` 门禁拒绝（`Type check error`），**当前不可上链**。`processTransaction` 生产无调用点；0x19/0x1a 分支仅日志、无状态写入。

各阶段交付后同步回填本节与 README/ARCHITECTURE 表述（**交付即收敛，不留口径漂移**）。

## 七、其余关联项（已闭环，不属本专项）

- 批次⑥ 第 6 项 nonce 上界——已修（PR #148，`MAX_TX_NONCE` + 回归测试）。
- 批次⑥ 第 1/2/3 项（Merkle A-2 决策 / Groth16 真实路径 / KZG 诚实标注）——维持现状，已收敛。

## 八、实施记录

### Phase 3（2026-10-20，已交付）

**交付**（分支 `feat/payment-extension-honesty`）：
- 口径修正（Option A，不改共识）：README / ARCHITECTURE / 本文件 / 审计路线文档 / CHANGELOG
  统一为"0x10–0x1a 仅有类型定义与 `BasicRule` 单规则预检；完整区块/RPC 路径被
  `TransactionCheck.TransactionFormatCheck` 的 `type<=15` 门禁拒绝（`Type check error`），
  当前不可上链"。
- 代码边界注释：`TransactionCheck` 门禁、`BasicRule` 单规则预检、`PaymentTransactionProcessor`
  0x19/0x1a 仅日志分支、SDK `TxUtils.CreateRawSubscriptionAuthTransaction`、Gateway
  订阅授权注释（实际是 0 金额普通转账，不是 0x1a）。
- 门禁回归：`TransactionCheckGateTest`（0x19/0x1a → 5000 `Type check error`；0x0f 不被类型门禁误伤）+
  `PaymentExtensionBlockGateTest`（含 0x19/0x1a 的区块在 `AccountRule` 被拒绝）。
- **不做的清单**：不接 `processTransaction`、不放宽 `type<=15`、不改 `TYPE_MAX`/`GAS_TABLE`/proto、
  不把 `PaymentStateStore` 当共识状态。

**为什么不做"validate-only 接线"**：若让 0x19/0x1a 通过门禁，`Miner` 会为每笔交易给
coinbase 记手续费（`Miner.java:145`），而 `MerkleRule` 对这两类 no-op（不扣费、不递增 nonce），
形成凭空增发；`hashMerkleState` 只承诺账户 ID + 高度（`Block.calculateMerkleState`），
`StateReplayManager` 用同一规则重放会一致，**状态根发现不了**。因此"区块接受但不执行"
必须作为独立共识变更（激活高度、费率/余额/nonce、状态根承诺、全节点升级），不能混入本期。

### Phase 2（2026-10-20，已交付）

**交付**（分支 `feat/state-root-rollback`）：
- 配置：新增 `nexus.core.state-root-mismatch.action=observe|rollback`（默认 `observe`；
  环境变量 `NEXUS_STATE_ROOT_MISMATCH_ACTION`）。未知值一律回落 observe（fail-safe）。
- 事件与处置：新增 `StateRootMismatchEvent`（`StateReplayManager` 在写锁内发布）+
  `StateRootMismatchHandler`（`@Async @EventListener`）。异步化的原因：确认路径
  `StateDB.writeBlock` 全程持写锁并等待 `pendingBlock` 握手，在栈内直接回滚会重入
  `writeBlock` 导致 60s 自旋——处置必须出栈执行。
- 回滚动作（`rollback`）：`StateDB.executeUnderWriteLock`（新增，锁可重入）内完成
  `rollbackTo(h-1)` + `AccountDB.deleteFromHeight(h)` / `IncubatorDB.deleteFromHeight(h)`
  （新增删除 API），封闭"回滚后并发确认又把状态行写回"的竞态；随后按高度区间拉黑来源对端。
- 对端溯源：新增 `BlockOriginTracker`（有界 4096 条 LRU，`SyncManager.onBlocks/onProposal`
  入口记录 `区块哈希 → (高度, 对端)`）。此前 SyncManager 收块即丢弃 remote，确认期
  （≥3 深度）发现问题时无从归因；本地出块不入表，避免自拉黑。
- 最终化护栏（ADR-030）：不一致区块已最终化时拒绝回滚并记录 ERROR（需人工介入）；
  计数器 `finalityRejections`。同时修复 `ReorgManager` epoch 长度硬编码 32
  → 读取 `nexus.finality.epoch-length`（与 `FinalityCoordinator` 同源）。
- 观测计数：`mismatchEvents` / `rollbacksExecuted` / `finalityRejections` /
  `blacklistedPeers`。
- 回归：`StateRootMismatchHandlerTest`（6 用例：observe / 未知值回落 / rollback 回滚+拉黑 /
  最终化拒绝 / 已回滚幂等 / 配置 epoch）+ `BlockOriginTrackerTest`（4 用例：记录查询 /
  空输入 / 区块入口 / 容量上界）+ `ReorgManagerTest` 增补配置 epoch 用例 +
  `StateReplayManagerTest` 增补事件发布断言——本地 19/19 通过。

**诚实边界（Phase 2 范围内的已知限制）**：
1. 回滚沿用既有 `ReorgManager` 语义：只回内存链缓存 + 删除状态行，不删除已持久化的
   区块头/块体（由更长分叉链覆盖写入，读取以最新高度为准）。
2. 来源追踪不持久化：进程重启后旧块无法归因（不影响重启后的实时处置）。
3. 拉黑为内存态（`PeersCache` 评分衰减，约 11 分钟），非永久封禁；且按高度区间归因，
   可能误伤仅转发后续区块的中继对端——灰度期先看数据再收紧范围。
4. 不做"自动寻找替代分叉并重放"：回滚后节点停在 `h-1`，等待更长分叉链由
   `ReorgManager` 正常切换；交易池未回滚。
5. 异步处置存在窗口：事件排队期间若又确认了若干区块，回滚目标仍为 `h-1`，
   会一并回退后续区块（`rollbackTo` 语义），状态行按 `> h-1` 统一清理。

### Phase 1（2026-10-19，已交付）

**交付**（分支 `feat/state-replay-manager`）：
- 新增 `StateReplayManager`（nexus-core）：`replayBlock` 复用 `MerkleRule.validateMerkle`
  重放 → 持久化 `account`/`incubator`（语义与旧 `StatetreeUpdate` 完全一致）→
  `MerkleRule.verifyStateRoots` 二次校验；计数器 `replayedBlocks` / `stateRootMismatches`。
- `MerkleRule.verifyStateRoots`：从 `validateBlock` 内联逻辑逐字节等价提取（行为不变），
  交易所节点跳过孵化根语义保持。
- `StatetreeUpdate` 委托 `StateReplayManager`；异常路径保持发布 `AccountUpdateFailedEvent`。
- 回归：`StateReplayManagerTest`（4 用例：一致区块 / 声明根不一致观察位 / 块体不完整
  根不一致复现 / 重放失败向上抛出）+ `MerkleRuleStateRootsTest`（6 用例：状态根与孵化根
  接受/拒绝、交易所语义、`validateBlock` 等价性）——本地 10/10 通过。

**策略（Phase 1）**：观察位——根不一致 ERROR + 计数，不阻断、不回滚；Phase 2 接强制处置
（`ReorgManager` 回滚 + 对端拉黑）。

**实施期修正（相对本文件初稿，诚实记录）**：
1. `MerkleHandler` 对账对象是**块体交易树**而非账户状态树（见 §二修正条）——初稿
   "状态树经 MerkleMessageEvent → MerkleHandler P2P 传播"的表述不成立；账户状态链路的
   真实实现是确认路径重放（本 Phase 已收敛到 `StateReplayManager`）。
2. `Account.getId() = pubkeyHash + blockHeight`：现有 `hashMerkleState` 承诺"本区块触碰的
   账户集合 + 高度"，不含余额/Nonce 明细——Phase 2 的"校验失败判定"需以此语义为基线
   （仅余额差异不会导致根不一致）。
3. 同步路径写链前 `merkleRule.validateBlock` 仍可能因本地状态滞后失败（A-1 语义）；
   重放后校验才是权威比对。

### Phase 0（2026-10-19，已交付）

DB schema 版本化基建（PR #151）：`SchemaMeta`（PG 登记表 `nexus_schema_meta` +
fail-fast 三分支）、LevelDB `__meta/schema_version`、三个 store 接入、
`docs/db-schema-versioning.md`。
