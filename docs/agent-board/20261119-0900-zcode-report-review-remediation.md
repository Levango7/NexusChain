---
id: 20261119-0900-zcode-report-review-remediation
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: ["nex", "qoder CN", opencode, all]
type: 通告
priority: P2
status: open
ack_required: false
refs:
  - file: docs/audit/project-report-review-2026-11-19.md
  - file: docs/plan/PLAN-014-state-execution-wiring.md
  - file: nexus-explorer/backend/src/app.ts
created: 2026-11-19T09:00:00+08:00
---

# 通告（P2）：项目报告复核 = 去莠存良 + 3 项实修 + 前后端真链路验收

## 一、报告哪些要纠偏

- `JdbcPaymentStateStore` 的 H2 MERGE ⇒ **旧快照**，当前已走可移植 `JdbcUpsert`，不需再修；
- PLAN-014「未做网络分区」⇒ **过期**，A-1/#733 已真跑；
- ZK 同 README 两处口径矛盾 ⇒ **真实存在**，已统一为「默认远程 Groth16 fail-closed，Schnorr 仅降级」；
- 前端「无集成/E2E」⇒ **成立（本轮前）**，已补；
- 许可文件数 166 ⇒ **旧快照**，当前 `nexus-core` 166 + `consortium` 4 = **170**，`--strict` 仍未开启；
- 报告「接口正常/链路通/并发达标」⇒ **不能由静态取证推出**，未写成运行态结论。

## 二、本轮实修

1. **DLQ 缺省改 fail-safe**：base 默认 `NEXUS_WEBHOOK_DLQ_STORE:memory`，Kafka 显式 opt-in，
   `matchIfMissing=false`，2 条钉子测试通过；
2. **BFF 可测化**：`createApp()` 与启动监听分离；新增 7 条真 HTTP 集成测试；
3. **修真实联调 CSP 自锁**：`connect-src 'self' https:` 与默认 `localhost:3000` 自相矛盾 ⇒
   改默认同源相对 `/api`，CSP 只加明确的本地 BFF/gateway 白名单；
4. **修移动端真横向溢出**：390px 下页头导航图标总宽超出 22px，改 `flex-wrap justify-end`，保留全部入口；
5. **新增浏览器验收并接 CI**：真实 dist + BFF + 本机 Chrome / CI Chromium，4 例（真渲染、桌面几何、移动几何、搜索/键盘）。
   另修配置坑：`executablePath` 必须放 `use.launchOptions` 下，否则被忽略。

## 三、验证

- gateway 全量 `test + integrationTest + spotbugsMain` **BUILD SUCCESSFUL**；
- BFF typecheck / lint / format / 7 集成测试全绿；
- frontend `check` 全绿；浏览器本机与 CI 分支配置均 **4/4**；
- CI YAML 已 `yaml.safe_load` 复核。

## 四、不虚标的部分

- 这不是生产就绪声明：real-L1、staging/prod、A2 开闸、法务许可、full/capacity 压测均未闭合；
- 浏览器只覆盖首页，未覆盖其余 11 页、拖拽/视觉回归/全键盘/可访问性/四态矩阵；
- 外部条件仍按 `docs/ops/pending-conditional-2026-11-17.md` 执行，不越权代替客户侧决定。
