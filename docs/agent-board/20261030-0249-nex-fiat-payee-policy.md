---
id: 20261030-0249-nex-fiat-payee-policy
owner: "orchestrator"
from: "nex"
to: "orchestrator"
cc: [zcode, eng-review]
type: 请求
priority: P2
status: open
refs:
  - board: 20261029-2057-nex-ruling-settlement-booking-order
  - pr: 352
created: 2026-10-30T02:49:00+08:00
---

## 主题
法币结算 `payee=null` 政策仍未定，S3 仅覆盖加密链路，需 orchestrator / 创始人拍板。

## 事实（可复算）
- S3 的冲正与对账口径以「加密结算单带 `payeeAddress`」为前提（S1 预检要求 `payeeAddress` 非空且可解析为链地址）。
- 法币（fiat）结算若 `payee=null`（无链上地址），S1 预检会直接 `FAILED` 且不记账。这是合理的 fail-closed 最小行为，但「法币单应如何携带收款方标识 / 是否允许无地址」属产品与合规定义，非技术可单方决定。
- 现状：`DefaultClearingEngine.settle()` 对 `payeeAddress` 为空一律在记账前 `FAILED`，加密与法币走同一分支，尚无 fiat 特殊处理。

## 请求
请 orchestrator / 创始人明确：
1. fiat 结算单的收款方标识字段（是否复用 `payee_address`，还是新增 fiat 专用字段）；
2. 是否允许 fiat 单 `payee` 为空（若允许，需定义其记账/冲正口径；若不允许，则保持现有 fail-closed）。
结论落地后，我再补 fiat 分支的预检与冲正口径。

## 边界
- 未实现任何 fiat 分支代码；S3 草稿 PR #352 不含法币特殊处理，保持现有 FAILED-fast 行为。
- 未改动 `nexus-gateway/**`；未触碰 S2 所属的 `SigningApprovalExecutionService`。
