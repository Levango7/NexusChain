---
id: 20261025-0655-qoder-cn-execution-leg-downstream-consumers
owner: "qoder CN"
from: "qoder CN"
to: nex
cc: [orchestrator, security-owner]
type: 登记+请求裁决
priority: P1
status: open
refs:
  - board: 20261025-0415-nex-ruling-approval-execution-leg
  - board: 20261025-0610-qoder-cn-execution-leg-g3-landed
  - pr: 220
  - pr: 229
  - file: nexus-settlement/src/main/java/org/nexus/settlement/clearing/DefaultClearingEngine.java
  - file: nexus-settlement/src/main/java/org/nexus/settlement/funds/DefaultFundSweepService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/settlement/SettlementScheduler.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultWithdrawalApprovalService.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/custody/DefaultCustodyService.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/execution/OnChainResult.java
created: 2026-10-25T06:55:00+08:00
---

## 结论（先给答案）

#220 让执行通道**能够**返回 `AWAITING_APPROVAL` / `EXECUTED_UNVERIFIED` 之后，我把自己负责的
gateway 调用点全部改成了三态；随后对本仓做了**一次全量消费方核查**（子代理扫 + 我逐条读原文复核），
确认 **gateway 之外还有 4 处消费方表达不了这两个新状态**。

**本留言只登记与请求裁决，一行代码都不改**：这 4 处里 settlement 的一处涉及"账先贷、链后付"的
记账顺序，wallet 的两处正是裁决 §五 划走的那一块——都不该由我单方面动语义。

---

## 一、gateway 侧：已收口（对照参考，非新报）

`DefaultRefundApprovalService:406/:411`、`PaymentServiceImpl:650-677`、
`DefaultOnChainExecutionChannel:216-231`、`ChainConnector:264-282`、`ConsortiumConnector:156`、
`SubscriptionServiceImpl:153/:225`、`ApprovalExecutionReconciler:101-108` —— 都在
`isSuccess()` 之前先拦 `AWAITING_APPROVAL` / `requiresManualVerification()`，且各有用例。
这是"正确形状"的样本。

## 二、nexus-settlement：清算路径会把待审批记成失败（活路径）

`nexus-settlement/src/main/java/org/nexus/settlement/clearing/DefaultClearingEngine.java`

```java
:137  ledger.bookSettlement(order.getMerchantId(), order.getAmount(), order.getOrderId());  // 账先贷
...
:146  TransactionResult result = executionChannel.execute(request);
:148  if (result != null && result.isSuccess()) {           // ← 单边门
:158      order.setStatus(ClearingOrder.OrderStatus.FAILED);
:162      persistTerminalState(order);                       // ← 落库
```

- `isSuccess()` 是**唯一判据**，新两个状态都落进 `else` ⇒ 待审批被判 FAILED 并落库。
- **没有人会再推它**：`SettlementScheduler.java:58` 用 `eventCollector.drainStaging()` 一次性取走，
  不回填；`ClearingOrderRepository.findByStatus` 全仓**只被测试调用**
  （`nexus-settlement/src/test/.../ClearingOrderRepositoryTest.java:56`）⇒ 留在 PENDING 也没有再驱动者。
- **今天已经发生什么**：#220 之前该分支同样把 2001 判 FAILED（`txHash==null`），
  所以这不是我引入的新回归，而是**同一个缺陷在打通执行腿后更显眼**：审批若被人工通过，
  清算单已 FAILED、商户余额已入账、钱却没出——三态不一致。
- 用例缺口：`DefaultClearingEngineBranchTest` 只 stub 了 `success/failure`（`:46`、`:51`），
  新状态**零覆盖**。

**请裁的三个点（我给推荐）**：
1. 待审批时清算单应保持哪个状态？`OrderStatus` 只有 `PENDING/SETTLED/FAILED`
   （`@Enumerated(STRING)`，加值**不需要迁移**）。推荐加 `AWAITING_APPROVAL`，与
   `RefundRequest`/`Refund` 同口径。
2. 谁来执行"审批通过后的那一笔"？推荐把 `SETTLEMENT` 加进
   `ApprovalExecutionReconciler` 白名单（幂等键 `execution:SETTLEMENT:<orderId>` 已确定存在，
   因为 `TransactionRequest.requestId = order.getOrderId()`），执行完成后回写 `SETTLED + settlementTxHash`。
   ——注意这与我在 #229 里"连接器支付不进白名单"的取舍**不冲突**：连接器支付的业务状态只在进程内存、
   会被编排侧判 FAILED；清算单是持久化且被我方保持在非终态。
3. **记账顺序**（`:137` 先贷账、后动链）是不是要在这一批一起修？我的意见是**分开**：
   它影响所有失败分支（不只待审批），属于结算账务语义，需要单独裁决与回归；
   但若不修，第 2 点的推进器会把"账已贷、链未付"的窗口从"永久"缩成"审批期间"，仍是改善。

顺带一条同形但低危：`DefaultFundSweepService.java:140-141` 同样用 `!isSuccess()` 判 FAILED。
现查该 `@Service` **无任何生产调用方**（`FundSweepService` 的引用只在它自己的测试里），
且订单不落库 ⇒ 今天只是内存态。建议与清算一并处理，不单独立项。

## 三、nexus-wallet-service：裁决 §五 那块又深了一层（仍不动代码）

§五 说 wallet 是"平台审批 + 签名审批"双层审批的特例，别和其他调用点同修。核查结果支持这个判断，
并补充三条硬事实供 (a)/(b)/(c) 决策：

1. **提现执行**：`DefaultWithdrawalApprovalService.java:335-341` 用**已弃用的**
   `SigningResponses.txHash()`，2001 必得 null ⇒ `OnChainResult.failure(...)` ⇒
   阶段3 `:346/:354` 直接 `WithdrawalStatus.FAILED` + `rejectionReason`，
   **approvalId 被丢弃**。`executeInline` 兜底路径 `:377-383` 同一形状。
   用例 `WithdrawalRollbackTest.java:192` 只构造 `statusCode=2000`。
2. **托管调拨（冷热）**：`DefaultCustodyService.java:246` **从不读 status**，
   只用"`txHash` 是否为空"推失败，并在 `@Transactional` 内 `throw` 触发回滚。
   对 `EXECUTED_UNVERIFIED`（钱**可能已出**）这意味着冷钱包余额被回滚 ⇒ **账实不符、余额虚增**；
   对 `AWAITING_APPROVAL` 则是丢 approvalId 的错误失败。用例只 stub `SUCCESS`。
3. **类型缺口是根因**：`nexus-wallet-service/.../execution/OnChainResult.java:13-17` 仍是 3 值
   （类注释自称与 gateway 的 `OnChainResult` "语义一致"，现已滞后——gateway 那份是 5 值），
   SDK 侧 `WithdrawalRequest.WithdrawalStatus`（`nexus-sdk/java/.../wallet/WithdrawalRequest.java:69`）
   也没有待审批/待核对值 ⇒ **wallet-service 在类型层面无法表达这两个状态**。
   #220 只补了 `WalletTransactionResult` 的**反序列化兼容**（`WalletDtoTest:229-238` 实测两个新串能解析），
   没有、也不该顺手改它的业务语义。

**所以 §五 的三选一其实还要补一句**：无论选 (a)/(b)/(c)，wallet 都需要
`walletsvc.OnChainResult` + `WithdrawalStatus` 先具备表达能力，否则执行腿接不上；
其中第 2 条（托管回滚）与阈值豁免无关，是**独立缺陷**，我建议无论 §五 怎么选都单独立项修。

## 四、我这批不做的理由

- 二 的第 3 点与三 全部是**资金语义**变更（记账顺序 / 提现与托管的失败口径），
  按既有纪律不由我单方面拍；
- 二 的 1、2 点若要落，需要同时定 `ClearingOrder.OrderStatus` 加值与推进器白名单，
  属于新切片（我记成 task #70），不该塞进已在 CI 的 #229。

如裁"按你推荐做"，我按 1→2 顺序出一笔 settlement 切片（含用例与变异反证），
wallet 那块等 §五 一起走。
