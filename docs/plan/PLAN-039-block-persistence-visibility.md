# 方案 PLAN-039：区块持久化失败与滞留的可见性（roadmap #29 收口）

- **状态**：代码完成（2026-10-26）；新单测 + 模块定向回归绿；故障注入验证见 §四
  （首轮基线已发散、无效，已重置三节点重跑）
- **前置**：PLAN-019（PendingBlocksManager + 持久化 outbox）、#195（outbox 恢复缺陷修复）
- **范围**：`StateDB` 写库失败/状态超时的内存聚合 + `GET /consensus/block-writer`；
  outbox 滞留信号（最老行年龄 / 最大重试次数）并入 `GET /consensus/block-outbox`
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

- 新类 `BlockWriteStats`（`org.nexus.db`）：计数 `writeFailures`（跳块）与
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
- **判读方式（写给运维）**：行数不清零 + 最老行年龄 ≫ 本应恢复时间（分钟级）
  ⇒ 有永远写不进去的行；配合 `maxAttempts` 持续增长确认。

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
- **测试床故障注入验证**（2026-10-26，本机虚拟化边界多机：A=Windows / B=WSL2 / C=Docker）：
  见下（执行后如实填写；仅凭单测不宣称"故障注入已验证"）。

### 验证记录（B 的 PG 停止 → 恢复）

**首轮（2026-10-26 02:35–02:49，无效）**：实验开始前测试床已处于分叉/停滞状态
（基线 A=10173、B=10029、C=11707；A/B 已不再出块），B 在停机前就没有新块可写。
因此 PG 停机窗口内：

- `/consensus/block-writer` 始终 `writeFailures=0`、`stateUpdateTimeouts=0`；
- `/consensus/block-outbox` 在 PG 停机时正确降级为 `databasePending=-1`，
  恢复后回到 `0`（端点自身在库不可用时仍可读，这一点有效）；
- B 高度始终 10029，未发生"写库失败→跳块"路径，**不能**用于验证 ①。

**结论**：首轮只验证了"库故障期间观测端点可读/可降级"，不能宣称写失败聚合或
outbox 滞留信号已被故障注入验证。已重置三节点（A/B 同一高度、C 在线）后重跑：
停 B 的 PG，让 A/C 继续出块并驱动 B 追块，再恢复 PG 观察 `writeFailures` /
`oldestPendingCreatedAt` / `maxAttempts` 与追平情况。

**干净重跑（2026-10-26）**：见本轮 `pg-outage-clean.log` 与下表（执行后回填）。
