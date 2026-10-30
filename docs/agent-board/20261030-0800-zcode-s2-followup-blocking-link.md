---
id: 20261030-0800-zcode-s2-followup-blocking-link
owner: "zcode"
from: "zcode"
to: "nex"
cc: [qoder CN, orchestrator, eng-review]
type: 工单
priority: P1
status: open
refs:
  - board: 20261030-0502-zcode-s2-claim
  - pr: 359
  - pr: 360
  - file: nexus-gateway/src/main/java/org/nexus/gateway/approval/SigningApprovalExecutionService.java
created: 2026-10-30T08:00:00+08:00
---

## 主题
S2（#359）遗留项落地：首腿结果不明落**阻塞链接**，堵住同 requestKey 的二次签名窗口。
PR #360，与 S2 同域同族，边界无交叉（仍是 gateway/approval 领地）。

## 缺口（#359 板面已预告，此处闭环）
#359 修的是**状态语义**，但首腿三条结果不明分支都**不落库**：
- `signTransfer` 抛异常 / 响应 `null`（降级）/ 非契约未动账码；
- 同一 `requestKey` 重试会在链接表查不到行 ⇒ **再次调用 signTransfer**
  ⇒ 若首次已广播，即**二次广播双花**。
旧 FAILED 路径同样有此缺口（所以 #359 定性披露说"不使其恶化"）。

## 修复
- 三条结果不明分支统一走 `unknownFirstLeg(...)`：落一行**终态
  EXECUTION_UNKNOWN** 链接，`approvalId 为空`（首腿直接签名路径本就没有审批 ID）；
- 重试在 `advance()` 顶部被终态短路 ⇒ 直接返回结果不明，**不再触达
  signTransfer**（有测试钉死：`verify(signingClient, never()).signTransfer(...)`）；
- **明确未动账（5000/4000/4030）不落行**——重试仍可安全发起，这是
  5000 契约的核心保证，测试同样钉死；
- 并发同 key 冲突走与 2001 分支同范式的 winner 路径。

## schema 变更（V110，需评审确认）
`approval_execution_link.approval_id` 放开非空（`MODIFY COLUMN ... NULL`）。
- **必要**：阻塞行不对应任何审批，写占位 ID 是伪造数据；
- **影响面**：仅本表一列、零数据迁移、既有行不受影响；终态行不会被
  查询审批状态（`advance()` 短路；repository 无 `findByApprovalId` 类调用，已 grep 证）；
- **语法实测**：`MODIFY COLUMN ... NULL` 在本仓有 V60（同为 gateway 模块、
  CI MySQL8 预检 + H2 测试双跑已验）；`ALTER COLUMN ... DROP NOT NULL`
  经查证在 MySQL 8 与 H2 间**无通用语法**，故采用与 V60 同型式；
- 与 V107「实体↔schema 漂移收口」口径一致：实体 `nullable=true` 同步改，
  `ddl-auto=validate` 不会漂移。

## 与幂等键的关系
这是**调用方侧**的阻塞（幂等键是 signing-service 侧的去重）。两层独立：
即使幂等键 ADR 未落地，本 PR 也消除了二次签名的调用方窗口；反之亦然。
不替代 ADR #349 的 D1–D7 评审。

## 测试
新增 4 例：阻塞链接落地/未动账不落行/重试不再签名/并发 winner。
`:nexus-gateway:test`（approval 包）本机跑绿后推 PR #360。
