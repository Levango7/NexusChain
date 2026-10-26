# 方案 PLAN-048：最终性告警重标定——粘滞水位 + NaN 盲区修复（承接 PLAN-047）

- **状态**：已交付（2026-10-27）
- **前置**：PLAN-047（三验证人实测：lag 基线恒 2；死锁时 lag 变 NaN ⇒ 告警熄火）、
  PLAN-045（原告警）、PLAN-044（快照端点）
- **范围**：`finalitySnapshot` 的 lag/水位语义改**粘滞** + 两条告警规则改用
  `changes(水位)`/`progress_percent` 持续量 + runbook 重写对应章节
- **不包含**：注册表僵死项回收（PLAN-047 发现的产品风险，另立治理切片）、
  多验证人 lag 基线的正式 SLA 定档

---

## 一、问题（PLAN-047 实测暴露的两个缺陷）

1. **阈值拍脑袋**：PLAN-045 的 `FinalityStalled` 用 `lag >= 2`（critical）。
   PLAN-047 实测：**健康 3 验证人拓扑的 lag 基线恒为 2**（轮转 + 视图切换使投票
   稳定落后链头 2 个 epoch，20+ 连续采样无一例外）⇒ 该规则在健康集群里
   **持续误报 critical**；
2. **NaN 盲区（更严重）**：lag 定义为"8-epoch 回溯窗内最近有票 epoch − …"，
   窗口内无记录时返回 `null/NaN` ⇒ **最终性真死锁时告警熄火**——最该响的时候
   哑了。PLAN-047 P3 实测复现：A/D 最终性冻结 8 分钟+，`latest_voted_epoch`
   跌出窗口 ⇒ 指标 NaN ⇒ 无告警。

## 二、方案

### 2.1 `finalitySnapshot`：粘滞水位（`FinalityCoordinator`）

- 新增私有 volatile `finalityWatermarkEpoch`（初值 -1）与
  `finalityWatermarkProgress`；
- 每次快照：在 8-epoch 回溯窗内找到记录 ⇒ 水位**只前进不后退**
  （`foundEpoch >= watermark` 才更新，防乱序/重放拽回）；窗内无记录 ⇒
  **水位保持**；
- 输出语义：`latestVotedEpoch` = 水位（进程内从未有记录才 `null`）、
  `finalityLag` = `currentEpoch − 水位`（死锁时**无界增长**）、
  `progress` = 水位对应的最后已知进度（**冻结可观测**，如 25%）。
- 线程安全：仅被 Prometheus 抓取/状态端点调用，volatile + 单调写入足够
  （最坏多算一次，不回退）。

### 2.2 告警规则（`deploy/k8s/50-prometheus-rules.yaml`，总数仍 17）

| 告警 | 表达式 | for | 级别 |
|---|---|---|---|
| `FinalityStalled` | `changes(nexus_finality_latest_voted_epoch[15m]) == 0` | 10m | critical |
| `FinalityBelowQuorum` | `nexus_finality_progress_percent < 100` | 10m | warning |

- 用**水位是否在动**替代 lag 阈值：健康时水位约每 6s 推进一次（2 块/epoch×3s），
  拓扑无关；`changes == 0` 对"死锁"是充分特征，不依赖任何拓扑基线；
- `FinalityBelowQuorum` 保留，但 progress 现在来自**粘滞水位** ⇒ 死锁时冻结在
  最后已知值（如 25%）⇒ 持续 10min 必触发（原来会 NaN 熄火）；
- 非 pos 模式（未装配）：指标无序列 ⇒ 表达式不成立，不误报（原
  `nexus_finality_enabled` gauge 的兜底作用由"无序列"承担）。

### 2.3 runbook

`docs/runbooks/alerts.md` 最终性章节重写：触发条件、两个重标定要点
（基线 2 / NaN 盲区）、排查处置（门限不达要看验证人集合——PLAN-047 的
"第 4 验证人"场景）、"3 验证人掉 1 个时 67% + finalized=true 是正常"。

## 三、测试（16 例）

- `FinalitySnapshotTest` 8（+2 新增）：**水位粘滞**（窗口无记录时不后退、
  lag 无界增长、progress 冻结）、**水位只前进不后退**（乱序/重放不拽回）；
- `FinalityMetricsTest` 6（+1 新增）：`stalledFinalityKeepsNumbersSoAlertsFire`
  （死锁映射后 lag/percent/finalized 都是有 ⇒ 两条告警可触发）；
- `ConsensusControllerFinalityTest` 2 不回归。

## 四、验证

- 三条测试类全绿（16 例）；规则 YAML 静态校验：8 groups / 17 alerts、
  无重名、全带 `for`；
- 未做测试床复测（重标定依据是 PLAN-047 的实测数据 + 语义论证；
  下一条多机/3 验证人实验顺带复核 `changes(...)==0` 不误报）。

## 五、边界（勿夸大）

- `changes(水位[15m]) == 0` 依赖 **30s 抓取间隔**：15min 窗内至少应有 ~50 个
  样本点；若部署方抓取间隔 >1min，需按比例放大窗口；
- 水位是**进程内**语义：重启后重新累计，不跨重启（与指标整体口径一致）；
- 僵死验证人回收（PLAN-047 的产品风险）**未修**：本切片只让它"可被发现"
  （门限不达progress 冻结 ⇒ 告警 + runbook 指引），治理动作另立；
- lag 仍作为仪表盘指标暴露（`nexus_finality_lag`），只是不再用于告警阈值。
