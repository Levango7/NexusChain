---
id: 20261106-2227-nex-close-cross-tenant-idor
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, "qoder CN"]
type: 交接
priority: P2
status: open
refs:
  - board: 20261025-0545-nex-merchant-api-cross-tenant-idor
  - pr: 265
  - pr: 276
  - pr: 211
  - file: nexus-gateway/src/main/java/org/nexus/gateway/audit/AuditEventController.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/reconciliation/link/ReconciliationLinkController.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/config/WebConfig.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/reconciliation/DiscrepancyResolutionController.java
created: 2026-11-06T22:27:00+08:00
---

## 主题

`20261025-0545-nex-merchant-api-cross-tenant-idor`（商户侧资金类 API 跨商户越权）按当前 master 状态收口。

## 事实（可复现）

在 `origin/master` = `8667f138` 上复核，原登记中的“未修 / 不能机械修”三处均已收口：

1. `AuditEventController`
   - 四个查询端点全部以调用方商户为准：`requireMerchantId` + `requireOwned`（`:65-121`），
     `/action`、`/window`、`GET` 不再返回全平台数据；
   - 类级 `@PreAuthorize` 已移除，避免商户 API Key 调用方恒 403（`:30-49`）；
   - 跨商户“全平台审计视图”仍未提供，注释明确需要 WebConfig 配置变更（`:47-49`）。
2. `ReconciliationLinkController`
   - 类级 `@PreAuthorize("hasRole('ADMIN')")`（`:30-40`）；
   - 路径保持在 `ApiKeyInterceptor` 之外，读端点不做商户归属比对，与原登记中
     “必须先决定角色策略”的口径一致。
3. `WebConfig`
   - 管理动作路径通过 `excludePathPatterns` 显式放行到 JWT 链（`:99-129`）；
   - `/api/reconciliation/**` 已纳入限流（`:84-89`），对齐“所有 API 路径限流”的设计意图。

另：原登记中的 B 形态（资源号）写操作，`DiscrepancyResolutionController`
也已有归属校验（`:64`、`:87`、`:145`），不再是“按 ID 直改他人记录”的形状。

相关合并记录：#265 `46e43c17`、#276 `bf2c8d02`、#211 `a98f8a6f`。

## 请求

无需动作；本条只作旧登记收口。

## 边界

1. 仅复核当前 master 代码与已有提交，未重跑测试、未做本地或 CI 之外的验证；
2. 未修改任何功能代码、配置或部署文件；
3. 未触碰他人在途分支 / PR；未对 WebConfig 的后续演进作任何裁决；
4. 不改变原登记中已经完成的部分（#222 等先期修复）的历史事实。
