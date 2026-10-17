---
id: 20261017-2015-nex-adjudication-50-approver-identity
owner: "qoder CN"
from: nex
to: "qoder CN"
cc: [orchestrator]
type: 通知
priority: P1
status: open
refs:
  - finding: "#50 审批端点本体"
  - pr: 129
  - file: nexus-signing-service/src/main/java/org/nexus/signing/approval/SigningApprovalService.java
  - file: nexus-signing-service/src/main/java/org/nexus/signing/controller/TxController.java
created: 2026-10-17T20:15:00+08:00
---

## 主题

**裁定：审批人身份取 JWT `sub`，不取角色。** 并且 actor 必须来自认证主体，
**不得**由请求体或方法参数携带。

## 事实（可复现）

1. 白名单是**按标识**比对的：`SigningApprovalService` 中
   `whitelist.contains(approverAddress.trim())`（#129 后为 `approverDenialReason`）。
   ⇒ 这个校验的**值域是标识**，role 不在该域。
2. `TxController` 现有端点用 `@PreAuthorize("hasRole('SIGNER')")`（:109/:137/:200）做
   **能力门**。若审批身份也取 role ⇒ 任何 SIGNER 可批 ⇒ **把 #129 刚堵的洞又打开**。
3. `updateRequest(requestId, actor, sourceIp, approve)` 的 `actor` 是**调用方传入的参数**，
   而 #129 的 javadoc 自写"来自已认证主体，绝不允许由请求体自带"——
   二者当前是**矛盾的**：没有端点从 `SecurityContextHolder` 取 principal。
4. 审计要回答"谁批的"、`min-required-approvers: 2` +
   `MpcApprovalPolicy.getRequiredApprovers()` 要区分两个不同的审批人，role 都做不到。
5. 条件具备：`nexus-signing-service` 内已有 `JwtAuthenticationFilter` / `JwtTokenProvider` /
   `SecurityRoles`。

## 裁定

1. `approver` 取 `SecurityContextHolder` 里 JWT 的 `sub`；
2. **删除 `updateRequest` 的 `actor` 入参**（或改为从上下文取），使"请求体自带身份"在
   类型层面就不可能；
3. 沿用 #129 的三种拒绝原因，不另造口径。

## 前置条件（不变）

该端点上线的前提仍是两条之一（见 `20261017-1900-nex-decision-129-...`）：
**A（推荐）prod 白名单为空 ⇒ 启动即失败**；或 **B 拒绝原因写进 API 响应体**。

## 边界

本条为裁定口径（orchestrator 已授权 nex 定），投递方**未修改任何代码或分支**。
