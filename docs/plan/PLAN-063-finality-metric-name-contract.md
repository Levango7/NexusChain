# 方案 PLAN-063：最终性指标的指标名契约门禁（PLAN-062 的 finality 侧 + 解析去重）

- **状态**：已交付（2026-10-31）
- **前置**：PLAN-062（block-persistence 侧指标名契约）、PLAN-058
  （`@Component` 缺失 ⇒ 三条告警永不会触发）
- **范围**：只加门禁；`FinalityMetrics` / `AbstainRiskMetrics` /
  `50-prometheus-rules.yaml` / `BlockPersistenceMetrics` **零改动**
- **不包含**：为任何指标新增告警（那是产品决策）；threshold 语义设计

---

## 一、缺口

PLAN-062 交付的是 `nexus_block_*` ↔ `nexuschain.block-persistence` 的双向契约。
finality 侧有**同一条缝的另一头**：

`FinalityMetricsRegistrationTest`（PLAN-058 固定下来的）断两件事——两个 binder
带 `@Component`，以及 5 个 `nexus_finality_*` 名字在 registry 里存在。
它**不读规则文件**。所以：

- 规则侧把 `nexus_finality_progress_percent` 写错/改旧 ⇒
  `FinalityBelowQuorum` 永远不会触发，而该测试全绿；
- 代码侧改名 ⇒ 同样，全绿。

这与 PLAN-058 同型（类写对了、注册了、名字两端各写一份、中间没人验）。

## 二、改动

| 文件 | 改动 |
|---|---|
| `org/nexus/metrics/PrometheusRulesFixture.java` | 新增：规则文件定位 + 组 expr 解析 + counter `_total` 归一化，供两个契约测试共用 |
| `org/nexus/metrics/FinalityRulesContractTest.java` | 新增：finality 侧双向契约（两个告警组取并集） |
| `org/nexus/metrics/BlockPersistenceRulesContractTest.java` | 改为复用 fixture，删掉自带的那一份解析（否则两份必然漂移） |

## 三、顺带钉下的现状

finality 侧共注册 **12** 个指标（`FinalityMetrics` 7 + `AbstainRiskMetrics` 5）。

规则里引用 **3** 个，分处两个组：

| 组 | 告警 | 引用的指标 |
|---|---|---|
| `nexuschain.finality` | `FinalityStalled` | `nexus_finality_latest_voted_epoch` |
| `nexuschain.finality` | `FinalityBelowQuorum` | `nexus_finality_progress_percent` |
| `nexuschain.block-adoption` | `BlockAdoptionAbstainRisk` | `nexus_finality_abstain_risk` |

其余 **9** 个进 `ALERTLESS_BY_DESIGN` 白名单：
`enabled`、`current_epoch`、`lag`、`finalized`、`finalized_checkpoints`、
`defer_total`、`defer_max_height`、`defer_max_count`、`abstain_current`。

**白名单的语义要说清**：本测试只强制"有意识地分类"，**不裁决**这些指标是否该有
告警。例如 `nexus_finality_lag` 的健康值本就不是 0（新区块产生后即 >0），
要给它设阈值属于产品决策；门禁能保证的只是"将来新加指标不许静默进这个集合"。

## 四、验证

| 项 | 结果 |
|---|---|
| 三个测试（fixture + block + finality）+ `FinalityMetricsRegistrationTest` | 全绿（`--rerun-tasks` 实跑，`gradle_exit=0`，22 个测试方法） |
| 绑定自检 | `assertEquals(12, …)`——两个 finality binder 必须注册 12 个；block 侧 `assertEquals(10, …)` 不变 |
| **反向对照（finality 侧）** | 在 `nexuschain.finality` 组把 `progress_percent` 改成 `progress_percentage` ⇒ **两个测试同时红**（规则→代码方向报"引用了未注册的名字"；代码→规则方向报 `nexus_finality_progress_percent` 变成"无告警消费"）；还原后恢复绿 |
| 归一化正确性 | `nexus_finality_defer_total` 是 FunctionCounter，规则侧若带 `_total` 也能对上（PLAN-062 第一版在这里虚报过一次，pattern 已固化进 fixture） |

## 五、为什么把解析抽成 fixture

两个契约测试都要读同一个 YAML 文件、取同一个组的 expr 名字。解析逻辑写两遍，
一边改了另一边必然漂移——而那正好会让"门禁"自己变成不一致的源头。
`PrometheusRulesFixture` 刻意只做一件事：**给一个组名，返回它 expr 里的
`nexus_*` 名字**。不做 YAML 通用解析、不校验规则语义、不取 `description`
里的名字（那里提指标名是给人看的，不是契约）。

## 六、遗留

- 覆盖 `nexus_finality_*`（2 组）与 `nexus_block_*`（1 组）；
  `nexuschain.services` / `resource` / `infra` / `jvm` 等组引用的非 `nexus_*`
  指标（JVM、kube-state 等）不在契约范围；
- 只固定量对比，不做值域契约；
- 与既有三层门禁的分工：`WiringAuditTest` 管"注册路径"、
  `FinalityMetricsRegistrationTest` 管"类装没装、名字在不在"、
  本测试管"规则引用的名字与注册出来的名字是否同一个"、各 `*MetricsTest`
  管"值"。四层各问一个问题，互不重复。
