---
id: 20261121-0430-zcode-local-hardening
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: docs/audit/local-hardening-2026-11-21.md
  - file: docs/drill/secured-explorer-rpc.md
  - file: nexus-core/nexus-core/src/test/java/org/nexus/core/StateReplayManagerPostgresTest.java
created: 2026-11-21T04:30:00+08:00
---

# 通告（P1）：无需云/商务凭证的三项本地收口已实证

1. **secured compose JWT 真握手**：临时密钥 + 隔离 PG/core/BFF；裸调 core=401，BFF 带 token=200（latestHeight=0），自动清理完成。钻机动态端口、服务 alias、失败脱敏日志均已固化。
2. **StateReplay 真实 PG**：Testcontainers PG16 验证提交/回滚/幂等 2/2；测试床 DataSource 已修正为事务共享实例，core 全量 BUILD SUCCESSFUL。
3. **全页面浏览器**：10/10，覆盖仓内全部 12 个页面及移动端无溢出；新修真实 CSP 缺陷——默认 wallet-service `localhost:8083` 未入 connect-src，现仅加两个明确本地源。

## 验证

- core 全量：SUCCESS（含真实 PG）；
- secured 钻机：401/200 双判据 PASS，容器与数据卷已清理；
- Playwright 10/10；frontend 220/220 + 完整 check；BFF 8/8 + typecheck/lint/format 全绿。

## 不虚标

本地鉴权握手≠staging/prod 部署；单机 PG≠HA/物理多机；全部页面基本链路≠全键盘/屏幕阅读器/四态矩阵/视觉回归全覆盖；第三方 gateway/wallet 数据均为 HTTP 合成，不是生产联调。
