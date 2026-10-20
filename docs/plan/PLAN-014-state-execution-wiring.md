# 方案 PLAN-014：状态执行接线专项（同步状态重放 A-2/A-3 + 支付扩展执行接线 + 欺诈证明锚定状态）

- **状态**：Approved（2026-10-19 拍板：批次⑥ 路线文档 §8 结论——合并立项、一次设计、分期交付）；Phase 0/1 已交付（2026-10-19），Phase 2/3 已交付（2026-10-20），Phase 4 持久化面已交付（2026-10-20），Phase 5 双节点连续验证已交付（2026-10-20，见 §八）。**未交付边界**：`real-l1-enabled=true` 真机 L1 链路未验证，桥的存款/提款/已提交根仍为内存态。
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
| 批次⑥ §4 | 欺诈证明"机制真实、锚定未完成"（原：状态内存态 + L1 交互默认模拟；状态已持久化——Phase 4） | 执行结果的状态持久化与外部锚定 |

三者共同点是**同一条状态执行链路的三个消费方**：A-2/A-3 需要"按区块重放交易"的执行入口；支付扩展需要"区块接受路径调用执行器"的接线；欺诈证明需要"执行结果/批次状态的持久化"。分开实施会三次改动同一路径（`PendingBlocksManager` / `StateDB` / `MerkleTreeManager`），存在反复回归风险——**合并立项、一次设计、分期交付**。

## 二、现状证据（file:line）

- **同步写链路径**：`PendingBlocksManager` 接收块轻校验（A-1 止血已上线：merkle 校验失败明确 WARN + 块入缓存，PLAN-012 实施记录 5a6e7fa）。
- **状态执行引擎存在**（PLAN-012 A-2 深挖修正）：`StateDB.applyTransactions` / `getAccountUnsafe`（按需重放）完整存在——缺口不是"引擎缺失"，而是**接线缺失**。
- **merkle 对账链路的实现语义（Phase 1 实施期修正）**：`MerkleHandler` 的 GET_TREE_NODES/TREE_NODES 对账对象是**块体交易树**（按 `block.body` 交易哈希构建、按索引替换交易）——不是账户状态树；账户状态链路的真实实现是确认路径 `StateDB.writeBlock → NewBestBlockEvent → StatetreeUpdate`（Phase 1 已收敛到 `StateReplayManager`）。同步路径写链前的 merkle 校验失败（PLAN-011/012 死锁根因）语义不变：本地状态未重放至校验高度时的时序问题（A-1 已改为"延后 + 明确日志 + 继续写链"）。
- **支付扩展（2026-10-20 Phase 3 修正）**：类型定义齐备（`core/account/Transaction.java:130-141`，CHANNEL_OPEN 0x10 … SUBSCRIPTION_AUTH 0x1a）；`BasicRule` 有单规则预检（`BasicRule.java:187-205`）；`PaymentTransactionProcessor.processTransaction` 分发 0x10–0x18 有逻辑、0x19/0x1a 仅日志（`:161-170`），**但 `processTransaction` 生产无任何调用点**。更重要的是：完整区块/RPC 路径先经 `AccountRule` 的 `TransactionFormatCheck`，其 `type<=15` 门禁（`TransactionCheck.java:119-125`）会以 `Type check error` 拒绝 0x10–0x1a——**当前不可上链**，不是"上链后不执行"。
- **欺诈证明**：单步二分证明 + 挑战窗 + challenge bond + 罚没奖励 + 多挑战者冲突解决（first-valid-wins）实现完整；批次/挑战/bond 状态已 PG 持久化（Phase 4，`JdbcFraudProofStateStore`，store=`l2_fraud_state`）；**但** L1 侧 `DefaultL2BridgeContract` 默认注入内存模拟客户端（`real-l1-enabled=false`，:26），且桥的存款/提款/已提交根仍为进程内存（见 §八 Phase 4/5 边界）。

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
         批次/挑战状态持久化（PG：l2_fraud_batches / l2_fraud_bonds / l2_fraud_conflicts，
         SchemaMeta store=l2_fraud_state 版本登记）✅ 持久化面已交付（2026-10-20）
         + real-l1-enabled=true 真机全链路（未交付：Phase 5 仅覆盖双节点共识/同步，
           桥的存款/提款/已提交根仍为进程内存）
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
| **Phase 4**（持久化面已交付 2026-10-20；real-L1 面未交付，见 §八） | 欺诈证明锚定：批次/挑战状态持久化（PG 单存储决策——不做 LevelDB、不双写）+ `real-l1-enabled=true` 真机链路 | 2–3d | ✅ 进程重启后批次/挑战状态不丢失（11 用例）；❌ 真机 L1 交互未验证（转后续专项） |
| **Phase 5**（已交付，2026-10-20，见 §八） | 双节点连续验证（两个真实节点进程 + 容器化 PG）：持续同步 + 确认不中断（PLAN-011/012 死锁根除）；状态根对账一致 | 1d | ✅ 30 分钟长跑 A/B/PG 高度 3546 一致、ERROR 0、replay mismatch 0、finality 2000/2000 FINALIZED；边界：real-L1 未覆盖 |

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

1. **Optimistic 路线**：欺诈证明机制真实；批次/挑战/bond 状态已 PG 持久化（Phase 4，2026-10-20），但 L1 交互默认内存模拟（`real-l1-enabled=false`）——"锚定到真实 L1 状态"未完成；Phase 5 仅覆盖双节点共识/同步，real-L1 真机验证仍未交付。
2. **支付扩展（0x10–0x1a）**：仅有类型定义与 `BasicRule` 单规则预检；完整区块/RPC 路径被 `TransactionCheck.TransactionFormatCheck` 的 `type<=15` 门禁拒绝（`Type check error`），**当前不可上链**。`processTransaction` 生产无调用点；0x19/0x1a 分支仅日志、无状态写入。

各阶段交付后同步回填本节与 README/ARCHITECTURE 表述（**交付即收敛，不留口径漂移**）。

## 七、其余关联项（已闭环，不属本专项）

- 批次⑥ 第 6 项 nonce 上界——已修（PR #148，`MAX_TX_NONCE` + 回归测试）。
- 批次⑥ 第 1/2/3 项（Merkle A-2 决策 / Groth16 真实路径 / KZG 诚实标注）——维持现状，已收敛。

## 八、实施记录

### Phase 5（2026-10-20，双节点连续验证已交付；real-L1 未交付）

**交付**（分支 `plan/phase5-dual-node-verify`）：
- **P2P 区块签名透传**：`NexusChain.proto` 的 `Block` 追加 `bytes block_notice = 11`（保留
  `body = 10`），用 protoc 3.25.5 重新生成 checked-in `NexusChainOuterClass.java`；
  `Utils.encodeBlock/parseBlock` 透传 R/S，新增 `UtilsBlockSignatureRoundTripTest`
  （真实 Ed25519 签名跨编解码可验签，篡改 S 分量验签失败）。同时把生成类中已存在、
  但 proto 长期缺失的 `TransactionType` 16–26 补回 proto，保持线上枚举值不变。
- **PoS 状态根**：`PosConsensusEngine.buildBlock` 在 `merkleRule.validateMerkle` 后计算真实的
  `hashMerkleState` / `hashMerkleIncubate`，不再硬编码零根；
  `testProposeComputesStateRootFromReplay` 通过。
- **最终性投票源**：`FinalityCoordinator` 从 `NewBlockMinedEvent`（仅本节点出块时发布）改为
  `NewBlockEvent`（`StateDB` 确认写链后发布）——round-robin 下检查点高度落在对端时，
  本节点也会投票。修复前 A 的投票数为 0，最终性退化为单节点。
- **质押权重稳定**：`PosRewardDistributor.distributeBlockReward` 不再把出块奖励累加到
  proposer 的质押（奖励已由 coinbase 入账）；`StakingService.seedStake` 只初始化质押账本，
  不修改 Validator 注册信息；`ValidatorNodeBootstrapper` / `SyncManager` 对自身/对端验证人
  填充账本。修复前本地权重膨胀、远端不可见，曾观察到 A/B 权重分叉（如 epoch 240：
  A `5300/6210`、B `4310/5210`），存在单节点跨过 2/3 的风险；修复后两端稳定为
  `2000/2000`。
- **启动竞态**：新增 `PosConsensusEngine.isProposerKnown`，`ConsensusRule` 对"验证人集合
  尚未同步"返回可识别错误，`PendingBlocksManager` 以 INFO 记录并留在 pending 队列重试，
  不再刷 ERROR；`SignatureAggregator.CollectingAggregator` 固定列表快照，
  `FinalityGadget.submitVote` 串行化，修复并发投票下 `arraycopy` 越界与 pending block
  超时（长跑首轮曾在 B 启动期各出现 1 次）。
- **工程卫生**：`AccountDB.selectaccount` 首次查无账户不再刷
  `EmptyResultDataAccessException` ERROR；root `data-A/`、`data-B/` 加入 `.gitignore`；
  `scripts/dev-cluster-*.sh` 支持自动解析 PG 容器名（`nexus-pgsql` / 历史 `nexus-pg`）。
- **回归**：定向测试 `UtilsBlockSignatureRoundTripTest` / `PosConsensusIntegrationTest` /
  `StakingServiceImplTest` / `PosRewardDistributorTest` / `SignatureAggregatorTest` /
  `FinalityGadgetTest` / `FinalityChainOrchestrationTest` /
  `FinalityEndToEndIntegrationTest` 全绿；`installDist` 成功。

**30 分钟双节点连续验证（2026-10-20 16:47–17:17）**：
- 拓扑：两个真实节点进程（A/B，HTTP 19585/19586，P2P 9235/9236）+ 容器化 PostgreSQL
  （`nexus-pgsql`，127.0.0.1:55432）；固定 Ed25519 私钥 `a*64`/`b*64`，
  `min-validators-to-mine=2`，round-robin。
- 结果：A/B/PG 高度均 **3546**（A/B delta 3546，30 分钟约 1.97 block/s）；
  `state replay mismatch` A/B 均 **0**；validator-set 注册各 **1** 次；ERROR A/B 均 **0**；
  finality epoch **885** 两端均 `FINALIZED`、`voted_weight=2000`、`total_weight=2000`、
  `progress=100`；A/B epoch 885 checkpoint 均为
  `2a2b90297b63bdd18003370a003e3b312cdccc8ff6fb45ccd37325e850edfc82`（一致）。
- 3 分钟复跑：A/B 高度 257/256、`state replay mismatch=0`、ERROR 0、finality
  `2000/2000 FINALIZED`。B 启动仅 1 条 `unknown proposer` INFO（不再 ERROR），随后自动追平。
- 观测口径：`/rpc/v1/finality/epoch/{epoch}` 受 `nexus.localonly=true` 与
  `LocalRpcInterceptor` 保护，必须携带 JWT `token` 头；无 token 返回 HTTP200 空 body，
  不能用它判断端点异常。验收查询最新 epoch 的前一 epoch，避免投票尚未收齐的瞬时 50%。

**不做（本期边界）**：
1. `real-l1-enabled=true` 真机 L1 链路未验证；桥的存款/提款/已提交根仍为进程内存。
   Phase 5 的"双节点"是同一 Windows 主机上的两个节点进程 + 容器化 PG，不是两台物理机
   （与 PLAN-012 口径一致）。
2. ~~最终性状态仍为进程内 `InMemoryFinalityStateStore`（`finality_votes` 表不存在），
   重启后旧 epoch 可能返回 `UNKNOWN`；长跑验收查询当前 epoch。~~
   **2026-10-21 Phase 6A 已关闭**：`nexus.finality.store=jdbc` 下
   `JdbcFinalityStateStore` 已装配，`finality_votes` / `finality_checkpoints` 落 PG；
   双节点强制重启后旧 epoch 仍 `FINALIZED`、权重 `2000/2000`。详见
   [PLAN-015](PLAN-015-finality-persistence-resilience.md)。仍保留的边界是
   多副本缓存一致性、网络分区/PG 故障注入与 real-L1 未验证。
3. 未做网络分区、拜占庭节点、PG 故障注入；节点强制重启已由 PLAN-015 Phase 6A 覆盖；未写逐块状态根全量对账脚本，
   以 `state replay mismatch=0` + 最终性 checkpoint 一致作为验收证据。
4. 未覆盖桥经济链路（存款/提款/根提交）与 `Web3jL1ContractClient` 真实调用路径。

### Phase 4（2026-10-20，持久化面已交付；real-L1 面未交付）

**交付**（分支 `feat/fraud-proof-persistence`）：
- 新增 `FraudProofStateStore`（`org.nexus.l2.persistence`，接口 + 快照记录）与
  `JdbcFraudProofStateStore`（`@Profile("!dev")`）：三表 `l2_fraud_batches`（批次 JSON/提交时间/
  提交者）、`l2_fraud_bonds`（金额/状态/质押时间）、`l2_fraud_conflicts`（first-valid-wins 状态 +
  证明 JSON + 三类名单）；`SchemaMeta` 版本登记（store=`l2_fraud_state`，version=1）。
- upsert 采用"先 UPDATE、缺行再 INSERT、唯一键竞争时重试 UPDATE"的可移植写法——
  Phase 4 侦察实测 H2 2.2.224 仅支持 `ON CONFLICT DO NOTHING`、不支持 `DO UPDATE`；
  `MERGE ... KEY()` 是 H2 专有、PostgreSQL 报 bad SQL grammar（`JdbcPaymentStateStore` 的
  同款写法因此是潜在生产缺陷，见"遗留发现"）。
- `FraudProofVerifier`：写穿批次（onSubmit / markChallenged / finalizeBatch）、bond
  （stake / release / reward / invalid-slash）；`@PostConstruct restoreState()` 启动恢复
  批次 + bond + 冲突；未注入 store 时行为与旧版一致（纯内存）。
- `ChallengeConflictResolver`：first-valid-wins 状态写穿 + `restore(...)`；`clear` 同步删行；
  `firstValidProof` 类型 `Object` → `FraudProof`（调用点仅 verifier，无破坏）。
- `MerkleProof` 构造器补 Jackson `@JsonCreator/@JsonProperty`（FraudProof 嵌套反序列化所需，
  行为不变）。
- 回归：`JdbcFraudProofStateStoreTest`（6 用例：schema 登记 / 批次 JSON 往返 / upsert 幂等 /
  bond 状态更新 / 冲突嵌套证明往返 / 删除）+ `FraudProofVerifierPersistenceTest`
  （5 用例：批次+提交者+窗口恢复 / bond 状态恢复 / finalize 后状态恢复 / first-valid-wins 恢复 /
  clear 删除）——本地 11/11 通过；`FraudProofVerifierTest` + `EndToEndRollupTest` 回归通过。

**不做（本期边界）**：
1. `real-l1-enabled=true` 真机 L1 链路未验证（Phase 5 仅覆盖双节点共识/同步，real-L1 仍转后续专项）；`DefaultL2BridgeContract`
   的存款/提款/已提交根仍为进程内存。
2. `ChallengePeriodPolicy` 的"可疑行为延期"标记仍为进程内存。
3. 多副本共享同一 PG 时为 last-writer-wins（单进程 synchronized 语义未分布式化，无分布式锁/
   乐观版本）；完整多副本一致性另行设计。
4. `challengerStore` 字段无写入点（历史遗留），本期不持久化、不删除。
5. 持久化失败向上抛出（fail-fast）；进程在"内存已变、库未写"窗口崩溃时，重启以库中状态为准。

**实施期修正（相对本文件初稿，诚实记录）**：
- 初稿"PG/LevelDB 双写"被否决为 PG 单存储：nexus-core 多副本共享同一 PG，LevelDB 为各副本
  本地库，双写只会引入分叉；LevelDB 侧不新增欺诈证明状态。
- 初稿假设"ON CONFLICT DO UPDATE 可同时兼容 PG/H2"不成立（H2 版本限制），改为可移植 upsert。

**遗留发现（已处理，2026-10-20）**：
- `JdbcPaymentStateStore` / `JdbcFinalityStateStore` 使用 H2 专有 `MERGE ... KEY()`，
  生产 PostgreSQL 下写路径会 bad SQL grammar；因 `PaymentTransactionProcessor` 生产无调用点
  （Phase 3 结论）而暂未暴露。**已修复**：统一改用 `org.nexus.db.JdbcUpsert` 可移植
  upsert（先 UPDATE → `INSERT ... ON CONFLICT DO NOTHING` → 竞争失败再 UPDATE），并新增
  Testcontainers 真实 PostgreSQL 契约测试锁定。
- `ValidatorSetPersistence.upsert` 使用 `ON CONFLICT DO UPDATE`（H2 不支持、异常被 catch
  静默降级）**已修复**：改为复用 `JdbcUpsert`，H2 测试路径不再降级为 no-op。
- 顺带修复 `JdbcFinalityStateStore.reload()` 的 `RowCallbackHandler` 误用（回调内再调
  `rs.next()`）——重启后投票/终局状态只会恢复隔行、单行时一行都读不到；由新增的
  "模拟重启"测试暴露。

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
