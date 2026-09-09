# NexusChain Grafana Dashboard（服务端服务监控）

> 说明：本目录存放可直接导入 Grafana 的服务端 dashboard JSON。所有 PromQL 引用的 metric 名均**以仓库证据为准**（Java 源码中的 MeterRegistry 注册、alerts/ServiceMonitor/HPA 规则引用的真实指标），不虚构 metric。本 README 为文档性质——不修改 kustomize / Helm Chart / CI（GitOps 接入留给后续批次）。

## 第1章 文件清单

| 文件 | UID | 对应服务 | 面板数 | 关键指标 |
| --- | --- | --- | --- | --- |
| `dashboards/nexus-overview.json` | `nexus-overview` | 全部服务总览 | 20 | `up`、`http_server_requests_seconds_count/_sum/_bucket`、`jvm_memory_*`、`jvm_gc_pause_seconds_sum`、`nexuschain_block_height`、`ALERTS` |
| `dashboards/nexus-gateway.json` | `nexus-gateway` | nexus-gateway（8080） | 24 | 上述通用指标 + `nexus_orders_created_total` / `nexus_payments_confirmed_total` / `nexus_payments_failed_total` / `nexus_refunds_issued_total` / `nexus_webhooks_delivered_total` / `nexus_webhooks_failed_total` / `nexus_payment_latency_seconds_*` |
| `dashboards/nexus-api-gateway.json` | `nexus-api-gateway` | nexus-api-gateway（8085） | 21 | HTTP/JVM/容器通用指标（无自定义业务指标） |
| `dashboards/nexus-wallet-service.json` | `nexus-wallet-service` | nexus-wallet-service（8083） | 21 | HTTP/JVM/容器通用指标（无自定义业务指标） |
| `dashboards/nexus-signing-service.json` | `nexus-signing-service` | nexus-signing-service（8082） | 21 | HTTP/JVM/容器通用指标（无自定义业务指标） |
| `dashboards/nexus-core.json` | `nexus-core` | nexus-core（19585） | 24 | HTTP/JVM 通用指标 + `nexuschain_block_height`（块高/出块速率/抓取滞后） |
| `dashboards/backend-services.json` | `nexus-backend-services` | settlement / compliance / analytics / oracle（占位） | 6 | 仅 `up{}` + 说明文本（见第6章） |
| `dashboards/mpc-engine.json` | `nexus-mpc-engine` | mpc-engine（Rust，gRPC 50051，占位） | 7 | `up{}` + K8s 资源面（`container_*` / `kube_pod_*`） |

另存在上一批次（P2-T4）的 5 个业务面 dashboard，位于 `deploy/monitoring/grafana-dashboards/`（payment-success-rate / chain-latency / bridge-volume / risk-trigger-rate / jvm-health），**已废弃**（引用文档约定但代码从未注册的 metric 名，业务面板全无数据）——详见该目录 `README.md` 的退役说明，勿再挂载。本批 dashboard 才引用代码真实注册名。

## 第2章 数据源变量

每个 dashboard 均含模板变量 `DS_PROMETHEUS`（`"type":"datasource","query":"prometheus"`），导入时选择你的 Prometheus 数据源即可；若你的数据源 UID 非标准值，导入后修改该变量刷新重选一次即可。

## 第3章 挂载方式

### 3.1 ConfigMap + Grafana sidecar（kube-prometheus-stack，推荐）

仓库现有 `deploy/monitoring/kube-prometheus-stack-values.yaml` 已配置：

```yaml
grafana:
  sidecar:
    dashboards:
      enabled: true
      provider: ...            # /var/lib/grafana/dashboards/nexus
  dashboardsConfigMaps:
    nexus-dashboards: "nexus-grafana-dashboards"   # ConfigMap 名
```

将本目录 JSON 打进 ConfigMap（sidecar 会按文件名自动热加载；**不要**把废弃的 `deploy/monitoring/grafana-dashboards/` 一并挂入）：

```bash
kubectl -n monitoring create configmap nexus-grafana-dashboards \
  --from-file=deploy/grafana/dashboards/ \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n monitoring label configmap nexus-grafana-dashboards \
  grafana_dashboard=1 --overwrite
```

Grafana sidecar 约 30s 后自动加载，仪表盘出现在 `NexusChain` 文件夹。

### 3.2 grafana.com/dashboards annotation 方式（仅文档）

如果使用社区 `k8s-sidecar`（kiwigrid）而非 kube-prometheus-stack 自带 sidecar，可在监控命名空间加注解自动发现全部 namespace 内的仪表盘 ConfigMap：

```bash
# 让 sidecar 扫描 nexus 命名空间寻找带注解的 ConfigMap
kubectl -n nexus annotate namespace nexus grafana.com/dashboards="1"

kubectl -n nexus create configmap nexus-grafana-extra \
  --from-file=deploy/grafana/dashboards/ --dry-run=client -o yaml | kubectl apply -f -
```

> 本文档只说明接入方式；是否切换到 annotation 模式请按部署形态自行决策，本批不修改任何 kustomize / chart。

### 3.3 手动导入

Grafana UI → `Dashboards` → `New` → `Import` → 上传 JSON → 选择数据源 `Prometheus`。

## 第4章 关键指标来源对照（证据）

### 4.1 已核实真实存在、dashboard 实际引用

| Metric | 来源证据 |
| --- | --- |
| `http_server_requests_seconds_count/_sum/_max` | Spring Boot 3 Micrometer 默认 HTTP 观测（所有引入 actuator + micrometer-registry-prometheus 的服务）；`deploy/monitoring/prometheus-adapter-rules.yaml` 的 HPA 规则引用 `http_server_requests_seconds_count`，标签 `uri/method/status/outcome/exception` |
| `http_server_requests_seconds_bucket` | **仅在注入** `management.metrics.distribution.percentiles-histogram=true`（或 SLO）后导出；参考 `deploy/monitoring/micrometer-config.yaml`，经 Helm env 注入。仓库当前没有任何已部署配置实际注入该开关 → 直方图分位数面板**可能无数据**，可用每个 dashboard 里的"平均时延"面板 |
| `jvm_memory_used_bytes` / `jvm_memory_committed_bytes` / `jvm_memory_max_bytes`（label `area=heap/nonheap`、`id`） | Micrometer JVM 默认指标；`deploy/k8s/50-prometheus-rules.yaml` 的 `JvmHeapUsageHigh` 用 `jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes{area="heap"}` |
| `jvm_gc_pause_seconds_sum/_count`（label `cause`、`action`） | 同上；`JvmGcTimeHigh` 告警引用 `jvm_gc_pause_seconds_sum` |
| `jvm_threads_live_threads` / `jvm_threads_peak_threads` / `jvm_threads_daemon_threads` | Micrometer JVM 默认指标 |
| `nexus_orders_created_total`、`nexus_payments_confirmed_total`、`nexus_payments_failed_total`、`nexus_refunds_issued_total`、`nexus_webhooks_delivered_total`、`nexus_webhooks_failed_total`、`nexus_payment_latency_seconds_sum/_count/_max` | `nexus-gateway/src/main/java/org/nexus/gateway/observability/PaymentMetrics.java`：`Counter.builder("nexus.orders.created")` 等、`Timer.builder("nexus.payment.latency")`。PrometheusNamingConvention 将句点/驼峰转下划线并追加 `_total`/`_seconds` |
| `nexuschain_block_height` | `nexus-core/nexus-core/src/main/java/org/nexus/metrics/CoreMetricsConfig.java`：`Gauge.builder("nexuschain_block_height")`（StateDB bestBlock）。`deploy/k8s/50-prometheus-rules.yaml` 的 `BlockchainCoreStalled` 告警引用 |
| `up` | Prometheus 抓取目标自带；`ServiceDown` / `SeataTcDown` 告警引用 |
| `ALERTS` | Prometheus 内置告警状态指标（规则加载后才存在） |
| `container_cpu_usage_seconds_total` / `container_memory_working_set_bytes` / `kube_pod_status_phase` / `kube_pod_container_status_restarts_total` | cAdvisor（kubelet）与 kube-state-metrics，仅 K8s 环境（kube-prometheus-stack 默认采集）；`deploy/k8s/50-prometheus-rules.yaml` 的 `MpcEngineCrashLooping` / `PodCpuUsageHigh` / `PodMemoryUsageHigh` 引用同源 kube_* |

### 4.2 job 标签的两种形态（dashboard 已兼容）

- K8s + Prometheus Operator（ServiceMonitor，`release=kube-prometheus-stack` 发现）：`job="<namespace>/<ServiceMonitor名>"`（形如 `nexus/nexus-gateway`）；
- 本地 `prometheus.yml`（repo 根，docker-compose 场景）静态配置：`job="nexus-gateway"` 等 6 个（gateway/core/signing/wallet/bridge/api-gateway）。

因此所有面板用 `job=~".*nexus-gateway.*"` 这类正则同时匹配两种形态。若你按其他方式命名 job（如自定义 jobLabel），面板顶部无变量可改、需直接改对应 JSON 里的正则。

### 4.3 各服务的 metric 标签说明

- 项目没有全局 meterfilter / common tags（已全仓检索 `MeterFilter`、`commonTags`、`metrics.tags`、Helm `MANAGEMENT_METRICS_TAGS_*` 注入：除 `deploy/monitoring/micrometer-config.yaml` 参考文档外**均无实际配置**）——因此**不存在 `service`/`application` 标签**，dashboard 一律按 `job` 区分服务；旧批次（P2-T4）dashboard 里的 `service="$service"` 选择器在现网不会命中。
- K8s 相关面板额外使用 `namespace="nexus"` + `pod=~"...-.*"`（pod 名从 Deployment/StatefulSet 模板推导，容器名 = Chart 名，例外：nexus-core 的容器名是 `core`，面板里已按此写死 pod 前缀 `nexus-core-.*`）。

## 第5章 服务采点与抓取对照

| 服务 | Port | 路径 | ServiceMonitor | 自定义业务指标 |
| --- | --- | --- | --- | --- |
| nexus-gateway | 8080 | /actuator/prometheus | `deploy/helm/charts/nexus-gateway/templates/servicemonitor.yaml` | 有（PaymentMetrics，见 4.1） |
| nexus-api-gateway | 8085 | /actuator/prometheus | `deploy/helm/charts/nexus-api-gateway/templates/servicemonitor.yaml` | 无 |
| nexus-wallet-service | 8083 | /actuator/prometheus | `deploy/helm/charts/nexus-wallet-service/templates/servicemonitor.yaml` | 无 |
| nexus-signing-service | 8082 | /actuator/prometheus | `deploy/helm/charts/nexus-signing-service/templates/servicemonitor.yaml` | 无 |
| nexus-core | 19585 | /actuator/prometheus | `deploy/helm/charts/nexus-core/templates/servicemonitor.yaml`（headless `rpc` 端口） | 有（`nexuschain_block_height`） |
| nexus-bridge | 8084 | /actuator/prometheus | `deploy/helm/charts/nexus-bridge/templates/servicemonitor.yaml` | 无（本轮未做专属 dashboard，可在总览查看其 HTTP/JVM） |
| mpc-engine | 50051（gRPC） | — | `deploy/helm/charts/mpc-engine/templates/servicemonitor.yaml`（`/metrics` 指向 gRPC 端口，无法工作） | **完全无应用指标**（见第6章） |

## 第6章 诚实现状清单（无指标的服务）

1. **mpc-engine（Rust）**：`Cargo.toml` 无 prometheus / metrics / actix / axum / hyper 等依赖，`src/` 仅 gRPC（tonic + mTLS）。已存在的 ServiceMonitor 抓取 `/metrics` 会失败——`up{job=~"mpc-engine"}` 恒为 0 属预期。`mpc-engine.json` 为占位：仅 `up{}` + K8s 资源面（CPU/内存/重启/Pod 阶段）真实面板，并含说明文本。引擎健康请以 K8s TCP 探活与签名成功率判断。
2. **nexus-settlement / nexus-compliance / nexus-analytics / nexus-oracle**：`build.gradle` 未引入 actuator 与 micrometer-registry-prometheus；无 Helm chart、无 ServiceMonitor、不在 `prometheus.yml` 静态列表中——Prometheus 中连 `up{}` 都不存在。`backend-services.json` 为占位：说明文本 + 4 个 `up{}`（显示 No data 属预期）+ 接入指引。
3. **nexus-wallet-service / nexus-signing-service / nexus-api-gateway**：有标准 HTTP/JVM 指标（build.gradle 已引入依赖、chart 注入 `management.endpoints.web.exposure.include`），但**代码中未注册任何自定义业务 Counter/Timer**（全仓检索仅 nexus-gateway 与 nexus-core 两处注册）。对应 dashboard 用文本面板如实标注并保留 HTTP/JVM 全量面板。
4. **告警规则与代码命名不一致（历史遗留，已在本批解决）**：原 `deploy/monitoring/alerting-rules.yaml` 与旧 dashboard 引用约定名 `nexus_payment_total` / `nexus_payment_failure_total` / `nexus_payment_duration_seconds_bucket` / `nexus_bridge_*` / `nexus_chain_*` / `nexus_risk_*` / `nexus_span_*`，但代码实际注册的是 `nexus.payments.confirmed`（→ `nexus_payments_confirmed_total`）等另一套名——那些告警/旧 dashboard 永不触发/无数据。本批（2026-09-09）已退役该文件：其中引用幽灵指标的 11 条规则删除、6 条真实指标规则（JVM/GC、Pod 资源、ServiceDown、SeataTcDown）并入 `deploy/k8s/50-prometheus-rules.yaml`；旧 dashboard 标注废弃。本节只引用代码真实注册名。
5. **histogram buckets 未开启**：分位数（P50/P95/P99）面板依赖 `_bucket` 序列，而 `management.metrics.distribution.percentiles-histogram` 等注入目前在 Helm 配置里不存在（仅参考文档）。相关面板已写明依赖并给出"平均时延"兜底面板。

## 第7章 校验

- 每个 JSON 均为合法 Grafana schema（schemaVersion 39、uid 全局唯一、`time.from=now-6h`、`refresh=30s`、面板类型仅 timeseries/stat/gauge/table/text）。
- 已用 Node（`E:\dev-tools\nodejs\node.exe`）逐个 `JSON.parse` 并通过括号配对检查（校验脚本为一次性工具，位于 repo 外 `F:\Nexus\build\`，不入库）。复验方法：`node -e "JSON.parse(require('fs').readFileSync('dashboards/nexus-overview.json'))"`。

## 第8章 与告警（PrometheusRule）的联动

总览 dashboard 中"firing 告警列表 / 告警数"用 `ALERTS` 指标直接展示 `deploy/k8s/50-prometheus-rules.yaml`（11 条，CI 校验的唯一告警来源）与 `deploy/k8s/40-monitoring.yml` 内规则的实际 firing 状态；各面板阈值（5xx 5%、GC 20%、堆 85%）与对应告警阈值对齐，便于在 dashboard 上预判告警。