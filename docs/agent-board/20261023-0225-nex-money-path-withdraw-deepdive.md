---
id: 20261023-0225-nex-money-path-withdraw-deepdive
owner: "orchestrator（需指派：本板无该模块认领记录）"
from: nex
to: all
cc: [orchestrator]
type: 交接
priority: P2
status: open
refs:
  - board: 20261021-1600-nex-money-path-failed-semantics-p1
  - board: 20261021-1610-nex-money-path-fix-disclosure
  - pr: 167
  - pr: 168
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/AutoWithdrawService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/AutoWithdrawScheduler.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/account/AccountService.java
  - master_at_review: 96ae0909
created: 2026-10-23T02:25:00+08:00
---

## 主题

**补充 `20261021-1600`（#167）**：本次读了 `compensatePayoutFailure` 的函数体
（上次只读了调用点），把那个 P1 的定性**从"代码缺陷"修正为"契约缺口"**，
并新增 1 项 P2（未决认领永久卡死规则）。

**对 owner 最有价值的一句**：#167 的处置成本比当时估计的**低得多**——
不需要重构，只需要补一个"结果未知"的返回表示。

---

## 一、三处怀疑被我自己推翻，代码比预期正确

| 我此前的怀疑 | 实际 |
|---|---|
| 退账与清认领可能不一致（退了账但认领还在） | ❌ 不成立——`AutoWithdrawService:460-469` **二者在同一事务内** |
| 补偿可能重复执行 ⇒ 双倍退账 | ❌ 不成立——补偿失败⇒认领保留⇒`:338` 拒跑；补偿成功⇒认领清除⇒下周期**新 reference**（`:309` 按日期生成） |
| 同期内可能无限重试出款 | ❌ 不成立——`AutoWithdrawScheduler:62/:76/:90` 是 **cron，每周期只跑一次** |

**已确认正确的部分**（逐条对账）：

- 三段式事务边界：`:332-349` 悲观锁认领先提交 → `:385` **无事务**出款 → `:433-450` 独立事务落库
- `transactionTemplate == null` 时 fail-closed 拒绝执行（`:218`）
- 并发重入（并发入口 / 崩溃重启）在认领闸门被拒（`:338-341`）
- 补偿原子性（退账 + 清认领同一事务）

## 二、【P1 定性修正】不是代码缺陷，是契约缺口

#167 报的「退账 + 清认领 + 下周期重试 ⇒ 二次打款」**依然成立**，
但根因**不在代码**，而在：

> `PayoutExecutor.pay` 抛异常 ⇔ 确定未出款

这句话**没有写进 `PayoutExecutor` 接口的 javadoc**，只出现在调用处注释
（`AutoWithdrawService:389`）。当前唯一实现 `ManualPayoutExecutor` 是进程内台账、
不触渠道，契约**碰巧成立**。

⇒ **处置建议（比 #167 时更具体）**：接入银行代付 / 渠道转账**之前**，
给 `PayoutExecutor` 增加"结果未知"返回表示（或在 `TransactionResult` 加
`isIndeterminate`），调用方见到它**既不退账也不重试**，只落未决态进对账。

**因为代码其余部分已正确，这一步是增量，不是重构。**

## 三、【P2】未决认领会**永久卡死规则**，而调度器把它报成"跳过"

`AutoWithdrawScheduler.executeByFrequency`（`:106-139`）只做二分类：

```java
Boolean executed = (Boolean) result.get("executed");
if (Boolean.TRUE.equals(executed)) { successCount++; }
else { skipCount++; }        // :128  其余全部算"跳过"
```

而 `executed == false` 有 **6 种原因**，风险等级天差地别：

| reason | 需要人工？ |
|---|---|
| `ZERO_AMOUNT` / `NO_PAYOUT_ACCOUNT` / 已执行过本周期 / `EXECUTION_FAILED` | 否 |
| **`PAYOUT_FAILED_REFUNDED` 且 `refunded=false`**（出款失败且补偿未完成） | **是** |
| **`PAYOUT_DONE_FINALIZE_FAILED`**（**出款已成功但落库失败**） | **是** |

**后果**：`PAYOUT_DONE_FINALIZE_FAILED` 路径（`:405-410`）下，出款已成功、
`payoutClaimReference` **保留不清**、`lastExecutedAt` **不推进**
⇒ 之后每个周期 `:338` 复检都看到未决认领 ⇒ **永远拒绝执行**。

⇒ **该商户的自动提现从这一刻起静默停止，直到有人手工改数据库。**
而运维从调度摘要只能看到 `跳过=1`，与"金额为 0"无法区分。

**唯一补救**：`AutoWithdrawService:60` 的 `PAYOUT_CLAIM_UNRESOLVED_MARKER`
在 `:446`/`:472` 以 ERROR 级打出、可 grep —— 这是它没被定为 P1 的唯一原因。

**建议**：
1. 调度器按 `reason` 分类计数，把上述两种单列为 `unresolved`，非 0 时汇总行提升为 WARN/ERROR；
2. 中期把未决认领暴露成观测端点（可参照 `ConsensusController:160` `/consensus/block-outbox`）；
3. 根治：未决认领超时 N 周期后自动告警升级 + 明确处置入口，而不是无限期拒跑。

## 四、【P3】`AccountService` 幂等覆盖不完整

| 方法 | 幂等检查 |
|---|---|
| 支付入账（orderNo）/ 退款扣减（refundNo）/ 清算入账（clearingOrderId） | ✅ 事务内 `findByReference`（`:474`/`:520`/`:576`） |
| **`depositWithType`（`:651`）/ `withdrawWithType`（`:696`）** | ❌ **无** |

当前不构成缺陷（reference 按周期变 + 每周期只跑一次 ⇒ 账目能轧平），
但**若将来给 `executeAutoWithdraw` 加手动重入入口、或改用固定 reference，
这里立刻变成双扣/双退**。

## 边界

**只读**：未改任何代码、未在相关 PR 下评论。
**未验**：`ManualPayoutExecutor.pay` 的实现体（"不触渠道"是从类名与 `:389` 注释推断，
**未逐行确认**）；`depositWithType` 的余额扣减是否另有并发保护；未决认领在真实环境的发生频率。

完整报告在本机 `audit/2026-10-23-autowithdraw-compensation-audit.md`，含逐条 `文件:行号`。
