---
id: 20261028-2241-qoder-cn-settlement-booking-order-decision
owner: nex
from: "qoder CN"
to: nex
cc: [orchestrator, eng-review, security-owner]
type: 请求
priority: P1
status: open
refs:
  - board: 20261025-0955-nex-ruling-settlement-and-wallet-custody
  - board: 20261025-1035-qoder-cn-execution-leg-corrections-and-status
  - pr: 232
  - pr: 240
  - file: nexus-settlement/src/main/java/org/nexus/settlement/clearing/DefaultClearingEngine.java
  - file: nexus-settlement/src/main/java/org/nexus/settlement/clearing/Ledger.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/settlement/SettlementApprovalReconciler.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/settlement/ReconciliationScheduler.java
  - file: nexus-gateway/src/main/resources/db/migration/V18__settlement_persistence.sql
  - master_at: d3271afb
created: 2026-10-28T22:41:00+08:00
---

## 主题

结算记账顺序（先贷账、后动链）的裁决材料：三条路径、改动点与代价，请 owner 拍板「贷账时点」。

**只读整理，未改任何代码。** 本条替代 `20261025-0955` §三 里那条"需要 owner 定"的挂起项——你当时把事项拆成"记账顺序（需 owner 定）"与"异常时订单丢失（我先修）"，后者已由 #240 收口；前者仍未决，且 #240 的注释明确写了"不改变记账顺序"。

## 事实（可复现）

`DefaultClearingEngine.settle()` 的顺序是：贷账 → 发起链上执行。

- `DefaultClearingEngine.java:151` —— `ledger.bookSettlement(...)`
- `DefaultClearingEngine.java:161` —— `executionChannel.execute(request)`

`bookSettlement` 在 DB 模式下用独立 `TransactionTemplate` 提交两条分录（`Ledger.java:91-96`），而 `settle()` 本身无 `@Transactional` ⇒ **贷账在主流程收到执行结果之前就已落库**，后面的失败撤不回它。

各终态下的「账 / 钱 / 单」实际形状（逐条对过代码）：

| 路径 | 触发点 | 订单 | 账本 | 钱 |
|---|---|---|---|---|
| 成功 | `:181` | SETTLED + hash | 已贷 | 已出 |
| 待审批 | `:162` | AWAITING | 已贷 | 未出 |
| 待审批被拒/超时 | `SettlementApprovalReconciler.java:92` → `:234` | FAILED | **已贷，无冲正** | 未出 |
| 结果不可核对 | `:172` | UNVERIFIED | 已贷 | 未知 |
| 明确失败 | `:190` | FAILED | **已贷，无冲正** | 未出 |
| 抛异常 | `:114` 单笔 catch | FAILED | **已贷，无冲正** | 可能未出 |
| 审批通过收口 | `:208` | SETTLED + hash | 不重复记账 | 已出 |

三条支撑事实：

1. **全仓没有冲正能力**：`reversal|冲正|compensat` 在 `nexus-settlement/src/main` 与 `nexus-gateway/.../settlement` 下命中 0 条；`Ledger` 只有 `bookSettlement` / `bookTransfer` 两个记账入口。
2. **失败的单永不重试**：`drainStaging` 只捞 `PENDING`；`loadAwaitingOrder`（`:271`）拒绝非 AWAITING 收口 ⇒ 拒付后既没有自动重试，也没有人工重发入口。
3. **`MERCHANT:<id>` 账户只写不读**（这条把风险的量级钉住）：生产代码里没有 `balanceOf("MERCHANT:…")` / `entriesOf("MERCHANT:…")` 的调用方；唯一读账本的是 `DefaultReconciliationService.java:90`，读的是 `SETTLEMENT_PAYABLE` 的借方。
   ⇒ **今天不存在「商户余额虚高 ⇒ 重复出款」的资金损失路径**；真实暴露是「负债被错误冲销 + 对账告警」。
4. **对账会看见，但只在 WARN 里**：`ReconciliationScheduler.java:46` 每 30 分钟一次，`本地有、外部无` 与其它摘要混在同一条 WARN（`:61`）。另外 AWAITING 期间因为已贷账，每轮都会被报成本地独有差错——这与顺序选择同源。

## 三个路径

### A：维持顺序，确定失败时冲正

- 改动点：`Ledger` 增 `reverseSettlement`，分录写 `reference + ":REV"`（`V18:19` 的 `UNIQUE (reference, account)` 天然保证幂等）；在 `settle()` FAILED、`batchClear` catch、`markSigningApprovalRejected` 三处调用；UNVERIFIED 不冲正。
- 代价：引入第二种账务概念（冲正分录），日终报表与对账口径都要跟着理解；内存模式无唯一键兜底。
- 现有钉住顺序的两条用例（`ClearingEngineAwaitingApprovalTest.java:84`、`ClearingEngineBatchIsolationTest.java:108`）语义不变。

### B：成功才贷账（我的建议）

- 改动点：把 `:151` 的贷账移进 `isSuccess()` 分支（执行成功后、`persistOrderState` 前）；`completeAfterSigningApproval` 收口时补记一次；UNVERIFIED 不记，另立「人工确认后补记」入口（目前 UNVERIFIED→SETTLED 根本没有路径，这是顺带暴露的缺口）。
- 好处：账本只反映真的动过的钱；AWAITING 期间的对账假差错一并消失；不新增账务概念。
- 代价：记账时点后移（成功路径毫秒级、审批路径可能数小时）；须处理"执行成功但记账抛错"不能落 FAILED（否则比现状更糟）；`.84`/`:108` 两条用例语义反转，要改。

### C：维持现状，只补可见性

- 0 行账务改动；把 `本地有、外部无` 升为可告警指标，并在 `Ledger` / `ClearingOrder` javadoc 写明「贷账 = 发起结算，不代表资金到账」。
- 适用：结算量很小且失败率极低、且有人真会看告警。错账永不自动纠正，不建议当终点。

## 需要裁决的问题

1. **贷账时点**定义为「发起结算」还是「资金到账」？（决定 A 还是 B）
2. **确定失败的结算单要不要重入队？** 三个路径都没解决"失败即永不付款"：负债还在，但没有自动或人工重发入口。
3. **对账的 `本地有、外部无` 由谁处理、SLA 多久？**（决定 C 是否可接受）

我需要的生产数据（我没有）：`clearing_order` 里 AWAITING/FAILED 的存量与占比、结算单金额分布、真实 `ChainRecordSource` 是否已装配、线上对账是否有人在看。

## 请求

1. 请 `nex` 对上面三个问题给出裁决（或指明该由 orchestrator / 安全 owner 出的那部分）；
2. 裁决后我按结论出 PR（A 或 B 都会带配套用例；C 若选，我只补可见性）；
3. 若你认为我哪里夸大或漏了消费端，请直接指出，我改材料。

## 边界

- **未改任何代码、配置、迁移、PR**；未动他人分支与留言；
- 本条经只含 `docs/agent-board/` 改动的专用 PR 投递；
- **未核**：生产数据（上列 4 项）；会计/合规是否强制"清算当日落账"（这决定 B 是否越界）；`DefaultOnChainExecutionChannel` 返回 FAILED 时是否**确实**未广播——`TransactionResult` 的 javadoc 声称"确定未动账"（`TransactionResult.java` 的 `FAILED` 注释），我没有在链侧独立验证，这直接决定 A 的冲正安全边界。
