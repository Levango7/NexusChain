# 方案 PLAN-052：`abstain-risk` 指标化（把 PLAN-051 的拒绝路径接进 Prometheus）

- **状态**：开发中（2026-10-27）
- **前置**：PLAN-051（`PendingBlocksManager` 的 `unknownProposerDeferCount` +
  ERROR 升级，#291 已合并）、PLAN-045/048（告警体系）、PLAN-050（演练验证）
- **范围**：把"按高度累计的 unknown-proposer defer 计数"暴露为 Micrometer gauge，
  并加一条 warning 告警（**拒绝路径维度的第三信号**）
- **不包含**：改 defer 语义、改阈值策略、定位弃权根因（PLAN-051 已定性为
  偶发时序窗口，不做猜测性修复）

---

## 一、问题（三路信号的最后一环）

PLAN-050/051 建成三路弃权/死锁信号：

| 信号 | 维度 | 局限 |
|---|---|---|
| `FinalityStalled`（水位 `changes==0`） | **结果**（最终性停滞） | 滞后：要等 15min 窗口才判"没动" |
| `FinalityBelowQuorum`（percent < 100） | **结果**（票不齐） | 同上，且死锁时 percent 冻结也能抓 |
| `abstain-risk` ERROR（`PendingBlocksManager`） | **原因**（拒绝路径） | **只有日志**——Prometheus 抓不到，无法告警/聚合 |

第三路是**最早出现症状**的（拒绝第一次发生就能看到），但目前只能 grep 日志。
把它指标化 ⇒ 告警可以从"结果滞后 15min"提前到"原因出现即警告"。

## 二、方案

### 2.1 指标（`org.nexus.metrics.BlockPersistenceMetrics`？不——新建独立类）

新增 `org.nexus.metrics.AbstainRiskMetrics`（`MeterBinder`）：

- `nexus_finality_defer_total`（FunctionCounter，单调）：全局 defer 累计次数；
- `nexus_finality_defer_max_height`（Gauge）：**当前 defer 计数最高的高度**
  （= 最可能卡住的采纳点）；
- `nexus_finality_defer_max_count`（Gauge）：该高度的累计 defer 次数；
- `nexus_finality_abstain_risk`（Gauge 0/1）：是否**已有任一高度**达到
  `ABSTAIN_DEFER_THRESHOLD(5)`——**告警直接用它**（1 = 风险中）。

数据源是 `PendingBlocksManager` 的 `unknownProposerDeferCount`（PLAN-051 已建），
**只读**，抓取零 DB 访问。未装配/空表 ⇒ `abstain_risk=0`、其余 0（不用 NaN——
"没有拒绝"是明确事实，与"没数据"不同）。

### 2.2 告警（第 18 条，warning）

```yaml
- alert: BlockAdoptionAbstainRisk
  expr: nexus_finality_abstain_risk == 1
  for: 5m
   severity: warning   # 单节点症状，不等于全网最终性死锁（那由 FinalityStalled 管）
```

**为什么是 warning 而不是 critical**：单节点 defer 卡住只影响**该节点**的
投票（其它节点仍在最终化）；全网死锁由 `FinalityStalled`（critical）覆盖。
两条互补：本告警抓"原因 + 单节点"，Stalled 抓"结果 + 全网"。

## 三、测试（`AbstainRiskMetricsTest`）

`SimpleMeterRegistry` + 真 `PendingBlocksManager`（或注入 map）：
1. 无 defer ⇒ `abstain_risk=0`、max 系列 0；
2. 3 次 defer（< 5）⇒ `abstain_risk=0`、`max_count=3`；
3. **5 次 defer ⇒ `abstain_risk=1`**（阈值语义与 PLAN-051 一致）；
4. 多高度独立：最高计数的高度正确（不是 sum）。

## 四、验证

- 单测全绿；模块全量回归；
- **测试床实抓**（复跑 PLAN-051 脚本，同内核三节点）：起节点后
  `curl /actuator/prometheus | grep nexus_finality_` 验证四个指标名与值
  （重点：height=1 冷启动窗口是否把 `abstain_risk` 推到 1——若会，则 5m for
  天然覆盖该瞬态；若不会，说明阈值在生产更稳）。

## 五、边界（勿夸大）

- 指标是**进程内、按高度累计、不清零**（与 PLAN-051 语义一致）：一个历史
  defer 过 5 次的高度会让 `abstain_risk=1` 一直亮——**语义是"本进程见过
  弃权风险"，不是"当前正在弃权"**。告警的 `for: 5m` 不解决这一点
  （gauge 不回退）；如需"当前"语义，下一切片改为"最近 N 分钟内 defer 增量"
  （需要时间序列或衰减计数）。**本切片如实登记这个局限**，不改语义；
- 未接 Alertmanager 路由（部署侧）；
- 阈值 5 与 PLAN-051 同源（经验值，冷启动实测到 7 次收敛）。
