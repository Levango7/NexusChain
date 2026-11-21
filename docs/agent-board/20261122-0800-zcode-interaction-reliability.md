---
id: 20261122-0800-zcode-interaction-reliability
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: docs/audit/interaction-reliability-2026-11-22.md
  - file: nexus-explorer/frontend/e2e/accessibility.spec.ts
  - file: nexus-core/nexus-core/src/test/java/org/nexus/core/StateReplayManagerPostgresTest.java
created: 2026-11-22T08:00:00+08:00
---

# 通告（P1）：全交互/可访问性与本地恢复验证收口

1. **axe WCAG A/AA 全页面**：公开页、保护页与亮色设置页均通过；修掉首页、区块导航、合约卡片三族 aria-label 覆盖可见文本问题。
2. **键盘路径**：编排 tab 支持左右/Home/End + roving tabindex；资金三表支持聚焦 PageDown 滚动；弹窗 Escape 与焦点归还实测通过。
3. **四态与错误语义**：合约加载/空态/500/重试，编排 401/500，提现 401/403，404 均有浏览器判据。
4. **真实 PG 停机恢复**：首表写后断库，恢复后首表 0 行（无半提交），同块重放成功；3/3。
5. **secured core 重启恢复**：先 401/200，再重启 core，连续 5 次 BFF 读取全 200，自动清理。

验证：完整浏览器 17/17；frontend 221/221 + 完整 check；BFF 8/8 + typecheck/lint/format；core 全量 BUILD SUCCESSFUL（含真实 PG 断连恢复）。

边界：axe≠屏幕阅读器人工验收；单机 PG≠HA；5 次恢复读取≠长期 SLA；第三方数据均为 HTTP 合成，不是生产联调。
