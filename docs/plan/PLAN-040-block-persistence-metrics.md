# 方案 PLAN-040：区块持久化指标的告警对接（roadmap #29 ③ 收口）

- **状态**：已交付（2026-10-26）；单测 6 例 + 定向回归 + 本地实抓 `/actuator/prometheus`
  + 规则静态校验（全部见 §四）
- **前置**：PLAN-039（`BlockWriteStats` + `GET /consensus/block-writer` +
  outbox 滞留字段）、PLAN-002 后续（`/actuator/prometheus` +
  `deploy/k8s/50-prometheus-rules.yaml`）
- **范围**：把 PLAN-039 的**内存信号**接进 Prometheus/Micrometer，并补齐 4 条告警规则
  （写失败、状态更新超时、outbox 积压、outbox 不可读）+ runbook
- **不包含**：自动清理 outbox、自动重试策略变更、物理多机/真实机房断电与网络分区、
  真实"永久不可恢复"行的复现（见 §五）

---

## 一、问题（roadmap #29 ③）

PLAN-039 已经把"写库失败了多少次、最后失败高度、outbox 最老行年龄/最大重试次数"
做成**端点**，但端点只有人去轮询才有意义：

- 生产告警链路是 Prometheus → Alertmanager，当前 `nexus-core` 只暴露
  `nexuschain_block_height`（链停摆）。写库失败/跳块、`pendingBlock` 状态更新超时、
  outbox 积压**都没有指标**，无法配置告警；
- `GET /consensus/block-writer` 在 PG 故障期间仍可读，但没人会一直盯着；
  真正的故障窗口（整库停机 4 分钟、写事务持续失败 2 分钟）恰恰是没人盯屏的时候；
- outbox 的"行数不清零"只能靠人工 curl，和"正在快速排空"无法在监控上区分。

因此 #29 ③ 里"告警系统未对接"是当前唯一**不依赖硬件/真实链**、可以立即收口的子项。

## 二、方案

### 2.1 指标（Micrometer → `/actuator/prometheus`）

新增 `org.nexus.metrics.BlockPersistenceMetrics`（`MeterBinder` + `@Component`），
随 Spring Boot 自动绑定到 `MeterRegistry`：

| 指标 | 类型 | 来源 | 语义 |
|---|---|---|---|
| `nexus_block_writer_write_failures_total` | FunctionCounter | `BlockWriteStats.writeFailures` | 写入失败/跳块的**尝试次数**（同一高度重试重复计数，PLAN-039 口径） |
| `nexus_block_writer_state_update_timeouts_total` | FunctionCounter | `BlockWriteStats.stateUpdateTimeouts` | `pendingBlock` 等待超时被强制清空的次数 |
| `nexus_block_writer_last_failure_height` | Gauge | `BlockWriteStats.lastFailureHeight` | 最近一次失败尝试的高度（无失败为 -1） |
| `nexus_block_writer_last_failure_age_seconds` | Gauge | `lastFailureAtMillis` | 距最近一次失败尝试的秒数（无失败为 NaN） |
| `nexus_block_outbox_enabled` | Gauge | `PendingBlocksManager.outboxStatus().enabled` | outbox 是否装配（0/1） |
| `nexus_block_outbox_memory_pending` | Gauge | `memoryPending` | 存储故障后的内存重试队列长度 |
| `nexus_block_outbox_database_pending` | Gauge | `databasePending` | 持久化 outbox 待补写行数（读不到为 NaN） |
| `nexus_block_outbox_oldest_pending_age_seconds` | Gauge | `oldestPendingCreatedAt` | 最老滞留行年龄（无滞留/读不到为 NaN） |
| `nexus_block_outbox_max_attempts` | Gauge | `maxAttempts` | 单行最大重试次数（无滞留/读不到为 NaN） |
| `nexus_block_outbox_readable` | Gauge | `databasePending >= 0` | outbox 可读性（1=可读或未装配；0=已装配但读不到；首次采样前 NaN） |

**抓取安全（关键设计）**：

- 写失败/超时/最后失败高度是**纯内存读**，抓取时零 DB 访问；
- outbox 的 DB 读放在 **`@Scheduled(fixedDelay=30s)` 采样**里（`outboxStatus()` 自带
  逐项异常兜底），Gauge 只读 volatile 缓存 —— **`/actuator/prometheus` 抓取绝不因
  PG 抖动而阻塞**（否则一个 Gauge 卡住会连带 `nexuschain_block_height` 整页抓取失败，
  把链停摆告警一起打哑）；
- 采样前 outbox 系列返回 **NaN**（而不是 0），避免"还没采到"被误读成"队列为空"；
- 采样失败时保留上一次成功值（不把缓存清零），由 `readable=0` 显式表达"现在读不到"。

### 2.2 告警规则（`deploy/k8s/50-prometheus-rules.yaml`）

新增 group `nexuschain.block-persistence`（4 条，全部 `runbook_url` 指向
`docs/runbooks/alerts.md`）：

| 告警 | 级别 | 表达式 | 含义 |
|---|---|---|---|
| `BlockWriteFailuresDetected` | warning | `increase(nexus_block_writer_write_failures_total[10m]) > 0` for 5m | 10 分钟内有写库失败/跳块尝试 |
| `BlockWriterStateUpdateTimeouts` | critical | `increase(nexus_block_writer_state_update_timeouts_total[10m]) > 0` for 5m | 状态更新等待超时（`pendingBlock` 被强制清空，可能卡住后续写入） |
| `BlockPersistenceBacklog` | warning | `nexus_block_outbox_memory_pending > 0 or nexus_block_outbox_database_pending > 0` for 10m | 内存队列或持久化 outbox 持续积压（PG 写入慢/不可用） |
| `BlockOutboxUnreadable` | warning | `nexus_block_outbox_readable == 0` for 5m | outbox 已装配但连续读不到数据库 |

**为什么写失败只给 warning**：PLAN-039 的计数是**尝试次数**，单次失败会在后续
确认轮次补写（测试床已实证撤除故障后追平）。它需要人看一眼，但不等于链停摆——
链停摆由既有 `BlockchainCoreStalled`（critical）覆盖。

**为什么积压给 10 分钟**：outbox 正常排空是秒级；持续 10 分钟仍有待补写行，
要么 PG 写不进去，要么补写循环没进展。此时看 `oldest_pending_age_seconds` /
`max_attempts` 区分"慢"与"卡死"。

### 2.3 明确不做

- **不自动清理 outbox**：沿用 PLAN-039 §2.3 的决策——滞留行是恢复数据/审计证据；
- **不改写失败重试策略**：本切片只做观测与告警；
- **不把告警接到具体 PagerDuty/Slack 路由**：仓库只维护 PrometheusRule，
  路由由部署方的 Alertmanager 配置决定（与既有 11 条一致）。

## 三、测试

`BlockPersistenceMetricsTest`（`SimpleMeterRegistry` + Mockito）：

1. 写失败/超时计数经 FunctionCounter 暴露，且随 `BlockWriteStats` 递增；
2. `last_failure_height` / `last_failure_age_seconds` 反映最近一次失败；
3. outbox 快照：正常值映射（pending / oldest age / max attempts / readable=1）；
4. `databasePending=-1` ⇒ DB 系列为 NaN、`readable=0`；
5. `enabled=false` ⇒ `readable=1`（未装配不算故障）、DB 系列 NaN；
6. 采样前 outbox 系列为 NaN（不冒充 0）；
7. `outboxStatus()` 抛异常时 `refreshOutboxSnapshot()` 不抛出、保留上一次值。

外加 `BlockWriteStatsTest` 对新增 getter 的断言（如未覆盖）。

## 四、验证（全部已做实）

1. **单测**：`BlockPersistenceMetricsTest` 6 例 + `BlockWriteStatsTest` 定向回归
   **BUILD SUCCESSFUL**（0 失败 0 错误）。
2. **真实抓取**（2026-10-26，本机单节点 + 测试床 PG 55433，启动 ~8s 首采后）：
   - FunctionCounter 后缀正确：`# TYPE nexus_block_writer_write_failures_total counter`、
     `# TYPE nexus_block_writer_state_update_timeouts_total counter`，值 0.0；
   - Gauge 正确：`nexus_block_writer_last_failure_height -1.0`、
     `nexus_block_writer_last_failure_age_seconds NaN`；
   - outbox 健康态：`nexus_block_outbox_enabled 1.0`、
     `nexus_block_outbox_database_pending 0.0`、`nexus_block_outbox_memory_pending 0.0`、
     `nexus_block_outbox_readable 1.0`、
     `oldest_pending_age_seconds / max_attempts NaN`（无滞留行）；
   - `/consensus/block-writer` 返回 `writeFailures=0` 与指标一致；
   - 既有 `nexuschain_block_height gauge 401.0` 仍在（未受影响）。
3. **规则静态校验**：Python 解析 `50-prometheus-rules.yaml` ⇒ **7 groups / 15 alerts**，
   4 条新告警在 `nexuschain.block-persistence`、全部带 `for`（5m/10m）、severity 正确；
   无重复告警名。CI 的 `k8s-sync-check` promtool 提取校验按既有流程跑。
4. **未做（如实）**：本次未复跑 PLAN-039 的触发器故障注入看告警触发——计数取数
   对象与 PLAN-039 实测路径是同一 `BlockWriteStats`，由 ② 实抓佐证接线；
   端到端触发留给部署后按 runbook「验证告警会响」流程执行。

## 五、边界（勿夸大）

- 指标是**进程内**状态：进程重启后 FunctionCounter 归零、Gauge 回到初值；
  跨重启的持久化审计仍看 outbox 表与日志；
- 采样周期 30s：outbox 指标最多滞后 30s（告警 `for` 已覆盖，不影响判定）；
- **物理多机/真实机房断电与网络分区**仍未验证（本机虚拟化边界三节点）；
- **真实"永久不可恢复"outbox 行**仍未复现：告警能指出"持续积压 + 年龄增长"，
  但不能替运维判定该行是否该删（PLAN-039 的 revisit 条件不变）；
- 告警规则只覆盖 `nexus-core` 进程；`Alertmanager` 路由、静默策略不在仓库内。

## 六、后续

1. 若生产出现"outbox 长期积压但 age 不增长"（新行持续进来、最老行被删），
   再评估是否需要按 `max_attempts` 分级告警；
2. 物理多机/真实断网断电实验具备条件后，把 PLAN-039 §四 的三组验证扩展到
   多机拓扑（roadmap #29 ③ 剩余项）。
