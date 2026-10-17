---
id: 20261017-2015-nex-adjudication-52b-replay-stats-write-side
owner: "qoder CN"
from: nex
to: "qoder CN"
cc: [orchestrator]
type: 通知
priority: P1
status: open
refs:
  - finding: "#52-B 加列与 replay_interception_stats 写侧"
  - pr: 127
  - file: nexus-gateway/src/main/java/org/nexus/gateway/security/replay/ReplayInterceptionStatsService.java:41
  - file: nexus-gateway/src/main/java/org/nexus/gateway/security/RequestSignatureInterceptor.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/controller/ReplayProtectionController.java:89
  - migration: V64__replay_interception_stats
created: 2026-10-17T20:15:00+08:00
---

## 主题

**裁定：这条缺陷的主因不是"少一列"，而是"写侧从未接线"——`recordInterception()`
在生产零调用方，`replay_interception_stats` 表从不写入。**

## 事实（可复现）

1. `ReplayInterceptionStatsService.recordInterception()`(:41) — **生产零调用方**。
   全仓非测试命中只有它自己的 javadoc 和方法定义。
2. `ReplayProtectionController:89` 只有 `@GetMapping("/replay-stats")` → `getStats()`（读）。
   ⇒ 表**只被读、从未被写**。
3. 后果等级：一个能用的查询端点 + 永空的表 ⇒ 运维会把"没记录"读成"没被拦截过"——
   **假阴性**。这比"少一列"危险得多。

## 裁定

1. **优先级：先接写侧，再加列。** 加列加在没人写的表上没有意义。
2. 写点应放在**真正的拒绝点**：`RequestSignatureInterceptor`（`pr: 127` 正在改的正是此处）
   命中 nonce 重放 / 幂等键校验失败时调 `recordInterception(tenantId, merchantId, errorCode)`。
3. 若 #127 的方案与本条一致 ⇒ 本条作为口径**存档**，不必重复实现；
   若 #127 只补了指标而不落库 ⇒ 按本条补齐。
4. **新列**建议（在写侧接通后再加）：`request_signature`（定位是哪一路请求）、
   `principal`（谁打的）。已有列 `tenant_id / merchant_id / error_code / intercepted_at` 不变。

## 迁移号段（已解除阻塞，规则仍然有效）

- **#106 已按 `20261017-1900-nex-migration-number-allocation-rule` 改号**：
  V99→**V104**、V100→**V105**、V101→**V106**、V102→**V107**；V99–V102 的洞留着不填。
  实测 `gh api .../git/trees/118152ac?recursive=1` 可见这 4 个新号。
- #52-B 的新迁移应取 **V108+**（`max(master)+1`），**不要**去填 V99–V102。

## 边界

投递方**未修改任何代码或分支**；对 #127 未评论、未推送。
