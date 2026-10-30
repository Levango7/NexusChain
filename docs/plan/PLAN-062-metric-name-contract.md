# 方案 PLAN-062：指标名契约门禁——告警规则引用的名字必须与代码注册的名字一致

- **状态**：已交付（2026-10-31）；新增
  `BlockPersistenceRulesContractTest` 双方向门禁 + `nexus-core` 的
  `inputs.file(...)` 登记
- **前置**：PLAN-040（block-persistence 指标）、PLAN-058（`@Component` 缺失 ⇒
  指标从未注册）、PLAN-059（门禁覆盖面只覆盖一个模块）
- **范围**：只加门禁，不改任何被守卫的生产逻辑；`BlockPersistenceMetrics`
  与 `50-prometheus-rules.yaml` 均**零改动**（两个反向对照的探针已移除并还原）

---

## 一、缺口

PLAN-058 修的是"指标从未注册 ⇒ 三条告警永不会触发"。修完之后，
`nexus_block_*` 这条链路剩下两个测试都没问的问题：

| 已有门禁 | 问了什么 | **没问**什么 |
|---|---|---|
| `BlockPersistenceMetricsTest` | 指标**值**对不对（单调/NaN/readable 翻转） | 名字有没有人和它对接 |
| `WiringAuditTest` | MeterBinder 有没有 `@Component` | 注册出来的名字与告警规则引用的名字是否同一个 |

于是留缝：

- **规则 → 代码**：`deploy/k8s/50-prometheus-rules.yaml` 的 expr 引用
  `nexus_block_writer_write_failures_total`。谁在规则里写错名字、或谁在代码里
  改了名字，这条告警就**永远不会触发**——而现有测试全绿（它们不读规则文件）；
- **代码 → 规则**：新加一个指标没有任何告警消费它 = 白暴露，也没有测试会问
  "这个指标有人在看吗"。

这正是 PLAN-058 的同型：类写对了、也注册进容器了，但**两端各写了一个名字**，
中间那个"是不是同一个名字"没人验。同族第 6 例。

## 二、现状盘点（门禁顺带钉下的数字）

`BlockPersistenceMetrics.bindTo()` 实测注册 **10** 个指标；
`nexuschain.block-persistence` 组的 expr 消费其中 **5** 个：

| 消费（expr 引用） | 类型 |
|---|---|
| `nexus_block_writer_write_failures_total` | counter |
| `nexus_block_writer_state_update_timeouts_total` | counter |
| `nexus_block_outbox_memory_pending` | gauge |
| `nexus_block_outbox_database_pending` | gauge |
| `nexus_block_outbox_readable` | gauge |

其余 **5** 个由 `ALERTLESS_BY_DESIGN` 白名单登记用途（诊断用 Gauge：
`last_failure_height/_age_seconds`、`outbox_enabled`、
`oldest_pending_age_seconds`、`max_attempts`——服务于人工排障，配合
`GET /consensus/block-writer` / `block-outbox` 端点）。

白名单的意义不是"豁免这 5 个"，而是：**将来新加指标必须被有意识地分类**，
而不是静默变成又一个没人看的指标。

## 三、改动

| 文件 | 改动 |
|---|---|
| `nexus-core/.../metrics/BlockPersistenceRulesContractTest.java` | 新增。① 绑定一次真 `SimpleMeterRegistry` 拿实际注册名；② 解析规则文件里 block-persistence 组的 `expr`；③ 双向断言 |
| `nexus-core/nexus-core/build.gradle` | `test` 任务登记 `inputs.file(<repo root>/deploy/k8s/50-prometheus-rules.yaml)` |

第二处不是装饰：不加这句，改动规则文件后本地 Gradle 判定 test up-to-date 而
跳过该测试（本轮实测踩到，反向对照时 `BUILD SUCCESSFUL in 2s` 但一个测试都没跑）。
"门禁写对了、却不知道输入变了"，与 PLAN-059 同型。

## 四、验证

| 项 | 结果 |
|---|---|
| 基线（规则/代码均原样） | 2 个测试全绿，`BUILD SUCCESSFUL` |
| 绑定自检 | `assertEquals(10, registeredNames.size())`——绑定失灵会先红，不会假绿 |
| **反向对照 A：规则侧笔误** | `nexus_block_writer_write_failures_total` → `..._TYPO_total` ⇒ **两个测试同时红**：规则→代码方向报"引用了未注册的名字"，代码→规则方向报"write_failures 变成无告警消费" |
| **反向对照 B：代码侧改名** | `nexus_block_outbox_max_attempts` → `..._max_attempt` ⇒ 代码→规则方向红（该指标既无告警、也不在白名单），报出改名后的名字 |
| 探针已清除 | 规则与生产类均已还原；`git status` 只余门禁自身的改动 |

反向对照 A/B 覆盖了两个方向：不是只有一道门禁，而是"接错任一端都会红"。

## 五、本轮一并尝试但被环境阻塞的事（如实记录）

原计划沿用 PLAN-058 的做法**真起应用**再 curl 一次
`/actuator/prometheus` 实证 10 个指标在抓取端上（PLAN-058 只真验过
finality 的 12 个）。

本轮未能完成，原因是**本机 PostgreSQL 实例损坏**，与仓库无关：

- `postmaster` 能启动并完成恢复，但**首个客户端连接**必然杀死它：
  `server process (PID xxxx) was terminated by exception 0xC0000142`
  ⇒ `shutting down due to startup process failure`，确定性复现 3 次；
- 新起的 java 进程（节点本身）能正常启动，只有 PG 的 forked backend 崩
  ⇒ 不是通用"建不出进程"，是 PG 的 fork 路径；
- 本机非管理员，无法把 PG 注册成 Windows 服务来换 session 上下文；
  无 WER 崩溃报告可取到故障模块名；DLL 齐全、无 MOTW 下载标记；
  当时机器上 944 个进程、约 914 个孤儿（大量泄漏的 conhost/cmd/node）。

**因此不得宣称"真起应用验证过 block-persistence 指标"**——本轮没有这个证据。
留下的替代更强：本门禁在 CI 里常驻，双向绑定 + 双向反向对照都成立。
`F:\Agent\opencode\workspace\verify-block-metrics.ps1` 已备好（PLAN-058 同款
模板），PG 恢复后可直接重跑，把 10 个指标逐个落到抓取端上。

## 六、遗留

- 本门禁只覆盖 `nexus_block_*` / `nexuschain.block-persistence` 一组；
  `nexus_finality_*`（PLAN-052 / PLAN-058）侧已有
  `FinalityMetricsRegistrationTest`，但它断言的是"两个 binder 带 `@Component`" +
  "5 个 `nexus_finality_*` 名字在 registry 里存在"——**它不读规则文件**，
  所以规则侧改错名字仍然没人拦。模式已跑通，扩它是重复劳动而非新设计，
  属可独立交付的后续切片；
- 只固定量对比，不做**值域**契约（例如 `readable == 0` 的语义）；
- `metric_contract` 与 `WiringAuditTest` 分工：后者管"注册路径"，
  本测试管"名字对接"；再往前 `BlockPersistenceMetricsTest` 管"值"。
  三层各问一个问题，都不重复。
