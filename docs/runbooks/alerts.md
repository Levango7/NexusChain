# PrometheusRule 告警

## 设计原则

- 起步 **4 条** critical/warning（设计稿 PLAN-002 §3.2）；2026-09-07 起 5 条、
  2026-09-09 起 11 条（当前值，单一真源 = `deploy/k8s/50-prometheus-rules.yaml`）
- 以 `kube_*` 通用 metrics 为主；`nexuschain_block_height` / `jvm_*` /
  `container_*` 等已确认真实注册或集群开箱即有的指标为辅——**规则只允许引用
  真实存在的指标**（2026-09-09 整合时退役了 11 条引用幽灵指标名的 legacy 告警）
- `for:` 必填——避免抖动误报（链停/资源类 5-10min；在线类 1min）
- 告警标签统一（severity + component + service）

## 11 条告警（2026-09-07 +1 链停告警；2026-09-09 告警真值整合 +6；2026-10-18 批次③ runbook 补全）

| 名称 | 级别 | 触发条件 | 含义 |
|---|---|---|---|
| BlockchainCoreStalled | critical | `max(nexuschain_block_height)` 5min 无增长 | 链停摆（精确信号） |
| MpcEngineCrashLooping | critical | mpc-engine 容器 CrashLoopBackOff ≥ 5min | 签名服务停摆 |
| MpcEngineNotReady | warning | mpc-engine Pod 至少 1 个非 Ready ≥ 5min | 安全冗余损失 |
| MpcEngineResourceUnbound | critical | PVC 未绑定 或 mpc-engine Pod 非 Running ≥ 5min | 存储或调度故障 |
| NexusServiceUnhealthy | warning | Java 编排服务不可用副本 > 50% 持续 5min | 任何 Java 服务降级 |
| JvmGcTimeHigh | warning | GC 时间/运行时间 > 20% 持续 5min | 内存压力 / Full GC 前兆 |
| JvmHeapUsageHigh | warning | 堆使用/堆上限 > 85% 持续 5min | OOM 风险 |
| PodCpuUsageHigh | warning | 容器 CPU / limit > 80% 持续 10min | CPU 节流影响延迟 |
| PodMemoryUsageHigh | warning | working_set / limit > 90% 持续 10min | OOMKilled 风险 |
| ServiceDown | critical | Prometheus `up` 抓取失败持续 1min | 服务不可达（最基础信号） |
| SeataTcDown | critical | Seata TC 抓取失败持续 1min | 分布式事务协调停摆 |

后 6 条的排障手册见下方“新增 6 条告警排障手册”章节（2026-10-18 批次③ 补全）。

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

## 新增 6 条告警排障手册（2026-09-09 整合入规则 · 2026-10-18 批次③ 补全 runbook）

### JvmGcTimeHigh（warning · component=jvm）

- **触发**：`rate(jvm_gc_pause_seconds_sum[5m]) / clamp_min(rate(process_uptime_seconds[5m]), 1) > 0.20`（按 service/instance）
- **含义**：GC 时间占运行时间 > 20%——内存压力或频繁 Full GC 的前兆，先于 OOM 出现
- **排查**：
  ```bash
  kubectl -n nexus top pod
  # 容器内取 actuator 指标（distroless 镜像自带 /busybox wget；端口按服务：
  # gateway:8080 / signing:8082 / wallet:8083 / bridge:8084 / api-gateway:8085）
  kubectl -n nexus exec <pod> -- /busybox wget -qO- \
    http://localhost:8080/actuator/metrics/jvm.gc.pause
  ```
- **处置**：伴随 `JvmHeapUsageHigh` 时按该条处置（扩容/查泄漏）；仅 GC 高而堆平稳 → 查分配速率（批量任务/流量突增/回放）
- **注意**：指标需服务暴露 `/actuator/prometheus` 且 ServiceMonitor 抓取在线；`service` 标签为 K8s Service 名（nexus-*）

### JvmHeapUsageHigh（warning · component=jvm）

- **触发**：`sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85` 持续 5min
- **含义**：堆逼近上限——继续增长将 Full GC 频繁、最终 OOMKilled（容器视角联动 `PodMemoryUsageHigh`）
- **排查**：
  ```bash
  kubectl -n nexus exec <pod> -- /busybox wget -qO- \
    http://localhost:8080/actuator/metrics/jvm.memory.used
  kubectl -n nexus describe pod <pod> | grep -A5 "Last State"   # 是否已有 OOMKilled 历史
  ```
- **处置**：短期扩容 memory limit（helm: `deploy/helm/charts/nexus-*/values.yaml` 的 `resources.limits.memory`；静态清单/compose 同步同口径——2026-10-18 批次③ 起三路径均已显式限定）；中长期查泄漏（对象滞留/无界缓存）
- **注意**：堆上限 `jvm_memory_max_bytes` 与容器 memory limit 联动（JDK17 容器感知默认取 limit 的 1/4）

### PodCpuUsageHigh（warning · component=resource）

- **触发**：`sum(rate(container_cpu_usage_seconds_total[5m])) / kube_pod_container_resource_limits{resource="cpu"} > 0.80` 持续 10min
- **含义**：逼近 CPU limit → CFS 节流（throttling）→ 请求延迟上升（尤其 MPC 仪式/桥接批处理路径）
- **排查**：
  ```bash
  kubectl -n nexus top pod
  kubectl -n nexus get pod <pod> -o jsonpath='{.spec.containers[*].resources.limits.cpu}'
  # 节流证据（PromQL）：rate(container_cpu_cfs_throttled_periods_total{namespace="nexus"}[5m])
  #                    / rate(container_cpu_cfs_periods_total{namespace="nexus"}[5m]) > 0.25
  ```
- **处置**：确认非死循环后调高 CPU limit / 启用 HPA（helm values HPA 段；HPA 启用时 `replicaCount` 作为初始值）；mpc-engine 高 CPU 属仪式期正常负载

### PodMemoryUsageHigh（warning · component=resource）

- **触发**：`container_memory_working_set_bytes / kube_pod_container_resource_limits{resource="memory"} > 0.90` 持续 10min
- **含义**：working_set 逼近 limit——OOMKilled 风险（working_set 比 JVM 堆更接近内核判定口径）
- **排查**：
  ```bash
  kubectl -n nexus top pod
  kubectl -n nexus describe pod <pod> | grep -B2 -A6 "OOMKilled"   # 重启历史
  # mpc-engine（Rust）额外查会话落盘量：MPC_ENGINE_SESSION_DIR
  kubectl -n nexus exec mpc-engine-0 -- du -sh /app/data/sessions
  ```
- **处置**：扩容 memory limit；Rust 引擎清理历史会话数据；Java 服务联动 `JvmHeapUsageHigh` 处置

### ServiceDown（critical · component=service）

- **触发**：`up{job=~"nexus-.*", namespace="nexus"} == 0` 持续 1min（本组最灵敏的一条）
- **含义**：Prometheus 抓取目标不可达——Pod 崩溃 / 端口漂移 / NetworkPolicy 阻断 / ServiceMonitor 配置漂移
- **排查**：
  ```bash
  kubectl -n nexus get pod | grep <job 去掉 nexus- 前缀>
  kubectl -n nexus get servicemonitor
  kubectl -n nexus get endpoints <service>
  # Prometheus UI → Status → Targets → 找到对应 job 看最近错误
  ```
- **处置**：恢复 Pod（`kubectl describe` / `logs`）；目标失踪时查 ServiceMonitor 的 endpoint/port 与 Service 端口名对齐

### SeataTcDown（critical · component=seata）

- **触发**：`up{job="seata-tc", namespace="nexus"} == 0` 持续 1min
- **含义**：Seata TC 不可达——分布式事务无法提交/回滚，支付与桥操作可能挂起（gateway/wallet/signing 的全局事务路径）
- **排查**：
  ```bash
  kubectl -n nexus get pod | grep seata
  kubectl -n nexus logs <seata-pod> --tail=200
  kubectl -n nexus exec <seata-pod> -- bash -c 'echo > /dev/tcp/127.0.0.1/8091 && echo TC-LISTEN-OK'
  ```
- **重要前提（2026-09-10 实测修正）**：仅当集群存在 `job="seata-tc"` 抓取目标时本条才有数据；无目标时 `up` 序列缺失、表达式无数据、不触发（原 `absent()` 分支会恒为 1 造成永久假告警，已移除）。部署时需为 Seata 配置对应抓取，否则本条为“已定义未生效”状态。
- **处置**：优先恢复 TC；查 nacos 注册状态（`deploy/k8s/31-seata.yml`）与 DB 连接（按 seata 存储模式）

## 后续升级路径（gRPC 错误率）

当前**不告警**：gRPC 错误率——需 nexus-signing-service 暴露客户端 metrics
（2.x TODO）。

## CI 验证

`.github/workflows/k8s-sync-check.yml` 的 "PrometheusRule 静态校验" step：
- `kubectl apply --dry-run=client -f deploy/k8s/50-prometheus-rules.yaml` 验 CRD schema
- 告警数 ≥ 5（当前 11 条）
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

3. ~~**块高停滞类告警缺失**~~（2026-09-07 已补）：`nexuschain_block_height`
   随 `/actuator/prometheus` 落地，BlockchainCoreStalled 已上线（见顶部说明）。
   旁注：JVM/Pod 类新告警依赖 ServiceMonitor 抓取在线——新服务接入时勿忘
   配置，否则对应告警“已定义未生效”（与 SeataTcDown 同族，见其手册条目）。

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

## 验证告警会响（部署后必做——2026-09-08 A 批）

**CI 已覆盖的部分**（每次 push 自动跑，`.github/workflows/k8s-sync-check.yml`
"Install amtool + 校验 Alertmanager 路由树" step）：
- 路由树语法（receivers/route/inhibit 全树）经 `amtool check-config` 校验
- severity 路由数 ≥ 2（critical/warning 两级防误删）

**CI 覆盖不了的部分**（占位 URL → 真值后，集群侧人工验证一次）：

```bash
# 1. 替换占位值（运维操作，不入仓）
#    deploy/monitoring/kube-prometheus-stack-values.yaml 中：
#    REPLACE_ME_SLACK_WEBHOOK_URL → 真 Slack Incoming Webhook
#    REPLACE_ME_SMTP_PASSWORD     → 真 SMTP 凭据
#    smtp.example.com:587         → 真邮件服务器
#    oncall@nexuschain.io         → 真 oncall 邮箱
#    然后重新 helm upgrade（上方部署命令）

# 2. 集群侧验证路由可达
amtool config routes test --config.file=<抽出的 am-config.yaml> \
  --tree --verify-receivers  # receivers 全部可达（URL 格式合法）

# 3. 发一条真测试告警（不打扰——用 warning 级别，确认 Slack #nexus-alerts 收到）
kubectl exec -n monitoring alertmanager-kube-prometheus-stack-alertmanager-0 \
  -- amtool alert add \
    --annotation summary="测试告警-验证通知链路" \
    --annotation description="收到即链路通；可忽略" \
    alertname="TestNotificationRoute" severity="warning"
# Slack #nexus-alerts 应在 30s 内收到 [WARNING] TestNotificationRoute
```

**验证不过时的排查顺序**：
1. `kubectl get alertmanager -n monitoring`（实例健康）
2. `amtool config routes test`（路由树匹配——告警进的是哪个 receiver）
3. Slack webhook 本身（curl 发 webhook 看 Slack 是否真有该频道）
4. Alertmanager 日志（`kubectl logs` 看发送失败原因）


