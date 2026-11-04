# 方案 PLAN-077：metrics Bearer 双 token 零空窗轮换

- **状态**：已交付（2026-11-05）
- **前置**：PLAN-075（Bearer 门禁）、PLAN-076（本地真实链路演练 + runbook；
  其中如实登记了「单 token 轮换有空窗」）
- **范围**：六个 metrics 服务同时接受 current + previous 两个 Bearer token；
  配置/Helm/compose 接线；runbook 零空窗流程；测试与反向对照
- **不包含**：token 热更新、三 token 链、自动轮换、生产 Secret/Operator 落地

---

## 一、问题

PLAN-076 §三 runbook 登记的已知代价：单 token 轮换时，Prometheus 侧与应用侧
不可能同时切换 ⇒ 切换窗口内必然 401，`up=0` 会造成抓取空窗与告警误报。
指标是 pull 型，不丢业务数据，但「已知会红」本身是运维债。

## 二、设计

### 2.1 双 token 语义

- `nexus.actuator.prometheus-token` = current（**必须配置**）；
- `nexus.actuator.prometheus-previous-token` = previous（可空）；
- 匹配 current **或** previous 即放行；
- **current 缺失 ⇒ 503**（即使 previous 有值）——避免误配把 fail-closed
  变成「只认旧值」；
- 常量时间比较两个候选；token 不写日志；Authorization 仍限定 `Bearer `。

### 2.2 覆盖模块

| 类型 | 模块 |
|---|---|
| servlet `Filter` | core / gateway / bridge / signing-service / wallet-service |
| WebFlux `WebFilter` | api-gateway（该模块禁止 servlet 栈） |

### 2.3 配置接线

- env：`NEXUS_METRICS_TOKEN` / `NEXUS_METRICS_TOKEN_PREVIOUS`；
- core chart：新增 `NEXUS_METRICS_TOKEN_PREVIOUS`（`secretKeyRef` + `optional: true`）；
  其余服务经共享 `nexus-secrets` 的 `envFrom` 自然生效；
- compose：六个服务新增
  `NEXUS_METRICS_TOKEN_PREVIOUS=${NEXUS_METRICS_TOKEN_PREVIOUS:-}`（默认空 = 行为不变）。

### 2.4 零空窗流程（runbook §三已改写）

1. 应用侧先把新 token 写 previous 并滚动（current=旧、previous=新，都接受）；
2. Prometheus 换成新 token；
3. 验证 `up=1`；
4. 应用侧把 current 提升为新 token、清空 previous 再滚动。
⇒ 全程 401 空窗为 0；失败只需清空 previous 回退。

## 三、验证

- `ActuatorPrometheusAuthFilterTest`（core，servlet）**6 例**：未配置 503、
  current 未配置但 previous 有值仍 503、错 token 401、current 放行、
  **previous 放行**、health 不鉴权；
- `ActuatorPrometheusAuthFilterTest`（api-gateway，WebFlux）同 6 例；
- **反向对照**：把 previous 匹配去掉 ⇒
  `acceptsPreviousTokenDuringRotation` 精确红（`Wanted but not invoked:
  filterChain.doFilter(...)`），同批次无其他失败；还原后两套 12 例全绿；
- 其余四个 servlet 模块 `compileJava` / `compileTestJava` 通过；
- compose 占位渲染、Code Hygiene 见交付 PR。

## 四、边界（勿夸大）

1. **同内核 JVM 单测 + 与 PLAN-076 相同的本机真实链路环境**；非物理多机；
2. previous 机制不降低强度：只放宽到「显式配置的已知旧 token」；
3. **仍无热更新**：env 改动必须滚动进程；
4. 不支持超过两个 token；轮换流程仍是 operator 手工动作；
5. 未触碰真实 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支/PR。
