# 方案 PLAN-066：最终性投票拒绝计数指标与告警（PLAN-065 的可观测缺口）

- **状态**：已交付（2026-11-02）
- **前置**：PLAN-065（准入验签前移，#389）、PLAN-016/018/021（故障注入 / 签名落库 /
  跨节点收敛）、PLAN-058/062/063（指标注册与名字契约门禁）、PLAN-064（promtool
  规则演练门禁）
- **范围**：拒绝计数端口 + Micrometer 适配器 + 四条拒绝路径埋点 + 两条告警规则与
  promtool 演练用例 + 三层指标门禁同步 + 回归测试与文档
- **不包含**：按验证人地址的标签分桶、真实网络分区、物理多机、真实 L1、桥资金链路、
  性能/吞吐基准、Grafana 看板与通知链路

---

## 一、问题（为什么必须做）

PLAN-065 把单票 Ed25519 验签前移到计票/落库之前，伪造票不再能占位卡死最终化。
但**拒绝发生时只有一行 WARN 日志**：

| 拒绝点 | 日志 | 修复前问题 |
|---|---|---|
| 公钥绑定失败 | `Vote pubkey binding FAILED` | 需要登容器逐行 grep |
| 单票验签失败 | `Vote signature verification FAILED` | 同上 |
| 启动恢复丢坏行 | `Startup restore: dropping vote with invalid signature` | 同上 |
| 聚合验签失败 | `Finality aggregate verification failed` | 同上 |

后果是三个**无法回答**的问题：

1. **是否发生过攻击？** 日志是本地、未聚合、未留存的；节点重启后历史行打不出来。
2. **是否正在被攻击？** 只能人工登机器，没有告警；
   PLAN-047 实测过的"出块正常但最终性死掉"正是最需要自动发现的形态。
3. **是配置错配还是伪造？** 换钥后未重新注册会产生大量 `signature_invalid`，
   与真实攻击的量级差异只有日志能看，且看的时候要人肉分组。

PLAN-065 §六 已把这一项列为遗留第一顺位："验签失败计数指标（按验证人/原因分桶）
与告警，避免只靠日志"。本切片就是它。

## 二、设计与取舍

### 2.1 端口与被使用方定义

拒绝判定发生在 `org.nexus.consensus.finality`，而指标绑定权在
`org.nexus.metrics`——后者已经依赖前者（`FinalityMetrics` 消费
`FinalityCoordinator`）。若让 `FinalityGadget` 直接引用 metrics 包的具体类，
会在两包间形成循环依赖。

⇒ 由被使用方定义端口 `FinalityRejectionCounter`（consensus 包），
`org.nexus.metrics.FinalityRejectionMetrics` 做适配器实现（@Component + Micrometer
Counter），依赖方向保持单向。

### 2.2 标签是闭合词表，不是自由字符串

```text
nexus_finality_vote_rejected_total{reason, phase}   ← 票级
reason : validator_unknown / validator_inactive / malformed_vote /
         pubkey_mismatch / signature_invalid / zero_total_weight
phase  : admission / convergence / restore
nexus_finality_finalize_rejected_total{reason}      ← 检查点级
reason : aggregate_verification_failed
```

- **reason / phase 都是 enum**：标签值即 `name().toLowerCase(ROOT)`。自由字符串
  意味着任一处日志拼接都能造出新标签 ⇒ 标签基数不可控；闭合词表由编译器保证。
- **`phase` 必须分开**：`signature_invalid@convergence` 的含义是"库里躺着坏行"
  （历史污染），`signature_invalid@admission` 的含义是"正在被网络攻击"。
  合成一个标签会让告警失去定位能力。

### 2.3 刻意不做：validator 地址标签

PLAN-065 §六 的原始设想是"按验证人/原因分桶"。**本切片没有做验证人维度**：

> 攻击者用随机验证人地址洪泛，即可让 `validator="0x…"` 标签基数无界增长——
> **被观测对象会被观测本身打垮**（Prometheus 内存与查询代价，且这条攻击路径
> 恰好就是本切片要观测的那种伪造票）。

⇒ 按人定位仍走日志（WARN 行已含地址与 epoch，PLAN-065 起如此）。这是有意的
取舍，不是遗漏；若将来要做，必须配基数上限（如 Top-N + 兜底标签），不在本切片
冒充完成。

### 2.4 构造期事件的重放

`restoreFromStore()` 跑在**构造器**里，而端口是 setter 注入（避免改 3 参构造器
打断全部测试替身）。若直接丢弃，"重启后发现库被污染"这个最关键的信号在指标上
恒为 0——观测自己变成静默失败。

⇒ `FinalityGadget` 缓冲构造期的拒绝事件（`MAX_REPLAY_EVENTS=4096` 防泄漏护栏），
`setRejectionCounter()` 注入完成后补记并清空；已重放过的事件不再重复记（计数器
不能重复计数）。重放语义由用例直接钉住。

### 2.5 只有"伪造票"两类原因进告警

`validator_unknown` / `validator_inactive` / `malformed_vote` / `zero_total_weight`
多为启动期或治理期的正常现象，逐条告警只会产生噪声（与 PLAN-064 修掉的死告警
同源的另一个极端：活告警但永远是噪声）。这一类 reason 的 series 供按标签排查，
不进告警；`FinalityRulesContractTest` 的 `ALERTLESS_BY_DESIGN` 没有为它们开新的
白名单口子——它们**不新增指标名**，只是既有 counter 的标签值。

## 三、代码改动

| 文件 | 改动 |
|---|---|
| `consensus/finality/FinalityRejectionCounter.java` | 新增：端口 + `Reason`/`Phase`/`FinalizeReason` 三个闭合词表 enum |
| `metrics/FinalityRejectionMetrics.java` | 新增：@Component 适配器；`MeterRegistry` 可选注入（缺则 no-op + 一次 WARN），registry 缺失不会阻断 Spring 上下文 |
| `consensus/finality/FinalityGadget.java` | `validateVoteBinding(vote, phase)` 增加 phase 入参；四条拒绝路径埋点；`setRejectionCounter()` + 构造期缓冲；`tryFinalize()` 的聚合验签失败埋点 |
| `deploy/k8s/50-prometheus-rules.yaml` | +2 条告警：`FinalityVoteRejected`（warning）与 `FinalityFinalizeRejected`（critical）；头部告警数注释 18 → 20 |
| `deploy/k8s/tests/prometheus-rules.test.yaml` | +6 个演练用例（触发 / 不触发 / 良性 reason 不触发 / 合法 phase 变体） |

**没有改动任何共识判定语义**：四条拒绝路径的 fail-closed 行为与日志一字未改，
新增的只有"同时发一次计数"。

## 四、验证

### 4.1 Java 侧

| 测试 | 覆盖点 | 结果 |
|---|---|---|
| `FinalityRejectionCounterWiringTest`（新，7 例） | 四条拒绝路径各自计数、合法票不计数、reason/phase 键名逐字对应、构造期重放、检查点级拒绝独立 | 7/7 绿 |
| `FinalityRejectionMetricsTest`（新，5 例） | 序列独立、counter 单调累加、两指标不混计、null 与缺 registry 安全、闭合词表组合数与标签字面量 | 5/5 绿 |
| `FinalityMetricsRegistrationTest`（改） | 新增 binder 的 `@Component` 断言 + 两个新名字"计数后必须在 registry 里" | 绿 |
| `FinalityRulesContractTest`（改） | 绑定新 binder、`EXPECTED_METER_COUNT` 12 → 14、新名字必须**被告警消费**（未放 `ALERTLESS_BY_DESIGN`） | 绿 |
| `metrics.*` + `consensus.finality.*` 全包 `--rerun-tasks` | 无回归 | **169 用例 / 0 失败 / 0 跳过** |

### 4.2 promtool 规则演练（PLAN-064 门禁同步）

环境：WSL2 Ubuntu-24.04 内的 promtool **v3.15.1**（与 CI 固定的同一版本），
规则文件由 `scripts/extract-prometheus-rules.py` 提取；应用与 DB 均不参与，
**只是 CLI 工具，不构成多节点/分布式声明**。

```text
extracted 9 groups / 20 rules (20 alerts)
Checking rules.yaml
  SUCCESS: 20 rules found
  SUCCESS                        ← promtool test rules 全部用例通过（含新增 6 个）
```

- 首轮 `test rules` 报的 3 个失败全部是**本切片漏写 `exp_annotations`**：既有
  17 条告警的用例零失败 ⇒ 新告警没有破坏旧演练；
- 期望值按 promtool 实际输出校准（PLAN-064 的既定纪律），`{{ $value }}` 实测为
  6 / 7 / 11 次，逐字固化进测试文件；
- 良性 reason（`validator_unknown`）与零增长的 counter 两个用例证明告警**不会
  误触发**——这是本切片新告警最容易被忽略也最危险的一面。

### 4.3 反向对照（实跑）

把"转发到端口"的三处调用抹掉（`counter.countVoteRejected` /
`counter.countFinalizeRejected` / `rejectionCounter.countVoteRejected`），
保留端口、枚举、setter 与全部拒绝判定——反射的是"接了计数就发指标、不接就
只有日志"：

```text
FinalityRejectionCounterWiringTest > startupRestoreDropsAreReplayedWhenCounterIsInjectedLater FAILED
FinalityRejectionCounterWiringTest > convergenceRejectionIsCountedOnItsOwnPath FAILED
FinalityRejectionCounterWiringTest > forgedVoteIncrementsSignatureRejectionOnAdmissionPath FAILED
FinalityRejectionCounterWiringTest > unknownValidatorIsCountedSeparatelyFromBadSignature FAILED
FinalityRejectionCounterWiringTest > zeroTotalWeightIsCountedSoSilentValidatorSetFailuresBecomeVisible FAILED
FinalityRejectionCounterWiringTest > aggregateVerificationFailureIsCountedOnFinalizePath FAILED
7 tests completed, 6 failed
```

恢复后同一批 **7/7 绿**。第 7 例（`honestVotesDoNotIncrementAnyRejectionCounter`）
在反向对照下**保持绿**是预期结果：它是负向用例，断的是"诚实票不得计数"，
与"计数是否发出"无关；若它随反向对照变红，说明它依赖了被抹掉的实现路径，
那才是缺陷。

### 4.4 CI 抓到的一个真实缺陷（SAST / SpotBugs，首轮红）

PR #400 首轮 CI：必检 `SAST - SpotBugs + FindSecBugs` 以 **1 finding** 判红
（`nexus-core` 模块 `main.xml` 中 1 条）：

```text
BIT_IOR_OF_SIGNED_BYTE (CORRECTNESS, priority 1)
  In method org.nexus.metrics.FinalityRejectionMetrics.key(Reason, Phase)
```

**根因**：map key 的打包写作 `((long) reason.ordinal() << 8) | (byte) phase.ordinal()`。
`byte` 是有符号的，符号扩展后参与按位或会把高 56 位置起来——按当时取值
（phase 序数 0..2）不会真的撞 key，但这是一个**随取值变化会变成真碰撞**的
写法，SpotBugs 抓的是这个模式，判红是对的。

**修法**：两个非负 int 各占一半位宽，不再借道 `byte`：

```java
return ((long) reason.ordinal() << Integer.SIZE) | phase.ordinal();
```

**取证**：本地 `./gradlew :nexus-core:nexus-core:spotbugsMain`（在线拉取与 CI
同一版本的 `findsecbugs-plugin`）⇒ `main.xml` **0 BugInstance**、BUILD SUCCESSFUL；
复跑 metrics+finality 全包 **169 / 0 失败**。

**这个 finding 抓不到的那一半要说清**：它**不是**测试能抓到的问题——按闭合词表
的当前取值，key 根本不会碰撞，`tagValuesMatchEnumNames` 这类值断言测不出"写法里
埋着未来的碰撞"。这正是"必检 SAST 与单测互补"的实证：单测管行为、SAST 管
**取值变化后会变成缺陷的模式**。不得因为"现在不 collision"就把这类 finding
当噪声豁免。

## 五、诚实边界（勿夸大）

1. **同内核 JVM 测试 + 本地 promtool CLI**：没有真实网络分区、没有物理多机、
   没有跨内核对比。promtool 跑在 WSL2 的 Linux 内核上，但那是**同一个物理机的
   另一个内核里的一个 CLI 工具**，不构成任何分布式/多机结论。
2. **两条新告警均未在真实集群触发过**：只证明 PromQL 可解析、表达式与标签集
   在合成序列上按预期触发/不触发（PLAN-064 的同一条边界）。没有起真实
   Prometheus/Alertmanager，没有通知链路验收。
3. **`FinalityFinalizeRejected` 在金丝雀语义**：PLAN-065 之后，入场前已逐票验签，
   `CollectingAggregator` 下该计数预期恒为 0；告警是"一旦出现即异常"的安全
   金丝雀，不是常态指标。这已在注解里写明，不得拿它证明"聚合验签正在失败"。
4. **没有验证人维度、没有吞吐基准**：见 §2.3；每票一次 Ed25519 验签的成本在
   PLAN-065 边界里已登记，本切片未追加测量。
5. **`/actuator/prometheus` 抓取端未复验**：本切片未起应用；新增指标是否出现在
   抓取端沿用 PLAN-063 §七 的口径（需要时同样以 WSL2 PG 单机边界补验，明确
   标注非物理多机）。
6. **未触碰真实 L1 / 桥资金链路 / wallet / settlement lane**。

## 六、后续

- 抓取端补验（WSL2 PG 单机边界）：确认 `nexus_finality_vote_rejected_total` +
  `nexus_finality_finalize_rejected_total` 在 `/actuator/prometheus` 可见；
- 验证人维度（如需）：带基数上限的 Top-N 或"最近 N 个被冒充地址"视图，先做设计
  再实现；
- 验签失败的量级曲线（rate 而非 increase）与按 reason 的分组看板；
- BLS 聚合替换后，复核"单票验签 + 聚合验签 + 拒绝计数"三层分层仍成立；
- 完整拜占庭矩阵剩余项（roadmap #27）：真实网络分区、验证人协同双签、私钥泄露、
  长程攻击、消息重放/洪泛。

## 七、相关文档回填

- [PLAN-065](PLAN-065-forged-vote-signature-bypass.md) §六 遗留项第一条
  （"验签失败计数指标与告警"）由本切片关闭；§五 #3 "每票一次 Ed25519 验签未做
  基准" 与 §五 #6 "未新增拒绝计数指标" 两条边界同步更新后者的状态。
- PLAN-063 §三 的 12 个 finality 指标口径 → 本切片后为 14 个
  （`FinalityMetrics` 7 + `AbstainRiskMetrics` 5 + `FinalityRejectionMetrics` 2）；
  其"要么被告警消费、要么进 `ALERTLESS_BY_DESIGN`"的分类门禁本 slice 起覆盖
  这两个新名字（走的是前者：真被两条告警消费）。
