# 方案 PLAN-070：最终性拒绝指标的抓取开销与稀疏序列语义

- **状态**：已交付（2026-11-03）
- **前置**：PLAN-066（拒绝计数与告警，#400）、PLAN-068（抓取端可见性，#413）、
  PLAN-069（历史脏行清理，#418）
- **范围**：`phase × reason` 的基数上界实证、抓取开销实测、稀疏序列语义确认、
  结构性门禁（条数/体积/懒注册）、文档与 CHANGELOG
- **不包含**：压力测试与容量规划、Prometheus 服务端存储/查询成本、真实集群、
  物理多机、改生产代码的注册方式

---

## 一、先纠错：这个切片的原提法前提不成立

PLAN-068 的交付文档把遗留项写成：

> 吞吐/基数基准（高基数下 `phase × reason` 序列的抓取开销）

认领前查代码发现**"高基数"这个前提是错的**：

| 事实 | 位置 |
|---|---|
| `Reason` 是 **9 项闭合枚举** | `FinalityRejectionCounter.Reason` |
| `Phase` 是 **3 项闭合枚举**（ADMISSION/CONVERGENCE/RESTORE） | `FinalityRejectionCounter.Phase` |
| `FinalizeReason` 是 **1 项** | `FinalityRejectionCounter.FinalizeReason` |
| map 键是 `long`（两个 `int` ordinal 打包） | `FinalityRejectionMetrics.key()` |
| map 键是 `String`（枚举 `name()`） | `finalizeCounters` |

两个 map 都是 `ConcurrentHashMap`，**键集合由编译器保证有界**：

```
序列数上限 = 9 × 3 + 1 = 28
```

**与流量、验证人数、攻击者地址数完全无关。** PLAN-066 当初刻意不给 counter 加
`validator` 地址标签，正是为了让基数保持有界（其文档已记录这个取舍）。

⇒ **不存在"高基数 OOM"或"基数随攻击放大"的风险。**
本切片因此不做压力测试，改测真正没验证过的两件事。

> 这条纠错本身有价值：若不纠正，后续可能有人照着"高基数"去写一个
> 测不出问题的压测，或者更糟——为了"减少基数"而把有用的 reason 标签合并掉。

## 二、实测（Micrometer 1.16.7 + prometheus-metrics 1.4.3，JDK 17）

仪器与仓库实际依赖版本一致；脚本在仓库外
（`C:\Users\winge\AppData\Local\Temp\opencode\p070\ScrapeProbe.java`），
不引入任何构建依赖。

### 2.1 抓取开销随序列数

| 序列数 | scrape 平均耗时 | 抓取文本 |
|---|---|---|
| 1 | 0.0469 ms | 172 字节 |
| 9 | 0.0695 ms | 764 字节 |
| 27 | **0.0749 ms** | **2096 字节** |

- 27 序列全注册后单次 scrape 约 **0.075 ms**、文本约 **2 KB**；
- 1 → 27 序列耗时只涨约 **1.6 倍**（固定开销占主导），**不是 27 倍**；
- 按 ServiceMonitor 的 30s 间隔，27 序列下每日抓取量约 `2 KB × 2880 ≈ 5.8 MB`。

### 2.2 抓取与计数并发

27 序列注册后：一端单线程 200k 次 `increment`，一端连续 scrape 2000 次：

| 指标 | 值 |
|---|---|
| 平均 | **0.0433 ms** |
| 最差 | **1.5466 ms** |
| 并发结束后序列行数 | **27**（无丢失、无重复） |

最差 1.5 ms 属 JIT/GC 噪声量级，不是锁竞争——`ConcurrentHashMap` 读路径无锁。

### 2.3 零值 / 稀疏序列语义（**纠正了预查阶段的一个错误假设**）

预查时我写下："Micrometer 在 counter 从未 increment 时**不注册**该序列"。
**实测是错的**——`Counter.builder(...).register(registry)` 一旦执行，即使 count 为 0，
抓取文本里**就有这一行**：

```
nexus_finality_vote_rejected_total{phase="admission",reason="checkpoint_hash_invalid"} 0.0
```

但**生产代码用的是懒注册**：

```java
Counter counter = voteCounters.computeIfAbsent(key(reason, phase), k ->
        Counter.builder(METER_VOTE_REJECTED)...register(registry));
```

`computeIfAbsent` 的 mapping function **只在首次拒绝时**执行 ⇒ 生产上
"从未发生过的 reason"**确实不会**出现在抓取文本里。这个差异很重要：

| 写法 | 未发生的 reason 是否暴露 | 评价 |
|---|---|---|
| 预注册全量（启动时 register 28 条） | 是（值 0.0） | 不致命，但恒常占预算 |
| **懒注册（生产现状）** | **否** | **符合"只在需要时占预算"的设计** |

### 2.4 稀疏序列是否破坏告警

不破坏。两条依据：

1. `increase(<不存在的序列>[10m])` 返回**空向量**（不是 0），
   `expr: increase(...) > 0` 的比较结果恒为空 ⇒ **不触发、不误报**；
2. 首次拒绝发生后序列**立刻**出现且值为 1 ⇒ 下一次规则评估即可能触发
   ⇒ **没有"第一次攻击看不见"的延迟窗口**。

⇒ 稀疏序列**不是缺陷**，但必须写进文档，否则后人会把"序列没出现"
误判成"指标坏了"，进而错误地把注册方式改成预注册全量。

## 三、交付的门禁

新增 `FinalityRejectionScrapeBudgetTest`（4 例），全部断言**结构性事实**，
不断言绝对耗时（绝对耗时会 flaky）。

| 用例 | 钉住什么 | 会怎么红 |
|---|---|---|
| `fullVocabularyStaysWithinScrapeBudget` | 闭合词表全注册后，票级序列条数 **= 9×3**、检查点级 **= 1**；**每个序列的标签键必须严格等于 `{reason, phase}` / `{reason}`**；文本体积 < `28 × 200` 字节 | 有人给 counter 加高基数标签（如 `validator` 地址）⇒ 标签键断言立刻红 |
| `unReachedReasonsAreAbsentBecauseRegistrationIsLazy` | 一次拒绝都没有时 0 条序列；只触发一种时恰好 1 条；未触发的 reason **不得**出现 | 有人把懒注册改成预注册全量 ⇒ 28 条恒常暴露 |
| `missingSeriesDoesNotBreakAlertSemantics` | 首次拒绝后序列立刻可见且值为 1（无延迟窗口） | 注册路径被改坏 / 标签值大小写漂移 |
| `cardinalityBudgetMatchesClosedVocabulary` | 预算常量与枚举组合数一致；PLAN-070 文档必须写明序列上限 | 有人加枚举值却不同步预算/文档 |

**为什么用"每序列 200 字节"这个上界**：实测 27 序列约 2096 字节（约 78 字节/序列，
含 HELP/TYPE 摊销）。取 200 给描述文案变动留余量，但远低于"加了地址标签"后的量级
（那会是几百条序列、数十 KB）——所以它抓得住真问题，又不会因为改一段
`description` 就误红。

> **反向对照抓到本门禁自己的一个真实缺陷（关键，值得记）**：
> 最初的预算断言只数"序列行数"（`text.lines().filter(startsWith(name + "{"))`）。
> 给 counter 加 `validator` 标签后，每个 `(reason, phase)` 组合**仍然只有一行**
> ⇒ 条数仍是 7、体积也只涨几十字节 ⇒ **预算断言完全不红**。
> 当时是另一条用例（标签字面量拼接不一致）意外变红的，属"红得不精确"。
> 修法：直接解析并断言**标签键集合**，多余维度立刻报错。这条修正后，
> 变异 A 的报错明确指向 `实际 [phase, reason, validator]`。

## 四、验证

### 4.1 定向

| 项目 | 结果 |
|---|---|
| `FinalityRejectionScrapeBudgetTest` | 4 用例 / 0 失败 |
| `org.nexus.metrics.*` | 全绿 |

### 4.2 反向对照（2 组）

| 组 | 变异 | 实测结果 |
|---|---|---|
| A | 给 `FinalityRejectionMetrics` 的 counter 加 `validator` 地址标签 | 标签键断言红（`实际 [phase, reason, validator]`）+ 字面量用例红 |
| B | 把懒注册改成预注册全量（构造时注册 27 条） | 稀疏序列两例红（预期 0 条，实际 **27** 条） |

还原后 `FinalityRejectionMetrics.java` 的 SHA-256 与变异前逐字一致：

```
metrics  3caf6870252b8f03307b024178ea204a14f770f34bbb1192743e3504be773ea1
```

还原后 `--rerun-tasks` 重跑 4/4 绿。

> 变异 A 第一次运行时**没有命中预算断言**——见 §三 的说明。那次"红"来自字面量
> 拼接不一致，属偶然。补上标签键断言后重跑，才精确命中。这条留在这里，
> 因为它是"反向对照要检查红了哪一条，而不只是有没有红"的直接实例。

## 五、边界（勿引用为更强结论）

1. **不是压力测试/容量规划**：只测单进程抓取端在"序列数上限"下的开销量级，
   **不测** Prometheus 服务端的存储、索引、查询成本（那是监控平台自身的事，
   需真实 Prometheus + 真实数据量）；
2. **同内核单机**：所有数据来自一台 Windows 机器上的 JVM，**不是物理多机**，
   也不代表生产硬件（CI runner / 云主机）的绝对数值；
3. **未起真实 Prometheus/Alertmanager**：稀疏序列的告警语义由 PromQL 语义
   与 Micrometer 行为**推导 + 单测**确认，未在真实规则评估循环里观测；
4. **不改生产注册方式**：懒注册是刻意设计，本切片只把它钉住、不改变它；
5. **绝对耗时只作文档记录**：门禁不断言耗时，避免机器差异造成 flaky；
6. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 六、后续

- 若将来确有需要观测"按验证人归因"，必须**另开一个低基数维度**
  （例如按状态分桶），不得直接把地址做成标签；
- Prometheus 服务端成本属监控平台侧，需要真实集群与真实数据量，不在本切片；
- roadmap #27 剩余：epoch 窗口/规范检查点语义（阻塞于链接口）、
  真实集群告警演练、物理多机矩阵、通用 retention。
