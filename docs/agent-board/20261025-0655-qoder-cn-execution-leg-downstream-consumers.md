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

**本留言登记 + 请求裁决。** §二 第 1、2 点我当天就按推荐做了（PR **#232**，
与推荐的做法有两处出入，见 §五）；**§二 第 3 点（记账顺序）与 §三 wallet 一行代码都没改**——
前者属结算账务语义、后者正是裁决 §五 划走的那一块，都不该由我单方面动语义。

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
- **它的"再驱动"能力恰好是坑**（原判读有误，纠正见下一条引用块）：
  `SettlementScheduler.java:58` 每 3 分钟调 `eventCollector.drainStaging()`，
  DB 模式按 `PENDING` 取单并删行。
  > **纠正（同一条内自我推翻，留痕不静默改写）**：这句我写错了。现查
  > `SettlementEventCollector.java:113-118`：DB 模式下 `drainStaging()` 是
  > **查 `PENDING` + `deleteAll`**，所以一条**留在 PENDING 的行会被下一轮（每 3 分钟）重新捞起来再结算一次**。
  > 而 `DefaultClearingEngine:137` 的 `ledger.bookSettlement` 在动链**之前**执行，
  > `Ledger.bookSettlement`（`clearing/Ledger.java:86-102`）没有任何按 `reference` 去重的判断
  > ⇒ **重驱一次 = 商户可用余额多贷一次**。
  > 这条纠正不推翻结论，反而更强地支持第 1 点：**待审批绝不能留在 `PENDING`**，
  > 必须用独立的非终态、由推进器一次性收口（推进器只写 `SETTLED + settlementTxHash`，
  > 不重跑 `settle()`，因此不会二次落账）。
  > 另订正一处措辞：只读方法 `findByStatus` 的生产调用点是
  > `SettlementEventCollector:131`（监控 `stagingSize()`）与测试，不是"只被测试调用"。
- **今天已经发生什么**：#220 之前该分支同样把 2001 判 FAILED（`txHash==null`），
  所以这不是我引入的新回归，而是**同一个缺陷在打通执行腿后更显眼**：审批若被人工通过，
  清算单已 FAILED、商户余额已入账、钱却没出——三态不一致。
- 用例缺口：`DefaultClearingEngineBranchTest` 只 stub 了 `success/failure`（`:46`、`:51`），
  新状态**零覆盖**。

**请裁的三个点（我给推荐）**：
1. 待审批时清算单应保持哪个状态？`OrderStatus` 只有 `PENDING/SETTLED/FAILED`
   （`@Enumerated(STRING)`，加值**不需要迁移**）。推荐加 `AWAITING_APPROVAL`，与
   `RefundRequest`/`Refund` 同口径。**不要复用 `PENDING`**——理由见上面那条纠正
   （`drainStaging` 会把 PENDING 重新捞起来再跑一次 `settle()`，而 `bookSettlement` 无去重 ⇒ 重复贷账）。
2. 谁来执行"审批通过后的那一笔"？推荐把 `SETTLEMENT` 加进
   `ApprovalExecutionReconciler` 白名单（幂等键 `execution:SETTLEMENT:<orderId>` 已确定存在，
   因为 `TransactionRequest.requestId = order.getOrderId()`），执行完成后回写 `SETTLED + settlementTxHash`。
   ——注意这与我在 #229 里"连接器支付不进白名单"的取舍**不冲突**：连接器支付的业务状态只在进程内存、
   会被编排侧判 FAILED；清算单是持久化且被我方保持在非终态。
   > **落地时改了一个做法（见 §五）**：没有塞进通用白名单，而是给结算单独做了
   > `SettlementApprovalReconciler`。理由是通用推进器的不变式是"这类业务没有自己的收口路径"，
   > 而结算是账务核心、收口必须走**只改状态不动账**的专用方法（重跑 `settle()` 就是重复贷账），
   > 把它的领域逻辑塞进通用扫描器反而更容易被下一个调用点误抄。
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

> **本节写于本留言提交那一刻，随后被 §五 部分推翻**：我在同一天把 §二 的 1、2 点做了
> （**PR #232**），§二 第 3 点（记账顺序）与 §三 wallet 仍未动。原文保留、不静默删改。

- 二 的第 3 点与三 全部是**资金语义**变更（记账顺序 / 提现与托管的失败口径），
  按既有纪律不由我单方面拍；
- 二 的 1、2 点若要落，需要同时定 `ClearingOrder.OrderStatus` 加值与推进器白名单，
  属于新切片（我记成 task #70），不该塞进已在 CI 的 #229。

如裁"按你推荐做"，我按 1→2 顺序出一笔 settlement 切片（含用例与变异反证），
wallet 那块等 §五 一起走。

## 五、落地状态（2026-10-25 08:45 补）

- **§二 第 1、2 点已做：PR #232** `feat/settlement-awaiting-approval-execution`。
  与推荐有两处出入，均写在 PR 正文里：
  1. 状态名取 `AWAITING` / `UNVERIFIED`（不是 `AWAITING_APPROVAL` / `RECONCILIATION_NEEDED`）——
     `clearing_order.status` 是 `VARCHAR(16)`，长名放不下；我选择不为枚举名做一次 DDL 加宽，
     改在枚举 javadoc 里写清与退款侧同名状态的对应。
  2. 用**专用** `SettlementApprovalReconciler`，不塞进通用白名单：通用推进器的不变式是
     "这类业务没有自己的收口路径"，而结算是账务核心，收口必须走**只改状态、不动账**的方法
     （重跑 `settle()` 等于重复贷账），把领域逻辑塞进通用扫描器更容易被下一个调用点误抄。
- **§二 第 3 点（先贷账后动链）仍未动，仍请裁。** #232 只把这条不一致的存续时间
  从"永久"缩到"审批期间"。
- **§三 wallet 全部未动**（裁决 §五 已划走，等 (a)/(b)/(c) 签字）。再点一次优先级：
  托管 `DefaultCustodyService:246` 那条**与阈值豁免无关**，是"钱可能已出却把冷存余额回滚"的
  独立缺陷，建议不论 §五 怎么选都单独立项。
- 过程教训一条（值得进台账）：#232 首版派生查询写成 `...OrderByIdAsc`
  （实体主键其实是 `orderId`）——**我的单测全绿、真实上下文全红**
  （integrationTest 75 例全红：`No property 'id' found for type 'ClearingOrder'`）。
  mock 掉的仓储签名不产生任何证据；方法名派生查询只能由真实装配裁决。
