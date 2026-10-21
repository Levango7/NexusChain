# 方案 PLAN-018：最终性持久化闭环（Phase 6C 第二切片）

> 状态：实现完成，验收通过（证据见 §四）
> 前置：[PLAN-016](PLAN-016-finality-fault-injection.md)（Phase 6B 故障注入）、
> [PLAN-017](PLAN-017-account-store-degradation.md)（Phase 6C 第一切片：PG/AccountDB 降级）
> 关联缺口：`docs/roadmap-unresolved.md` #27；独立审计
> `docs/agent-board/20261021-1320-nex-audit-finality-6a-6b.md` 的 P1/P0 条目

---

## 一、问题与目标

PLAN-016 把「最终性表短时不可用」从「污染内存 / 提前对外可见 / 拖垮区块采纳」修到了
「不污染、不提前、可重试」。但**重试闭环本身**还有三个洞，本切片补掉：

| # | 缺口 | 后果 | 证据 |
|---|------|------|------|
| **G1** | `finality_votes` 只存 `(epoch, checkpoint, validator)`，**不存签名** | 节点重启后只恢复得出「谁投过」，恢复不出「投的是什么签名」→ `FinalityGadget.tryFinalize` 的聚合验签无票可验 → 检查点**永远无法在重启后补最终化** | `FinalityGadget.java:231`（落库只传 3 个参数）、`FinalityStateStore.java:29`（接口签名） |
| **G2** | 写库失败的重试队列**纯内存** | 进程崩溃即丢；数据库长时间不可用时，「写库意图」无处安放 | `FinalityCoordinator.java:81-82`（`Deque<Vote> pendingVotes`） |
| **G3** | 接收侧写库失败 → **只打一条 WARN，票直接蒸发** | 发送方**不会**重发（它只在自己写库失败时才重发）⇒ 对方库故障时，这张远端票没有任何一方会重投，**静默丢失** | `FinalityVoteBroadcaster.java:140-147`（旧注释写「发送方会重发」，该前提并不成立） |

**目标**（本切片的三件事）：

1. **持久化 outbox**：待补写票连同完整签名落进 `finality_vote_outbox` 表，跨进程重启存活。
2. **完整 Vote 签名**：`finality_votes` 增列 `signature` / `public_key`，重启后能恢复完整
   Vote 对象 → 重启后**不需要新票**即可补最终化。
3. **接收侧独立重试**：收到远端票写库失败时，**接收方自己**排队重试落库。

顺带修掉三处队列语义缺陷（审计 P3 与本次实测暴露）：

- **队首阻塞**：旧 `flushPendingVotes()` 队首失败即 `return`，后面的票被无限期饿死。
- **早退顺序**：旧代码在「本节点不是活跃验证人」时**先早退、后 flush** ⇒ 节点被移出
  ACTIVE 后队列永远排不空。
- **溢出丢最旧**：队列满时静默丢最旧票（只打 ERROR），且只有本地票进队列。

## 二、根因与修复

### 2.1 签名随票落库（修 G1）

- `FinalityStateStore` 新增 **default 方法** `recordVote(epoch, hash, validator, signature, publicKey)`
  —— default 退化为旧的 3 参版本，**既有实现与测试替身不必同步改造**（避免把一次
  加固变成全仓接口改造）。
- `JdbcFinalityStateStore` 覆写为真落库：可移植 upsert（`JdbcUpsert.upsert`）先 UPDATE
  补齐后到的签名，未命中再 INSERT。
- DDL 是 **additive-only**（`docs/db-schema-versioning.md` §三.1）：
  `ALTER TABLE finality_votes ADD COLUMN IF NOT EXISTS signature/public_key`，
  在 `init()` 里执行且**幂等** —— 因为 `SchemaMeta` 只登记版本号，不会校验已登记为
  version=1 的旧库到底有没有这两列（不补列 ⇒ 新代码写签名直接 `BadSqlGrammarException`）。
- `FinalityGadget.submitVote` 改为传全量；`restoreFromStore` 恢复带签名的 Vote 对象
  （**跳过已终局的检查点**，避免把全部历史 Vote 读进内存）。

### 2.2 持久化 outbox（修 G2）

新增表（同样 additive-only、幂等 DDL）：

```sql
CREATE TABLE IF NOT EXISTS finality_vote_outbox (
  epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL,
  validator VARCHAR(128) NOT NULL, origin VARCHAR(16) NOT NULL,
  signature VARCHAR(256), public_key VARCHAR(256),
  created_at BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512),
  PRIMARY KEY (epoch, checkpoint_hash, validator, origin))
```

`origin` 区分 `local`（本节点签发）与 `received`（对端来的），决定补写成功后要不要补发广播。

新增 `FinalityOutboxManager`（`org.nexus.consensus.finality`）作为**重试中枢**：

- **内存队列**（每类 256 上界）：数据库整体不可用时的兜底；
- **持久化 outbox**：数据库可用时的耐久层，跨进程重启存活；
- 两者同源：先入内存队列，再尽力落 outbox；补写成功后同时清理；
- `@Scheduled(fixedDelay=5s)` 定时补写 + 出块事件即时触发；
- 补写顺序：先内存（对象完整）→ 再库里残留的行（重启恢复路径）。

### 2.3 接收侧独立重试（修 G3）

`FinalityVoteBroadcaster.onVoteReceived` 的 catch 分支从「只打 WARN」改为
`outboxManager.enqueueReceived(vote, e)`：**接收方自己**负责把这张远端票补写进库。

> 旧注释里「发送方的重试队列会在数据库恢复后重新广播该票」是**错的**：
> 发送方只在**它自己**写库失败时才入队重发；对方库故障时它一切正常，不会有任何重投。

### 2.4 补最终化扫描

- **启动恢复后**立即扫一遍：权重已达 2/3 但未标记最终化的检查点，重新走
  「验签覆盖度 → 聚合验签 → 写库标记」；
- 另有 `@Scheduled(fixedDelay=10s)` 的定时扫描（此时聚合器已注入，比启动时更可靠）；
- 单轮最多处理 50 个检查点，避免历史积压时聚合验签造成 CPU 尖峰；
- 写库失败只跳过该检查点，不阻断其它检查点，下一轮再试。

### 2.5 队列语义修正

| 旧行为 | 新行为 |
|--------|--------|
| 队首失败即 `return`，后面的票饿死 | 每轮遍历全部待写票，单张失败不影响其它票 |
| 非活跃验证人早退在 flush 之前 ⇒ 队列永排不空 | flush 提到两个早退**之前** |
| 队列满丢最旧票（仅本地票进队列） | 内存队列仍有界（256）且显式 ERROR；**落库路径同时有持久化 outbox**；本地票与远端票分队列计数 |
| 补写成功不补发广播（发送方那张票从未广播出去） | 本地票补写成功后由 `FinalityOutboxManager` 回调协调器**补发一次 P2P 广播** |

### 2.6 可观测

新增 `GET /consensus/finality-outbox`（与 `/consensus/storage-status` 同样免鉴权，
不依赖数据库）：返回 `state`、`localPending`、`receivedPending`、`databasePending`、
`totalEnqueued/totalFlushed/totalFailed`、`lastError`、最近入队/刷新时间。

## 三、测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `FinalityOutboxManagerTest`（新，5 例） | 本地票写库失败→排队→库恢复→补写落库→outbox 行清理；**接收侧**远端票本地补写（不依赖发送方重发）；**进程重启**后从库里捞回 outbox 行补写；数据库全挂时入队/flush 不抛异常且票留在内存；补写成功后补发广播 |
| `FinalityRestartFinalizationTest`（新，2 例） | 票连同签名落库；**重启后不需要新票**即可补最终化（PLAN-016 审计 P1 的正面修复证据）；恢复出的 Vote 带签名与公钥 |
| `JdbcFinalityStateStoreTest`（+3 例） | 签名/公钥落库并可重载；outbox 增删改查往返且**跨重启存活**；老库（无 signature 列）经 `init()` 幂等升级后可正常写入 |
| `FinalityVoteBroadcasterTest`（+1 例） | 接收侧写库失败时票进入本地补写队列（不再被丢弃） |

> `FinalityGadgetFaultInjectionTest.restartAfterVoteButBeforeFinalizationNeedsNewSignatureToFinish`
> 保留：它的测试替身只实现旧的 3 参 `recordVote`（**不落签名**），因此仍表现为
> 「重启后需要新票」；生产路径的行为由 `FinalityRestartFinalizationTest` 覆盖。

core 全量测试：**1619 个用例，0 失败，19 跳过**（177 个测试套件；本切片新增 2 套件 11 用例）。

## 四、真实 PG 验收

环境：同主机双进程 + 容器 PostgreSQL 16（**不是多机高可用**）；脚本
`C:\Users\winge\AppData\Local\Temp\opencode\p6c-pg-outage.ps1`（临时目录，不入库）。
证据原始输出：`p6c-s2-finality-console2.log` / `p6c-s2-pg-console.log`。

### 4.1 主验收：最终性表不可用（链继续出块）

故障注入：把 `finality_votes` / `finality_checkpoints` **改名**（表不可用），
其余（链、账户、状态接口）正常 —— 这正是"链还在跑、但最终性写不进去"的场景。

- 故障时长 **25.4 秒**；高度 4423/4424 → 4574/4574。
- **故障期间** `/consensus/finality-outbox`：

  | 节点 | state | localPending | receivedPending | databasePending | totalFlushed |
  |------|-------|--------------|-----------------|-----------------|--------------|
  | A | `DEGRADED` | 11 | 0 | **22** | 0 |
  | B | `DEGRADED` | 11 | 0 | **22** | 0 |

  ⇒ 每节点 11 张票进入内存队列，**22 行「写库意图」真的落进了
  `finality_vote_outbox` 表**（跨进程重启可恢复的那一层）。
- **恢复后**：`state=UP`，`localPending=0`、`receivedPending=0`、`databasePending=0`，
  A 补写 24 张、B 补写 14 张；日志里既有 `origin=local` 也有 `origin=received`
  的补写记录（**接收侧独立重试路径确实被走到**）。
- **数据库原子快照**（同一时刻单条 SQL，避免跨时刻口径漂移）：

  ```
  finality_votes        : 172 行，其中带 signature 172 行、带 public_key 172 行（100%）
  finality_vote_outbox  : 0 行（全部补写完并清理）
  finality_checkpoints : 86 行，epoch 1060..1145，检查点缺口 0
  每 epoch 投票数        : 2..2
  ```
- 其它：`ERROR` 行 A=14/B=14（**日志采样之后**；采样前同场景为 64/70）、
  `pendingBlock` 超时 0、区块采纳失败 0、proposer 暂停 0。

### 4.2 对照：PG 整体停机

- 停机 **27.8 秒**；`/consensus/finality-outbox` 全程 `UP`（0/0/0），
  `ERROR` 0，checkpoint 缺口 0。
- 原因（重要，不要误读）：Phase 6C 第一切片之后，**PG 整体不可用时 proposer 会安全暂停，
  链根本不再出块 ⇒ 不产生新票 ⇒ 没有"写库意图"需要保存**。
  也就是说：本切片的 outbox 针对的是"链还在跑、最终性写不进去"，
  而不是"整库停机"——后者由第一切片的 fail-closed 语义处理。

### 4.3 结论

本切片证明了：

1. 票写不进 `finality_votes` 时，「写库意图」会**同时**进内存队列与**持久化 outbox 表**，
   跨进程重启可恢复（`FinalityOutboxManagerTest.outboxRowSurvivesProcessRestartAndIsFlushedByFreshManager`）。
2. 存储恢复后，队列与 outbox 表**自动清空**，票被补写进 `finality_votes`，
   且带签名与公钥（172/172），最终性检查点**无缺口**。
3. 接收侧收到的远端票在写库失败时**不再蒸发**，由接收方自己补写
   （`origin=received` 的补写日志 + 单元测试）。
4. 重启后不需要新票即可补最终化（`FinalityRestartFinalizationTest`）。

未证明（勿外推）：多机/多副本下的行为、真实网络分区、主机断电、PG 主备切换、
outbox 表自身的复制与故障。

## 五、明确边界（勿夸大）

1. **数据库整体不可用时，outbox 落不了盘**：此时只有内存队列兜底，进程崩溃仍会丢票。
   真正的耐久需要数据库可用（哪怕只是能写 outbox 那张表）。
2. **老数据没有签名**：迁移前写入 `finality_votes` 的行没有 `signature`，重启后聚合验签
   只能覆盖新票。计数口径保持不变（DB 行本身就是「本节点验签通过后才写入」的凭证），
   但这条残余口径只影响迁移前的老 epoch，会随时间自然消失。
3. **多副本最终性缓存一致性未解决**：`JdbcFinalityStateStore` 的读路径仍是启动快照，
   A 终局的检查点不会让 B 的内存缓存失效（既有缺口，非本切片引入）。
4. **接收侧不重新广播**：避免投票风暴；对端票由接收方自己落库即可。
5. **未覆盖**：真实网络分区、完整拜占庭矩阵、主机断电/磁盘损坏、PG 主备切换、
   outbox 跨库复制。
6. **单机共享一个 PG 的拓扑下**，outbox 行可能被另一节点补写掉 —— 这是安全的
   （票最终落进 `finality_votes`，任何节点重启后都会重载它），但意味着「谁补写」
   不等于「谁计票」，多副本内存计票仍受第 3 条限制。

## 六、后续顺序

1. **持久化区块 outbox**（与本切片的最终性 outbox 同构，但对象是区块）；
2. **3 节点多主机混沌测试**（真正的多机、网络分区、主机断电）；
3. **real-L1 / 桥资金链路持久化**；
4. 性能、灾备与外部安全验证。
