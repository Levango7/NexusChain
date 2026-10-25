# 方案 PLAN-039：区块持久化失败与滞留的可见性（roadmap #29 收口）

- **状态**：已交付（2026-10-26）；单测 + 模块定向回归 + 三组测试床验证
  （整库停机 / 写入事务失败 / outbox 读取链路，见 §四）
- **前置**：PLAN-019（PendingBlocksManager + 持久化 outbox）、#195（outbox 恢复缺陷修复）
- **范围**：`StateDB` 写库失败/状态超时的内存聚合 + `GET /consensus/block-writer`；
  outbox 滞留信号（最老行 created_at / 最大重试次数）并入 `GET /consensus/block-outbox`
- **不包含**：outbox 自动清理（决策见 §2.3）、写失败自动重试策略变更、告警系统对接

---

## 一、问题（roadmap #29 ① ②）

**① 写库失败只进日志**：`RDBMSBlockChainImpl.writeBlock` 失败 → `StateDB.writeBlock`
按 A2 修复记录 ERROR 日志并**跳过该块**（不再持锁无限重试；后续确认轮次补写）。
但失败没有任何聚合/端点：运维无法回答"有没有跳块、跳了多少、最后发生在什么高度"。

**② outbox 滞留无信号**：`pending_block_outbox` 行只在补写成功后删除；"永远写不进去"
的行（数据损坏/校验恒失败）会无限滞留，而观测只有行数——无法区分"正在快速排空"
与"卡住不动"。

## 二、方案

### 2.1 写失败聚合（①）

- 新类 `BlockWriteStats`（`org.nexus.db`）：计数 `writeFailures`（写入失败/跳块的
  **尝试次数**——同一高度在后续确认轮次重试会重复计数，不是不同区块数）与
  `stateUpdateTimeouts`（pendingBlock 等待超时被强制清空），并记录**最近一次异常**
  的高度/时刻；纯内存、只增不减、进程重启清零（定位是"失败聚合/告警"，
  不是持久化审计——单次失败的完整堆栈仍在日志里）。
- `StateDB.writeBlock` 三个既有吞点接入：跳块分支（`writeBlock=false`）记
  `writeFailures`；两处 pendingBlock 等待超时分支（强制清空防死锁）记
  `stateUpdateTimeouts`。
- 观测端点 **`GET /consensus/block-writer`**：内存读、**不依赖数据库**——
  PG 故障期间仍可读；`LocalRpcInterceptor` 白名单同步。

### 2.2 滞留信号（②）

- `PendingBlockOutbox` 接口加两个 default 方法：`oldestCreatedAt()`、
  `maxAttempts()`（默认 -1 = 未知/不支持，旧实现不受影响）。
- `JdbcPendingBlockOutbox` 实现：`MIN(created_at)` / `COALESCE(MAX(attempts), -1)`；
  不可读/未装配一律 -1（与 `pendingCount` 同款降级）。
- `PendingBlocksManager.outboxStatus()` 增字段 `oldestPendingCreatedAt` /
  `maxAttempts`。
- **判读方式（写给运维）**：行数不清零 + `now - oldestPendingCreatedAt` ≫ 本应恢复
  时间（分钟级）⇒ 有永远写不进去的行；配合 `maxAttempts` 持续增长确认。

### 2.3 决策：outbox **不自动清理**

滞留行的结局只有两种：能被修好（人工修数据/对端重放），或被判定永久不可恢复。
**自动删除会先于"判定"销毁恢复数据/审计证据**，风险大于收益；本次只上可见性，
把"删不删、何时删"留给运维（可选动作：导出后人工清理）。
**revisit 条件**：真实部署出现"不可恢复行长期滞留导致存储增长/告警噪音"时，
再设计"导出先行"的保留策略。

## 三、测试

| 用例 | 断言 |
|---|---|
| `BlockWriteStatsTest`（3） | 初始零值 / 写失败累计且"最近异常"取最新 / 超时与写失败分桶 |
| `JdbcPendingBlockOutboxTest`（+2） | 最老行时刻与最大 attempts 反映真实行 / 空表与未装配 → -1 |
| `PendingBlocksManagerStorageRetryTest`（+2） | outboxStatus 滞留字段 / 未装配降级 |

模块全量回归见 CHANGELOG。

## 四、边界与验证记录

- 计数是**内存聚合**：进程重启清零；不替代日志。
- `stateUpdateTimeouts` 非零是更严重信号（状态更新等待超时），本次只计数、不自动动作。
- outbox 滞留信号仅在装配了 Jdbc outbox 时有意义（未装配 → -1）。
- **测试床边界**（2026-10-26，本机虚拟化边界多机：A=Windows JVM + Windows PG /
  B=WSL2 JVM + WSL PG / C=Docker）：不是物理多机、不是真实机房；没有断电/网络分区，
  只有"停 PG 服务"与"DB 触发器"两类受控注入。仅凭单测不宣称"故障注入已验证"。

### 4.1 验证 A：B 的 PG 停止 → 恢复（整库不可达，有效）

三节点重置后对齐（A=38 / B=34 / C=38）；02:58:52 停 B 的 PG，03:02:58 恢复。

| 时点 | A | B | C | memoryPending | databasePending | oldest/max | writeFailures |
|---|---|---|---|---|---|---|---|
| 基线 | 38 | 34 | 38 | 0 | 0 | -1 / -1 | 0 |
| DOWN+30s | 53 | 34 | 53 | 14 | -1 | -1 / -1 | 0 |
| DOWN+120s | 89 | 34 | 89 | 52 | -1 | -1 / -1 | 0 |
| DOWN+240s | 140 | 34 | 140 | 100 | -1 | -1 / -1 | 0 |
| UP+30s | 155 | 147 | 155 | 0 | 0 | -1 / -1 | 0 |
| 后续核对 | 219 | 219 | 221 | 0 | 0 | -1 / -1 | 0 |

**结论**：
- 观测端点在库不可达期间**仍可读**（`block-writer` 纯内存；`block-outbox`
  的 DB 字段按约定降级为 `-1`）。
- 积压**可见**：`memoryPending` 随 A/C 出块线性增长到 100；恢复后 30s 内归零，
  B 从 34 追到 147（约 113 块，gap 8），符合"正在快速排空"。
- `writeFailures=0` 在整库停机窗口是**正确语义**：该路径是"`bc.writeBlock`
  抛 `DataAccessException` → `PendingBlocksManager` 内存重试队列"，不是
  "`bc.writeBlock` 返回 false → 跳块"。因此验证 A **不能**验证 ①，需要验证 B。

### 4.2 验证 B：header INSERT 触发器（库在线、写入事务失败，有效）

03:03:50 安装 `BEFORE INSERT ON header` 抛异常触发器（`ON CONFLICT DO NOTHING`
也会先触发），03:05:52 撤除。基线 A=159 / B=156 / C=161，`writeFailures=0`。

| 时点 | writeFailures | lastFailureHeight | stateUpdateTimeouts | B 高度（内存） |
|---|---|---|---|---|
| FAIL+15s | 5 | 155 | 0 | 159 |
| FAIL+30s | 127 | 168 | 0 | 171 |
| FAIL+60s | 448 | 180 | 0 | 183 |
| FAIL+120s | 1666 | 204 | 0 | 207 |
| REC+15s | 1666（停止增长） | 204 | 0 | 210 |
| REC+约 50s | 1666 | 204 | 0 | header max=216；A=B=219 |

**结论**：
- ① 的"写入失败 → 跳块"路径被真实注入触发并聚合；`lastFailureHeight` 跟随
  最新失败尝试。
- 撤除故障后计数停止增长；后续确认轮次把跳过的 header 补写到 216，
  三节点追平（219 / 219 / 221）。
- **口径**：1666 是**失败尝试次数**，不是不同区块数——2 分钟内
  `lastFailureHeight` 覆盖 155→204 约 50 个高度区间，同一批高度在每次确认
  轮次被反复尝试，故重复计数。端点/CHANGELOG 已按"尝试次数"表述。
- **高度 ≠ 已落库**：写入失败但校验通过时，`/consensus/status` 的 B 高度仍随
  内存链前进（207，而最后失败高度 204）。只看高度会误判持久化健康，
  `writeFailures` / `lastFailureHeight` 补的正是这个盲区。

### 4.3 验证 C：outbox 滞留字段读取链路（合成行，仅读路径）

插入一条**明确标注**的合成行（`block_hash='plan039-synthetic-row'`，
`created_at=now-1h`，`attempts=9`）后，`GET /consensus/block-outbox` 返回：

- `databasePending=1`、`oldestPendingCreatedAt=1792951597686`（≈ now−3 600 000 ms）、
  `maxAttempts=9`；
- 删除该行后回到 `0 / -1 / -1`。

**边界**：这是合成行，只验证端点对真实 DB 行的读取与降级；**没有**复现
"永久不可恢复的真实滞留块"（需要数据损坏/恒定校验失败，属运维场景）。
字段 SQL 语义由单测覆盖，不宣称真实滞留已被故障注入。

### 4.4 验证总结与未覆盖面

- 三组验证分别覆盖：整库不可达的观测降级与队列排空（4.1）、写入事务失败的
  跳块聚合与补写（4.2）、outbox 字段读取链路（4.3）。三者不是同一路径，不能互相替代。
- 首轮实验（02:35–02:49）因测试床基线已发散（A=10173 / B=10029 / C=11707，
  A/B 已停止出块）而**无效**，仅证明"库故障期间端点可读/可降级"；已重置重跑，
  本文件只把有效结果作为验证依据。
- **未覆盖**：物理多机/真实机房断电与网络分区、真实"永久滞留"行、告警系统对接；
  内存计数重启清零。PLAN-039 只交付可见性，不包含自动清理/自动修复。
