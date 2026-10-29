---
id: 20261030-0108-nex-done-settlement-s1
owner: nex
from: "nex"
to: founder
cc: [zcode, all]
type: 交接
priority: P2
status: done
refs:
  - pr: 347
  - board: 20261029-2057-nex-ruling-settlement-booking-order
  - board: 20261029-2352-nex-addendum-settlement-s1-scope
  - board: 20261029-2345-zcode-lane-redivision
  - file: nexus-settlement/src/main/java/org/nexus/settlement/clearing/DefaultClearingEngine.java
  - file: nexus-settlement/src/main/java/org/nexus/settlement/execution/OnChainExecutionChannel.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/execution/DefaultOnChainExecutionChannel.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/service/OrchestrationService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/service/OrderServiceImpl.java
created: 2026-10-30T01:08:00+08:00
---

## 主题

S1 完成：结算收款地址预检前移（记账之前 fail-closed）。功能 PR #347 已开，squash auto-merge 已挂，待 CI 通过即合入 master。

## 事实（可复现）

变更提交于 `feat/settlement-payee-address-s1` @ `fe058037`（9 files changed, +274/-3），PR #347（base master）。本地 Gradle 已跑通 4 个测试类：BUILD SUCCESSFUL。

行为变更：

- `DefaultClearingEngine.settle()`：把 `canTransferTo(payeeAddress)` 预检**移到 `bookSettlement` 之前**；地址不可用则记 `FAILED` 并落库、**不写 `ledger_entry`**；`toAddress` 改用真实 `order.getPayeeAddress()`（不再用 `"MERCHANT:" + merchantId` 合成名，见板 20261029-2057 §F3）。
- `OnChainExecutionChannel`：新增 `default boolean canTransferTo(toAddress)`（默认非空校验，保二进制兼容）。
- `DefaultOnChainExecutionChannel`：覆写 `canTransferTo`，调用 `WalletUtils.addressToPubkeyHash` 做真实可解析性校验（与 `executeProduction` 第 1 步同源）。
- `OrchestrationService:476`：发布 `PaymentCompletedEvent` 时由 `null, null` 改为 `p.getPayerAddress(), p.getPayeeAddress()`，修复编排链路 `clearing_order.payee_address` 恒为 NULL（板 20261029-2352 §F1）。
- `OrderServiceImpl:247`：商户缺失时日志 `warn` → `error`（占位符因 `payment_orders.payee_address` NOT NULL 保留，结算将 fail-closed）。

测试（本地跑通）：

- 新增 `ClearingEnginePayeeAddressS1Test`：钉死 `null` / 空白 / 通道拒收 三种 fail-closed 不记账；地址可用时仍先记账再动链，且 `payeeAddress` 进入 `TransactionRequest.toAddress`。
- `DefaultOnChainExecutionChannelTest`：新增 `canTransferTo` 三类用例（`null`/空白、非法字符、真实 `pubkeyHash` 往返地址）。
- 既有 `ClearingEngineAwaitingApprovalTest` / `ClearingEngineBatchIsolationTest` 补设 `payeeAddress` 以适配预检。

## 请求

无需动作（知会）。S2（签名三态拆分）、S3（冲正 + 对账 LOCAL_ONLY 指标化）属同一 lane，待 #347 合并后推进。

## 边界

- 仅改动 `nexus-settlement` / `nexus-gateway` 内结算相关源码与测试；未触碰 zcode lane 声明不碰的 signing/approval/fallback/wallet 源码（板 20261029-2345 §共享文件规则）。
- 未修改他人分支 / PR，未替他人评论；留言板改动经独立 PR 投递（即本文件所在 PR），不夹带进功能 PR #347（板 README §4）。
- 法币结算 `payee` 可为 null 的处置策略**未在本 PR 决定**（见板 20261029-2352 §F1），交由编排 / 创始人决策。
