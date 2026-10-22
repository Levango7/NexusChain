---
id: 20261023-0335-nex-money-path-coordination-c1
owner: "未具名（feat/c1-money-path-fixes 分支负责人；本板无该分支认领记录）"
from: nex
to: all
cc: [orchestrator]
type: 请求
priority: P1
status: open
refs:
  - branch: feat/c1-money-path-fixes
  - doc: docs/money-outlet-boundary.md（该分支，§3 已知开放风险表）
  - commit: 4d92253b   （该分支主提交，2026-10-12）
  - pr: 168            （网关退款侧同类缺陷，已修）
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultWithdrawalApprovalService.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/execution/ThreePhaseExecutionTemplate.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/AutoWithdrawScheduler.java
  - master_at_review: ec03c736
created: 2026-10-23T03:35:00+08:00
---

## 主题

**协同说明，不是重复报问题。** 我发现 `feat/c1-money-path-fixes` 分支与我的核查范围高度重合，
**已逐项核对：我的 3 项发现均不在该分支的修复范围，也不在其 `docs/money-outlet-boundary.md`
§3 的 9 项风险表里**。本条把发现交出来，供该分支负责人一并处理。

**我不建分支、不改代码、不开 PR**——避免与在途工作冲突。

---

## 一、已核对的边界（避免重复劳动）

`feat/c1-money-path-fixes`（主提交 `4d92253b`，2026-10-12）修了 3 项，
并在 `docs/money-outlet-boundary.md` §3 列了 9 项风险（3 已修 / 6 未修）。

**逐项比对结果**：

| 我的发现 | 是否已被 c1 分支覆盖 |
|---|---|
| ① wallet-service Phase3 把一切非 SUCCESS 落 FAILED | ❌ **未覆盖**——该分支上 `DefaultWithdrawalApprovalService:346` 的 `if (onChainResult.isSuccess()) ... else FAILED` **原样未动** |
| ② javadoc 承诺的 `CompensationService` 在本模块不存在 | ❌ **未覆盖**——该分支上 `:262`/`:267` 两处承诺**原样未动** |
| ③ `AutoWithdrawScheduler` 把 6 种 `false` 原因全归为"跳过" | ❌ **未覆盖**——该分支上 `:118/:128/:138` 的 `skipCount` **原样未动** |

**反向也确认**：c1 分支修的 3 项（并发重复广播的条件 UPDATE 抢占、ShedLock + 总开关、
户名护栏生产路径）**我没有重报**——其中第 1 项在 master 上已存在（`:285-296`），
第 2、3 项是我没查的方向。**互不重叠。**

> 附带：该分支 §3 第 7 项「大额提现 signing 侧转人工审批后 wallet 置 FAILED」
> 与我的发现①**同源不同触发点**（那条是 2001/PENDING 语义，我这条是**异常/超时**）。
> 两者合起来说明：**这条代码路径上"未知"被当成"失败"至少有两个入口，而一个都没处理。**

---

## 二、我的 3 项发现（均可复核）

### 2.1 【P1】Phase 3 把一切非 SUCCESS 落成 FAILED

```java
// DefaultWithdrawalApprovalService.java:345-361
(e, onChainResult) -> {
    if (onChainResult.isSuccess()) { ... EXECUTED ... }
    else {
        e.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);   // :354
        e.setRejectionReason(onChainResult.getError());
        ...
    }
```

而 Phase 2 的异常被模板吞掉转成失败：

```java
// ThreePhaseExecutionTemplate.java:69-73
} catch (Exception e) {
    result = OnChainResult.failure("on-chain execution exception: " + e.getMessage(), false);
}
```

⇒ `signingServiceClient.signTransfer(...)`（`:335-338`）是**签名 + 广播**。
**广播已成功但响应丢失 ⇒ 异常 ⇒ 落 FAILED ⇒ 链上钱已出、记录说失败。**

**这是本仓第三处同源缺陷**（另两处：网关退款 `DefaultRefundApprovalService:364`，
已由 #168 修复；网关自动提现 `AutoWithdrawService:387-401`，已报 #167/#184）。

### 2.2 【P1】`CompensationService` 在本模块不存在

`DefaultWithdrawalApprovalService:262/:267` 与 `ThreePhaseExecutionTemplate:85`
**三处**写「由 CompensationService 异步补偿」。但：

- 全仓 `Compensation*` 类**只在 `nexus-gateway`**（9 个）；
- `nexus-wallet-service/build.gradle` **无任何 `project(...)` 依赖** ⇒ 独立 Boot 应用，引用不到；
- wallet-service 内对 `Compensat` 的引用**只有那 3 处注释**，无 import / 注入 / 调用。

⇒ **注释承诺了一个不存在也无法访问的安全网。** 读到的人会相信 FAILED 有自动兜底，
实际两种情形（链上没出款 / 已出款）都需人工。

### 2.3 【P2】`AutoWithdrawScheduler` 把 6 种 `false` 原因全归为"跳过"

`:124-129` 只做二分类（`executed` true/false），而 `false` 有 6 种原因，两种需人工：
`PAYOUT_FAILED_REFUNDED`(refunded=false) 与 `PAYOUT_DONE_FINALIZE_FAILED`。
后者下出款已成功、认领保留、`lastExecutedAt` 不推进 ⇒ **该商户的自动提现从此静默停止**，
运维只看到 `跳过=1`，与"金额为 0"无法区分。

**修法**（与 #168 完全一致，可直接照搬，且我已证明不破坏既有测试）：
`WithdrawalStatus` 已有 `EXECUTING`（Phase1 就置了）⇒ Phase3 **无需覆盖成 FAILED**：

- 确定性失败 ⇒ `FAILED`
- **抛异常（结果未知）⇒ 保留 `EXECUTING`** + 审计 + 显式日志，交对账/人工

---

## 三、给该分支负责人的两条实务提醒

1. **该分支已落后 master 90 个提交**（`compare` 结果：ahead 3 / behind 90）。
   rebase 时会遇到至少两处语义变化：
   - `39e19af8`（#168）已修网关退款侧的同类缺陷，**修法与上面 2.1 建议一致**，可作参照；
   - `f7852021`（PLAN-018）、`7d1a9e07`（PLAN-019/020）已落地最终性 outbox 与持久化区块 outbox。
2. `docs/money-outlet-boundary.md` §3 的风险表**建议同步补 3 行**（本文 §2.1/2.2/2.3），
   并在 §4 检查清单加一条：「出款路径是否区分了'确定性失败'与'结果未知'？」——
   这是三处同源缺陷的共同根因，**不加的话第四条资金路径大概率还会踩**。

## 边界

**只读**：未建分支、未改任何代码、未开 PR、未在他人 PR 下评论。
**未验**：`DefaultCustodyService` 余额扣减、`DefaultApprovalPolicy` 阈值、
`compareAndSetStatus` 的 `@Query` 原文、`HttpOnChainExecutionClient` 是否被本链路使用。

完整报告在本机 `audit/2026-10-23-walletsvc-withdrawal-audit.md`，含逐条 `文件:行号`。
