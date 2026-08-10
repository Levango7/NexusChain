# NexusChain 分布式追踪部署文档

> P2-T5：分布式追踪增强 — 从 Zipkin 迁移到 OpenTelemetry Collector + Jaeger
>
> P3-T5：分布式追踪深化 — 业务 span + trace_id 日志关联（Loki + Promtail）+ 异常 span 告警
>
> 适用范围：NexusChain v2.0.0 Phase 2 / Phase 3 生产就绪
>
> 追踪栈：OpenTelemetry Collector（OTLP 接收 + 处理 + 导出）+ Jaeger all-in-one（Query UI + Collector + Agent）+ Loki（日志聚合）+ Promtail（日志采集）

## 第1章 文件清单

| 文件 | 用途 |
| --- | --- |
| `otel-collector-config.yaml` | OpenTelemetry Collector 配置（接收 OTLP → 限流 → 资源增强 → 属性裁剪 → 尾端采样 → 批量 → 导出 Jaeger） |
| `jaeger-deployment.yaml` | Jaeger all-in-one + OTel Collector K8s 部署清单（ConfigMap / Deployment / Service / Ingress） |
| `tracing-config-snippet.yml` | Spring Boot tracing 配置片段，通过 `spring.config.import` 注入，Helm values 挂载至 `/app/config/tracing.yml` |
| `promtail-config.yaml` | Promtail ConfigMap：采集 nexus 服务 JSON 日志，提取 traceId/spanId 作为 Loki 标签（P3-T5） |
| `loki-promtail-deployment.yaml` | Loki + Promtail K8s 部署清单（Loki Deployment + Promtail DaemonSet + RBAC）（P3-T5） |
| `README.md` | 本文档（部署指南 + 业务 span 规范 + 依赖迁移说明 + trace_id 日志关联） |

## 第2章 架构概览

### 2.1 数据流

图：追踪数据流架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        NexusChain 应用层                            │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │ gateway  │  │ bridge   │  │ signing-svc  │  │ wallet-svc   │    │
│  │ :8080    │  │ :8084    │  │ :8082        │  │ :8083        │    │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  └──────┬───────┘    │
│       │              │               │                 │            │
│       └──────────────┴───────┬──────┴─────────────────┘            │
│                              │ Micrometer OTel bridge              │
│                              │ OTLP/gRPC (W3C traceparent)        │
└──────────────────────────────┼─────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    OTel Collector (:4317 / :4318)                   │
│                                                                     │
│  receivers.otlp  →  memory_limiter  →  resource (增强属性)          │
│                  →  attributes (裁剪)  →  tail_sampling (尾端采样)  │
│                  →  batch (聚合)       →  exporters.otlp/jaeger     │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ OTLP/gRPC
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│              Jaeger all-in-one (:16686 UI / :4317 OTLP)             │
│                                                                     │
│  Collector (OTLP 接收)  →  Memory Storage  →  Query UI              │
└────────────────────────────────────────────────────────────────────-┘
                               │
                               ▼
                     Jaeger UI :16686（Ingress）
```

### 2.2 端口对照

表：追踪组件端口对照表

| 组件 | 端口 | 协议 | 用途 |
| --- | --- | --- | --- |
| OTel Collector | 4317 | gRPC | OTLP trace 接收（Spring Boot 默认） |
| OTel Collector | 4318 | HTTP | OTLP trace 接收（备选） |
| OTel Collector | 8888 | HTTP | Collector 自身 Prometheus 指标 |
| OTel Collector | 13133 | HTTP | 健康检查（liveness / readiness） |
| OTel Collector | 55679 | HTTP | zPages 运行时自省 |
| Jaeger Collector | 4317 | gRPC | OTLP 接收（Collector → Jaeger） |
| Jaeger Collector | 14250 | gRPC | Jaeger model 接收 |
| Jaeger Agent | 6831 | UDP | Compact thrift（兼容旧客户端） |
| Jaeger Agent | 6832 | UDP | Binary thrift（兼容旧客户端） |
| Jaeger Query | 16686 | HTTP | Jaeger UI |

### 2.3 与原 Zipkin 方案对照

表：Zipkin 与 OTel+Jaeger 方案对照表

| 维度 | Zipkin（原） | OTel Collector + Jaeger（新） |
| --- | --- | --- |
| 上报协议 | Zipkin v2 JSON（HTTP POST） | OTLP gRPC |
| 传播格式 | W3C traceparent | W3C traceparent（不变） |
| SDK bridge | Brave | OpenTelemetry SDK |
| 中间处理层 | 无（服务直连 Zipkin） | OTel Collector（限流 / 采样 / 属性裁剪） |
| 采样策略 | 服务端固定概率 | 服务端概率 + Collector 尾端采样（错误 / 慢链路兜底） |
| UI 端口 | 9411 | 16686 |
| 存储后端 | mem / mysql / ES | mem / ES / Cassandra |

## 第3章 前置条件

### 3.1 集群要求

- Kubernetes ≥ 1.24
- 命名空间 `nexus` 已创建（由 `deploy/helm/templates/namespace.yaml` 管理）
- Ingress Controller（nginx）已部署（用于 Jaeger UI Ingress）
- StorageClass 可用（生产环境 Jaeger 持久化需要）

### 3.2 应用侧依赖迁移

4 个 Spring Boot 服务需将 tracing 依赖从 Brave + Zipkin 切换到 OTel + OTLP。

当前 `build.gradle` 中的依赖（需移除）：

```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
```

迁移目标依赖（需添加）：

```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

Spring Boot 3.2.5 BOM 管理版本：

- `micrometer-tracing-bridge-otel` 1.2.5
- `opentelemetry-exporter-otlp` 1.34.0

> **注意**：本任务不修改 `build.gradle`（依赖迁移由后续 PR 统一处理）。本任务仅提供配置文件与文档，依赖切换前服务仍使用 Zipkin 上报。切换后 `application.yml` 中的 `management.zipkin.*` 配置段应移除，由 `tracing-config-snippet.yml` 中的 `management.otlp.*` 替代。

### 3.3 配置注入机制

不修改现有 `application.yml` 和 `application-dev.yml`，通过以下机制注入 tracing 配置：

1. **`spring.config.import`**：在 `tracing-config-snippet.yml` 中声明 `optional:file:./config/tracing.yml`，Spring Boot 运行时自动合并
2. **ConfigMap 挂载**：Helm 将 `tracing-config-snippet.yml` 内容写入 ConfigMap，挂载至 `/app/config/tracing.yml`
3. **环境变量覆盖**：通过 `NEXUS_TRACING_SAMPLING_PROBABILITY` / `NEXUS_OTLP_ENDPOINT` 等环境变量覆盖默认值（12-factor）

## 第4章 部署步骤

### 4.1 部署 Jaeger + OTel Collector

```bash
# 应用 K8s 部署清单
kubectl apply -f deploy/tracing/jaeger-deployment.yaml -n nexus

# 等待 Pod 就绪
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=jaeger -n nexus --timeout=120s
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=otel-collector -n nexus --timeout=120s

# 验证 Jaeger UI 可访问
kubectl port-forward svc/jaeger-query -n nexus 16686:16686
# 浏览器打开 http://localhost:16686
```

### 4.2 验证 Collector 配置

```bash
# 检查 Collector 健康状态
kubectl exec -n nexus deploy/otel-collector -- curl -s http://localhost:13133/

# 查看 Collector 自身指标
kubectl port-forward svc/otel-collector -n nexus 8888:8888
# 浏览器打开 http://localhost:8888/metrics

# 查看 zPages（运行时采样 / 处理器统计）
kubectl port-forward svc/otel-collector -n nexus 55679:55679
# 浏览器打开 http://localhost:55679/debug/tracez
```

### 4.3 应用服务配置注入

通过 Helm values 注入 tracing 配置（不修改 `application.yml`）：

```bash
# 在 deploy/helm/charts/<service>/values.yaml 中扩展 config 段
# 或通过 --set 命令行覆盖
helm upgrade nexus deploy/helm \
  --set global.tracing.enabled=true \
  --set global.tracing.otlpEndpoint=http://otel-collector.nexus.svc.cluster.local:4317 \
  --set global.tracing.samplingProbability=0.1 \
  -f deploy/helm/values-prod.yaml \
  -n nexus
```

### 4.4 验证端到端追踪

```bash
# 触发一笔支付创建请求（生成跨服务 span 树）
curl -X POST http://gateway.nexus.local/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"amount":100,"currency":"USDT","recipient":"0x..."}'

# 在 Jaeger UI 中按 service=nexus-gateway 搜索
# 应看到完整 span 树：
#   nexus-gateway: PaymentService.create
#     ├── nexus-gateway: RouteDecision
#     ├── nexus-signing-service: SigningService.sign
#     │     └── nexus-signing-service: MpcRound
#     ├── nexus-gateway: OnChainSubmit
#     └── nexus-gateway: WebhookNotify
```

## 第5章 业务 Span 规范

### 5.1 支付创建链路

图：支付创建 span 树示意图

```
PaymentService.create (span name: "payment.create")
├── RouteDecision (span name: "payment.route")
│     @SpanTag: payment.route.strategy, payment.route.chain
├── SigningOrchestrate (span name: "payment.signing.orchestrate")
│     ├── SigningService.sign (跨服务, Feign)
│     │     ├── MpcRound (span name: "signing.mpc.round")
│     │     │     @SpanTag: signing.round.id, signing.round.participants
│     │     └── Broadcast (span name: "signing.broadcast")
│     └── SigningService.sign (跨服务, 多签)
├── OnChainSubmit (span name: "payment.onchain.submit")
│     @SpanTag: payment.tx.hash, payment.chain.id
└── WebhookNotify (span name: "payment.webhook.notify")
      @SpanTag: webhook.url, webhook.status
```

### 5.2 桥锁定链路

图：桥锁定 span 树示意图

```
BridgeHandler.lock (span name: "bridge.lock")
├── SourceChainLock (span name: "bridge.source.lock")
│     @SpanTag: bridge.source.chain, bridge.lock.amount
├── Relayer (span name: "bridge.relayer")
│     @SpanTag: bridge.relayer.id, bridge.relayer.confirmations
├── TargetChainOperation (span name: "bridge.target.operation")
│     ├── Mint (span name: "bridge.mint")     # 锁定 → 铸造
│     │     @SpanTag: bridge.target.chain, bridge.mint.amount
│     └── Burn (span name: "bridge.burn")     # 解锁 → 销毁
│           @SpanTag: bridge.target.chain, bridge.burn.amount
└── BridgeConfirm (span name: "bridge.confirm")
      @SpanTag: bridge.tx.id, bridge.status
```

### 5.3 签名编排链路

图：签名编排 span 树示意图

```
SigningService.sign (span name: "signing.orchestrate")
├── MpcRound (span name: "signing.mpc.round", 多轮)
│     ├── RoundCommit (span name: "signing.mpc.commit")
│     │     @SpanTag: signing.round.id, signing.round.index
│     └── RoundVerify (span name: "signing.mpc.verify")
│           @SpanTag: signing.round.id, signing.verify.result
├── ThresholdAggregate (span name: "signing.threshold.aggregate")
│     @SpanTag: signing.threshold.required, signing.threshold.collected
└── Broadcast (span name: "signing.broadcast")
      @SpanTag: signing.broadcast.channel, signing.broadcast.peers
```

### 5.4 业务 Span 注解使用

使用 `@WithSpan` 和 `@SpanTag` 注解为业务方法添加 span：

代码示例：支付创建业务 span（Java）

```java
import io.micrometer.tracing.annotation.WithSpan;
import io.micrometer.tracing.annotation.SpanTag;

@Service
public class PaymentService {

    @WithSpan(value = "payment.create")
    public PaymentResult create(
            @SpanTag("payment.amount") BigDecimal amount,
            @SpanTag("payment.currency") String currency,
            @SpanTag("payment.recipient") String recipient) {
        // 路由决策
        RouteDecision route = routeDecision(amount, currency);
        // 签名编排
        SignResult sign = signingOrchestrate(route);
        // 上链
        String txHash = onChainSubmit(sign);
        // Webhook 通知
        webhookNotify(txHash);
        return new PaymentResult(txHash);
    }

    @WithSpan(value = "payment.route")
    public RouteDecision routeDecision(
            @SpanTag("payment.amount") BigDecimal amount,
            @SpanTag("payment.currency") String currency) {
        // 路由逻辑
        return routeService.route(amount, currency);
    }

    @WithSpan(value = "payment.onchain.submit")
    public String onChainSubmit(
            @SpanTag("signing.result") SignResult sign) {
        return chainClient.submit(sign);
    }
}
```

代码示例：桥锁定业务 span（Java）

```java
import io.micrometer.tracing.annotation.WithSpan;
import io.micrometer.tracing.annotation.SpanTag;

@Service
public class BridgeHandler {

    @WithSpan(value = "bridge.lock")
    public BridgeResult lock(
            @SpanTag("bridge.source.chain") String sourceChain,
            @SpanTag("bridge.lock.amount") BigDecimal amount,
            @SpanTag("bridge.recipient") String recipient) {
        // 源链锁定
        sourceChainLock(sourceChain, amount);
        // Relayer 转发
        relayer(sourceChain, amount);
        // 目标链铸造
        mint(sourceChain, amount, recipient);
        // 确认
        return confirm();
    }

    @WithSpan(value = "bridge.mint")
    public void mint(
            @SpanTag("bridge.target.chain") String targetChain,
            @SpanTag("bridge.mint.amount") BigDecimal amount,
            @SpanTag("bridge.recipient") String recipient) {
        targetChainClient.mint(targetChain, amount, recipient);
    }
}
```

代码示例：签名编排业务 span（Java）

```java
import io.micrometer.tracing.annotation.WithSpan;
import io.micrometer.tracing.annotation.SpanTag;

@Service
public class SigningService {

    @WithSpan(value = "signing.orchestrate")
    public SignResult sign(
            @SpanTag("signing.payload") String payload,
            @SpanTag("signing.threshold") int threshold) {
        // MPC 多轮
        for (int i = 0; i < rounds; i++) {
            mpcRound(payload, i);
        }
        // 阈值聚合
        byte[] aggregated = thresholdAggregate(threshold);
        // 广播
        return broadcast(aggregated);
    }

    @WithSpan(value = "signing.mpc.round")
    public void mpcRound(
            @SpanTag("signing.payload") String payload,
            @SpanTag("signing.round.index") int roundIndex) {
        // MPC 单轮计算
        mpcEngine.compute(payload, roundIndex);
    }

    @WithSpan(value = "signing.broadcast")
    public SignResult broadcast(
            @SpanTag("signing.broadcast.payload") byte[] payload) {
        return broadcastClient.send(payload);
    }
}
```

### 5.5 启用 @WithSpan 注解

`@WithSpan` 注解需要 `micrometer-tracing-annotation` 依赖和 AOP 支持：

```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.micrometer:micrometer-tracing-annotation'
implementation 'org.springframework.boot:spring-boot-starter-aop'
```

并在配置中启用：

```yaml
management:
  tracing:
    enabled: true
    # 启用 @WithSpan / @SpanTag 注解 AOP 代理
    annotations:
      enabled: true
```

### 5.6 Span 命名约定

表：业务 span 命名约定表

| 业务域 | span name 前缀 | 关键 @SpanTag | 服务 |
| --- | --- | --- | --- |
| 支付创建 | `payment.*` | payment.amount, payment.currency, payment.tx.hash | gateway |
| 路由决策 | `payment.route` | payment.route.strategy, payment.route.chain | gateway |
| 签名编排 | `signing.orchestrate` | signing.payload, signing.threshold | signing-service |
| MPC 轮次 | `signing.mpc.round` | signing.round.id, signing.round.index | signing-service |
| 签名广播 | `signing.broadcast` | signing.broadcast.channel, signing.broadcast.peers | signing-service |
| 桥锁定 | `bridge.lock` | bridge.source.chain, bridge.lock.amount | bridge |
| 桥铸造 | `bridge.mint` | bridge.target.chain, bridge.mint.amount | bridge |
| 桥销毁 | `bridge.burn` | bridge.target.chain, bridge.burn.amount | bridge |
| 上链提交 | `payment.onchain.submit` | payment.tx.hash, payment.chain.id | gateway |
| Webhook | `payment.webhook.notify` | webhook.url, webhook.status | gateway |

## 第6章 生产持久化

### 6.1 Jaeger 存储后端切换

all-in-one 默认使用 in-memory storage（重启丢失），仅适用 dev / staging。生产环境应切换为 Elasticsearch 或 Cassandra：

```yaml
# Jaeger 生产部署示例（替换 all-in-one）
env:
  - name: SPAN_STORAGE_TYPE
    value: elasticsearch
  - name: ES_SERVER_URLS
    value: "http://elasticsearch.nexus.svc.cluster.local:9200"
  - name: ES_TAGS_AS_FIELDS_ALL_FIELDS
    value: "true"
  # 数据保留 7 天
  - name: ES_USE_ALIASES
    value: "true"
```

### 6.2 Collector 水平扩展

生产环境 OTel Collector 可水平扩展（已配置 `replicas: 2`）。`tail_sampling` 处理器需要亲和性保证同一 trace 路由到同一 Collector 实例：

```yaml
spec:
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app.kubernetes.io/name: otel-collector
                topologyKey: kubernetes.io/hostname
```

### 6.3 采样策略

表：环境采样策略对照表

| 环境 | 服务端采样 | Collector 尾端采样 | 说明 |
| --- | --- | --- | --- |
| dev | 1.0（100%） | 关闭 | 全量采集，便于调试 |
| staging | 0.5（50%） | errors 100% + latency > 800ms 100% + 其余 10% | 错误 / 慢链路兜底 |
| prod | 0.1（10%） | errors 100% + latency > 800ms 100% + 其余 10% | 生产流量，错误链路必采 |

## 第7章 验证清单

### 7.1 部署验证

- [ ] `kubectl get pods -n nexus -l app.kubernetes.io/component=tracing` 全部 Running
- [ ] Jaeger UI `http://jaeger.nexus.local` 可访问
- [ ] OTel Collector 健康检查 `http://otel-collector:13133/` 返回 200
- [ ] Collector Prometheus 指标 `http://otel-collector:8888/metrics` 可抓取

### 7.2 链路验证

- [ ] 支付创建请求生成完整 span 树（gateway → signing → onchain → webhook）
- [ ] 桥锁定请求生成完整 span 树（bridge.lock → relayer → mint/burn）
- [ ] 签名编排生成 MPC 多轮 span（signing.mpc.round × N）
- [ ] 跨服务 trace 上下文通过 W3C traceparent 传播（同一 traceId）
- [ ] 业务 span 携带 @SpanTag 属性（payment.amount / bridge.lock.amount 等）

### 7.3 采样验证

- [ ] dev 环境 100% 采样（所有请求在 Jaeger 可见）
- [ ] 错误链路 100% 采集（HTTP 5xx / 异常 span）
- [ ] 慢链路 100% 采集（> 800ms）
- [ ] 生产环境 10% 概率采样（正常链路约 1/10 可见）

## 第8章 回滚方案

如需回滚至 Zipkin：

1. `build.gradle` 恢复 Brave + Zipkin 依赖
2. `application.yml` 恢复 `management.zipkin.*` 配置
3. 移除 `spring.config.import` 中的 `tracing.yml`
4. 删除追踪组件：

```bash
kubectl delete -f deploy/tracing/jaeger-deployment.yaml -n nexus
kubectl delete configmap otel-collector-config -n nexus
```

5. 恢复 Zipkin Server（`docker-compose.yml` 中已保留 zipkin 服务定义）

## 第9章 与监控体系集成

### 9.1 Collector 指标接入 Prometheus

OTel Collector 自身指标暴露在 `:8888/metrics`，可通过 ServiceMonitor 接入 kube-prometheus-stack：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: otel-collector
  namespace: nexus
  labels:
    release: kube-prometheus-stack
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: otel-collector
  endpoints:
    - port: metrics
      path: /metrics
      interval: 30s
```

### 9.2 Span 指标关联

通过 `trace_id` / `span_id` 关联日志（需 Logback 配置 MDC 注入）：

```xml
<!-- logback-spring.xml -->
<pattern>%d{ISO8601} [%thread] [%X{traceId},%X{spanId}] %-5level %logger{36} - %msg%n</pattern>
```

配合 Loki + Promtail 可在 Grafana 中按 `trace_id` 检索日志，实现 trace ↔ log 联动（P3-T5 将深化）。

## 第10章 P3-T5：trace_id 日志关联（Loki + Promtail）

### 10.1 部署 Loki + Promtail

```bash
# 应用 Promtail ConfigMap
kubectl apply -f deploy/tracing/promtail-config.yaml -n nexus

# 应用 Loki + Promtail 部署清单
kubectl apply -f deploy/tracing/loki-promtail-deployment.yaml -n nexus

# 等待 Pod 就绪
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=loki -n nexus --timeout=120s
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=promtail -n nexus --timeout=120s
```

### 10.2 Promtail pipeline_stages 说明

Promtail 通过 `pipeline_stages` 从 JSON 日志中提取 traceId / spanId 并提升为 Loki 标签：

1. `json` stage：解析 LogstashEncoder 产出的 JSON 日志，提取 `traceId` / `spanId` / `level` / `service` 字段
2. `labels` stage：将 `traceId` / `spanId` / `level` 提升为 Loki 标签
3. `timestamp` stage：使用 `@timestamp` 作为日志时间戳

### 10.3 trace ↔ log 联动查询

在 Grafana 中通过 Loki 数据源查询日志，按 `trace_id` 标签过滤：

```logql
{namespace="nexus", service="nexus-gateway", trace_id="abcdef1234567890"}
```

或在 Jaeger UI 中查看 trace 后，点击 trace 详情页的 "Logs" 标签，自动跳转到 Loki 按 trace_id 查询。

### 10.4 logback-spring.xml MDC 注入

4 个服务的 `logback-spring.xml` 已配置 `%X{traceId}` / `%X{spanId}` pattern（dev）和 `includeMdcKeyName`（prod），Micrometer Tracing 自动将 traceId / spanId 写入 SLF4J MDC。

### 10.5 异常 span 告警

在 `deploy/monitoring/alerting-rules.yaml` 中新增 `nexus.tracing` 告警组，共 4 条规则：

| 告警名 | 表达式简述 | 持续 | 严重级别 |
| --- | --- | --- | --- |
| SpanErrorRateHigh | span 错误率 > 5% | 5m | critical |
| SpanP99LatencyHigh | span P99 延迟 > 2s | 5m | warning |
| SpanDropRateHigh | OTel Collector span 丢弃率 > 1% | 5m | warning |
| BusinessSpanMissing | 业务 span 指标缺失 | 10m | warning |

### 10.6 业务 span 设计

业务 span 设计详见 `docs/tracing-business-span.md`，包括：

- 支付全链路 span 树（创建 → 路由 → 签名 → 上链 → Webhook）
- 桥跨链全链路 span 树（锁定 → Relayer → 铸造/销毁）
- 签名编排 span 树（MPC 多轮 → 阈值聚合 → 广播）
- span 属性命名约定

### 10.7 验证清单

- [ ] Loki Pod 运行正常（`kubectl get pods -n nexus -l app.kubernetes.io/name=loki`）
- [ ] Promtail DaemonSet 运行正常（`kubectl get pods -n nexus -l app.kubernetes.io/name=promtail`）
- [ ] 4 个服务的 logback-spring.xml 配置 `%X{traceId}` / `%X{spanId}` pattern
- [ ] Promtail 从 Pod 日志提取 traceId 并推送至 Loki
- [ ] 在 Grafana 中可按 `trace_id` 标签检索日志
- [ ] Jaeger UI 可查看业务流程 span 树（支付全链路 + 桥跨链全链路）
- [ ] 异常 span 告警规则配置完成（4 条规则）