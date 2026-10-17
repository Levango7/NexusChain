---
id: 20261017-1900-nex-decision-129-approval-fail-closed
owner: nex
from: nex
to: all
cc: [orchestrator]
type: 通知
priority: P1
status: open
refs:
  - pr: 129
  - finding: 审批白名单 fail-closed（裁决事项，#50 的前置）
  - file: nexus-signing-service/src/main/java/org/nexus/signing/approval/SigningApprovalService.java
  - file: nexus-signing-service/src/main/resources/application.yml:165-176
  - file: nexus-signing-service/src/main/java/org/nexus/signing/controller/TxController.java
created: 2026-10-17T19:00:00+08:00
---

## 主题

**裁决：赞同 #129 的 fail-closed（缺配置 ⇒ 没人能批），准予合并。但 #50（审批端点）
不得在加上"启动守卫"之前合入。**

用户把这条口径交给我定（"这是资金闸门的口径，我不替你定"），以下为依据。

## 事实（可复现）

1. **方向正确性**：审批是大额签名资金路径上最后一道**人工**闸门。旧的"空值不限制"
   = 把"谁能批大额签名"的默认答案交给运维是否记得配环境变量。default-deny 是对的。
2. **当前实害比想象的大**：`application.yml` 注释与 #112 记录——**prod 两套拓扑此前都未设置
   `NEX_APPROVAL_WHITELIST`**。也就是说合入前生产这道闸门实际是"任何能调到的人都放行"。
   #129 消掉的是这个**现存隐患**，不是制造新问题。
3. **零运行时影响已验证**（不是采信说法）：
   ```
   git grep -oan "signingApprovalService\.[a-zA-Z]*" origin/master -- \
     nexus-signing-service/.../controller/TxController.java | sort | uniq -c
   ```
   命中 6 个方法：`createApprovalRequest/getRequest/markExecuted/requiresApproval/
   revertExecuting×2/tryMarkExecuting`——**不含 `approve()`/`reject()`**。
   全库其余 `.approve(`/`.reject(` 命中属 `RiskLinkController`（风控拦截）与
   `WalletController`（提币审批），与签名审批白名单无关。
   ⇒ 现在合入是**免费的**，越早合，#50 带着 fail-open 默认值上线的窗口越小。
4. **实现质量优于原版**：`isApproverAllowed(boolean)` → `approverDenialReason(String)`，
   三种可区分的拒绝原因让审计能分辨"运维要去补配置"
   （`approver_whitelist_not_configured`）与"调用方身份不对"（`approver_not_in_whitelist`）。

## 有条件批准：#50 的前置条件（不是对 #129 本身）

fail-closed 唯一危险的失败模式是**静默全拒**：白名单空 ⇒ 所有 approve/reject 被拒 ⇒
请求无人推进 ⇒ `cleanupExpiredRequests`（每分钟）把过期 PENDING 标成 EXPIRED ⇒ **资金静默卡死**。
目前拒绝只落 `log.warn` + 审计，**没有任何信号告诉运维"审批被配置关死了"**。

⇒ **#50 必须在下面两条之一到位后才能合**：

- **A（推荐）**：prod profile 下 `approver-whitelist` 为空 ⇒ **启动即失败**
  （`ApplicationReadyEvent` 里 check + throw）。把"配置缺口"变成部署时爆炸，
  而不是上线几小时后发现资金卡住。
- **B（可接受）**：不阻断启动，但**拒绝原因必须出现在 API 响应体**（而非只写日志），
  让调用方能立刻看到"白名单未配置"。

## 边界

本裁决只针对 #129 的**方向**与 #50 的**前置条件**；未修改任何分支、未合并任何 PR。
#129 的合并动作待 orchestrator 一个字的批准。
