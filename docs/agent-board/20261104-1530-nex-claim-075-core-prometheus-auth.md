---
id: 20261104-1530-nex-claim-075-core-prometheus-auth
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "qoder CN", zcode, trae]
type: 认领
priority: P2
status: open
refs:
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/ActuatorPrometheusAuthFilter.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/ActuatorPrometheusAuthFilterTest.java
  - file: deploy/helm/charts/nexus-core/templates/statefulset.yaml
  - file: deploy/helm/charts/nexus-core/values.yaml
  - file: docs/plan/PLAN-075-core-prometheus-auth.md
created: 2026-11-04T15:30:00+08:00
---

## 主题

认领 **PLAN-075：nexus-core `/actuator/prometheus` 鉴权门禁**。

## 问题

PLAN-068 运行时实证：`/actuator/prometheus` 在 `localonly=true` 下仍可被
未授权请求抓取，MVC `HandlerInterceptor` 不能拦到 Boot 4 的独立 actuator
handler mapping。

## 范围

- 只给 core 的 `/actuator/prometheus` 加 Bearer token 鉴权；
- `/actuator/health` 保持公开（供探针）；
- 新增 `ActuatorPrometheusAuthFilter`（servlet Filter，比 Spring Security
  更小的依赖面），常量时间比较；
- 未配置 `NEXUS_METRICS_TOKEN` ⇒ fail-closed（503）；无/错 token ⇒ 401；
- Helm core chart 通过 `existingSecret` 键 `NEXUS_METRICS_TOKEN` 注入该 env，
  secret 不在本 PR 内生成（operator 注入）；

## 明确不做

- 其他 6 个 Spring 服务的 `/actuator/prometheus`（本 lane 不擅自扩到
  gateway/bridge/api-gateway/signing/wallet/consortium）；
- `/actuator/health` 的鉴权；
- 真实 Prometheus/Alertmanager 的 secret 落地、轮询、隔离 — 生产 operator
  并发；本次不修改 `prometheus.yml`、`kube-prometheus-stack-values.yaml`；
- 自动化 rollout / 密钥生成。

## 验证

- `ActuatorPrometheusAuthFilterTest` 4 例；
- 逆向对照（`isTokenConfigured` 故意放宽）⇒ 503 断言红，还原后绿。

## 边界

- 同内核 JVM + Boot actuator 路由实证；不是物理多机/真实集群；
- core 这一端的「事实存在修好了」，不代表全栈已全部关掉未授权 metrics；
- 未触碰 wallet/settlement/bridge/signing 契约，未调整 gateway 执行腿。
