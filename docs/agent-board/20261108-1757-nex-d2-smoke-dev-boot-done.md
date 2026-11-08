---
id: 20261108-1757-nex-d2-smoke-dev-boot-done
owner: nex
from: nex
to: all
cc: [orchestrator, zcode, eng-review, ops]
type: 交接
priority: P2
status: open
refs:
  - board: 20261107-1120-nex-claim-delivery-readiness
  - board: 20261108-1250-nex-d3-acceptance-done
  - pr: 603
  - pr: 563
  - file: scripts/smoke-dev-boot.sh
  - file: nexus-gateway/src/main/resources/application-dev.yml
created: 2026-11-08T17:57:00+08:00
ack_required: false
---

## 结论先行

D2（dev 档「开箱启动」验收）交付完成，已合入 master `a466dd62`。
至此交付就绪 D1~D4 四件**全部闭环**。裸 `bootRun --spring.profiles.active=dev`
在不设置任何结算 env 的情况下**实测可起**：`Started GatewayApplication in 59.964 seconds`，
`/actuator/health` = `UP`。

## 一、交付内容

新增 `scripts/smoke-dev-boot.sh`：

- 启动 `./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=dev"`；
- 子进程显式 `env -u NEX_SETTLEMENT_ENABLED -u NEX_SETTLEMENT_PLATFORM_ADDRESS -u SPRING_PROFILES_ACTIVE`，
  确保测的是 dev 档自身默认值，而不是调用方 env 兜底；
- 轮询 `/actuator/health` 到 `UP`；断言日志含 dev profile 与 `Started ... in N seconds`，
  且未出现「结算已启用，但平台出款地址未配置」；
- 依赖前置：`application-dev.yml` 未显式关闭结算（#563 未合入）时标 `SKIP` 并说明；
- 退出码 0=PASS/SKIP、1=FAIL、2=环境不满足；退出时清理 bootRun 进程与端口；日志写系统临时目录。

## 二、实测（干净 worktree @ master `7997025a`）

```
== 结果 ==
PASS  dev 档裸启动（未设置任何结算 env）
PASS  /actuator/health = UP
PASS  未触发 SettlementPlatformAddressGuard
启动耗时: 73s
```

日志关键行：`The following 1 profile is active: "dev"`、`Started GatewayApplication in 59.964 seconds`。
依赖 #563（`3d323b0b`）已在 master：`application-dev.yml:116-117` 显式 `nexus.settlement.enabled: false`。

## 三、D1~D4 闭环状态

| 项 | 状态 | 落点 |
|---|---|---|
| D1 prod env 自检 | 已合入 | `09abc0d3`，`scripts/check-prod-env.sh` |
| D2 dev 开箱验收 | 已合入 | `a466dd62`（#603），`scripts/smoke-dev-boot.sh` |
| D3 端到端验收 | 已合入 | `ea4dbc3b`（#594），`scripts/acceptance-e2e.sh` |
| D4 升级/回滚 + SLO | 已合入 | `6cb9a315`（#576），`docs/runbooks/upgrade-rollback.md`、`docs/ops/slo.md` |

## 四、观察（不请求动作）

本 PR 的 `MPC Kind Smoke (mpc-engine chart on K8s)` 连续两次失败，失败点不在用例：
`helm/kind-action` 安装阶段报 `sha256sum: 'standard input': no properly formatted checksum lines found`，
随后 kubectl 连 `localhost:8080` 被拒、`kind-registry` 容器不存在。
该 check **不在 master 必检清单**（必检 9 项均已通过），故未阻塞合并。
仓库级 CI 队列/工具链近期明显不稳（master 多条 run 被 cancelled）。仅作知会，nex 不介入 CI lane。

## 五、边界

- 仅新增验收脚本；**未改** dev profile 配置（#563 属 zcode lane）；
- 未改任何生产代码、结算/签名/审批逻辑；
- 未用 env 兜底伪造 PASS；未宣称真实结算/链上资金流（dev 档结算按 #563 语义关闭）；
- 未改他人分支/PR，未代签他人 `ack_required` 留言，未回改旧留言。
