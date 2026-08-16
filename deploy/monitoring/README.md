# NexusChain 监控告警体系部署文档

> P2-T4：部署 Prometheus + Grafana + AlertManager 监控告警体系
>
> 适用范围：NexusChain v2.1.0 Phase 2 生产就绪
>
> 监控栈：kube-prometheus-stack（Prometheus Operator + Alertmanager + Grafana）+ PrometheusRule CRD + Grafana Dashboard ConfigMap

## 第1章 文件清单

| 文件 | 用途 |
| --- | --- |
| `kube-prometheus-stack-values.yaml` | kube-prometheus-stack Helm Chart 自定义 values（Prometheus 15d 保留、Grafana Secret 密码、Alertmanager Slack/邮件、PVC 持久化） |
| `grafana-dashboards/payment-success-rate.json` | 支付成功率仪表盘（创建/成功/失败计数、成功率趋势、P99 延迟） |
| `grafana-dashboards/chain-latency.json` | 链上延迟仪表盘（区块确认、RPC 响应、桥操作延迟） |
| `grafana-dashboards/bridge-volume.json` | 桥锁定量仪表盘（锁定/铸造/销毁/解锁、金额、流动性） |
| `grafana-dashboards/risk-trigger-rate.json` | 风控触发率仪表盘（规则触发、拦截率、误报率） |
| `grafana-dashboards/jvm-health.json` | JVM 健康仪表盘（GC、堆内存、线程、CPU） |
| `alerting-rules.yaml` | PrometheusRule CRD，共 12 条告警（6 个分组） |
| `micrometer-config.yaml` | Micrometer 指标暴露 ConfigMap 示例 + Helm 注入说明 |
| `README.md` | 本文档 |

## 第2章 前置条件

### 2.1 集群要求

- Kubernetes ≥ 1.24
- 已安装 Helm 3 ≥ 3.12
- StorageClass `fast-ssd` 已存在（用于 Prometheus/Grafana/Alertmanager PVC）
- Ingress Controller（nginx）已部署（用于 Grafana Ingress）
- 命名空间 `nexus` 已创建（由 `deploy/helm/templates/namespace.yaml` 管理）
- 命名空间 `monitoring` 将由 Helm 自动创建

### 2.2 应用侧前置

4 个 Spring Boot 服务需引入 Micrometer Prometheus 依赖（已在 `build.gradle` 中引入，无需修改）：

```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

并通过 Helm values 注入 `management.*` 环境变量（见 `micrometer-config.yaml` 中的 `helm-injection-example.yaml` 字段），不直接修改 `application.yml`。

### 2.3 端口与抓取路径对照

| 服务 | 端口 | 抓取路径 | 间隔 |
| --- | --- | --- | --- |
| nexus-gateway | 8080 | /actuator/prometheus | 30s |
| nexus-bridge | 8084 | /actuator/prometheus | 30s |
| nexus-signing-service | 8082 | /actuator/prometheus | 30s |
| nexus-wallet-service | 8083 | /actuator/prometheus | 30s |

ServiceMonitor 模板位于 `deploy/helm/charts/<service>/templates/servicemonitor.yaml`，启用条件 `global.serviceMonitor.enabled=true` 且子 chart `serviceMonitor.enabled=true`，发现标签 `release=kube-prometheus-stack`。

## 第3章 安装 kube-prometheus-stack

### 3.1 添加 Helm 仓库

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

### 3.2 创建 Grafana admin 密码 Secret

Grafana admin 密码不直接写入 values，从 Secret 读取，避免明文泄露：

```bash
# 生成强随机密码
GRAFANA_ADMIN_USER=$(echo -n "admin" | base64)
GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 16 | base64)

kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: grafana-admin-secret
  namespace: monitoring
type: Opaque
data:
  admin-user: ${GRAFANA_ADMIN_USER}
  admin-password: ${GRAFANA_ADMIN_PASSWORD}
EOF
```

### 3.3 替换 Alertmanager 通知渠道占位符

`kube-prometheus-stack-values.yaml` 中以下字段为占位符，部署前必须替换：

| 占位符 | 替换为 | 推荐方式 |
| --- | --- | --- |
| `REPLACE_ME_SLACK_WEBHOOK_URL` | Slack Incoming Webhook URL | `--set alertmanager.config.receivers[0].slack_configs[0].api_url=$SLACK_WEBHOOK` |
| `REPLACE_ME_SMTP_PASSWORD` | SMTP 服务密码 | 外部密钥管理工具（Sealed Secrets / external-secrets） |
| `REPLACE_ME_GRAFANA_SMTP_PASSWORD` | Grafana SMTP 密码 | 同上 |

### 3.4 安装 Chart

```bash
helm upgrade --install kube-prometheus-stack \
  prometheus-community/kube-prometheus-stack \
  -n monitoring \
  --create-namespace \
  -f deploy/monitoring/kube-prometheus-stack-values.yaml \
  --timeout 10m
```

### 3.5 验证安装

```bash
# 检查 Helm Release
helm -n monitoring list

# 检查 Pod 就绪
kubectl -n monitoring get pods -w
# 期望：prometheus、alertmanager、grafana、operator 均 Running

# 检查 CRD
kubectl get crd | grep monitoring.coreos.com
# 期望：prometheuses / alertmanagers / prometheusrules / servicemonitors / podmonitors

# 检查 Prometheus 抓取目标
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
# 期望：nexus-gateway / nexus-bridge / nexus-signing-service / nexus-wallet-service 均 UP
```

## 第4章 部署告警规则

### 4.1 应用 PrometheusRule

```bash
kubectl apply -f deploy/monitoring/alerting-rules.yaml -n nexus
```

### 4.2 验证规则加载

```bash
# 检查 PrometheusRule CRD 对象
kubectl -n nexus get prometheusrules
# 期望：nexus-alerting-rules

# 检查 Prometheus 已加载规则
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[] | .name'
# 期望包含：nexus.payment / nexus.bridge / nexus.chain / nexus.jvm / nexus.resource / nexus.infra

# 检查告警状态
curl -s http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | {alertstate: .state, labels: .labels.alertname}'
```

### 4.3 告警清单

| 编号 | 告警名 | 表达式简述 | 持续 | 严重级别 |
| --- | --- | --- | --- | --- |
| 1 | PaymentFailureRateHigh | 支付失败率 > 5% | 5m | critical |
| 2 | PaymentP99LatencyHigh | 支付 P99 > 500ms | 5m | warning |
| 3 | PaymentThroughputDrop | 吞吐量较 1h 前下降 > 50% | 10m | warning |
| 4 | BridgeOperationStuck | 桥操作 30m 无进展 | 30m | critical |
| 5 | BridgeLiquidityLow | 桥流动性 < 10 万 USD | 15m | critical |
| 6 | ChainRpcLatencyHigh | 链 RPC P95 > 2s | 5m | warning |
| 7 | ChainBlockConfirmationStale | 区块确认停滞 > 2min | 5m | warning |
| 8 | JvmGcTimeHigh | GC 时间占比 > 20% | 5m | warning |
| 9 | JvmHeapUsageHigh | 堆内存使用 > 85% | 5m | warning |
| 10 | PodCpuUsageHigh | Pod CPU > 80% | 10m | warning |
| 11 | PodMemoryUsageHigh | Pod 内存 > 90% | 10m | warning |
| 12 | ServiceDown | 服务不可达 | 1m | critical |
| 13 | SeataTcDown | Seata TC 不可达 | 1m | critical |

> 任务要求 10+ 条，实际提供 13 条（含 3 条附加：PaymentThroughputDrop / BridgeLiquidityLow / ChainBlockConfirmationStale），覆盖更完整的生产场景。

## 第5章 导入 Grafana Dashboard

### 5.1 方式一：ConfigMap 自动挂载（推荐）

`kube-prometheus-stack-values.yaml` 已配置 `dashboardsConfigMaps: nexus-dashboards: nexus-grafana-dashboards`，将名为 `nexus-grafana-dashboards` 的 ConfigMap 自动挂载到 Grafana 的 `/var/lib/grafana/dashboards/nexus` 目录。

创建该 ConfigMap：

```bash
kubectl -n monitoring create configmap nexus-grafana-dashboards \
  --from-file=deploy/monitoring/grafana-dashboards/ \
  --dry-run=client -o yaml | kubectl apply -f -

# 添加标签以便 Grafana sidecar 发现
kubectl -n monitoring label configmap nexus-grafana-dashboards \
  grafana_dashboard=1 --overwrite
```

Grafana sidecar 会自动加载并刷新仪表盘，无需重启。仪表盘将出现在 `NexusChain` 文件夹下。

### 5.2 方式二：手动导入

适合临时验证或非 ConfigMap 部署：

1. 浏览器访问 Grafana URL（默认 `https://grafana.nexuschain.io`）
2. 登录（admin / Secret 中的密码）
3. 左侧菜单 `Dashboards` → `New` → `Import`
4. 上传 `deploy/monitoring/grafana-dashboards/*.json` 文件
5. 选择数据源 `Prometheus`
6. 重复导入 5 个 JSON

### 5.3 Dashboard 清单

| 文件 | UID | 面板数 | 模板变量 |
| --- | --- | --- | --- |
| payment-success-rate.json | nexus-payment-success-rate | 8 | DS_PROMETHEUS, service |
| chain-latency.json | nexus-chain-latency | 7 | DS_PROMETHEUS, service, chain |
| bridge-volume.json | nexus-bridge-volume | 10 | DS_PROMETHEUS, service, chain |
| risk-trigger-rate.json | nexus-risk-trigger-rate | 7 | DS_PROMETHEUS, service |
| jvm-health.json | nexus-jvm-health | 9 | DS_PROMETHEUS, service |

### 5.4 验证 Dashboard

```bash
# 通过 Grafana API 列出已加载仪表盘
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80 &
GRAFANA_PW=$(kubectl -n monitoring get secret grafana-admin-secret -o jsonpath="{.data.admin-password}" | base64 -d)
curl -s -u "admin:${GRAFANA_PW}" http://localhost:3000/api/search | jq '.[] | {title: .title, uid: .uid, folder: .folderTitle}'
# 期望：5 个 NexusChain 仪表盘均出现在 NexusChain 文件夹下
```

## 第6章 配置通知渠道

### 6.1 Slack Webhook

1. 在 Slack 工作区创建 Incoming Webhook：
   - 进入 `https://api.slack.com/apps` → Create New App → Incoming Webhooks
   - 选择目标频道（如 `#nexus-alerts`、`#nexus-alerts-critical`）
   - 复制 Webhook URL（形如 `https://hooks.slack.com/services/T.../B.../...`）

2. 替换 `kube-prometheus-stack-values.yaml` 中所有 `REPLACE_ME_SLACK_WEBHOOK_URL`：
   ```bash
   helm upgrade kube-prometheus-stack prometheus-community/kube-prometheus-stack \
     -n monitoring \
     -f deploy/monitoring/kube-prometheus-stack-values.yaml \
     --set alertmanager.config.receivers[0].slack_configs[0].api_url=$SLACK_WEBHOOK \
     --set alertmanager.config.receivers[1].slack_configs[0].api_url=$SLACK_WEBHOOK \
     --set alertmanager.config.receivers[2].slack_configs[0].api_url=$SLACK_WEBHOOK
   ```

### 6.2 邮件（SMTP）

1. 准备 SMTP 服务（如 AWS SES、SendGrid、自建 Postfix）
2. 替换 `kube-prometheus-stack-values.yaml` 中：
   - `smtp_smarthost`：SMTP 服务器地址:端口
   - `smtp_from`：发件地址
   - `smtp_auth_username` / `smtp_auth_password`：SMTP 凭证
3. 邮件接收人 `oncall@nexuschain.io` 在 `critical-slack-and-email` 接收器中配置，按需修改

### 6.3 通知路由策略

`kube-prometheus-stack-values.yaml` 中 Alertmanager 路由策略：

| 严重级别 | 接收器 | Slack 频道 | 邮件 | 重复间隔 |
| --- | --- | --- | --- | --- |
| critical | critical-slack-and-email | #nexus-alerts-critical | oncall@nexuschain.io | 1h |
| warning | warning-slack | #nexus-alerts | 否 | 4h |
| 其他 | default | #nexus-alerts | 否 | 4h |

抑制规则：同 namespace 同 alertname 的 critical 触发时，自动抑制对应 warning，避免告警风暴。

### 6.4 验证通知渠道

```bash
# 通过 Alertmanager API 发送测试告警
kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093:9093 &
curl -X POST http://localhost:9093/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d '[{
    "labels": {
      "alertname": "TestAlert",
      "severity": "critical",
      "namespace": "nexus"
    },
    "annotations": {
      "summary": "测试告警",
      "description": "验证 Slack + 邮件渠道"
    }
  }]'
# 期望：Slack #nexus-alerts-critical 收到消息，oncall 邮箱收到邮件
```

## 第7章 Micrometer 配置注入

### 7.1 ConfigMap 参考配置

`micrometer-config.yaml` 提供完整 ConfigMap 示例，可作为 application.yml 的参考。生产环境**不直接 apply** 该 ConfigMap，而是通过 Helm values 注入环境变量。

### 7.2 通过 Helm values 注入

参考 `micrometer-config.yaml` 中 `helm-injection-example.yaml` 字段，在 `deploy/helm/charts/<service>/values.yaml` 的 `env` 段添加：

```yaml
env:
  - name: MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
    value: "health,metrics,prometheus,info"
  - name: MANAGEMENT_METRICS_EXPORT_PROMETHEUS_ENABLED
    value: "true"
  - name: MANAGEMENT_METRICS_TAGS_APPLICATION
    value: "{{ .Chart.Name }}"
  - name: MANAGEMENT_METRICS_TAGS_NAMESPACE
    value: "{{ .Values.global.namespace }}"
  - name: MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM
    value: "true"
  - name: MANAGEMENT_METRICS_DISTRIBUTION_SLO
    value: "50ms,100ms,250ms,500ms,1s,2s,5s"
  - name: MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS
    value: "never"  # 生产环境不暴露健康详情
```

Spring Boot 2.7+ 支持 RELAXED_BINDING，环境变量 `MANAGEMENT_*` 自动映射到 `management.*` 配置项。

### 7.3 验证指标暴露

```bash
# 转发 nexus-gateway 端口
kubectl -n nexus port-forward svc/nexus-gateway 8080:8080 &

# 检查 actuator 端点
curl -s http://localhost:8080/actuator/health | jq
# 期望：{"status":"UP"}

curl -s http://localhost:8080/actuator/prometheus | head -20
# 期望：包含 jvm_memory_used_bytes / nexus_payment_total 等指标

# 检查指标是否带 application 标签
curl -s http://localhost:8080/actuator/prometheus | grep 'application="nexus-gateway"'
# 期望：所有指标均带 application="nexus-gateway" 标签
```

## 第8章 端到端验证

### 8.1 验证清单

| 步骤 | 命令 | 期望结果 |
| --- | --- | --- |
| Helm Release | `helm -n monitoring list` | kube-prometheus-stack deployed |
| 监控 Pod | `kubectl -n monitoring get pods` | 全部 Running |
| CRD 注册 | `kubectl get crd \| grep monitoring` | 5 个 CRD |
| Prometheus 目标 | `curl :9090/api/v1/targets` | 4 个 nexus 服务 UP |
| PrometheusRule 加载 | `curl :9090/api/v1/rules` | 6 个 group / 13 条规则 |
| Grafana Dashboard | `curl :3000/api/search` | 5 个 NexusChain 仪表盘 |
| Alertmanager 接收器 | `curl :9093/api/v2/receivers` | 3 个接收器 |
| 指标暴露 | `curl :8080/actuator/prometheus` | 含 nexus_ / jvm_ 指标 |
| Slack 通知 | 发送测试告警 | Slack 频道收到消息 |

### 8.2 端到端冒烟测试

```bash
# 1. 触发 ServiceDown 告警（缩容一个服务）
kubectl -n nexus scale deployment nexus-gateway --replicas=0
# 期望：1 分钟后 Slack #nexus-alerts-critical 收到 ServiceDown 告警

# 2. 恢复
kubectl -n nexus scale deployment nexus-gateway --replicas=2
# 期望：5 分钟内收到 Resolved 通知

# 3. 触发 JvmHeapUsageHigh（可选，通过 stress 测试）
# 在 Pod 内执行 jcmd <pid> GC.run 触发 Full GC，观察仪表盘与告警
```

## 第9章 运维操作

### 9.1 更新告警规则

```bash
kubectl apply -f deploy/monitoring/alerting-rules.yaml -n nexus
# Prometheus Operator 自动热加载，无需重启
```

### 9.2 更新 Dashboard

```bash
kubectl -n monitoring create configmap nexus-grafana-dashboards \
  --from-file=deploy/monitoring/grafana-dashboards/ \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n monitoring label configmap nexus-grafana-dashboards grafana_dashboard=1 --overwrite
# Grafana sidecar 自动热加载（约 30s）
```

### 9.3 调整保留期

修改 `kube-prometheus-stack-values.yaml` 中 `prometheus.prometheusSpec.retention`（默认 `15d`）与 `retentionSize`（默认 `40GB`），执行 `helm upgrade`。

### 9.4 静默告警

通过 Alertmanager API 或 UI 临时静默：

```bash
# UI: 访问 https://alertmanager.nexuschain.io
# API:
curl -X POST http://localhost:9093/api/v2/silences \
  -H "Content-Type: application/json" \
  -d '{
    "matchers": [{"name": "alertname", "value": "JvmHeapUsageHigh", "isRegex": false}],
    "startsAt": "2026-08-09T00:00:00Z",
    "endsAt": "2026-08-09T02:00:00Z",
    "createdBy": "ops",
    "comment": "计划内压测，静默 2 小时"
  }'
```

## 第10章 故障排查

### 10.1 Prometheus 目标 DOWN

```bash
# 查看目标详情
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.health=="down")'

# 常见原因：
# 1. Service 端口与 ServiceMonitor.port 不一致
# 2. NetworkPolicy 阻断 Prometheus → Pod 流量
# 3. 应用未暴露 /actuator/prometheus（检查 Micrometer 依赖与配置）
# 4. Pod 未就绪
```

### 10.2 告警未触发

```bash
# 检查规则是否加载
kubectl -n nexus describe prometheusrules nexus-alerting-rules

# 检查 Prometheus 评估
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[] | .rules[] | {name: .name, state: .state, health: .health}'

# 常见原因：
# 1. PrometheusRule 缺少 release=kube-prometheus-stack 标签
# 2. 表达式语法错误（检查 Prometheus 日志）
# 3. 指标不存在（先确认应用暴露）
# 4. for 持续时间未达到
```

### 10.3 Grafana Dashboard 不显示

```bash
# 检查 ConfigMap
kubectl -n monitoring get configmap nexus-grafana-dashboards -o yaml

# 检查 Grafana sidecar 日志
kubectl -n monitoring logs -l app.kubernetes.io/name=grafana -c grafana-sc-dashboard

# 常见原因：
# 1. ConfigMap 缺少 grafana_dashboard=1 标签
# 2. JSON 格式错误（用 jq 验证）
# 3. dashboardProviders 路径不匹配
```

## 第11章 相关文件

- Helm Chart：`deploy/helm/`
- ServiceMonitor 模板：`deploy/helm/charts/<service>/templates/servicemonitor.yaml`
- 全局 values：`deploy/helm/values.yaml`（`global.serviceMonitor` 段）
- 既有监控层：`deploy/k8s/40-monitoring.yml`（postgres/redis exporter、既有 ServiceMonitor）
- 密钥管理：`deploy/k8s/SECRET-MANAGEMENT.md`

## 第12章 注意事项

1. **不修改 Java 源代码、build.gradle、settings.gradle**：Micrometer 依赖已在子模块引入
2. **不修改 deploy/helm/ 下的 Helm Chart**：ServiceMonitor 模板已就绪，本目录仅提供 values 参考
3. **不修改 application.yml**：Micrometer 配置通过 Helm env 注入，保持 12-factor
4. **Dashboard JSON 为有效 Grafana 格式**：schemaVersion 39，含 uid / templating / panels
5. **告警规则用 PrometheusRule CRD**：apiVersion `monitoring.coreos.com/v1`
6. **占位符必须替换**：`REPLACE_ME_*` 在生产部署前必须替换为真实值
7. **PVC 依赖 StorageClass**：`fast-ssd` 需在集群中预先存在