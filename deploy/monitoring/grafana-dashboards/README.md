# ⚠ 本目录 5 个 dashboard 已废弃（2026-09-09）

`payment-success-rate / chain-latency / bridge-volume / risk-trigger-rate / jvm-health`
这批 dashboard 是在"文档约定指标名"（`nexus_payment_total`、`nexus_bridge_*`、
`nexus_chain_rpc_duration_seconds` 等）假想下编写的，而这些指标名**从未在
代码中注册**——导入 Grafana 后所有业务面板永久无数据。

代码实际注册的业务指标：

| 服务 | 真实指标名 |
| --- | --- |
| nexus-gateway（PaymentMetrics.java） | `nexus_orders_created_total`、`nexus_payments_confirmed_total`、`nexus_payments_failed_total`、`nexus_refunds_issued_total`、`nexus_webhooks_delivered_total`、`nexus_webhooks_failed_total`、`nexus_payment_latency_seconds_*` |
| nexus-core（CoreMetricsConfig.java） | `nexuschain_block_height` |

**请改用 `deploy/grafana/dashboards/` 的 dashboard**（基于上述真实注册名 +
HTTP/JVM/K8s 通用指标，且以 `job=~".*nexus-.*"` 兼容 ServiceMonitor 与
静态抓取两种形态）。本目录仅作为历史留档保留，不要再挂进
`nexus-grafana-dashboards` ConfigMap。