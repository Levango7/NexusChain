# NexusChain 业务 Span 设计文档

> P3-T5：分布式追踪深化 — 为关键业务流程添加业务 span，trace_id 关联日志，异常 span 自动告警
>
> 适用范围：NexusChain v2.0.0 Phase 3 分布式追踪深化
>
> 追踪栈：OpenTelemetry Collector + Jaeger（P2-T5）+ Loki + Promtail（P3-T5）

## 第1章 文档目的

本文档说明 NexusChain v2.0.0 Phase 3 任务 P3-T5 中为关键业务流程添加的业务 span 设计，包括：

- 支付全链路 span 树结构（创建 → 路由 → 签名 → 上链 → Webhook）
- 桥跨链全链路 span 树结构（锁定 → Relayer → 铸造/销毁）
- 签名编排 span 树结构（MPC 多轮 → 阈值聚合 → 广播）
- span 属性（attributes）命名约定
- trace_id 日志关联机制（Loki + Promtail）
- 异常 span 告警规则

## 第2章 实现方式

### 2.1 BusinessSpan 工具类

为避免修改 `nexus-gateway/build.gradle` 和 `nexus-bridge/build.gradle` 的依赖（任务约束），不使用 `@WithSpan` 注解（需要 `micrometer-tracing-annotation` + AOP 额外依赖），而是封装手动 `Tracer.nextSpan()` 调用。

表：BusinessSpan 工具类对照表

| 服务 | 包路径 | 文件 |
| --- | --- | --- |
| nexus-gateway | `org.nexus.gateway.tracing` | `BusinessSpan.java` |
| nexus-bridge | `org.nexus.bridge.tracing` | `BusinessSpan.java` |
| nexus-signing-service | `org.nexus.signing.tracing` | `BusinessSpan.java` |

### 2.2 使用模式

BusinessSpan 实现 `AutoCloseable`，支持 try-with-resources 模式，span 在块退出时自动结束：

代码示例：业务 span 标准用法（Java）

```java
try (BusinessSpan span = BusinessSpan.start(tracer, "payment.create")
        .attr("payment.id", paymentId)
        .attr("payment.amount", amount)) {
    // 业务逻辑
    span.attr("payment.tx.hash", txHash);  // 后续追加属性
    return result;
} catch (Exception e) {
    // BusinessSpan.close() 自动结束 span
    // 异常 span 通过 span.error(e) 显式标记
    throw e;
}
```

### 2.3 降级机制

当 `tracer == null`（测试环境 / tracing 未启用）时，所有操作降级为 no-op，保证业务逻辑与测试不受影响。各服务提供两个构造器：

- `@Autowired` 构造器：注入 Tracer，生产使用
- 无 Tracer 构造器：测试兼容，业务 span 降级为 no-op

## 第3章 支付全链路 Span 树

### 3.1 span 树结构

图：支付创建 span 树结构图

```
payment.create (nexus-gateway: OrchestrationService.createPayment)
├── payment.route (nexus-gateway: RoutingEngine.resolve)
│     @attr: payment.route.strategy, payment.route.connectors
├── payment.connector.submit (nexus-gateway: PaymentConnector.createPayment)
│     @attr: payment.connector.id, payment.connector.error
├── payment.webhook.notify (nexus-gateway: OrchestrationWebhookDispatcher.dispatch)
│     @attr: webhook.url, webhook.status, webhook.attempt
└── [跨服务] signing.broadcast (nexus-signing-service: TxController.signAndBroadcast)
      @attr: signing.from.pubkey, signing.tx.hash, signing.nonce
```

图：支付确认 span 树结构图

```
payment.confirm (nexus-gateway: PaymentServiceImpl.confirmPayment)
├── payment.onchain.check (nexus-gateway: PaymentServiceImpl.isChainConfirmed)
│     @attr: payment.tx.hash, payment.onchain.confirmed
└── payment.aml.screen (nexus-gateway: ComplianceService.screenAml)
      @attr: aml.risk.score, aml.blocked
```

图：退款 span 树结构图

```
payment.refund (nexus-gateway: PaymentServiceImpl.refund)
├── [跨服务] signing.broadcast (nexus-signing-service: TxController.signAndBroadcast)
│     @attr: signing.from.pubkey, signing.tx.hash
└── @attr: payment.refund.no, payment.refund.status, payment.refund.tx.hash
```

### 3.2 span 属性清单

表：支付业务 span 属性清单

| span name | 属性 | 类型 | 说明 |
| --- | --- | --- | --- |
| payment.create | payment.id | string | 支付 ID（pay_xxx） |
| payment.create | payment.merchant.id | long | 商户 ID |
| payment.create | payment.amount | long | 支付金额 |
| payment.create | payment.currency | string | 币种 |
| payment.create | payment.status | string | 最终状态 |
| payment.create | payment.tx.hash | string | 链上交易哈希 |
| payment.create | payment.risk.decision | string | 风控决策 |
| payment.route | payment.route.strategy | string | 路由策略 |
| payment.route | payment.route.connectors | string | 候选连接器列表 |
| payment.connector.submit | payment.connector.id | string | 连接器 ID |
| payment.connector.submit | payment.connector.error | string | 连接器错误 |
| payment.webhook.notify | webhook.url | string | Webhook URL |
| payment.webhook.notify | webhook.status | int | HTTP 响应状态码 |
| payment.webhook.notify | webhook.attempt | int | 重试次数 |
| payment.confirm | payment.order.id | long | 订单 ID |
| payment.confirm | payment.tx.hash | string | 链上交易哈希 |
| payment.onchain.check | payment.onchain.confirmed | boolean | 链上确认状态 |
| payment.aml.screen | aml.risk.score | int | AML 风险评分 |
| payment.aml.screen | aml.blocked | boolean | 是否被 AML 拦截 |
| payment.refund | payment.refund.no | string | 退款单号 |
| payment.refund | payment.refund.amount | BigDecimal | 退款金额 |
| payment.refund | payment.refund.status | string | 退款状态 |

## 第4章 桥跨链全链路 Span 树

### 4.1 span 树结构

图：桥锁定 span 树结构图

```
bridge.lock (nexus-bridge: BridgeServiceImpl.lock)
├── @attr: bridge.tx.id, bridge.source.chain, bridge.target.chain
├── @attr: bridge.lock.amount, bridge.status, bridge.timelock
└── [后续] bridge.mint (nexus-bridge: BridgeServiceImpl.mint)
      ├── @attr: bridge.tx.id, bridge.target.chain, bridge.mint.amount
      ├── @attr: bridge.valid.signers, bridge.threshold
      └── @attr: bridge.status
```

图：桥销毁 span 树结构图

```
bridge.burn (nexus-bridge: BridgeServiceImpl.burn)
├── @attr: bridge.tx.id, bridge.source.chain, bridge.target.chain
├── @attr: bridge.burn.amount, bridge.status
└── [后续] bridge.unlock (nexus-bridge: BridgeServiceImpl.unlock)
      ├── @attr: bridge.tx.id, bridge.source.chain, bridge.unlock.amount
      ├── @attr: bridge.valid.signers, bridge.threshold
      └── @attr: bridge.status
```

### 4.2 span 属性清单

表：桥业务 span 属性清单

| span name | 属性 | 类型 | 说明 |
| --- | --- | --- | --- |
| bridge.lock | bridge.tx.id | string | 桥交易 ID（UUID） |
| bridge.lock | bridge.source.chain | string | 源链 ID |
| bridge.lock | bridge.target.chain | string | 目标链 ID |
| bridge.lock | bridge.lock.amount | long | 锁定金额 |
| bridge.lock | bridge.status | string | 交易状态（LOCKED/LOCK_PENDING） |
| bridge.lock | bridge.timelock | boolean | 是否触发 timelock |
| bridge.mint | bridge.tx.id | string | 桥交易 ID |
| bridge.mint | bridge.target.chain | string | 目标链 ID |
| bridge.mint | bridge.mint.amount | long | 铸造金额 |
| bridge.mint | bridge.valid.signers | int | 验签通过的签名者数 |
| bridge.mint | bridge.threshold | int | 签名阈值 |
| bridge.mint | bridge.status | string | 交易状态（MINTED） |
| bridge.burn | bridge.tx.id | string | 桥交易 ID |
| bridge.burn | bridge.burn.amount | long | 销毁金额 |
| bridge.burn | bridge.status | string | 交易状态（BURNED） |
| bridge.unlock | bridge.tx.id | string | 桥交易 ID |
| bridge.unlock | bridge.unlock.amount | long | 解锁金额 |
| bridge.unlock | bridge.valid.signers | int | 验签通过的签名者数 |
| bridge.unlock | bridge.status | string | 交易状态（UNLOCKED） |

## 第5章 签名编排 Span 树

### 5.1 span 树结构

图：签名编排 span 树结构图

```
signing.orchestrate (nexus-signing-service: MpcSigner.runSigningRounds)
├── signing.mpc.round × N (nexus-signing-service: MpcSigner.executeRound)
│     @attr: signing.round.index, signing.round.total, signing.round.messages
├── [后续] signing.threshold.aggregate (nexus-signing-service: MpcSignatureAggregator.aggregate)
│     ├── signing.threshold.verify (nexus-signing-service: MpcSignatureAggregator.verifyShares)
│     │     @attr: signing.session.id
│     └── @attr: signing.shares.collected, signing.threshold.required
└── signing.broadcast (nexus-signing-service: TxController.signAndBroadcast)
      @attr: signing.from.pubkey, signing.from.address, signing.nonce, signing.tx.hash
```

### 5.2 span 属性清单

表：签名业务 span 属性清单

| span name | 属性 | 类型 | 说明 |
| --- | --- | --- | --- |
| signing.orchestrate | signing.session.id | string | 签名会话 ID |
| signing.orchestrate | signing.participants | int | 参与者数 |
| signing.orchestrate | signing.rounds.total | int | 总轮次数 |
| signing.orchestrate | signing.threshold | int | 签名阈值 |
| signing.orchestrate | signing.shares.collected | int | 已收集 share 数 |
| signing.mpc.round | signing.session.id | string | 签名会话 ID |
| signing.mpc.round | signing.round.index | int | 当前轮次（1-based） |
| signing.mpc.round | signing.round.total | int | 总轮次数 |
| signing.mpc.round | signing.round.messages | int | 本轮消息数 |
| signing.threshold.aggregate | signing.session.id | string | 签名会话 ID |
| signing.threshold.aggregate | signing.shares.collected | int | 已收集 share 数 |
| signing.threshold.aggregate | signing.threshold.required | int | 签名阈值 |
| signing.threshold.aggregate | signing.signature.length | int | 最终签名长度 |
| signing.threshold.verify | signing.session.id | string | 签名会话 ID |
| signing.broadcast | signing.from.pubkey | string | 签名公钥 |
| signing.broadcast | signing.to.pubkey.hash | string | 接收方公钥哈希 |
| signing.broadcast | signing.amount | BigDecimal | 转账金额 |
| signing.broadcast | signing.from.address | string | 签名地址 |
| signing.broadcast | signing.nonce | long | 交易 nonce |
| signing.broadcast | signing.tx.hash | string | 链上交易哈希 |
| signing.broadcast | signing.error | string | 错误类型 |

## 第6章 trace_id 日志关联

### 6.1 MDC 注入机制

Micrometer Tracing 自动将 `traceId` / `spanId` 写入 SLF4J MDC（Mapped Diagnostic Context），日志 pattern 通过 `%X{traceId}` / `%X{spanId}` 引用。

表：logback-spring.xml 配置对照表

| 服务 | 文件 | dev pattern | prod encoder |
| --- | --- | --- | --- |
| nexus-gateway | `nexus-gateway/src/main/resources/logback-spring.xml` | `[%X{traceId:-},%X{spanId:-}]` | LogstashEncoder + includeMdcKeyName |
| nexus-bridge | `nexus-bridge/src/main/resources/logback-spring.xml` | `[%X{traceId:-},%X{spanId:-}]` | LogstashEncoder + includeMdcKeyName |
| nexus-signing-service | `nexus-signing-service/src/main/resources/logback-spring.xml` | `[%X{traceId:-},%X{spanId:-}]` | LogstashEncoder + includeMdcKeyName |
| nexus-wallet-service | `nexus-wallet-service/src/main/resources/logback-spring.xml` | `[%X{traceId:-},%X{spanId:-}]` | LogstashEncoder + includeMdcKeyName |

### 6.2 Loki + Promtail 部署

表：Loki + Promtail 部署文件清单

| 文件 | 用途 |
| --- | --- |
| `deploy/tracing/promtail-config.yaml` | Promtail ConfigMap：采集 Pod 日志，提取 traceId/spanId 作为 Loki 标签 |
| `deploy/tracing/loki-promtail-deployment.yaml` | Loki Deployment + Service + Promtail DaemonSet + RBAC |

### 6.3 Promtail pipeline_stages

Promtail 通过 `pipeline_stages` 从 JSON 日志中提取 traceId / spanId 并提升为 Loki 标签：

1. `json` stage：解析 LogstashEncoder 产出的 JSON 日志，提取 `traceId` / `spanId` / `level` / `service` 字段
2. `labels` stage：将 `traceId` / `spanId` / `level` 提升为 Loki 标签
3. `timestamp` stage：使用 `@timestamp` 作为日志时间戳

### 6.4 trace ↔ log 联动查询

在 Grafana 中通过 Loki 数据源查询日志，按 `trace_id` 标签过滤：

```logql
{namespace="nexus", service="nexus-gateway", trace_id="abcdef1234567890"}
```

或在 Jaeger UI 中查看 trace 后，点击 trace 详情页的 "Logs" 标签，自动跳转到 Loki 按 trace_id 查询。

## 第7章 异常 span 告警

### 7.1 告警规则

在 `deploy/monitoring/alerting-rules.yaml` 中新增 `nexus.tracing` 告警组，共 4 条规则：

表：异常 span 告警规则清单

| 编号 | 告警名 | 表达式简述 | 持续 | 严重级别 |
| --- | --- | --- | --- | --- |
| 14 | SpanErrorRateHigh | span 错误率 > 5% | 5m | critical |
| 15 | SpanP99LatencyHigh | span P99 延迟 > 2s | 5m | warning |
| 16 | SpanDropRateHigh | OTel Collector span 丢弃率 > 1% | 5m | warning |
| 17 | BusinessSpanMissing | 业务 span 指标缺失 | 10m | warning |

### 7.2 span 错误率定义

span 错误率 = 错误 span 数 / 总 span 数，错误 span 包括：

1. HTTP 5xx 响应（`http_server_requests_seconds_count{status=~"5.."}`）
2. 业务异常 span（通过 `BusinessSpan.error(Throwable)` 标记，记录为 `nexus_span_error_total` 指标）
3. OTel Collector 收到的 ERROR 级别 span

### 7.3 span P99 延迟定义

span P99 延迟基于 HTTP 服务端请求延迟（`http_server_requests_seconds_bucket`），覆盖所有业务 span 的根入口：

- `payment.create`（支付创建）
- `bridge.lock` / `bridge.mint` / `bridge.burn` / `bridge.unlock`（桥操作）
- `signing.broadcast`（签名广播）

## 第8章 验收清单

### 8.1 Jaeger span 树验证

- [ ] 支付创建请求生成完整 span 树（payment.create → payment.route → payment.connector.submit → payment.webhook.notify）
- [ ] 支付确认请求生成 span 树（payment.confirm → payment.onchain.check → payment.aml.screen）
- [ ] 退款请求生成 span 树（payment.refund → signing.broadcast）
- [ ] 桥锁定请求生成 span 树（bridge.lock）
- [ ] 桥铸造请求生成 span 树（bridge.mint）
- [ ] 桥销毁请求生成 span 树（bridge.burn）
- [ ] 桥解锁请求生成 span 树（bridge.unlock）
- [ ] 签名编排生成 MPC 多轮 span（signing.orchestrate → signing.mpc.round × N）
- [ ] 阈值聚合生成 span（signing.threshold.aggregate → signing.threshold.verify）
- [ ] 签名广播生成 span（signing.broadcast）
- [ ] 跨服务 trace 上下文通过 W3C traceparent 传播（同一 traceId）
- [ ] 业务 span 携带属性（payment.id / bridge.tx.id / signing.session.id 等）

### 8.2 trace_id 日志关联验证

- [ ] 4 个服务的 logback-spring.xml 配置 `%X{traceId}` / `%X{spanId}` pattern
- [ ] Loki Deployment 运行正常（`kubectl get pods -n nexus -l app.kubernetes.io/name=loki`）
- [ ] Promtail DaemonSet 运行正常（`kubectl get pods -n nexus -l app.kubernetes.io/name=promtail`）
- [ ] Promtail 从 Pod 日志提取 traceId 并推送至 Loki
- [ ] 在 Grafana 中可按 `trace_id` 标签检索日志

### 8.3 异常 span 告警验证

- [ ] PrometheusRule `nexus.tracing` 组加载成功
- [ ] SpanErrorRateHigh 告警在 span 错误率 > 5% 时触发
- [ ] SpanP99LatencyHigh 告警在 span P99 延迟 > 2s 时触发
- [ ] SpanDropRateHigh 告警在 Collector span 丢弃率 > 1% 时触发
- [ ] BusinessSpanMissing 告警在业务 span 指标缺失时触发

## 第9章 文件清单

表：P3-T5 交付物文件清单

| 文件 | 类型 | 用途 |
| --- | --- | --- |
| `nexus-gateway/src/main/java/org/nexus/gateway/tracing/BusinessSpan.java` | 新增 | gateway 业务 span 工具类 |
| `nexus-bridge/src/main/java/org/nexus/bridge/tracing/BusinessSpan.java` | 新增 | bridge 业务 span 工具类 |
| `nexus-signing-service/src/main/java/org/nexus/signing/tracing/BusinessSpan.java` | 新增 | signing 业务 span 工具类 |
| `nexus-gateway/src/main/java/org/nexus/gateway/orchestration/service/OrchestrationService.java` | 修改 | 添加支付创建 span |
| `nexus-gateway/src/main/java/org/nexus/gateway/orchestration/service/OrchestrationWebhookDispatcher.java` | 修改 | 添加 webhook span |
| `nexus-gateway/src/main/java/org/nexus/gateway/service/PaymentServiceImpl.java` | 修改 | 添加支付确认/退款 span |
| `nexus-bridge/src/main/java/org/nexus/bridge/BridgeServiceImpl.java` | 修改 | 添加桥操作 span |
| `nexus-signing-service/src/main/java/org/nexus/signing/mpc/MpcSigner.java` | 修改 | 添加签名编排 span |
| `nexus-signing-service/src/main/java/org/nexus/signing/mpc/MpcSignatureAggregator.java` | 修改 | 添加阈值聚合 span |
| `nexus-signing-service/src/main/java/org/nexus/signing/controller/TxController.java` | 修改 | 添加签名广播 span |
| `nexus-gateway/src/main/resources/logback-spring.xml` | 修改 | MDC 注入 trace_id/span_id |
| `nexus-bridge/src/main/resources/logback-spring.xml` | 新增 | MDC 注入 trace_id/span_id |
| `nexus-signing-service/src/main/resources/logback-spring.xml` | 新增 | MDC 注入 trace_id/span_id |
| `nexus-wallet-service/src/main/resources/logback-spring.xml` | 新增 | MDC 注入 trace_id/span_id |
| `deploy/tracing/promtail-config.yaml` | 新增 | Promtail 配置（trace_id 提取） |
| `deploy/tracing/loki-promtail-deployment.yaml` | 新增 | Loki + Promtail K8s 部署 |
| `deploy/monitoring/alerting-rules.yaml` | 修改 | 新增 nexus.tracing 告警组（4 条） |
| `docs/tracing-business-span.md` | 新增 | 本文档 |
| `deploy/tracing/README.md` | 修改 | 追加 P3-T5 章节 |