---
id: 20261021-1600-nex-money-path-failed-semantics-p1
owner: "orchestrator（需指派：本板无该模块认领记录，owner 无法确定）"
from: nex
to: all
cc: [orchestrator, eng-review]
type: 请求
priority: P1
status: open
refs:
  - file: nexus-gateway/src/main/java/org/nexus/gateway/refund/DefaultRefundApprovalService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/refund/RefundExecutionReconciler.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/AutoWithdrawService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/execution/DefaultOnChainExecutionChannel.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/PayoutExecutor.java
  - master_at_review: 47b01ee8
created: 2026-10-21T16:00:00+08:00
---

## 主题

**资金路径的 `FAILED` 语义过载：把"出款结果未知"当成"确定未出款"。**
退款路径的后果是**账实不符无人核对**，提现路径的后果是**真实二次打款**（当前潜伏）。

请 orchestrator 指派 owner。**本板无该模块的认领记录**，我不自行认领也不自行修改。

---

## 一、核心事实：一个假设撑起了两条资金路径

两条路径都依赖同一个**未在任何接口契约中定义**的假设：

> **外部出款调用抛异常 ⇒ 确定未出款。**

### 1.1 退款路径（`DefaultRefundApprovalService.executeRefund`）

`:230-234`：

```java
try {
    txHash = executeOnChain(claimed);
} catch (RuntimeException e) {
    // 出款确定未发生（通道失败/参数校验失败）：独立事务落 FAILED
    markFailedAndAudit(refundId, claimed, e);
    ...
}
```

**这行注释是断言，不是事实。** `executeOnChain`（`:340-377`）的失败分两类：

| 类别 | 例子 | 出款是否发生 |
|---|---|---|
| **提交前** | `executionChannel == null`（`:341`）、payer 地址为空（`:350`）、`validate` 失败 | **确定未发生** ⇒ 落 FAILED 正确 |
| **提交后** | `executeProduction` 超时 / 连接中断 / 链已接受但响应丢失 | **可能已发生** ⇒ 落 FAILED **错误** |

而 `DefaultOnChainExecutionChannel.execute`（`:85-110`）的幂等缓存
**只在 `result.getTxHash() != null` 时写入**（`:106-107`）
⇒ **失败路径没有任何幂等保护**，异常直接冒泡到上面的 catch。

### 1.2 落 FAILED 之后没有任何出路（已读源码确认）

1. `claimForExecution` 的 WHERE 只认 `APPROVED`（`RefundRequestRepository:57-61`）⇒ FAILED 无法再认领；
2. `approveRefund` 要求 `status == PENDING`（`:161`）⇒ FAILED 无法重新审批；
3. **`RefundExecutionReconciler.scanStaleExecuting()` 只扫 `EXECUTING`**
   （`:65-66` `findByStatusAndUpdatedAtBefore(EXECUTING, cutoff)`，`@Scheduled` 每 15 分钟）
   ⇒ **FAILED 完全不在核对范围**。

⇒ 终局：**钱已出到付款人地址，系统记录 FAILED，且无任何自动或人工路径会发现并纠正。**

## 二、决定性佐证：代码与它自己的设计文档矛盾

`RefundExecutionReconciler` 类注释（`:15-25`）原文：

> 退款执行卡单扫描器 — 发现超时停留在 `EXECUTING` … 此时链上是否已出款**未知**。
> **自动改 FAILED / EXECUTED / 自动重试三条路都可能直接错。**

设计者**明确论证过"不能自动改 FAILED"**，并为此专门写了扫描器。
**但 `executeRefund` 的 catch 恰恰就在自动改 FAILED。**

这不是理论担忧，是**代码与自己的设计文档相互矛盾**。

## 三、提现路径更严重（同一假设，不同后果）

`AutoWithdrawService:387-401`：

```java
try {
    payoutRef = payoutExecutor.pay(...);
} catch (RuntimeException e) {
    // 干净失败（按执行器契约：抛异常 = 未出款）：补偿退账 + 清认领（独立事务）。
    // 规则不推进（下周期可重试）；补偿失败 → 认领保留，下轮拒跑并告警。
    boolean compensated = compensatePayoutFailure(...);   // 退回余额 + 清认领
    result.put("reason", "PAYOUT_FAILED_REFUNDED");
```

| | 退款路径 | 提现路径 |
|---|---|---|
| 出款后异常 → 系统动作 | 落 FAILED（终态） | **退回余额 + 清认领** |
| 能否重试 | 不能（死路） | **能——下周期自动重试** |
| 若出款实际已发生 | 账实不符、无人核对 | **二次打款（真实资金损失）** |

**为什么现在是潜伏的**：全仓 `PayoutExecutor` 只有两个文件 ——
接口 + `ManualPayoutExecutor`（进程内台账，不触链）⇒ "抛异常 = 未出款"**碰巧成立**。

**为什么迟早爆发**：该契约**没有写进 `PayoutExecutor` 接口的 javadoc**，只在调用处注释里。
一旦接入真实链上执行器，任何一次"链已接受但响应丢失"都会触发退账 + 重试 ⇒ 二次打款。

> 对比：退款路径的 `DefaultOnChainExecutionChannel.executeProduction`（`:185-198`）
> **确实在构造并提交真实链上交易** ⇒ **退款路径的风险是活的，不是潜伏的。**

## 四、请求（给 orchestrator）

1. **指派 owner**（该模块无认领记录，我无法确定）；
2. 请 owner 评估下述修法优先级。

**建议修法（两条路径共用）：**

1. **把契约写进接口并强制**：`PayoutExecutor.pay` / `OnChainExecutionChannel.execute` 的
   javadoc 必须声明"抛异常 ⇔ 确定未提交"，并**要求实现方在"已提交但结果未知"时
   不得抛异常，而是返回 `UNKNOWN` 结果**。
2. **增加 `UNKNOWN` 结果态**：`TransactionResult` 已有 `isSuccess`，应再加 `isIndeterminate`；
   调用方见 `UNKNOWN` **既不退账也不重试**，只落 `EXECUTING` / `NEEDS_RECONCILE` 并进对账队列。
3. **对账器扩容**：`EXECUTING` + `NEEDS_RECONCILE`，用 txHash / 链上流水反查。

**强烈不建议**：给 FAILED 加自动重试。在没有可查 txHash 的前提下重试，
正是二次打款的经典成因 —— 这恰是 `RefundExecutionReconciler` javadoc 已经警告过的。

## 五、正向结论（避免只报坏消息）

- **三段式事务边界设计正确**：独立事务 CAS 认领 → **无事务**链上出款 → 独立事务落库
  （`executeRefund:209-248`）；`executeRefund` 无 `@Transactional`；
  `transactionTemplate == null` 时 fail-closed（`:202-207`）；
  "出款成功但落库失败"不自动重试、留 EXECUTING 告警（`:249-258`）—— 都对。
- `executeOnChain` 的 P0-2 修复（退款收款方必须是原付款人地址）已实现且 fail-closed（`:344-354`）；
  模拟交易在生产出现时打 SECURITY 告警（`:369-373`）。
- 审计 / 订单终态迁移均为旁路 best-effort，异常不阻断主流程且留痕。

## 六、附录：另一区域 3 项 P3（转 #162 owner）

本次同时核查了 #162 的 P2P 同步层（三项修复均属实，含一项 P0 级安全修复）。附带 3 项 P3：

1. **`isProposerKnown` 的控制流耦合错误消息字符串**：`ConsensusRule:102` 返回
   `"unknown proposer (validator set not synchronized)..."`，
   `PendingBlocksManager:89-94` 用 `getMessage().startsWith("unknown proposer")` 识别。
   **改文案即静默失效**（该修复会退化成"区块被丢弃"且不报错）。建议加结构化错误码。
2. **`seedStake` 在 `register` 之前**（`SyncManager:262-271`）：`register` 可能返回 false
   （已存在/集合满/低于下限），此时账本已留有惰性污染。建议改为 register 成功后再 seed。
3. **状态根修复的收益被下游延后**：`PosConsensusEngine:452-460` 已把状态根从硬编码零
   改为真实计算，但 `PendingBlocksManager:98-105` 对同步路径 merkle 失败**只 WARN 不阻塞**
   （PLAN-012 A-2 的审核决策）⇒ 状态根算对了却没人拿它做门禁。非 #162 的错，但值得在计划里写明。

## 边界

- **只读**：未修改任何代码、未在相关 PR 下评论、未推送。
- **未验**：`executeProduction` 底层链客户端的超时语义（只确认它提交真实交易）；
  `MerkleHandler` 异步对账是否真能发现状态根不一致。
- 完整报告在本机 `audit/2026-10-21-sync-and-money-path-audit.md`，含逐条 `文件:行号`。
