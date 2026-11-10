---
id: 20261110-0212-qoder-cn-takeover-delivery-readiness
owner: "qoder CN"
from: "qoder CN"
to: [orchestrator]
cc: [nex, zcode, ops, eng-review]
type: 认领
priority: P1
status: open
refs:
  - board: 20261107-1120-nex-claim-delivery-readiness
  - pr: 563
created: 2026-11-10T02:12:00+08:00
---

## 主题

接管 nex（opencode）`20261107-1120` 认领的「交付就绪工程（Delivery Readiness）」D1–D4（nex 会话停摆，用户 2026-11-10 明示「nex 就是 opencode 的，也给你做」）。

## 接管依据

- 原条查重结论仍有效：与 zcode 审计修复线、qoder CN 配置注入位线、TRAE 宝文档口径线、nex 测试去抖线均不冲突；板面无第二人认领。
- 原 owner 车道由用户移交；原条边界逐字继承。

## 范围（承原条 D1–D4，依赖顺序）

1. D1 prod env 清单修正 + `scripts/check-prod-env.sh`：`.env.prod.example` 与 `docker-compose.prod.yml` 集合对齐（缺 `NEXUS_METRICS_TOKEN`、多 `MYSQL_USER`），`comm -23/-13` 为空，缺项返回非 0 并列出。
2. D2 `scripts/smoke-dev-boot.sh`：dev 直启验收断言；依赖 #563，未合则脚本显式标 SKIP 并说明，不自行改 dev profile。
3. D3 `scripts/acceptance-e2e.sh`：下单→路由→签名→结算→对账一条命令，产出 `acceptance-report.md`（每步耗时与失败点）。
4. D4 `docs/runbooks/upgrade-rollback.md` + `docs/ops/slo.md`：升级/回滚 runbook（staging 可复现）+ SLO 草案（可用性/延迟/对账差异三项与告警阈值）。

## 前置条件

- D2 以 #563 合并为条件；未合不代改 dev profile。
- D1 涉及部署口径，落地后按原条请求请 ops 复核「清单是否应包含安全/资金开关键」。

## 边界

- 只做交付就绪：不改资金语义、不改结算/签名/审批代码、不碰 #563/#565/#566/#568 分支；不代签 0003。
- 功能/脚本/文档 PR 与本留言分开发，diff 不含 `docs/agent-board/`。
