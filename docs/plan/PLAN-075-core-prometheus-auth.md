# PLAN-075：nexus-core `/actuator/prometheus` 熊封令鉴权门禁

- **状态**：已交付（2026-11-04）
- **前置**：PLAN-002 后续（actuator + micrometer + ServiceMonitor）、
  PLAN-068（确认 actuator 由独立 `WebMvcEndpointHandlerMapping` 提供，MVC
  `HandlerInterceptor` 无法保护该路径）
- **范围**：nexus-core 的 `/actuator/prometheus` 要求 Bearer token；
  `/actuator/health` 维持公开（给探针使用）
- **未包含**：其余服务（nexus-gateway / nexus-bridge / nexus-api-gateway /
  nexus-signing-service / nexus-wallet-service / nexus-consortium）的同口径门禁、
  其他 actuator 端点、证书轮换与集群侧 secret 分发、真实 Prometheus/Alertmanager 认证配置 —
  这些有归属之争或跨 lane/prod deploy 风险，需 orchestrator 指定落地方案。
  本次只保证 core **本机/集群 env 未配置即 fail-closed**，不做“docs say secured but default open”的微妙回退。

---

## 一、问题

PLAN-068 实测证明：

- `/cheackTxlist`、`/nodeInfo` 不带 token 被 MVC interceptor 拒为 401；
- `/actuator/prometheus` 不带 token 返回 200，`/actuator/health` 也 200；
- Boot 4 actuator 不走 MVC interceptor，因此原来的配置并没有保护它。

metrics 暴露的不只是 `nexuschain_block_height`，还有应用、数据库、出块、
最终性等运行时信息（gateway/core/signing/wallet/bridge 的 ServiceMonitor
路径均为 `/actuator/prometheus`）。任何能访问 core 的网络位置都能读到
时间序列和标签，无需凭据。

## 二、设计

### 2.1 只保护 Prometheus endpoint

- `application.properties` 仅暴露 `health` + `prometheus`（不动，最小攻击面）；
- `/actuator/prometheus` 由 `ActuatorPrometheusAuthFilter`（servlet Filter +
  `@Component`）在请求解析前校验；
- `/actuator/health` 不加鉴权，给容器/Helm/compose 的 liveness/readiness 探针用；
- 不加 Spring Security，不引入新第三方依赖，直接沿用 servlet Filter。

### 2.2 Bearer token + fail-closed

- 属性名 `nexus.actuator.prometheus-token=${NEXUS_METRICS_TOKEN:}`；
- token 常量时间比较；大小写不敏感 `Bearer ` 必需；token 不写日志；
- **未配置 = 503 + JSON**（fail-closed），不是默认放行；
- 无 `Authorization` / token 错误 = 401 + `WWW-Authenticate` header + JSON；
- 不输出是否接近、是否配置好等额外细节。

### 2.3 部署侧

Helm chart 改用 `existingSecret` 新键 `NEXUS_METRICS_TOKEN` 注入 core env：

- `nexus-secrets` 新增 `NEXUS_METRICS_TOKEN`（必须由 operator 生成并由 secret 分发）；
- Secret 没有提交本仓（与 NEXUS_JWT_APP_SECRET / DB_PASSWORD 同等待遇，operator 注入）。

Prometheus 侧 token 分发由编排负责，因为它是 “core 既有 Secret → 监控 Prometheus”的角色
边界；本仓不擅自改编排配置。推荐：

```yaml
# prometheus operator (kube-prometheus-stack) 约束示例：把 token 放进单独的 monitoring Secret，不共享 app secret 本身
spec:
  secrets:
    - nexus-core-metrics-token
---
# ServiceMonitor endpoint (same namespace as prometheus)
endpoints:
  - port: rpc
    path: /actuator/prometheus
    authorization:
      type: Bearer
      credentialsFile: /etc/prometheus/secrets/nexus-core-metrics-token/NEXUS_METRICS_TOKEN
```

此文件内容与 app 端注入的 `NEXUS_METRICS_TOKEN` **必须同值**。文件 token 必须经
Secret 挂载，不能在 repo/镜像中内置。

### 2.4 未包含（故意为之）

- 其他 6 个 Spring 服务的 `/actuator/prometheus` 仍不鉴权：它们属于
  gateway/bridge/api-gateway/signing/wallet/consortium lane，不由本 lane 独擅；
- 不自动旋转/生成 token：需要 secret 供给方；
- 不变动 `management.endpoints.web.exposure.include`、不影响 `/actuator/health`、
  不动 MVC interceptor；
- 不声称“整个生产栈已加固”。

## 三、验证

- `ActuatorPrometheusAuthFilterTest` 4 例覆盖：
  未配置 token ⇒ 503；无/错 token ⇒ 401；正确 token ⇒ 正常放行；
  `/actuator/health` 不鉴权；
- 反向对照应红：
  `?q=`。（见 delivery PR 文档与 CI）

## 四、边界

1. **localonly 与 metrics token 不是同一凭据**：业务 RPC 仍走 JWT
  (`token`)；metrics 是 Bearer；
2. **fail-closed 的运营代价**：未重建 secret 的 operator 会看到 app 起不来 /
  metrics 503，必须先创建 `NEXUS_METRICS_TOKEN` 再升 core；
3. **没有 push-based automatic rotation**：轮换需要双方重建 env 与 prometheus
  secret file；
4. 只证明“core 这一端不再免鉴权敞开”，**不**证明“全栈已无未授权 metrics”；
5. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。
