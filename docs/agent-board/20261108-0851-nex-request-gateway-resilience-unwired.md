---
id: 20261108-0851-nex-request-gateway-resilience-unwired
owner: orchestrator
from: "Lea"
to: [orchestrator]
cc: [zcode, nex, WorkBuddy]
type: 请求
priority: P1
status: open
refs:
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/service/OrchestrationService.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/connector/ConnectorPaymentResult.java
  - file: nexus-gateway/src/main/resources/application.yml
  - pr: 584
created: 2026-11-08T08:51:00+08:00
---

# nexus-gateway：resilience 包当前未接线 —— 请求裁决「先接线后删除」

## 0. 摘要

- `nexus-gateway` 的 `org.nexus.gateway.resilience` 包（8 个类）**当前未接线**：生产支付路径不经过它，它的 4 个指标从未注册、配置整段无人读取（`default:` 段键名不匹配而静默不绑定；`instances` 可绑定但无读者；`fallback` 无 binder）、5 个运维端点读到的都是空内存结构。
- 作为整体它是**更弱的重复实现**（failover / 健康度都已有更强实现），但其中标注的「通道级快失败」缺口**真实存在且当前无覆盖**。
- 建议：**先接线补缺口，再删除冗余包**；不是「顺手清死代码」。本留言请求 orchestrator 裁决方向，不要求签收。

> 措辞说明：本仓 `3ffca5d9` 是一次 67 万行 / 3765 文件的 squash，`git log --follow` 对 resilience 包只能追到这一次提交，**无法证明它曾接上过、后来断了**。因此本文一律写「当前未接线」，不写「从来没接过」。

---

## 一、当前未接线的证据（master 代码，带行号）

| # | 证据 | 位置 | 说明 |
|---|---|---|---|
| 1 | 真实调用点没有熔断检查 | `nexus-gateway/src/main/java/org/nexus/gateway/orchestration/service/OrchestrationService.java:287`（`for (PaymentConnector connector : connectors)`）、`:296`（`result = connector.createPayment(req);`）、`:348`（失败后 `recordConnectorOutcome(false)`）、`:354-359`（全失败 → `FAILED` + `persist`） | 该循环里没有 `isCircuitOpen()`、没有 `recordSuccess/recordFailure`；在 `orchestration/` 下 grep `isCircuitOpen|recordFailure|recordSuccess` → **0 命中** |
| 2 | 熔断器本体没有任何生产调用方 | `resilience/ConnectorCircuitBreaker.java:26-27`（`@Component`）、`:49`（`getOrCreate`）、`:93`（`isCircuitOpen`）、`:103`（`recordSuccess`）、`:114`（`recordFailure`）、`:138-139`（`getRegisteredConnectors` 返回 `keySet()`） | 全仓引用者只有 `ResilienceController`（`:57`、`:118`）、`FallbackRouter:102` 和 3 个测试；**支付路径 0 引用** |
| 3 | 降级路由从未配置 | `resilience/FallbackRouter.java:30`（类）、`:36`（`fallbackRoutes` 空 `ConcurrentHashMap`）、`:49`（`configureRoutes`）、`:94`（`routeToFallback`） | `configureRoutes` 在 `src/main` 下**无调用方**（仅 `FallbackRouterTest`），所以 `routeToFallback` 永远查空表；`routeToFallback` 也仅测试调用 |
| 4 | 指标从未注册 | `resilience/ResilienceMetrics.java:51`（`registerConnectorMetrics`）、`:55`/`:65`/`:73`/`:78`（`nexus.circuitbreaker.state` / `.failure.rate` / `.calls`）、`:112`（`refreshAllGauges`）、`:126`/`:139`（`recordCallSuccess/recordCallFailure`） | `registerConnectorMetrics` 全仓**只有定义、没有调用**；`recordCallSuccess/recordCallFailure` 同样无调用方。`/metrics` 端点（`ResilienceController:112`）先 `refreshAllGauges()`，而它遍历的是空 `keySet()` → 恒为空 |
| 5 | 运维端点读到空结构 | `resilience/ResilienceController.java:27`（`@RequestMapping("/api/v1/resilience")`）、`:52`、`:70`、`:83`、`:99`、`:112` | `getRegisteredConnectors()` 从未被 `getOrCreate` 填充，所以 `GET /circuit-breakers`、`GET /metrics` 返回空；`POST /circuit-breakers/{connector}/reset`、`PUT /fallbacks/{connector}` 只改内存，进程重启即丢 |
| 6 | 配置只绑定了一半，且绑定后也无人读 | `application.yml:563-587`；`resilience/ConnectorCircuitBreakerConfig.java:16-17`（`@Configuration` + `@ConfigurationProperties(prefix = "nexus.resilience.connector")`）、`:23`（字段 `defaultConfig`） | YAML 用 `default:`（`:566`），字段名是 `defaultConfig` → Spring 绑定键是 `default-config`，**`default:` 段实际不绑定**；只因双方默认值恰好相同（`:84-93`：50 / 30s / 10 / 5）而无行为差异。`instances`（`:571-579`，仅 chain / consortium / stripe 3 条）能绑定，但 `getOrCreate` 无调用方，绑定后也无人使用 |
| 7 | 降级配置完全没有 binder | `application.yml:581-587`（`nexus.resilience.fallback.*`，6 条路由） | 全仓 `src/main` 中 `nexus.resilience.fallback` 无任何 `@ConfigurationProperties`/`@Value` 读取点；`FallbackRouter` 只接受手工 `configureRoutes`，而它没有调用方 |
| 8 | 指标零外部引用 | 全仓（origin/master）grep `nexus.circuitbreaker|nexus.fallback` → 仅命中 `ResilienceMetrics.java` 自身；`deploy/k8s/50-prometheus-rules.yaml` 对 `circuitbreaker|circuit_breaker|resilience|connector` **0 命中**；`.github/` **0 命中** | **不存在「告警引用了一个没注册的指标，所以从来没响过」这类独立事故**——这些告警规则本身就不存在。`nexus-gateway/README.md:115` 仍把 `/api/v1/resilience` 宣传为「熔断指标」，这是唯一一处需要清理的文档契约 |
| 9 | 无法证明历史接线 | `git log --follow -- .../resilience/ConnectorCircuitBreaker.java` → 仅 `3ffca5d9` | 见文首措辞说明 |

结论：**当前未接线**。删它之前必须先回答「它标注的那个缺口谁来补」。

---

## 二、与现有实现的对照：哪些冗余、哪些不冗余

| 能力 | 现有实现（已接线，更强） | resilience 包 | 判定 |
|---|---|---|---|
| 支付通道 failover | `OrchestrationService:287-351` 的 for 循环；候选顺序来自 `routingEngine.resolveDetailed()`（多目标评分 / A/B / 审计） | `FallbackRouter.routeToFallback()`，只有测试调用；路由是硬编码有序表 | **冗余**，且现有实现更强 |
| 通道健康 / 错误率 | `ChannelHealthService.evaluate()` + `MetricsCollector.record(connectorId, success, latency, costBps)`；由 `OrchestrationService:302`/`:333`/`:348`/`:450-457` 持续喂入 | `nexus.circuitbreaker.failure.rate` 从未注册 | **冗余**，且现有指标是活的 |
| 熔断快失败 | **无**。`RoutingEngine.java:245`（`.filter(PaymentConnector::isActive)`）、`:383`、`:436`、`:493`、`:502`、`:513` 只按 `isActive` 过滤通道，**不按健康度/错误率过滤**；一个硬故障 PSP 每笔支付仍会被调用，直到单次超时兜底 | `ConnectorCircuitBreaker`（当前未接线） | **不冗余，真缺口** |
| 降级计数 / 运维面板 | `nexus_routing_*` 指标 + `/api/v1/ops` | `nexus.fallback.count` + `/api/v1/resilience/*` | **冗余且误导**（空面板） |

---

## 三、唯一真缺口与接线的前置条件

**缺口**：`connectors` 由路由层给出后，逐通道调用；没有「该通道最近是否持续失败」的快速否决。硬故障通道的代价是**每笔支付多一次超时等待**，且失败会继续污染 `MetricsCollector` 的成功率窗口。

代码级证据：`RoutingEngine.java:245` 生成候选时用的是 `.filter(PaymentConnector::isActive)`，`:383`、`:436`、`:493`、`:502`、`:513` 的显式目标与降级路径同样只判 `isActive`——**没有任何健康度或错误率过滤**。`ChannelHealthService` 的评估结果不进入候选列表，所以「死 PSP 每笔都打一遍」不是推测，是当前代码的既定行为。

**接线前置条件（必须先裁决，否则接线会引入新缺陷）**：

1. 支付路径上的 8 个 connector **吞掉了基础设施异常**，统一转成失败结果：
   - `StripeConnector.java:114-116`（catch RuntimeException → `ConnectorPaymentResult.fail("Stripe error: ...")`）
   - `AdyenConnector.java:109-111`、`WeChatPayConnector.java:207-210`、`AlipayConnector.java:278-280`、`ChainConnector.java:204-206`、`ConsortiumConnector.java:184-186`、`DynamicHttpPspConnector.java:133`、`HttpPspConnector.java:145-147`
   - ⇒ 只按「抛异常」计失败的熔断器**永远不会打开**。
2. 而 `ConnectorPaymentResult` 只有 `success` + `errorMessage`，**没有失败分类**（无 `decline` / `unavailable` / `config` 区分）。
   - ⇒ 若改成「任何 `!isSuccess()` 都计失败」，一段连续**业务拒绝**（如连续多张余额不足的卡）就会把健康 PSP 熔断，比没有熔断更糟。

因此「接线」不是 20 行 try/catch，而是先补一个最小失败分类（`FailureKind`：`DECLINE` / `UNAVAILABLE` / `CONFIG`），熔断只计 `UNAVAILABLE`。这也是本留言请求裁决的核心：**是否同意把「失败分类」纳入本次范围**。

---

## 四、移除影响面与请求裁决

### 4.1 影响面

| 类别 | 具体项 |
|---|---|
| 代码 | `org.nexus.gateway.resilience` 下 8 个类：`ConnectorCircuitBreaker`、`ConnectorCircuitBreakerConfig`、`FallbackRouter`、`ResilienceController`、`ResilienceMetrics`、`CircuitBreakerStateDTO`、`FallbackConfigDTO`、`FallbackEvent` |
| 测试 | `ConnectorCircuitBreakerTest`、`FallbackRouterTest`、`ResilienceControllerTest` |
| 运维端点 | `GET /api/v1/resilience/circuit-breakers`、`POST /api/v1/resilience/circuit-breakers/{connector}/reset`、`GET /api/v1/resilience/fallbacks`、`PUT /api/v1/resilience/fallbacks/{connector}`、`GET /api/v1/resilience/metrics` |
| 配置契约 | `application.yml:563-587`：`nexus.resilience.connector.*`（default + 3 个 instances）+ `nexus.resilience.fallback.*`（6 条路由） |
| 指标名 | `nexus.circuitbreaker.state`、`nexus.circuitbreaker.failure.rate`、`nexus.circuitbreaker.calls`、`nexus.fallback.count`（**外部引用 0**，见证据 8） |
| 文档 | `nexus-gateway/README.md:115`（端点宣传）；`docs/diagrams/ops-monitoring.html:64`、`docs/diagrams/ops-monitoring.json:65`（`FallbackRouter` 节点）；`ARCHITECTURE.md:220-221`、`:276`（组件描述）；`CHANGELOG.md:4934`（历史记录，**不改**） |
| 数据库 / 支付状态 | **0 影响**：包内不落库、不参与 `OrchPaymentStatus`，删除不改任何状态机 |

> **范围界定**：只删除 `org.nexus.gateway.resilience` 包及其引用。`org.nexus.gateway.fallback` 包是另一套 Sentinel/Feign 降级（`BridgeServiceFallback`、`WalletMgmtFallback`、`SigningServiceFallback` 及 3 个 `*FallbackFactory`），**不在本次删除范围**。`ARCHITECTURE.md:221` 对该包的描述本身有误（把 `fallback` 写成 `FallbackRouter`），另行更正，不并入删除 PR。

### 4.2 三个选项

- **A（本留言主张）先接线后删除**：先补 `FailureKind` + 接线（约 4 处代码 + 8 个 connector 的失败分类 + 1 段 `resilience4j.*` 配置），验证通过后，再删除整个 resilience 包及其配置/端点/测试/文档。
- **B 只登记缺口、暂不删**：保留空面板与假指标，把缺口写进缺口台账，等到排期再处理。缺点：运维面板继续误导，配置继续假装生效。
- **C 现在就删 + 登记缺口**：删除后 0 行补缺口，硬故障通道继续每笔打一遍。缺点：把「真缺口」变成「已知缺口」，但没有止血。

请求裁决：**同意 A 的范围（含 FailureKind）**，或明确选 B/C。

### 4.3 执行约束（供裁决时参考）

- 接线 PR 与删除 PR **必须分开**：前者动资金路径语义（新增「快失败」与「全通道熔断 503」），需单独评审；后者是纯删除 + 契约下线。
- 删除 PR 内部，**代码 / 配置 / 端点 / 测试 / README / 图**必须同一个 PR，否则会出现半删状态（配置无 binder、README 指向 404）。
- 本事项不需要 `ack_required: true`，也不设 `expires`：这是知会 + 请求裁决，不冻结任何人。

**本留言只请求裁决，不设自动执行；裁决落地前不启动接线 PR，也不删除任何文件。**
