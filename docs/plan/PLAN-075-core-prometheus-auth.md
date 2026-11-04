# PLAN-075：Spring/Boot `/actuator/prometheus` 鉴权门禁

- **状态**：已交付（2026-11-05）
- **范围**：全仓实际暴露 `/actuator/prometheus` 的 Spring/Boot 服务在请求解析前要求 Bearer token；
  `/actuator/health` 维持公开（给探针使用）
- **新增模块**：nexus-gateway / nexus-bridge / nexus-api-gateway /
  nexus-signing-service / nexus-wallet-service /
  之前生成的 nexus-core
- **不包含**：nexus-consortium（仅 actuator 依赖，无 micrometer-registry-prometheus）；
  生产 Secret 建立/轮换/Prometheus/Alertmanager 的外部落地；其他 actuator 端点、
  `/actuator/health`
- **依据**：20261104-1530 认领 core 最小切片；用户明确“可以继续做剩余的”；
  20261104-1640 扩展认证为全仓 metrics 门禁，见板面。

---

## 一、问题

PLAN-068 运行时实证：

- `/cheackTxlist`、`/nodeInfo` 不带 token 被 MVC interceptor 拒为 401；
- `/actuator/prometheus` 不带 token 返回 200，`/actuator/health` 也 200；
- Boot 4 actuator 不走 MVC interceptor；所有同类服务同样暴露公开 metrics。

 metrics 包含应用、数据库、链高度、最终性/结算/签名等运行时信息。任何可访问服务的网络位置都能读取时间序列和标签，无需凭据。

## 二、设计

### 2.1 只保护 Prometheus endpoint

- `/actuator/prometheus` 由 `ActuatorPrometheusAuthFilter`（servlet Filter +
  `@Component`）在请求解析前校验；
- `/actuator/health` 不加鉴权；
- 不加 Spring Security，不引入第三方依赖；
- 其他 actuator endpoint 曝露面保持不动。

### 2.2 Bearer token + fail-closed

- 属性名：`nexus.actuator.prometheus-token=${NEXUS_METRICS_TOKEN:}`；
- token 常量时间比较；大小写不敏感 `Bearer ` 必需；token 不写日志；
- **未配置 = 503 + JSON**（fail-closed），不是默认放行；
- 无 `Authorization` / token 错误 = 401 + `WWW-Authenticate` + JSON；
- 不泄露是否配置已到限。

### 2.3 部署/Compose

- **所有六项 chart 均经 `envFrom` 引入 `nexus-secrets`**；
  operator 在 `nexus-secrets` 增加 `NEXUS_METRICS_TOKEN`，五个 Spring/ Boot 模块的 env  automatically 注入。
- core 的 env 已显式声明（`secretKeyRef`），同一个 `nexus-secrets` 提供 token；
  gateway/bridge/api-gateway/signing-service/wallet-service 通过 shared `nexus-secrets` envFrom 同样拿到该 token。
- `prometheus.yml` 全部 targets 加 `bearer_token_file:/run/secrets/nexus-metrics-token`；
- `docker-compose.prod.yml` services 全部 `NEXUS_METRICS_TOKEN=${NEXUS_METRICS_TOKEN:?...}`；
  top-level `secrets.nexus-metrics-token: environment: NEXUS_METRICS_TOKEN`；
  Promtheus 使用 `secrets: - nexus-metrics-token` 将 token 挂载到 `/run/secrets/nexus-metrics-token`；
- Secret 没有提交本仓（与 DB/JWT secret 同待遇，operator 部署前创建 `NEXUS_METRICS_TOKEN`）；

### 2.4 外部依赖（不自助拍死）

- `nexus-consortium` 没有 `micrometer-registry-prometheus`，`/actuator/prometheus` 不存在；
- 不自动生成/ rotate token；不改变 `nexus-secrets` 结构；
- 不 push 生产 secret/Prometheus Operator 配置，由 platform/orchestrator 部署，避免跨 lane / prod deploy 擅越；
- Production Rollout（`nexus-secrets` 写入 token + Prometheus/ServiceMonitor 读取 token + 灰度 + 监控 ownership）**未完成**，不在本仓闭环内；
  部署前应确认全部 `nexus-*` 服务同时设置 `NEXUS_METRICS_TOKEN`，否则会 fail-closed scrape。
- 不自主改变 Aligned `git-commit`、不自主 open PR/merge。

## 三、验证

- core `ActuatorPrometheusAuthFilterTest` 4 例：未配置 ⇒ 503；无/错 token ⇒ 401；正确 token ⇒ 放行；health 不鉴权；
- 反向对照（`isTokenConfigured` 放宽）⇒ 503 断言红；还原后绿；
- compose template render 使用 placeholder 通过（`?prod` 必需项全部注入测试 placeholder）；
- 所有新模块生成源码路径与启动主类已静态核对；
- 全量生产 Release process 未执行。

## 四、边界

1. **localonly 与 metrics token 不是同一凭据**：业务 RPC 仍走 JWT；
2. **fail-closed 的运营代价**：未加 `nexus-secrets` token 的部署会看到 `/actuator/prometheus` 503；
  需先 operator 做 secret then scrape；
3. **Health path 仍是 public**：所有容器/Compose readiness/liveness 探针不受影响；
4. **无生产 Rollout harness**：不自动 rotate、不改 Prometheus operator config、不做真实 prod 抓取/验收；
5. 指标标签可能含难分析的治理值；本次只阻断未授权抓取，不清理 metrics cardinality；
6. 未触碰真实 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支/PR。
