# 方案 PLAN-016：最终性故障注入首批切片（Phase 6B）

- **状态**：首批切片已交付（2026-10-21）；网络分区、完整拜占庭矩阵与持久化重试队列仍待后续
- **前置**：PLAN-015 Phase 6A（最终性持久化 + 强制重启，master `7d65ecda`）
- **分支**：`plan/phase6b-fault-injection`
- **范围**：重复/延迟/乱序投票、双签顺序反转、JDBC/PG 短时不可用、最终性表短时不可用时的区块采纳隔离
- **不包含**：真实网络分区、完整拜占庭节点矩阵、多副本最终性缓存一致性、持久化重试队列、real-L1、桥资金链路

---

## 一、问题与目标

Phase 6A 已证明“数据库可用 + 进程重启”不会丢失已最终化检查点，但没有回答
“数据库短时不可用 / 投票消息重复或乱序时，内存状态与持久化事实会不会分叉”。

首批故障注入先钉住可确定性复现的四个点：

1. `recordVote` 写库失败后，内存不能被污染出一个数据库里不存在的投票者。
2. `markFinalized` 写库失败后，不能先对外报告 `FINALIZED`；重启后不可逆结算不能丢。
3. 重复票、延迟票、乱序到达的票不能重复计权重，也不能让已最终化状态回退。
4. 最终性持久化失败不能沿 `NewBlockEvent` 监听器传播到 `StateDB`，导致区块采纳被
   `pendingBlock` 超时拖住。

---

## 二、实施中暴露并修复的真实缺陷

### 2.1 `FinalityGadget` 先改内存、后写数据库

原 `submitVote` 顺序为：

```text
voters.add(validator) -> stateStore.recordVote(...) -> 计权重
...
finalizedCheckpoints.add(key) -> stateStore.markFinalized(...)
```

故障注入暴露两个窗口：

- `recordVote` 抛异常时，`voters` 已加入该验证人；重试会被当成重复票直接返回，
  数据库里却永远没有这张票。
- `markFinalized` 抛异常时，`finalizedCheckpoints` 已置位；RPC 会报告
  `FINALIZED`，数据库没有最终化事实，重启后丢失不可逆结算。

**修复**：持久化先行，内存后置。

- `stateStore.recordVote(...)` 成功后才 `voters.add(...)`、计权重、记录 `Vote`。
- 聚合验签通过后先 `stateStore.markFinalized(...)`，成功后才
  `finalizedCheckpoints.add(...)`。
- 新增 `tryFinalize(...)`：统一处理“阈值判定 → 聚合验签 → 写最终化标记 → 更新内存”。
- 重复票是天然重试入口：若上一次 `markFinalized` 失败，重复票会再次尝试最终化；
  聚合验签已拒绝的检查点用 `finalizationRejected` 记录，避免 P2P 重复消息反复触发
  昂贵的聚合验算；有新投票者加入时解除。
- `setStateStore(...)` 同步清空 `epochCheckpointVotes` / `finalizationRejected`，
  避免切换存储后残留旧实例的投票对象或失败标记。

### 2.2 最终性写失败拖住 `StateDB` 区块采纳

2026-10-21 的最终性表短时不可用验收（把 `finality_votes` / `finality_checkpoints`
短时改名）复现了真实故障链：

```text
FinalityGadget.submitVote 抛 BadSqlGrammarException
  -> FinalityCoordinator.onNewBlock 未捕获
  -> StateDB.publishEvent(NewBlockEvent) 抛出
  -> StateDB.pendingBlock 未被清理
  -> 60 秒后 “Timed out waiting for pendingBlock state update”
```

修复前证据：高度从 1740 只推进到 1752，日志出现
`Local adoption failed at height 1755` 与两条
`Timed out waiting for pendingBlock state update at height 1752`。

**修复**：

- `FinalityCoordinator` 增加**有界内存重试队列**（最多 256 张本地票）。
- `submitVote` 失败时入队并返回 `null`，不再向 `StateDB` 抛异常；下一个区块事件
  （包括非检查点区块）先 flush 队列，数据库恢复后自动补写并补发 P2P 广播。
- 同一 `(epoch, checkpoint, validator)` 的重复票不会在队列中堆积；溢出时丢弃最旧票
  并显式 `ERROR`。
- `onNewBlock` 对 `onBlock` 的运行时异常做兜底捕获，保证最终性监听器永远不反向破坏
  区块采纳。
- `FinalityVoteBroadcaster.onVoteReceived` 捕获接收侧持久化失败，避免 P2P 同步线程
  被远端票写库失败拖垮；发送方的本地重试队列会重新广播。

**修复后证据（同一故障脚本，最终性表不可用 21.6 秒）**：

- 故障期间高度从 **2132/2133 → 2175/2175**，区块继续采纳；
- A 出现 12 条、B 出现 11 条预期内的 `Finality vote persistence failed ... queued for retry`，
  没有 `Local adoption failed`，没有 `pendingBlock` 超时；
- 表恢复后 A 重试 11 张、B 重试 10 张票，epoch **534–544** 全部补齐最终化；
- 高度继续到 **2297/2297**；PG 中 `finality_checkpoints` 在 519–574 连续无缺口
  （56 个检查点 / 112 票，每个 epoch 均为 2 票）。

---

## 三、自动化回归

新增/修改：

- `FinalityGadgetFaultInjectionTest`（6）
  - 写投票失败不留下幽灵投票者，恢复后重试只计一次权重；
  - 写最终化失败不提前报告 `FINALIZED`，重复票可补写；
  - 聚合验签拒绝后，重复票不重复触发验算，新票加入后才重试；
  - 乱序 + 延迟投票独立最终化，重复延迟票不回退；
  - 双签先 CP2 后 CP1 仍能检测；
  - 投票已落库但最终化写失败后重启，新签名票可继续完成最终化。
- `JdbcFinalityStateOutageTest`（1）
  - 用可切换 `DataSource` 模拟 JDBC 连接短时不可用：失败写不污染缓存/数据库，
    恢复后重试并最终化，校验 `finality_votes` / `finality_checkpoints` 行数。
- `FinalityCoordinatorTest`（+1）
  - `recordVote` 失败时 `onBlock` 不抛出、票入队；下一普通区块事件触发重试；
    同检查点重放不会堆积重复票。
- `FinalityVoteBroadcasterTest`（+1）
  - 接收侧 `recordVote` 失败时 `onVoteReceived` 不抛出，P2P 消息处理线程不被拖垮。

本批定向 `*Finality*` + `GovernanceWeightRefreshTest` 共 **64** 个用例全绿。

---

## 四、真实双节点故障注入证据

**拓扑**：同一 Windows 主机两个真实节点进程（A/B，HTTP 19585/19586，P2P 9235/9236）
+ 容器化 PostgreSQL（`nexus-pgsql`，127.0.0.1:55432）；固定 Ed25519 私钥，
`min-validators-to-mine=2`，round-robin，`nexus.finality.epoch-length=4`，
`nexus.finality.store=jdbc`。

### 4.1 PostgreSQL 整体短时停止

- 预热后 A/B 高度 **2358/2359**；epoch 588 两端 `FINALIZED`、`2000/2000`。
- `docker stop nexus-pgsql` 约 **20.1 秒**后重启。
- 停止期间 `/consensus/status` 两端不可达（A/B 均 `-1`），但旧 epoch 588 仍可从
  最终性 RPC 返回 `FINALIZED`（读路径走本地缓存）。
- 恢复后高度 **2484/2484**；epoch 620 两端 `FINALIZED`、`2000/2000`；PG 中
  `finality_checkpoints` 576–621 连续 46 个、92 票，每个 epoch 2 票。
- 说明：本轮 PG 整体停止首先打穿的是 `AccountDB`/提案路径（B 日志 18 条
  `CannotGetJdbcConnectionException` + 18 条 `Propose failed ... totalaccount is null`），
  不是最终性专用表；A/B 均未出现 `Finality vote persistence failed`。因此这组证据
  只能说明“PG 恢复后节点追平并继续最终化”，**不能**说明账户 DB 已具备降级能力。

### 4.2 仅最终性表短时不可用（直接命中写路径）

- 预热后 A/B 高度 **2132/2133**；epoch 532 两端 `FINALIZED`、`2000/2000`。
- 将 `finality_votes` / `finality_checkpoints` 改名 **21.6 秒**；期间链继续出块到
  **2175/2175**。
- 恢复表名后 A 重试 11 张、B 重试 10 张票，epoch **534–544** 全部补齐最终化；继续运行到
  **2297/2297**，epoch 573 两端 `FINALIZED`、`2000/2000`。
- 数据库：`finality_checkpoints` 519–574 连续 56 个，无缺口；`finality_votes=112`，
  每个 epoch 均为 2 票。
- 修复前后对照：修复前同一故障在 1752 高度触发 `pendingBlock` 60 秒超时；修复后
  无 `Local adoption failed` / `pendingBlock` 超时。

**验收脚本**：本机临时脚本 `p6b-pg-outage.ps1`（支持 `-FaultMode pg|finality`；
双节点启动、故障注入、RPC/高度/日志/数据库取证；不提交到仓库，避免固化本机路径）。

---

## 五、诚实边界

1. **重试队列不持久化**：进程崩溃会丢失队列中的票；生产级需要持久化 outbox/重试表。
2. **只覆盖本地票**：远端票接收失败会记录 WARN，依赖发送方重试重新广播；接收端没有
   独立重试队列。
3. **PG 整体故障仍影响账户/提案路径**：本次只隔离了最终性监听器对 `StateDB` 的
   反向破坏，没有给 `AccountDB` 加降级/重连队列。该缺口已由
   [PLAN-017](PLAN-017-account-store-degradation.md) 第一切片（Phase 6C）承接：
   账户读取 fail-closed、状态接口内存化、proposer 安全暂停；仍不等于 PG 高可用。
4. **不是多机 HA**：同一 Windows 主机双进程 + 容器 PG；未覆盖主机断电、磁盘损坏、
   PG 主备切换。
5. **未做真实网络分区/完整拜占庭矩阵**：重复/延迟/乱序与双签顺序反转是进程内/消息级
   测试；真实分区、消息丢失、伪造票洪泛、拜占庭多节点协同仍未覆盖。
6. **多副本最终性缓存一致性未解**：`JdbcFinalityStateStore` 读路径仍是启动快照，
   A 实例终局后 B 实例缓存不会自动失效。
7. **完整 `Vote` 签名对象未持久化**：重启后只恢复投票者/权重/最终化事实；重启后
   需要新的投票对象重新走聚合验签才能完成尚未落库的最终化。若所有投票者都已在
   `voters` 中，当前重复票不会补齐签名对象；两验证人网络可能只能等验证者集变化
  或人工恢复。该限制是 liveness 风险，不改变“数据库确认前不报告 FINALIZED”的
  安全边界；生产级修复需要持久化投票签名或 outbox。
8. **real-L1 与桥不变**：本次未触碰真机 L1、桥存款/提款/已提交根持久化。

---

## 六、后续

- 持久化最终性投票 outbox（或本地 WAL），覆盖进程崩溃窗口。
- 网络分区矩阵：双节点断连、单边分区、恢复后投票补投。
- 拜占庭矩阵：多验证人双签、伪造公钥/签名、重复票洪泛。
- 多副本最终性缓存一致性方案（ADR 级决策）。
