# 方案 PLAN-073：`finality_vote_outbox` 可观测性（精确积压 + 丢票拆分 + 4 条告警）

- **状态**：已交付（2026-11-04）
- **前置**：PLAN-018（投票 outbox）、PLAN-040（区块 outbox 指标先例）、
  PLAN-072（outbox 脏行不传播、无需清理）
- **范围**：修 `status()` 两个语义缺陷；新增 `FinalityOutboxMetrics`；
  把丢票从 `totalFailed` 拆成 `dropped`；4 条告警 + promtool 演练 +
  指标名/标签契约门禁
- **不包含**：重试策略/`attempts` 上限/自动清理、`submitVote` 拒绝语义、
  retention、真实集群告警演练、物理多机

---

## 一、问题（四条读代码确认的缺口）

PLAN-072 已确认 outbox 的脏行**不传播**（重放时被 `submitVote` 正常拒绝并移除），
所以 outbox 本身不需要清理。但那次取证同时暴露了另一件事：
**outbox 的运行状态在 Prometheus 上完全不可见**，而它恰恰是「票写不进库」的
最后兜底。具体有四条：

### 1.1 积压数最多只报到 64（现有端点的真实缺陷）

`FinalityOutboxManager.status()`：

```java
dbPending = store.loadPendingVotes(DB_FETCH_BATCH).size();   // DB_FETCH_BATCH = 64
```

而 `JdbcFinalityStateStore.loadPendingVotes(limit)` 是 `... LIMIT 64`
（`JdbcFinalityStateStore:214-219`）。⇒ 真实积压 500 行时，端点仍报
`databasePending = 64`，**低报**且看不出「还在增长」。

### 1.2 数据库读不到被报成 0（看起来健康）

```java
int dbPending = 0;
try {
    ...
    dbPending = store.loadPendingVotes(DB_FETCH_BATCH).size();
} catch (RuntimeException e) {
    // 数据库不可用时读不到行数，内存计数仍然有效。
}
```

⇒ PG 故障时 `databasePending = 0`，与「真的没有积压」**无法区分**。
PLAN-040 对区块 outbox 的做法是显式暴露 `readable`（1/0，采样前 NaN）。

### 1.3 零 Prometheus 指标、零告警

全仓 grep `finality_outbox` 在 `.java` / 规则 YAML 中 **0 命中**：

- `FinalityMetrics` 已有 7 个 `nexus_finality_*` 指标，**没有一个来自 outbox**；
- `deploy/k8s/50-prometheus-rules.yaml` 里 `FinalityStalled` 的 runbook 明写
  「查 `GET /consensus/finality-outbox`」——**要人去 curl**；
- 真正的故障窗口（PG 抖动/停机）恰恰是没人盯屏的时候。

### 1.4 永久丢票与临时失败混在同一个计数器

`totalFailed` 在三处自增：

| 位置 | 语义 | 后果 |
|---|---|---|
| `FinalityOutboxManager:132` | 队列满（`MAX_PENDING_PER_ORIGIN = 256`）**丢最老的票** | **永久数据丢失** |
| `FinalityOutboxManager:220` | 内存队列重试失败 | 临时失败，会重试 |
| `FinalityOutboxManager:251` | 库行重试失败 | 临时失败，会重试 |

⇒ 「丢了一张票」和「一次重试没成功」在监控上是同一个数，**丢票只写 ERROR 日志**。

## 二、方案

### 2.1 存储层精确计数

`FinalityStateStore` 增加：

```java
default long countPendingVotes() {
    List<FinalityOutboxEntry> rows = loadPendingVotes(Integer.MAX_VALUE);
    return rows == null ? -1L : rows.size();
}
```

- `JdbcFinalityStateStore` 覆盖为 `SELECT count(*) FROM finality_vote_outbox`；
- `InMemoryFinalityStateStore` 覆盖为 `outbox.size()`；
- 默认实现保留给「不支持 outbox」的实现（返回 0）与测试替身；
- `-1` 表示本实现无法给出精确值（调用方视为「读不到」）。

### 2.2 `status()` 的语义修正

- 改用 `countPendingVotes()`，不再把 `LIMIT 64` 的第一批结果当积压数；
- 新增 `databaseReadable`：读不到时 `false`，`databasePending` 只作占位；
- `isDegraded()` 纳入 `!databaseReadable`——不能因为读不到而显示 UP；
- `snapshot()` 增加 `databaseReadable` 与 `totalDropped`（字段名保持向后兼容）。

### 2.3 `FinalityOutboxMetrics`（`MeterBinder` + `@Component`）

**30s 定时采样、抓取零 DB 访问**（照抄 PLAN-040 的纪律，避免一个 Gauge 卡住
把整页抓取打哑）。

**「是否在跑」以 `FinalityCoordinator` 为准**：`FinalityOutboxManager` 是无条件
`@Component`，而 coordinator 带 `@ConditionalOnProperty(nexus.consensus.mode=pos)`。
若用 manager 判断，非 pos 模式下 PG 不可达就会让 `FinalityOutboxUnreadable`
在没有最终性的部署里误报。因此 coordinator 不存在时 `enabled=0`、不采样、
`readable=1`。

| 指标 | 类型 | 语义 |
|---|---|---|
| `nexus_finality_outbox_enabled` | Gauge | outbox 是否装配（1/0；采样前 NaN） |
| `nexus_finality_outbox_pending{origin="local"\|"received"}` | Gauge | 内存待补写队列长度 |
| `nexus_finality_outbox_database_pending` | Gauge | 持久化 outbox **精确**行数（读不到为 NaN） |
| `nexus_finality_outbox_database_readable` | Gauge | 1=可读或未装配；0=读不到；采样前 NaN |
| `nexus_finality_outbox_enqueued_total` | FunctionCounter | 入队总数 |
| `nexus_finality_outbox_flushed_total` | FunctionCounter | 补写成功总数 |
| `nexus_finality_outbox_failed_total` | FunctionCounter | 重试失败总数（**不含**丢票） |
| `nexus_finality_outbox_dropped_total` | FunctionCounter | **队列满丢票总数（永久丢失）** |

标签键集合严格等于 `{origin}`，取值只有 `local`/`received`（编译器/枚举保证有界，
不引入验证人地址等高基数字段）。

### 2.4 把「丢票」从 `failed` 里拆出来

`FinalityOutboxManager` 增加 `totalDropped`；队列满时**只**加 `totalDropped`，
`totalFailed` 保持「重试失败（还会再试）」语义。`status()` 与端点快照同步暴露
`totalDropped`。

### 2.5 告警（4 条）

| 告警 | 表达式 | `for` | 级别 |
|---|---|---|---|
| `FinalityOutboxDropping` | `increase(nexus_finality_outbox_dropped_total[10m]) > 0` | 1m | warning |
| `FinalityOutboxBacklog` | `nexus_finality_outbox_database_pending > 0` | 10m | warning |
| `FinalityOutboxMemoryBacklog` | `nexus_finality_outbox_pending > 0` | 10m | warning |
| `FinalityOutboxUnreadable` | `nexus_finality_outbox_database_readable == 0` | 5m | warning |

- 规则文件当前实际告警数从 **20** 增至 **24**（文件头原写 18，已按实际值修正）；
- `deploy/k8s/tests/prometheus-rules.test.yaml` 增加 4 组演练（含 for 窗口边界
  与「readable=1 不触发」对照），`promtool check rules` + `promtool test rules`
  实跑通过；
- runbook 补 4 条排障手册，并顺带补齐历史漏登的 2 条拒绝计数告警。

### 2.6 门禁

- `FinalityOutboxMetricsTest` **7 例**：采样前 NaN、可读快照精确映射（100 行不能被
  64 低报）、读不到时 `database_pending=NaN` + `readable=0`、未装配不算故障、
  非 pos 模式（有 manager 无 coordinator）不报 unreadable、采样异常保留上次成功值、
  `pending` 标签键集合严格等于 `{origin}`；
- `JdbcFinalityStateStoreTest.countPendingVotesIsExactBeyondFetchBatch`：
  100 行时 `countPendingVotes()==100`，同时 `loadPendingVotes(64).size()==64`；
- `FinalityOutboxManagerTest.statusUsesExactCountNotTheFetchBatch`：manager 必须
  调用精确计数（mock 出 `count=100` / `load=64`）；
- `FinalityOutboxManagerTest.queueOverflowCountsDroppedSeparatelyFromRetryFailures`：
  第 257 张票只加 `dropped`，`totalFailed` 保持 0；
- `FinalityRulesContractTest`：`EXPECTED_METER_COUNT` 14 → **22**，新 binder 注册，
  白名单登记 4 个无告警指标（enabled/enqueued/flushed/failed）；
- `FinalityMetricsRegistrationTest`：新类必须带 `@Component`，4 个告警消费的
  指标名必须存在；
- `WiringAuditTest` 自动覆盖新 `MeterBinder`。

## 三、反向对照（3 组，全部精确命中）

| 组 | 变异 | 精确变红的断言 | 还原后 SHA-256 |
|---|---|---|---|
| A | `countPendingVotes()` 的 `SELECT count(*)` 改回 `loadPendingVotes(64).size()` | `JdbcFinalityStateStoreTest.java:184`：`expected: <100> but was: <64>` | `F336E53A…F6680EC0` |
| B | `FinalityOutboxMetrics` 把「读不到」改回 `readable=true` + `databasePending=status.getDatabasePending()` | `FinalityOutboxMetricsTest.java:138`：`database_pending` 期望 NaN、实际 0.0 | `B9E6776C…73193412` |
| C | 队列满丢票改回只加 `totalFailed` | `FinalityOutboxManagerTest.java:236`：`totalDropped` 期望 1、实际 0 | `D30B5B8C…5A6599AB` |

每组都做了「变异 → 红 → 还原 → 同一用例绿 → SHA-256 与变异前逐字一致」的完整闭环
（三组均在最终代码树上重跑）；A 的红点是**精确计数断言**（不是同用例里的
`LIMIT 64` 断言），B 的红点是**NaN 断言**（同用例下一条 `readable==0` 也会红），
C 的红点是**`totalDropped` 断言**。

## 四、验证

- 定向单测：`FinalityOutboxMetricsTest` / `FinalityOutboxManagerTest` /
  `JdbcFinalityStateStoreTest` / `FinalityRulesContractTest` /
  `FinalityMetricsRegistrationTest` / `WiringAuditTest` 全绿；
- `promtool check rules`：**24 rules found / SUCCESS**；
  `promtool test rules`：全部演练 **SUCCESS**；
- 全量 `:nexus-core:nexus-core:test --rerun-tasks`：见交付 PR 的 CI 结果；
- `spotbugsMain` / Code Hygiene：见交付 PR 的 CI 结果。

## 五、边界（勿引用为更强结论）

1. **同内核单机**：指标与告警只能在单机 + promtool 上验证，**不是物理多机**，
   不构成集群结论；
2. **不做真实 Prometheus/Alertmanager 演练**：规则用 promtool 静态校验 +
   演练用例，不声称「已在真实集群触发」；
3. 采样周期 30s ⇒ 指标最多滞后 30s（告警 `for` 已覆盖）；
4. `databasePending` 是**本节点视角**的 outbox 行数，不代表其它节点；
5. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR；
6. 本切片只让丢票**可见**，不改重试策略/不加 `attempts` 上限/不做自动清理
   （PLAN-072 已登记为独立决策）。
