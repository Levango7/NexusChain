# PrometheusRule 告警

## 设计原则

- 起步只 **4 条 critical/warning**（设计稿 PLAN-002 §3.2）
- 全部用 `kube_*` 通用 metrics——**不依赖应用暴露 prom metrics**
- `for: 5m` 强制——避免抖动误报
- 告警标签统一（severity + component + service）

## 5 条告警（2026-09-07 升级：+1 条精确链停告警）

| 名称 | 级别 | 触发条件 | 含义 |
|---|---|---|---|
| BlockchainCoreStalled | critical | `max(nexuschain_block_height)` 5min 无增长 | 链停摆（精确信号） |
| MpcEngineCrashLooping | critical | mpc-engine 容器 CrashLoopBackOff ≥ 5min | 签名服务停摆 |
| MpcEngineNotReady | warning | mpc-engine Pod 至少 1 个非 Ready ≥ 5min | 安全冗余损失 |
| MpcEngineResourceUnbound | critical | PVC 未绑定 或 mpc-engine Pod 非 Running ≥ 5min | 存储或调度故障 |
| NexusServiceUnhealthy | warning | Java 编排服务不可用副本 > 50% 持续 5min | 任何 Java 服务降级 |

**BlockchainCoreStalled 说明（2026-09-07 应用 metrics 落地）**：
- 数据链路：`CoreMetricsConfig`（nexus-core）注册 `nexuschain_block_height`
  gauge（lazy 读 `stateDB.getBestBlock().nHeight`）→ `/actuator/prometheus`
  （actuator + micrometer-registry-prometheus，同 19585 端口）→ nexus-core
  ServiceMonitor（deploy/helm/charts/nexus-core/templates/servicemonitor.yaml，
  抓 headless Service rpc 端口）→ Prometheus
- 与 MpcEngineCrashLooping **互补保留**：链停可能是 mpc-engine 挂（CrashLoop
  覆盖）也可能是共识/存储问题（块高覆盖后者的盲区）
- **dev/staging 噪音警告**：genesis 刚起或单节点 dev 块高不增长是预期——
  仅 prod（多节点 + ENABLE_MINING=true）视为停摆；其他环境按
  Alertmanager 路由静默

## 后续升级路径（gRPC 错误率）

当前**不告警**：gRPC 错误率——需 nexus-signing-service 暴露客户端 metrics
（2.x TODO）。

## CI 验证

`.github/workflows/k8s-sync-check.yml` 新增 "PrometheusRule 静态校验" step：
- `kubectl apply --dry-run=client -f deploy/k8s/50-prometheus-rules.yaml` 验 CRD schema
- 告警数 ≥ 4
- `for:` 字段必填（避免抖动误报）

## 部署

```bash
# 1. 集群装 kube-prometheus-stack（一次性）
helm install prom prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace

# 2. apply 告警
kubectl apply -f deploy/k8s/50-prometheus-rules.yaml

# 3. 验证
kubectl get prometheusrules -n nexus
# 应看到 nexuschain-alerts CRD
```

## 已知限制（诚实标注——2026-09-07 完善批）

1. **namespace 硬编码 `nexus`**：所有告警表达式的 `namespace="nexus"`
   仅匹配 prod 命名空间。dev（nexus-dev）/ staging（nexus-staging）
   集群 apply 同一文件**不会触发任何告警**——多集群多命名空间部署
   需要按环境改写表达式（或用 PrometheusRule 的 `excludedFromEnforcement`/
   生成器参数化——留待需要时做）。

2. **`MpcEngineNotReady` 表达式过宽**：`kube_pod_status_ready{namespace="nexus"} == 0`
   匹配命名空间内**所有**非 Ready Pod（不只 mpc-engine）——nexus namespace
   只跑 mpc-engine + 各 Java 服务，Java 服务 Pod 挂也会触发此"mpc"告警
   （语义有歧义但不会漏报）。精确化需加 `pod=~"mpc-engine-.*"` 匹配——
   因 kube-state-metrics 的 label 名因版本而异（pod vs pod_name），留待
   真集群验证时对齐 label 后收窄。

3. **块高停滞类告警缺失**：链停最直接的信号（block height 不增长）
   需要 nexus-core 暴露 `/actuator/prometheus`——2.x TODO。当前用
   mpc-engine CrashLoop 作**间接**信号（5min 延迟的近似）。

## Alertmanager 路由（已配置——2026-09-07 完善批修正错误标注）

**此前此节错误声称"路由未做"——实际 `deploy/monitoring/kube-prometheus-stack-values.yaml`
早已含完整路由树**（kube-prometheus-stack chart 的 `alertmanager.config` 内联）：

- `severity=critical` → `critical-slack-and-email` 接收器（Slack
  #nexus-alerts-critical + 邮件 oncall@nexuschain.io），10s group_wait、
  1h repeat
- `severity=warning` → `warning-slack`（Slack #nexus-alerts），30s group_wait、
  4h repeat
- 抑制规则：critical 触发时抑制同 alertname+namespace 的 warning
- 占位值（部署时替换）：`REPLACE_ME_SLACK_WEBHOOK_URL` /
  `REPLACE_ME_SMTP_PASSWORD` / smtp host / 收件邮箱

**部署（随 kube-prometheus-stack 一起）**：

```bash
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  -f deploy/monitoring/kube-prometheus-stack-values.yaml
```

（无需独立 AlertmanagerConfig CRD——chart values 内联即权威配置。）

