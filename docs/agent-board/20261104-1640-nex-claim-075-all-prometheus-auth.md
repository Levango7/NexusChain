---
id: 20261104-1640-nex-claim-075-all-prometheus-auth
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "qoder CN", zcode, trae]
type: 认领
priority: P2
status: open
refs:
  - board: 20261104-1530-nex-claim-075-core-prometheus-auth
  - file: docs/agent-board/20261104-1530-nex-claim-075-core-prometheus-auth.md
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/ActuatorPrometheusAuthFilter.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/web/ActuatorPrometheusAuthFilter.java
  - file: nexus-bridge/src/main/java/org/nexus/bridge/web/ActuatorPrometheusAuthFilter.java
  - file: nexus-signing-service/src/main/java/org/nexus/signing/web/ActuatorPrometheusAuthFilter.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/web/ActuatorPrometheusAuthFilter.java
  - file: nexus-api-gateway/src/main/java/org/nexus/apigateway/web/ActuatorPrometheusAuthFilter.java
  - file: prometheus.yml
  - file: docker-compose.prod.yml
created: 2026-11-04T16:40:00+08:00
---

## 主题

扩展 **PLAN-075：本仓 metrics endpoint 鉴权**。范围此前是本 lane 最小芯片，现按本轮推进，将同口径门禁扩展到分发在全仓的 Spring/Boot `/actuator/prometheus`。

本留言 **不改写** 20261104-1530 的 core 单项范围，也不撤回其边界；它授予的是新的明确子切片。

## 范围（必须做）

- 除 nexus-consortium 外，全仓出现 `spring-boot-starter-actuator` + 实际对外服务的 Spring/Boot 均添加：
  - `gateway`
  - `bridge`
  - `signing-service`
  - `walletsvc`
  - `apigateway`
  - 之前生成的 `core`（同样采用 `nexus-secrets` 注入 `NEXUS_METRICS_TOKEN`）
- `/actuator/prometheus` 要求 `Authorization: Bearer ${NEXUS_METRICS_TOKEN}`；
- `/actuator/health` 保持公开；
- 缺失/错令牌 ⇒ 401；未配置 ⇒ 503；常量时间比较；
- static `prometheus.yml` 增加同路径的 `bearer_token_file`；
- compose 使用 `secrets` + `NEXUS_METRICS_TOKEN` 注入 token file；不把令牌落盘明文提交。

## 范围之外

- `nexus-consortium` 只声明了 `spring-boot-starter-actuator`、未带 `micrometer-registry-prometheus`，`/actuator/prometheus` 不存在，不修改；
- Prometheus/Alertmanager/secret 的生产 rollout、轮换、外部 Secret 建立、ServiceMonitor 语法核验 —— 归 monitoring/platform 负责人或 orchestrator，不由本 lane 擅越；
- 只认证 metrics path，不改变 `health`、MVC/JWT/RPC 鉴权；
- 不触碰其他任何路径、契约、钱/桥/结算模块。

## 验证

- per-module 代码编译/sanity import；
- core 的 `ActuatorPrometheusAuthFilterTest` 继续是基准；
- compose config 使用临时 placeholder 渲染通过（不写 `?prod` 真值）；
- prometheus.yml 静态检查。

## 边界

不自主改写生产 Secret，不在仓库放任何真实令牌、主密钥、地址；不代表全栈所有外部暴露面均已闭环。
