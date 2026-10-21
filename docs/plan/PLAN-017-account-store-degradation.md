# 方案 PLAN-017：PG/AccountDB 短时不可用降级（Phase 6C 第一切片）

- **状态**：第一切片已交付（2026-10-21）；PG 主备切换、多机混沌、持久化区块 outbox 仍待后续
- **前置**：PLAN-016 Phase 6B（最终性故障注入，master `5397e2c1`）
- **分支**：`plan/phase6c-state-db-resilience`
- **范围**：账户读取 fail-closed、状态接口内存链头、proposer 安全暂停、账户库降级观测、连接池快速失败、区块采纳异常隔离
- **不包含**：PG 主备自动切换、主机断电/磁盘损坏、真实网络分区、多机拜占庭矩阵、最终性持久化 outbox、real-L1、桥资金链路

---

## 一、问题与目标

Phase 6B 已证明“最终性表短时不可用”不会阻塞 `StateDB` 采纳，但 PG **整体停止**
会同时打穿三条路径：

1. `AccountDB.selectaccount()` 捕获所有运行时异常后返回 `null`。调用方无法区分
   “账户不存在”和“数据库不可用”；`MerkleRule` 对总账户直接解引用，产生
   `totalaccount is null` 的 NPE，proposer 每秒刷一次完整堆栈。
2. `/consensus/status` 与 P2P 状态广播调用 `bc.currentHeader()`，而它是真正的
   PostgreSQL 查询。PG 一停，状态接口和节点间状态握手同时 500。
3. `PendingBlocksManager.addPendingBlocks()` 是 `@Async` 方法，数据库断开瞬间
   异常直接抛给 Spring 默认异常处理器；待采纳链已经从 `BlocksCache` 弹出，
   调用方拿不到重试信号。

本切片的目标不是把 PG 变成高可用，而是明确三条边界：

- **账户状态不可读时，提案必须安全暂停**，不能把数据库故障当成“新账户/零余额”继续出块。
- **状态接口和 P2P 状态握手必须继续可用**，返回内存中的本地链头，而不是等数据库恢复。
- **短时故障恢复后自动继续**：提案恢复、区块继续采纳、最终性检查点连续、无 `pendingBlock` 超时。

---

## 二、根因与修复

### 2.1 账户读取 fail-closed

新增 `AccountStoreUnavailableException`，明确表示“存储不可用”，与“查询结果为空”分离：

- `AccountDB.selectaccount()`：查询异常不再返回 `null`，抛出 `AccountStoreUnavailableException`。
- `AccountDB.hasAccount()`：查询异常不再返回 `Optional.empty()`，避免 `StateDB` 创建零余额账户。
- `MerkleRule`：总账户为 `null` 时显式抛出 `IllegalStateException("total account is missing")`，消除 NPE。
- `PosConsensusEngine`：单独捕获账户存储异常，记录“Propose paused”，返回 `null` 交由调度器下一轮重试；
  不构造、不广播无法验证的区块。

**语义选择**：PG 整体不可用时，共识**主动暂停出块**，而不是用内存中的不完整账户状态继续。
这是 fail-closed，不是高可用；可用性由“状态接口仍可读 + 恢复后自动继续”保证。

### 2.2 状态接口改为内存链头

新增 `ChainHeadProvider`：

- 优先返回 `StateDB.getBestBlock()`（运行期内存链头/已确认块）。
- 内存尚无链头时回退到创世块。
- 不用于交易验证或状态写入；账户/状态读取仍必须走数据库。

接入点：

- `GET /consensus/status`
- `POST /consensus/status`（P2P 状态握手）
- `SyncClient.sendStatus()` / `SyncClient.getStatus()`

同时 `POST /consensus/status` 在“同高度”分支不再调用 `bc.hasBlock()`（数据库查询），
只比较内存链头哈希，避免状态握手路径在 PG 故障时再次 500。

### 2.3 账户库降级观测

新增 `AccountStoreHealth`：

- 账户读取成功 → `UP`；失败 → `DEGRADED`，记录最近失败时间、最近成功时间和根因摘要。
- 新增 `GET /consensus/storage-status`，不依赖数据库，PG 故障期间仍可返回：
  `{"state":"DEGRADED","lastFailureAt":...,"lastSuccessAt":...,"lastFailure":"..."}`。

**边界**：这是**单节点账户读取路径**的健康快照。若某节点在故障期间没有发生账户读取，
它可能仍显示 `UP`；验收中 proposer 节点为 `DEGRADED`、另一未读账户的节点为 `UP`，正是该语义。

### 2.4 日志抑制

`AccountDB` 失败日志从“每次完整堆栈 ERROR”改为：

- 每 10 秒最多一条 WARN 摘要；
- 其余失败降为 DEBUG（保留堆栈供排障）。

`PosConsensusEngine` 的 `Propose paused` 同样按 10 秒采样，避免 30 秒故障刷出 30 条 WARN。

### 2.5 区块采纳异常隔离与重试

`PendingBlocksManager`：

- 将单条链处理提取为 `processChain()`，`addPendingBlocks()` 捕获
  `DataAccessException` / `AccountStoreUnavailableException`，不再让 `@Async` 异常逃逸。
- `MerkleRule` 遇到 `AccountStoreUnavailableException` 时返回带
  `account store unavailable` 前缀的 `Result.Error`，而不是普通“merkle 校验失败”；
  `PendingBlocksManager` 识别该前缀后转入重试队列，避免把存储故障当成坏块静默丢弃。
- 失败链进入内存 `storageRetryBlocks`，由 `@Scheduled(fixedDelay=5s)` 的
  `retryStorageFailures()` 在存储恢复后重试。
- 该队列**不是持久化 outbox**：进程重启后仍依赖 P2P 同步重新拉取；持久化区块 outbox 属于后续切片。

### 2.6 连接池快速失败

`application.properties` 为 DBCP2 增加：

- `max-wait-millis=5000`：池耗尽时最多等 5 秒，避免线程无限挂起。
- `max-total=20`、`max-idle=8`、`min-idle=2`。
- `validation-query=SELECT 1`、`validation-query-timeout=3`、`test-on-borrow=true`、
  `test-while-idle=true`、`time-between-eviction-runs-millis=30000`：故障恢复后不继续借出坏连接。

---

## 三、测试

新增单元/回归测试：

| 测试 | 覆盖 |
|---|---|
| `AccountDBTest` | 无行返回 `null`/`Optional.empty()`；查询异常抛出 `AccountStoreUnavailableException` |
| `AccountStoreHealthTest` | 失败 → `DEGRADED`，成功 → `UP` |
| `MerkleRuleAccountFailureTest` | 总账户缺失显式失败；账户库异常返回可重试标记，不伪装成普通坏块 |
| `ChainHeadProviderTest` | 内存链头优先；无链头回退创世 |
| `ConsensusControllerStatusTest` | `GET /consensus/status` 不调用数据库型 `bc`；同高度状态握手不调用 `bc.hasBlock` |
| `PendingBlocksManagerStorageRetryTest` | 写库异常与账户校验异常均不逃逸 `@Async`，失败链入队并在恢复后重试 |

验证结果：

- 定向测试全绿。
- core 全量测试：**1608 个用例，0 失败，19 跳过**（175 个测试套件）。
- `:nexus-core:nexus-core:check`：通过（JaCoCo + SpotBugs）。

---

## 四、真实 PG 停止验收

环境：同一 Windows 主机双进程（A/B）+ 容器 PostgreSQL `nexus-pgsql`；
**不是多机高可用证据**。

### 4.1 修复前（对照）

- 停止 PG 约 43.2 秒。
- 故障期间 `/consensus/status` A/B 均不可达（高度 `-1`）。
- 节点 A 约 40 条 `AccountDB.selectaccount failed` 完整堆栈 ERROR。
- 节点 B 出现 `Local adoption failed at height 2572: JDBC rollback; This connection has been closed.`
- 恢复后 A/B 高度 2684/2684，最终性检查点连续，但状态接口在故障期间不可用。

### 4.2 修复后

- 故障时长：43.9 秒。
- 基线：A=3439、B=3440；故障期间：A=3444、B=3444（PG 完全停止前推进 4 个高度后暂停）。
- 故障期间 `/consensus/status` 均 HTTP 200；`/consensus/storage-status` 返回
  A=`DEGRADED`、B=`UP`（A 是当时 proposer，持续读账户；B 未发生账户读取）。
- 恢复后：A=3549、B=3549。
- 日志：A/B `ERROR` 行均为 **0**；`Local adoption failed`、`pendingBlock` 超时均为 **0**；
  A 的 `Propose paused` 按 10 秒采样后仅 **4** 条。
- 最终性：`finality_votes=110`、`finality_checkpoints=57`，vote epoch 834..892、checkpoint epoch 834..900，
  检查点缺口 **0**，每 epoch 投票数 2..2。
- 恢复后旧 epoch 与最新 epoch 查询均 `FINALIZED`，权重 2000/2000。

### 4.3 结论

本切片证明了：

1. PG 整体短时停止时，`/consensus/status` 与 P2P 状态握手仍可用。
2. proposer 不再把账户库故障当成“新账户”，不会继续产出零余额/无法验证的区块，而是安全暂停。
3. 恢复后提案、区块采纳、最终性检查点自动继续，测试窗口内无高度分叉、无检查点缺口。
4. 账户读取失败不再产生 NPE 和完整堆栈刷屏；可通过 `/consensus/storage-status` 观察到降级。

---

## 五、明确边界

- **不是 PG 高可用**：没有主备切换、没有连接路由、没有自动故障转移。PG 停止期间链**停止出块**，
  只是状态接口/同步握手不跟着失效。
- **不是多机验证**：同主机双进程 + 容器 PG，不能作为多机网络分区、时钟漂移、主机断电证据。
- **区块重试队列在内存**：进程重启丢失，依赖 P2P 同步恢复；持久化区块 outbox 未做。
- **最终性 outbox 未做**：Phase 6B 的重试队列仍只覆盖本地票且不持久化；完整 `Vote` 签名恢复、
  接收侧独立重试仍未实现。
- **AccountStoreHealth 是单节点读路径快照**：未发生账户读取的节点可能仍显示 `UP`。
- **未覆盖**：PG 主备切换、主机断电/磁盘损坏、真实网络分区、完整拜占庭节点矩阵、
  多副本最终性缓存一致性、real-L1、桥资金链路、性能与灾备演练。

---

## 六、后续顺序

1. 最终性持久化闭环：持久化投票 outbox、完整 `Vote` 签名恢复、接收侧独立重试。
2. 持久化区块 outbox 与重启恢复（避免仅依赖 P2P 重拉）。
3. 3 节点多主机混沌测试（网络分区、时钟漂移、单机 PG 故障）。
4. PG 主备切换与连接池/超时参数的故障演练。
5. real-L1、桥持久化、外部安全审计与性能/灾备验证。
