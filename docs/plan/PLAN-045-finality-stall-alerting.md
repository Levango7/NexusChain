# 方案 PLAN-045：最终性停滞指标与告警（接 PLAN-040 告警体系）

- **状态**：开发中（2026-10-27）
- **前置**：PLAN-044（`GET /consensus/finality` 只读快照 + `finalityLag`）、
  PLAN-040（PrometheusRule + runbook 体系，15 条）、PLAN-043（快照口径实测）
- **范围**：`FinalityMetrics`（Micrometer）+ `nexuschain.finality` 告警组 2 条 +
  runbook；`docs/runbooks/alerts.md` 15 → 17 条
- **不包含**：改最终性共识/投票逻辑、Alertmanager 路由（部署侧）、最终性指标持久化

---

## 一、问题

PLAN-044 把最终性视图做成了 `curl` 可读的快照，但**仍然是人工轮询**：
- "最终性跟不上出块"没有告警——链在出块（`BlockchainCoreStalled` 不响）但票没收齐时，
  现有 15 条告警全哑；
- PLAN-044 的 §五明确留了"未做指标化/告警（可另立，引用本端点同一数据源）"。

## 二、方案

### 2.1 `FinalityMetrics`（新，`org.nexus.metrics`，`MeterBinder`）

数据源 = PLAN-044 同一份只读内存快照：`ChainHeadProvider.currentHeader()`
（内存链头，**不查库**）+ `FinalityCoordinator.finalitySnapshot(height)`。
多个 Gauge 回调共享 1s TTL 快照缓存（单次抓取只算一次）。

| 指标 | 语义 |
|---|---|
| `nexus_finality_enabled` | 最终性共识是否装配（0/1；非 pos 为 0） |
| `nexus_finality_current_epoch` | 当前高度的 epoch（未装配 NaN） |
| `nexus_finality_latest_voted_epoch` | 最近一个有本地视图记录的 epoch |
| `nexus_finality_lag` | **currentEpoch − latestVotedEpoch** |
| `nexus_finality_finalized` | 最新已投票 epoch 是否达门限（1/0） |
| `nexus_finality_progress_percent` | 最新已投票 epoch 的聚合进度 |
| `nexus_finality_finalized_checkpoints` | 内存已最终化检查点总数 |

未装配/无记录一律 **NaN**（不冒充 0，Prometheus 比较中 NaN 使表达式不成立 ⇒
非 pos 模式不会误报）。

### 2.2 告警（`nexuschain.finality`，2 条）

| 告警 | 级别 | 表达式 | for |
|---|---|---|---|
| `FinalityStalled` | critical | `nexus_finality_lag >= 2` | 5m |
| `FinalityBelowQuorum` | warning | `nexus_finality_progress_percent < 100` | 10m |

**为什么不用"单次采样 percent < 100%"**：`progress_percent` 是**提交时快照**——
2 验证人拓扑下刚投完自己通常 50%、收到对端票后翻 100%（PLAN-043/044 实测的
顺序浮动），单点采样必误报。两条规则因此都用**持续量**（lag 与 10 分钟窗口）。

## 三、测试

`FinalityMetricsTest` 5 例：未装配 ⇒ enabled=0 + 其余 NaN；正常快照全字段映射；
无记录时 percent/finalized 为 NaN 而 epoch/lag 仍是数（可区分"没数据"与"有数据
没进度"）；lag 落后 3 + 50% 的告警输入可观测；TTL 缓存让多次回调只算一次快照。

## 四、边界（勿夸大）

- 指标是进程内视图：重启清零、冷恢复需补票重建（PLAN-042/025 已实证）；
  `finalityLag` 在冷恢复窗口内可能短暂 >0，`for: 5m` 即为此设计；
- 非 pos 模式（`nexus.consensus.mode != pos`）不装配协调器 ⇒ 指标 NaN ⇒ 两条
  告警天然不触发（不是"被静默"，是无数据）；
-  Alertmanager 路由/静默策略不在仓库内（与既有 15 条一致）；
- 未测：真实多验证人拓扑下的 lag 基线（本机 2 验证人实测全程 0）。
