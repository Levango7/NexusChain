# PLAN-084：事件接线门禁（Event Wiring Gate）

- 状态：立项（Design）｜2026-11-09 实现修订：发布点变量形式解析阶梯（见 §三 断言 A 第 2 条下注）
- 提出：zcode，2026-11-08
- 来源：生产就绪度审计（报告 `docs/audit/production-readiness-audit-2026-11-07.md` §二 A4 / §三 B8 B9）
  + WorkBuddy / TRAE 的独立复核
- 关联 PR：#562（审计报告）、#565（三条孤儿事件的接线修复）

## 一、要解决的问题

审计确认了**三个"发布但无人消费"的告警事件**，它们都在动账/运维路径上：

| 事件 | 发布点 | 后果 |
|---|---|---|
| `AccountLinkageFailedEvent` | `PaymentEventListener:135/:172` | 支付确认→商户余额**入账失败**只剩一条 `log.error`（且 `@TransactionalEventListener(AFTER_COMMIT)` ⇒ 无法回滚订单） |
| `DunningNotificationEvent` | `DunningManager:86` | 催收通知从未发出 |
| `SlaBreachEvent` | `SlaMonitorService:68` | SLA 违约告警无接收方 |

反方向也有：`@EventListener` 存在的意义是"有人发"，没人发的监听器是死代码。
**两个方向都需要一条可判的断言**，否则同类缺陷会再次静默产生。

## 二、为什么不用 ArchUnit（三条理由，都是原理性的）

1. **ArchUnit 表达不了这个不变量。** 它能断言"存在 `@EventListener` 方法"，
   但证不了"存在 `publishEvent(new XxxEvent(...))` 会命中它"——这是数据流问题，不是结构问题。
2. **会把三类假阳性全扫进来**（审计期已逐条实证）：
   - **父类派发**：Spring 按可赋值类型分发，`AccountBalanceNegativeEvent extends AccountBalanceChangedEvent`
     ⇒ 被父类监听器消费，按类型精确匹配会误判为孤儿；
   - **事件溯源记录**：`SagaCoordinator` 家族的 `SigningCancelledEvent` / `PaymentFailedEvent` 等
     是 **append 到事件存储的事实**（`SagaCoordinator:375` 原文 `…appended: aggregateId/version/globalTxId`），
     设计上不需要监听器；
   - **同名非事件**：`AlertEvent` 是 **JPA 实体**（`AlertEvent.java:15` 不 `extends ApplicationEvent`），
     走 `AlertNotifier` 三出口，被误当事件扫进来。
3. **本仓已有反例**：审计用机械口径扫出 23 条"孤儿"，逐条核验后**收缩到 3 条**。

⇒ 断言必须**基于类型闭包 + 显式豁免账**，不能靠 ArchUnit 的类结构规则。

## 三、两条断言（本计划要落地的）

### 断言 A（静态，双向）—— `scripts/check-event-wiring.py`

在 `nexus-gateway/src/main/java` 上：

1. 建**事件闭包**：`class X extends Y` 图中传递可达 `ApplicationEvent` 的类（排除 JPA 实体）。
2. 收集**发布点**：`publishEvent(new T(` 字面量（变量形式的 `publishEvent(x)` 记为"不可解析"，只计数不判定）。
   > **设计修订（2026-11-09 实现时确认）**：本条字面口径与 §五 验收 3 冲突——实测 A4
   > （`PaymentEventListener.java:210/:212`）与 B8（`DunningManager.java:86/:88`）均为
   > 「同文件声明变量再 publish」形式，纯字面口径只能抓到 B9 一条，验收 3 的 3/3 召回
   > 无法满足 ⇒ 实现补「变量形式解析阶梯」：字面量 → 同文件 `T v = new T(` +
   > `publishEvent(v)` 最近同名声明绑定 → 其余记为不可解析只计数。阶梯只扩大召回
   > （#565 修前树实跑 8 孤儿 + 1 死监听，A4/B8/B9 全复现，另多抓 5 条变量形式真孤儿，
   > 逐条核为真阳性后进豁免账），不缩小判定面。
3. 收集**监听点**：`@EventListener` / `@TransactionalEventListener` 方法的参数类型。
4. 双向判定（**均要考虑父类派发**：监听类型 L 覆盖所有 L 的子类型）：
   - `[ORPHAN-PUBLISH]`：发布的 T 没有任何监听器类型是 T 或其超类 —— **这就是 A4/B8/B9 的形态**；
   - `[DEAD-LISTENER]`：监听的 L 没有任何发布类型是 L 或其子类。
5. **豁免账** `config/event-wiring-exemptions.txt`：每行 `orphan|dead <简单类名> # 理由（必填）`，
   命中豁免的条目只打印 `[EXEMPT]` 不判红；**理由为空直接判配置错误 exit 2**。

### 断言 B（运行时 smoke，P3 阶段）

对**关键事件**（优先动账类）在集成测试里：构造并 `publishEvent` 一次，断言
① 对应的 `AlertEvent` 行被写入；② 对应 meter/计数出现。
静态断言只能证明"接线存在"，这条才能证明"接线真的通"。

## 四、自测样本（`--selftest`，必须先跑）

解析器必须先钉住自己，否则"空判绿"。至少 6 个合成样本：

| 样本 | 期望 |
|---|---|
| 发布 `A`，监听 `A` | 通过 |
| 发布 `B`，监听 `A`（`B extends A`） | **通过**（父类派发，不得误报） |
| 发布 `C`，无监听 | `[ORPHAN-PUBLISH]` |
| 监听 `D`，无人发布 | `[DEAD-LISTENER]` |
| 发布 `E`，监听 `E`（`E` 在豁免账且带理由） | `[EXEMPT]`，不判红 |
| 豁免账某行理由为空 | **exit 2**（配置错误，不是放行） |

实现版 selftest 为 9 行（表内 6 个必需样本 + 变量形式阶梯 2 例 + 注释/字符串噪声 1 例），尾行形如 `selftest: 9/9 … => PASS`。

## 五、验收标准（缺一不可）

1. `--selftest` 6/6 通过；
2. 对**当前 master** 实跑：`[ORPHAN-PUBLISH]` 恰好命中 A4/B8/B9 三条（若 #565 已合并则为 0），
   **0 条其它误报**；
3. 对「#565 修前」的代码树跑：**必须复现这 3 条**（证明它抓得住这次的真实缺陷；2026-11-09 实跑在 3 条之外另抓 5 条变量形式真孤儿 + 1 条死监听，逐条核为真阳性后进豁免账，见 §三 注）；
4. 接入 CI 后**首轮不得因误报判红**（先 report-only，豁免账稳定后再 `--strict`）。

## 六、分期与接入方式

| 期 | 内容 | 接入 |
|---|---|---|
| P1 | 断言 A + 豁免账 + selftest | **report-only**（`code-hygiene` job 里 `continue-on-error: true`），只产信号 |
| P2 | 误报清零、豁免账收敛、补 `--strict` | 转为阻断 |
| P3 | 断言 B 的运行时 smoke（先 `PaymentConfirmedEvent` / `AccountLinkageFailedEvent`） | 集成测试腿 |

**接入纪律**：任何新门禁先 report-only 跑满一轮真实 PR，再谈阻断 —— 依据是 2026-11-07 的
全仓冻结（成因是 `20261105-0003` 的 `ack_required` 逾期未签收，**不是**门禁误报；
但"新门禁一上来就阻断"确实会放大任何误报的代价）。

## 七、非目标

- 不覆盖 `nexus-core`（节点本地事件，如 `NewBlockEvent` 由 P2P/挖矿路径产生，跨模块发布不可静态判定）；
- 不覆盖 Kafka / Feign 等**非 Spring 事件**通道；
- 不删除任何现有事件或监听器（清理是独立条目）；
- 不在本计划里改 `OrchestrationService` / 熔断相关代码（那是另一条议题）。

## 八、开放问题（需 owner 或后续确认）

1. `nexus-gateway` 之外的模块（bridge / signing / wallet）是否纳入？—— 建议 P2 之后再扩。
2. 断言 B 的 meter 来源：`MetricsCollector` 还是 Micrometer `MeterRegistry`？需与 `ResilienceMetrics` 议题对齐。
3. 豁免账的 owner 是谁：需要一条"谁有权加豁免"的约束（否则会变成垃圾桶）。