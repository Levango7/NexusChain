# 方案 PLAN-015：最终性持久化与重启韧性（Phase 6A）

- **状态**：Phase 6A 已交付（2026-10-21）；Phase 6B（故障注入矩阵）待排期
- **前置**：PLAN-014 Phase 5（双节点连续验证，master `47b01ee8`）
- **分支**：`plan/phase6-finality-resilience`
- **范围**：把已经实现但未装配的 `JdbcFinalityStateStore` 接入生产路径；补齐最终性投票 P2P 两侧测试；完成双节点强制重启验收。
- **不包含**：real-L1、桥资金链路、网络分区/拜占庭/PG 故障注入、多副本最终性缓存一致性。

---

## 一、问题与目标

PLAN-014 Phase 5 的诚实边界之一是：最终性状态仍为进程内
`InMemoryFinalityStateStore`，`finality_votes` / `finality_checkpoints` 表虽然已有 JDBC
实现，但没有作为 Spring bean 装配；节点重启后旧 epoch 可能返回 `UNKNOWN`。
同时 `roadmap-unresolved #26` 记录：`FinalityVoteBroadcaster.sendOverP2P()` 与
`SyncManager.onTransactions()` 两侧都没有自动化测试触达。

Phase 6A 的目标：

1. 生产/本地双节点在 `nexus.finality.store=jdbc` 时使用 PostgreSQL 持久化；dev/test 保持内存语义。
2. 重启后旧 epoch 仍返回 `FINALIZED`，且 `voted_weight` 与重启前一致。
3. 用真实 `Transactions(VOTE)` 载荷覆盖 P2P 发送 → `SyncManager` 接收 → 计票的完整往返。
4. 如实记录边界，不把“同一主机双进程 + 容器 PG”说成多机高可用。

---

## 二、交付内容

### 2.1 生产装配

- `JdbcFinalityStateStore` 标记为 `@Component`、`@Profile("!dev")`、
  `@ConditionalOnProperty(name = "nexus.finality.store", havingValue = "jdbc")`。
- `application.properties` 增加 `nexus.finality.store=${FINALITY_STORE:jdbc}`；
  `application-local.properties` 显式设为 `jdbc`；`application-test.yml` 设为 `memory`，
  避免单测上下文误装配数据库依赖。
- `FinalityGadget` 保留默认内存构造器；通过 `@Autowired(required = false)` 的
  `setStateStore(...)` 注入 JDBC 实现。使用 setter 而不是多构造器，避免 Spring 在重载
  构造器间选择失败时静默回退内存实现。启动日志会打印
  `FinalityGadget: using finality state store JdbcFinalityStateStore`。
- `JdbcFinalityStateStore.reload()` 由“记录错误后按空缓存继续启动”改为 **fail-fast**：
  最终性状态是已确认不可逆结算的安全事实，数据库加载失败时拒绝带病参与共识。

### 2.2 实施中暴露并修复的真实缺陷

1. **`VARCHAR(128)` 溢出**：旧实现用 `Arrays.toString(byte[])` 编码 checkpoint，32 字节
   哈希可达 160+ 字符，PostgreSQL 写入直接报
   `value too long for type character varying(128)`。该异常在双节点首轮验收中导致
   `PosConsensusEngine` 本地采纳失败，并进一步触发 `StateDB` 等待/出块停滞。
   - 修复：`FinalityGadget`、`InMemoryFinalityStateStore`、`JdbcFinalityStateStore`
     统一使用 **hex** 编码 checkpoint（32 字节 → 64 字符），与接口文档的
     `epoch|checkpointHex` 约定一致；无需破坏性 schema 变更。
   - 回归：新增 32 字节 checkpoint 的 JDBC 往返/重启测试。

2. **恢复权重为 0**：重启时 `FinalityGadget` 可能早于验证人/质押账本装载，
   `restoreFromStore()` 按当时 `StakingService` 重算得到 0，导致 RPC 出现
   `FINALIZED` 但 `voted_weight=0` 的矛盾状态。
   - 修复：查询路径与下一票按当前 `epochCheckpointVoters` 重算权重；
     `getEpochProgress` / `getFinality` 不再直接返回启动时缓存的 0。
   - 测试：新增“质押账本后装载时权重自愈”用例。

3. **无效投票先落库**：原流程先把投票者写入 `voters`/DB，再做活跃验证人与公钥绑定校验；
   重启恢复可能把无效投票重新计入权重。
   - 修复：先做身份/公钥绑定校验，通过后再持久化、计权重、做双签检测。
   - 另外在总权重为 0（验证人尚未装载）时拒绝计票并记录 WARN，避免
     `0 >= 0` 被误判为 100% 最终化；0 权重不写入 epoch 快照，避免一次早期
     误判把该 epoch 的后续合法投票永久锁死。

### 2.3 P2P 测试（关闭 roadmap-unresolved #26）

新增 `FinalityVoteP2PRoundTripTest`：

- A 节点调用 `FinalityVoteBroadcaster.broadcast(vote)`，真实触达
  `sendOverP2P()` → `PeerServer.broadcast(Transactions)`；
- 捕获发送的 `Transactions(VOTE)`，构造真实 `Message(Code.TRANSACTIONS)`；
- 交给 B 节点 `SyncManager.onMessage(...)`，验证 `onTransactions()` 的魔数分流
  → `FinalityVoteBroadcaster.onVoteReceived()` → B 的 `FinalityGadget` 完成最终化。

该测试不再使用 `peerServer=null` 的两参构造器，也不通过直接调用
`onVoteReceived` 绕过接收判定点。

### 2.4 定向回归

本批定向运行 30 个用例，全部通过：

- `FinalityGadgetTest`（12，含 0 权重快照防污染）
- `GovernanceWeightRefreshTest`（4）
- `FinalityStatePersistenceTest`（4，含权重自愈）
- `FinalityVoteBroadcasterTest`（5）
- `JdbcFinalityStateStoreTest`（3，含 32 字节 checkpoint）
- `JdbcFinalityStatePersistenceTest`（1，JDBC + FinalityGadget 重启闭环）
- `FinalityVoteP2PRoundTripTest`（1）

---

## 三、双节点强制重启验收（2026-10-21）

**拓扑**：同一 Windows 主机两个真实节点进程（A/B，HTTP 19585/19586，P2P 9235/9236）
+ 容器化 PostgreSQL（`nexus-pgsql`，127.0.0.1:55432）；固定 Ed25519 私钥 `a*64`/`b*64`，
`nexus.consensus.mode=pos`、`min-validators-to-mine=2`、round-robin、
`nexus.finality.epoch-length=4`、`nexus.finality.store=jdbc`。

**步骤与结果**：

1. 清空 `finality_votes` / `finality_checkpoints` 后启动 A/B，运行 2 分钟：
   - A/B 高度均 **1268**；
   - 查询 epoch **316**：两端均 `FINALIZED`、`voted_weight=2000`、
     `total_weight=2000`、`progress=100%`。
2. 强制终止两个节点进程（`Stop-Process -Force`，模拟崩溃），保留 LevelDB 数据目录与 PG。
3. 以相同数据目录、相同验证人私钥重启 A/B（P2P 传输密钥按进程重新生成）：
   - 重启后高度 **1297/1298**，旧 epoch 316 两端仍 `FINALIZED`、`2000/2000`；
   - 日志：`JdbcFinalityStateStore: reloaded 61 vote records, 61 finalized checkpoints`
     （A/B 各一次），随后 `FinalityGadget: restored 61 finalized checkpoints, 61 vote records`；
   - 继续运行 45 秒，高度 **1386**，证明重启后出块/同步/最终性继续推进；
   - 本次重启写出的 `nodeA.log` / `nodeB.log` 中 `ERROR` 行数：A/B 均 **0**。
4. 验收结束时 PG 中 `finality_votes=180` 行、`finality_checkpoints=90` 行
   （首轮最终化并持久化 61 个 epoch，重启后新增 29 个；每个 epoch 两个验证人投票）。

**验收脚本**：本机临时脚本 `p6-finality-restart.ps1`
（双节点启动、强制停止、重启、RPC 查询与日志摘录；不提交到仓库，避免固化本机路径）。

---

## 四、诚实边界

1. **不是多机 HA**：A/B 是同一 Windows 主机上的两个进程 + 本机容器 PG，不是两台物理机；
   强制重启也不覆盖主机断电、磁盘损坏、PG 主备切换。
2. **未做故障注入矩阵**：没有网络分区、拜占庭节点、PG 故障、消息丢失/重复/延迟、
   同时写入竞争等场景。Phase 6B 另立。
3. **多副本缓存一致性未解**：`JdbcFinalityStateStore` 的读路径是启动时快照，
   实例 A 终局后实例 B 的缓存不会自动失效；当前双节点各自记录收到的投票并各自恢复，
   没有跨实例缓存刷新。生产多副本部署前必须拍板方案（事件增量更新/定时 reload/读库）。
4. **恢复的是最终性事实与投票者集合**：`epochCheckpointVotes`（完整 `Vote` 签名对象）
   未持久化；重启后不会对旧 epoch 重新执行聚合验签。已最终化状态直接恢复，
   新投票仍走完整 Ed25519 逐一验签。
5. **P2P 单测是进程内往返**：`FinalityVoteP2PRoundTripTest` 通过捕获
   `PeerServer.broadcast` 载荷再喂给 `SyncManager`，不是真实 gRPC 网络；
   真实 gRPC 路径由双节点运行间接覆盖，但没有网络故障注入。
6. **real-L1 与桥不变**：`real-l1-enabled=true` 真机 L1 链路仍未验证；桥的存款/提款/
   已提交根仍为内存态，Phase 6A 没有触碰。
7. **`memory` 回退不具备重启恢复**：设置 `FINALITY_STORE=memory` 或使用 dev profile
   时，行为与 Phase 5 相同，旧 epoch 重启后可能 `UNKNOWN`。

---

## 五、后续

- **Phase 6B（建议下一批）**：故障注入矩阵——网络分区、写入窗口崩溃/重启（含重启中继续投票）、
  PG 短时不可用、重复/延迟/乱序投票、双签/拜占庭注入；输出可复现证据与失败模式。
- **多副本最终性缓存一致性**：在 6B 或单独 ADR 中拍板；当前只保证单实例重启恢复。
- **real-L1 专项**：另立任务，先用本地 Hardhat/Anvil + 真实 `Web3jL1ContractClient`，
  并把桥的存款/提款/已提交根持久化。
