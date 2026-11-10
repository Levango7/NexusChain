# 资金事件单一记账源语义设计（证据先行，接线待裁）

> 建立于 2026-11-10，对应 2026-11-10 用户会话拍板的五项产品决策之⑤「资金事件接哪本账」。
> 本文档只产出**语义设计证据**：定原则、定边界、定待裁问题。**不接线**——
> §5 的三个问题由 orchestrator 裁完后，接线实施另开 PR（裁决 ⑤ 的口径：
> 「哪个解决的更彻底就留哪个」不适用于本文档，因为本文档不实施）。

## 1. 一句话原则

**一笔资金贷记/冲正只有一个记账源：nexus-settlement 清算引擎的命令路径
（`DefaultClearingEngine` → `ledger.bookSettlement`）。Spring 事件是「既成事实的
通知」，不是记账指令；任何 `@EventListener` 方法体内禁止资金记账动作。
gateway 侧 `AccountService.creditOnClearing` 是平行的第二记账源，永不接线。**

## 2. 为什么（全部证据可按行号在 master `c24134ad` 复算）

| # | 事实 | 位置 | 推论 |
|---|---|---|---|
| 1 | 权威记账点：`ledger.bookSettlement(merchantId, amount, orderId)` | `nexus-settlement/.../DefaultClearingEngine.java:225` | 结算贷记的唯一动作 |
| 2 | 记账前预检：`canTransferTo(payeeAddress)` 为 false ⇒ 记账前 `FAILED` | `DefaultClearingEngine.java:214-216` | 预检失败零账务偏移 |
| 3 | `bookSettlement` 无去重 ⇒ 重复贷账不可自动纠正 | `DefaultClearingEngine.java:207,239`（注释） | 重复动账是永久偏移，必须结构性防住 |
| 4 | 生产入口链：`SettlementScheduler` → `SettlementEventCollector.drainStaging()` → `batchClear` | `nexus-gateway/.../clearing/DefaultSettlementService.java:35-47`（类级 javadoc 明文声明权威路径） | 权威路径已接入每商户 T+N 周期 |
| 5 | 防误接闸门：`legacy-batch-settlement.enabled=false` 默认关，误接即抛错 | `DefaultSettlementService.java:47,59,69` | 旧批量实现（死）被闸门封死 |
| 6 | 第二记账源（死）：`accountService.creditOnClearing(...)` | `nexus-gateway/.../clearing/ClearingSettlementService.java:28,59,83,203` | 与 :1 同一笔钱的重复贷记 |
| 7 | 死监听链：`ClearingBatchCompletedEvent` 全仓无发布点 ⇒ `ClearingSettlementListener.onClearingBatchCompleted` → `processClearingBatch` 生产从不触发 | `ClearingSettlementListener.java:34,41`；`config/event-wiring-exemptions.txt` dead 行；`ClearingRetryScheduler.java:25`（类注释实证） | 链路整条死，但**接线诱惑真实存在**：一旦有人在 `batchClear` 末尾发事件，:6 立即变活 ⇒ 重复动账 |
| 8 | 冲正能力不存在：`FAILED 是终态、drainStaging 只捞 PENDING、全仓无冲正能力` | `DefaultClearingEngine.java:207`（注释，2026-10-30 S1 审计结论） | `OrderReversedEvent`/`OrderVoidedEvent` 的 javadoc 承诺（清算模块消费后生成反向清算记录）无对应实现 |
| 9 | 对照样本：① 结算确认写侧接线（PR #637）遵循同一原则——确认记录是观测能力、幂等键 paymentId、失败旁路不阻断主路径 | `ChainSettlementConfirmationService.java:88,97`；`OrchestrationService.java:342-343`（本批接线点） | 观测/通知类能力可以接，资金类能力不许接 |

## 3. 三类资金事件的语义定义

| 事件 | 现状 | 语义（本文档定义） | 消费者约束 |
|---|---|---|---|
| `ClearingBatchCompletedEvent` | 死监听（:7） | 「批次完成」= 该批次内全部订单**已按 batchClear 记账完毕**的既成事实通知 | 只读：对账视图/通知/审计。禁止任何 `credit`/`book`/冲正动作 |
| `OrderReversedEvent` | 孤儿发布（`ReversalService` 变量形式发布，豁免账已登记） | 支付冲正请求的通知；**当前无冲正记账能力**（:8），事件不代表钱已动 | 只读；javadoc 的「清算模块生成反向清算记录」承诺必须先修正（见 §5-Q3） |
| `OrderVoidedEvent` | 孤儿发布（`VoidService`） | 支付作废通知；同上 | 同上 |

非资金孤儿（`ApiVersionDeprecatedEvent`/`EscrowStatusChangedEvent`/`PreAuthStatusChangedEvent`）
不在本文档范围，处置仍由板 `20261109-0259-zcode-event-wiring-gate-live` 请 orchestrator 裁。

## 4. 不变量（改任一行代码前必须同时成立）

- **I1** 资金记账只能由清算引擎命令路径发起；`@EventListener` 方法体内禁止
  `ledger.book*` / `credit*` / 冲正类调用。
- **I2** 资金事件 = 既成事实通知；发布点必须在记账事务成功之后。推荐发布点
  = `batchClear` 成功末尾（`DefaultClearingEngine.java:151` 日志行处）；
  不得在记账前发布（消费者会读到未发生的事实）。
- **I3** `creditOnClearing` 永不接线。除非先删除 `ClearingSettlementService`
  或整体降级为只读对账（其 `ClearingSettlementRecord` 表与 `ClearingRetryScheduler`
  一并处置），否则该调用点保持零调用方。
- **I4** 新增任何资金事件前，PR 描述必须回答「记账源是哪个方法（文件:行号）」，
  答不出即驳回（与 `scripts/check-event-wiring.py` 的 report-only 口径衔接，
  待 orchestrator 决定是否升级为门禁断言）。
- **I5** 幂等/reference 命名空间不与权威路径重叠；禁止为第二记账源新增
  `CLR_<batchNo>_<merchantId>` 之外前缀的并行键（参照 `docs/money-outlet-boundary.md` §2.3）。

## 5. 待裁三问（接线决策交 orchestrator/用户）

- **Q1 发布点**：`ClearingBatchCompletedEvent` 定在 `batchClear` 成功末尾（:151），
  还是 `drainStaging` 调度轮次完成处？前者粒度=批次，后者粒度=轮次（可含空批次）。
  推荐前者。
- **Q2 死监听链处置**：`ClearingSettlementListener` + `ClearingSettlementService` +
  `ClearingRetryScheduler` + `clearing_settlement_record` 表 —— 删（审计 #565 哲学：
  不留「看起来有响应」的假象）还是降级只读？推荐删：它是第二记账源的唯一载体，
  留着就是误接诱惑；删后 I3 结构性成立。
- **Q3 javadoc 超前承诺**：`OrderReversedEvent`/`OrderVoidedEvent` 的 javadoc 承诺
  「清算模块消费后生成反向清算记录」——修正 javadoc 为「通知性质，无自动冲正记账」
  （推荐），还是实现清算侧只读消费？注意：冲正记账能力本身不存在（:8），属产品缺口，单独立项。

## 6. 边界（本文档没做什么）

- 未发布/未接线任何事件；未改任何监听器；未碰 `creditOnClearing` 调用点；
- 未改 `config/event-wiring-exemptions.txt`（6 条豁免原样）；
- 未改板帖原文（`20261109-0259`/`20261108-0851`/`20261105-0642`/`20261108-1402`）；
- 行号锚 master `c24134ad`；引用 PR：#611（事件接线门禁）、#637（①写侧接线，在飞）。
