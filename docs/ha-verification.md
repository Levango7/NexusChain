# NexusChain 高可用验证手册

> Phase 2 - P2-T9 灾备与高可用配置验证文档
> 版本：v2.0.0 | 更新日期：2026-08-09 | 维护：NexusChain DevOps

## 第1章 概述

### 1.1 文档目的

本文档定义 NexusChain 平台在 Kubernetes 生产环境中的高可用（HA）验证流程，覆盖以下维度：

| 维度 | 验证目标 | 关键指标 |
|------|----------|----------|
| HPA 自动扩容 | 流量驱动副本数自动调整 | 0 → 3 副本 < 60s |
| PDB 滚动更新 | 自愿中断期间零宕机 | 0 审机 |
| 多 AZ 部署 | Pod 跨可用区均匀分布 | maxSkew ≤ 1 |
| 故障注入 | 节点/AZ 故障下服务可用 | RTO < 30s |

### 1.2 适用环境

- **生产环境（prod）**：完整执行所有验证项
- **预发环境（staging）**：执行 HPA + PDB 验证（多 AZ 可选）
- **开发环境（dev）**：不执行（HA 配置未启用）

### 1.3 前置条件

| 组件 | 要求 | 验证命令 |
|------|------|----------|
| Kubernetes | ≥ 1.21，多 AZ 集群 | `kubectl version --short` |
| Helm | ≥ 3.8 | `helm version --short` |
| metrics-server | 已部署（CPU/内存 HPA 依赖） | `kubectl get deployment metrics-server -n kube-system` |
| Prometheus Adapter | 已部署（RPS HPA 依赖） | `kubectl get apiservice v1beta1.custom.metrics.k8s.io` |
| kube-prometheus-stack | 已部署 | `kubectl get statefulset -n monitoring -l app.kubernetes.io/name=prometheus` |
| NexusChain Helm Chart | 已部署到 nexus 命名空间 | `helm list -n nexus` |

### 1.4 服务清单

| 服务 | 端口 | prod 副本 | HPA 范围 | PDB | 多 AZ |
|------|------|-----------|----------|-----|-------|
| nexus-gateway | 8080 | 3 | 3-10 | minAvailable=2 | ✅ |
| nexus-bridge | 8084 | 3 | 3-10 | minAvailable=2 | ✅ |
| nexus-signing-service | 8082 | 3 | 3-10 | minAvailable=2 | ✅ |
| nexus-wallet-service | 8083 | 3 | 3-10 | minAvailable=2 | ✅ |

## 第2章 HPA 自动扩容验证

### 2.1 CPU/内存 HPA 验证（P2-T2 已配置）

#### 2.1.1 检查 HPA 状态

```bash
# 查看所有服务的 HPA
kubectl get hpa -n nexus

# 预期输出：
# NAME                            REFERENCE                       TARGETS   MINPODS   MAXPODS   REPLICAS   AGE
# nexus-gateway-hpa               Deployment/nexus-gateway        45%/70%   3         10        3          1h
# nexus-bridge-hpa                Deployment/nexus-bridge         38%/70%   3         10        3          1h
# nexus-signing-service-hpa       Deployment/nexus-signing-service 52%/70%   3         10        3          1h
# nexus-wallet-service-hpa        Deployment/nexus-wallet-service 41%/70%   3         10        3          1h
```

#### 2.1.2 触发 CPU 扩容

```bash
# 1. 施加 CPU 压力（使用 hey/wrk 压测工具）
hey -z 120s -c 200 -m POST -d '{"amount":"100","currency":"USDT","to":"0x..."}' \
  http://nexus-gateway.nexus.svc.cluster.local:8080/api/v1/payments

# 2. 观察 HPA 扩容过程（每 5s 刷新）
watch -n 5 'kubectl get hpa nexus-gateway-hpa -n nexus'

# 预期：REPLICAS 从 3 逐渐增加到 4, 5, ... 直到 CPU 利用率降至 70% 以下
# 扩容应在 60s 内完成（stabilizationWindowSeconds=0 默认）
```

#### 2.1.3 验证缩容

```bash
# 1. 停止压测，等待 5 分钟（默认 stabilizationWindowSeconds=300）
# 2. 观察缩容过程
watch -n 10 'kubectl get hpa nexus-gateway-hpa -n nexus'

# 预期：REPLICAS 缓慢回落到 3（每 60s 最多缩 1 个 Pod）
```

### 2.2 RPS 自定义指标 HPA 验证（P2-T9 新增）

#### 2.2.1 前置检查

```bash
# 1. 确认 RPS HPA 已创建
kubectl get hpa -n nexus -l nexus.io/hpa-metric=rps

# 预期输出：
# NAME                            REFERENCE                       TARGETS   MINPODS   MAXPODS   REPLICAS   AGE
# nexus-gateway-rps-hpa           Deployment/nexus-gateway        800/1000  3         10        3          5m
# nexus-bridge-rps-hpa            Deployment/nexus-bridge         600/1000  3         10        3          5m
# nexus-signing-service-rps-hpa   Deployment/nexus-signing-service 300/500  3         10        3          5m
# nexus-wallet-service-rps-hpa    Deployment/nexus-wallet-service  500/800   3         10        3          5m

# 2. 确认 Custom Metrics API 可用
kubectl get apiservice v1beta1.custom.metrics.k8s.io -o jsonpath='{.status.conditions[?(@.type=="Available")].status}'
# 预期输出：True

# 3. 确认指标可查询
kubectl get --raw "/apis/custom.metrics.k8s.io/v1beta1/namespaces/nexus/pods/*/http_requests_per_second" | jq .
# 预期输出：包含各 Pod 的 RPS 值
```

#### 2.2.2 触发 RPS 扩容

```bash
# 1. 生成持续流量（目标：超过单 Pod 1000 RPS）
#    使用 hey 施加 4000 RPS（3 副本 × 1000 RPS + 余量）
hey -z 180s -q 4000 -m POST -d '{"amount":"100","currency":"USDT","to":"0x..."}' \
  http://nexus-gateway.nexus.svc.cluster.local:8080/api/v1/payments

# 2. 观察 RPS HPA 扩容
watch -n 5 'kubectl get hpa nexus-gateway-rps-hpa -n nexus -o wide'

# 预期：
#   - 0-30s：指标采集，HPA 读取当前 RPS
#   - 30-60s：触发扩容，REPLICAS 从 3 → 4 → 5
#   - 60s 内完成扩容（scaleUp.stabilizationWindowSeconds=60）
```

#### 2.2.3 验证扩缩行为

```bash
# 查看 HPA 详细状态（含扩缩条件和行为配置）
kubectl describe hpa nexus-gateway-rps-hpa -n nexus

# 关键字段验证：
#   - Metrics: Able to collect 1 metrics
#   - Conditions: ScalingActive=True, ScalingLimited=False
#   - Behavior:
#     Scale Up: Stabilization Window=60s, SelectPolicy=Max
#     Scale Down: Stabilization Window=300s, SelectPolicy=Min
```

### 2.3 HPA 扩容时间验证（0 → 3 副本 < 60s）

```bash
# 场景：从 0 副本（Deployment 缩容到 0）恢复到 3 副本
# 注意：HPA minReplicas=3，所以从 0 恢复时 HPA 会立即扩容到 3

# 1. 记录开始时间
START=$(date +%s)

# 2. 将 Deployment 副本数设为 0（模拟服务完全下线后恢复）
kubectl scale deployment nexus-gateway -n nexus --replicas=0

# 3. 等待 Pod 完全终止
kubectl wait --for=delete pod -l app.kubernetes.io/name=nexus-gateway -n nexus --timeout=60s

# 4. 触发恢复（删除手动 scale 注解，HPA 接管）
#    HPA 会立即将副本数恢复到 minReplicas=3
kubectl patch hpa nexus-gateway-hpa -n nexus --type=json -p='[{"op":"replace","path":"/spec/minReplicas","value":3}]'

# 5. 等待 3 副本就绪
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=nexus-gateway -n nexus --timeout=120s

# 6. 记录结束时间
END=$(date +%s)

# 7. 计算耗时
DURATION=$((END - START))
echo "恢复耗时：${DURATION}s"

# 验证标准：DURATION < 60s（含 Pod 启动 + 就绪探针通过）
# 注意：JVM 慢启动可能需要 40-60s，建议启用 startupProbe 宽限
```

## 第3章 PDB 滚动更新验证

### 3.1 PDB 配置检查

```bash
# 查看所有服务的 PDB
kubectl get pdb -n nexus

# 预期输出：
# NAME                            MIN AVAILABLE   ALLOWED DISRUPTIONS   AGE
# nexus-gateway-pdb               2               1                     1h
# nexus-bridge-pdb                2               1                     1h
# nexus-signing-service-pdb       2               1                     1h
# nexus-wallet-service-pdb        2               1                     1h

# 关键指标：ALLOWED DISRUPTIONS=1（3 副本 - minAvailable 2 = 1）
# 含义：滚动更新时最多允许 1 个 Pod 不可用，即始终至少 2 个 Pod 可用
```

### 3.2 滚动更新零宕机验证

#### 3.2.1 方法一：Helm 升级触发滚动更新

```bash
# 1. 在另一个终端持续探测服务可用性
while true; do
  kubectl run -i --rm curl-test --image=curlimages/curl:8.5.0 --restart=Never -- \
    curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
    http://nexus-gateway.nexus.svc.cluster.local:8080/actuator/health
  sleep 1
done

# 2. 触发滚动更新（修改 ConfigMap 内容触发 checksum 变化）
helm upgrade nexus-chain deploy/helm/ \
  -f deploy/helm/values-prod.yaml \
  -f deploy/helm/values-prod-rps-hpa.yaml \
  --set nexus-gateway.podAnnotations."verify-rolling-update"="$(date +%s)" \
  -n nexus

# 3. 观察滚动更新过程
kubectl rollout status deployment/nexus-gateway -n nexus

# 4. 验证结果：
#    - 探测终端应始终返回 200（无 503/超时）
#    - rollout status 应显示 "deployment successfully rolled out"
```

#### 3.2.2 方法二：kubectl set image 触发滚动更新

```bash
# 1. 触发滚动更新
kubectl set image deployment/nexus-gateway \
  nexus-gateway=ghcr.io/nexus/nexus-gateway:2.0.1 -n nexus

# 2. 实时观察 Pod 状态
watch -n 2 'kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway'

# 预期行为：
#   - 新 Pod（ReplicaSet v2）逐个创建
#   - 旧 Pod（ReplicaSet v1）在新 Pod Ready 后才终止
#   - 全程至少 2 个 Pod 处于 Ready 状态（maxUnavailable=0 + PDB minAvailable=2）
```

#### 3.2.3 验证 maxUnavailable=0 生效

```bash
# 检查 Deployment strategy
kubectl get deployment nexus-gateway -n nexus -o jsonpath='{.spec.strategy}' | jq .

# 预期输出：
# {
#   "type": "RollingUpdate",
#   "rollingUpdate": {
#     "maxUnavailable": 0,
#     "maxSurge": 1
#   }
# }

# 含义：
#   maxUnavailable=0：滚动更新时不主动删除任何可用 Pod
#   maxSurge=1：最多超出期望副本数 1 个（先创建新 Pod，再删旧 Pod）
```

### 3.3 节点 Drain 验证（自愿中断）

```bash
# 1. 找到运行 nexus-gateway Pod 的节点
NODE=$(kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway \
  -o jsonpath='{.items[0].spec.nodeName}')
echo "目标节点：$NODE"

# 2. Drain 节点（PDB 会阻止同时驱逐 2 个以上 Pod）
kubectl drain $NODE --ignore-daemonsets --delete-emptydir-data --timeout=120s

# 3. 验证：
#    - Drain 成功（PDB 允许驱逐 1 个 Pod）
#    - 剩余 2 个 Pod 仍在其他节点运行
#    - 被驱逐的 Pod 在其他节点重新调度

# 4. 恢复节点
kubectl uncordon $NODE
```

## 第4章 多 AZ 部署验证

### 4.1 topologySpreadConstraints 配置检查

```bash
# 检查 Deployment 的 topologySpreadConstraints
kubectl get deployment nexus-gateway -n nexus -o jsonpath='{.spec.template.spec.topologySpreadConstraints}' | jq .

# 预期输出：
# [
#   {
#     "maxSkew": 1,
#     "topologyKey": "topology.kubernetes.io/zone",
#     "whenUnsatisfiable": "DoNotSchedule",
#     "labelSelector": {
#       "matchLabels": {
#         "app.kubernetes.io/name": "nexus-gateway"
#       }
#     }
#   }
# ]
```

### 4.2 Pod 跨 AZ 分布验证

```bash
# 1. 查看 Pod 分布到哪些节点
kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway \
  -o wide --no-headers | awk '{print $1, $7}'

# 2. 查看每个节点所在的 AZ
kubectl get nodes -o custom-columns=NAME:.metadata.name,ZONE:.metadata.labels.topology\.kubernetes\.io/zone

# 3. 汇总 Pod 跨 AZ 分布
echo "=== nexus-gateway Pod AZ 分布 ==="
kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway -o json | \
  jq -r '.items[] | .spec.nodeName' | \
  while read NODE; do
    ZONE=$(kubectl get node $NODE -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}')
    echo "$NODE -> $ZONE"
  done | sort | uniq -c

# 预期输出（3 副本跨 3 个 AZ 均匀分布）：
#    1 node-a -> us-east-1a
#    1 node-b -> us-east-1b
#    1 node-c -> us-east-1c

# 验证标准：maxSkew ≤ 1（任意两个 AZ 的 Pod 数差不超过 1）
```

### 4.3 AZ 故障模拟

```bash
# 1. 标记一个 AZ 的所有节点为不可调度（模拟 AZ 下线）
AZ="us-east-1a"
kubectl get nodes -l topology.kubernetes.io/zone=$AZ -o name | \
  xargs -I{} kubectl cordon {}

# 2. 触发滚动更新（新 Pod 不会调度到被 cordon 的 AZ）
kubectl rollout restart deployment/nexus-gateway -n nexus

# 3. 验证：
#    - WhenUnsatisfiable=DoNotSchedule：如果剩余 AZ 无法满足 maxSkew=1，Pod 会 Pending
#    - 3 副本 2 AZ：分布应为 2+1（maxSkew=1 满足）
kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway -o wide

# 4. 恢复节点
kubectl get nodes -l topology.kubernetes.io/zone=$AZ -o name | xargs -I{} kubectl uncordon {}
```

### 4.4 所有服务多 AZ 验证

```bash
# 一键验证所有 4 个服务的 Pod 跨 AZ 分布
for SVC in nexus-gateway nexus-bridge nexus-signing-service nexus-wallet-service; do
  echo "=== $svc ==="
  kubectl get pods -n nexus -l app.kubernetes.io/name=$SVC -o json | \
    jq -r '.items[] | .spec.nodeName' | \
    while read NODE; do
      ZONE=$(kubectl get node $NODE -o jsonpath='{.metadata.labels.topology\.kubernetes\.io/zone}')
      echo "  $ZONE"
    done | sort | uniq -c
done
```

## 第5章 故障注入测试

### 5.1 Pod 故障注入（kill 单 Pod）

```bash
# 1. 持续探测服务可用性（另一终端）
while true; do
  CODE=$(kubectl run -i --rm curl-test --image=curlimages/curl:8.5.0 \
    --restart=Never -- curl -s -o /dev/null -w "%{http_code}" \
    http://nexus-gateway.nexus.svc.cluster.local:8080/actuator/health 2>/dev/null)
  echo "$(date +%T) HTTP $CODE"
  sleep 0.5
done

# 2. 随机 kill 一个 Pod
POD=$(kubectl get pods -n nexus -l app.kubernetes.io/name=nexus-gateway \
  -o jsonpath='{.items[0].metadata.name}')
kubectl delete pod $POD -n nexus

# 3. 验证：
#    - 探测终端应无 503/超时（PDB 保证至少 2 副本可用）
#    - Pod 自动重新调度（Deployment controller 检测到副本数不足）
#    - 恢复时间 < 30s（Pod 重启 + 就绪探针通过）
```

### 5.2 节点故障注入（cordon + drain）

```bash
# 1. 找到运行最多 nexus Pod 的节点
NODE=$(kubectl get pods -n nexus -o wide --no-headers | \
  awk '{print $7}' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')
echo "目标节点：$NODE"

# 2. Drain 节点（模拟节点维护/故障）
kubectl drain $NODE --ignore-daemonsets --delete-emptydir-data --timeout=180s --force

# 3. 验证：
#    - 所有被驱逐的 Pod 在其他节点重新调度
#    - 服务持续可用（PDB 保证 minAvailable）
#    - Pod 跨 AZ 重新均衡（topologySpreadConstraints 生效）

# 4. 检查恢复后状态
kubectl get pods -n nexus -o wide

# 5. 恢复节点
kubectl uncordon $NODE
```

### 5.3 AZ 故障注入（模拟整 AZ 下线）

```bash
# 1. 选定一个 AZ
AZ="us-east-1a"
echo "模拟 AZ 下线：$AZ"

# 2. Cordon 该 AZ 所有节点
kubectl get nodes -l topology.kubernetes.io/zone=$AZ -o name | \
  xargs -I{} kubectl cordon {}

# 3. 观察服务状态（持续 2 分钟）
watch -n 5 'kubectl get pods -n nexus -o wide; echo; kubectl get svc -n nexus'

# 4. 验证：
#    - 现有 Pod 继续运行（cordon 不驱逐已有 Pod）
#    - 新 Pod 不会调度到该 AZ
#    - 当Unsatisfiable=DoNotSchedule：如果剩余 AZ 无法满足 maxSkew=1，新 Pod Pending

# 5. 恢复 AZ
kubectl get nodes -l topology.kubernetes.io/zone=$AZ -o name | xargs -I{} kubectl uncordon {}
```

### 5.4 网络分区模拟（高级）

```bash
# 使用 Chaos Mesh 进行网络分区测试（需安装 Chaos Mesh）
# 安装：https://chaos-mesh.org/docs/quick-start/

# 1. 创建网络分区实验（隔离 nexus-gateway 的一个 Pod）
cat <<EOF | kubectl apply -f -
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: gateway-network-partition
  namespace: chaos-testing
spec:
  action: partition
  mode: one
  selector:
    namespaces:
      - nexus
    labelSelectors:
      app.kubernetes.io/name: nexus-gateway
  direction: both
  duration: 30s
  scheduler:
    cron: "@once"
EOF

# 2. 验证：
#    - 被隔离的 Pod 健康检查失败，从 Endpoints 摘除
#    - 流量自动路由到其他健康 Pod
#    - 30s 后网络恢复，Pod 重新加入 Endpoints

# 3. 清理
kubectl delete networkchaos gateway-network-partition -n chaos-testing
```

## 第6章 验证脚本

### 6.1 一键验证脚本

项目提供一键验证脚本 `deploy/scripts/verify-ha.sh`，执行所有验证项：

```bash
# 基本用法
bash deploy/scripts/verify-ha.sh

# 指定命名空间
bash deploy/scripts/verify-ha.sh -n nexus

# 仅验证 HPA
bash deploy/scripts/verify-ha.sh -c hpa

# 仅验证 PDB
bash deploy/scripts/verify-ha.sh -c pdb

# 仅验证多 AZ
bash deploy/scripts/verify-ha.sh -c az

# 模拟滚动更新
bash deploy/scripts/verify-ha.sh -c rolling
```

### 6.2 脚本输出说明

脚本输出格式：

```
[CHECK] 检查项名称
  [PASS] 检查点 1
  [PASS] 检查点 2
  [FAIL] 检查点 3 - 失败原因
  [WARN] 检查点 4 - 警告信息

=== 验证摘要 ===
总计：12  通过：10  失败：1  警告：1
```

## 第7章 验证检查清单

### 7.1 部署前检查

| 序号 | 检查项 | 命令 | 预期 |
|------|--------|------|------|
| 1 | K8s 集群多 AZ | `kubectl get nodes -L topology.kubernetes.io/zone` | ≥ 3 个 AZ |
| 2 | metrics-server | `kubectl get deployment metrics-server -n kube-system` | Available |
| 3 | Prometheus Adapter | `kubectl get apiservice v1beta1.custom.metrics.k8s.io` | True |
| 4 | Helm Chart lint | `helm lint deploy/helm/` | OK |
| 5 | 模板渲染 | `helm template nexus-chain deploy/helm/ -f deploy/helm/values-prod.yaml` | 无错误 |

### 7.2 部署后检查

| 序号 | 检查项 | 命令 | 预期 |
|------|--------|------|------|
| 1 | 所有 Pod Ready | `kubectl get pods -n nexus` | 12/12 Running |
| 2 | HPA 已创建 | `kubectl get hpa -n nexus` | 4-8 个 HPA |
| 3 | PDB 已创建 | `kubectl get pdb -n nexus` | 4 个 PDB |
| 4 | PDB minAvailable=2 | `kubectl get pdb -n nexus -o jsonpath='{.items[*].spec.minAvailable}'` | 2 2 2 2 |
| 5 | topologySpread | `kubectl get deploy -n nexus -o jsonpath='{.items[*].spec.template.spec.topologySpreadConstraints}'` | 非空 |
| 6 | Pod 跨 AZ | 见 4.2 节 | maxSkew ≤ 1 |
| 7 | 服务可用 | `kubectl get endpoints -n nexus` | 所有服务有 endpoints |

### 7.3 故障恢复检查

| 序号 | 检查项 | 方法 | 预期 |
|------|--------|------|------|
| 1 | Pod kill 恢复 | kill 1 Pod | < 30s 恢复 |
| 2 | 节点 drain 恢复 | drain 1 node | 0 审机 |
| 3 | AZ 下线容忍 | cordon 1 AZ | 服务可用 |
| 4 | 滚动更新零宕机 | helm upgrade | 0 个 503 |

## 第8章 故障排查

| 问题 | 可能原因 | 排查命令 | 解决方案 |
|------|----------|----------|----------|
| HPA 显示 `<unknown>/70%` | metrics-server 未部署 | `kubectl get deployment metrics-server -n kube-system` | 安装 metrics-server |
| HPA 不扩容 | 当前指标未超阈值 | `kubectl describe hpa <name> -n nexus` | 检查 TARGETS 列 |
| RPS HPA 无指标 | Prometheus Adapter 未部署 | `kubectl get apiservice v1beta1.custom.metrics.k8s.io` | 部署 prometheus-adapter |
| RPS HPA 指标为 0 | 应用未暴露 http_server_requests_seconds_count | `kubectl exec -it <pod> -n nexus -- curl localhost:8080/actuator/prometheus \| grep http_server_requests` | 确认 Micrometer 配置 |
| PDB AllowedDisruptions=0 | 副本数 ≤ minAvailable | `kubectl get pdb -n nexus` | 增加副本数 |
| Pod Pending（topologySpread） | AZ 数不足 | `kubectl describe pod <pod> -n nexus` | 增加 AZ 或调整 maxSkew |
| 滚动更新卡住 | PDB 阻止驱逐 | `kubectl get events -n nexus --field-selector reason=FailedDrain` | 检查 PDB 配置 |

## 附录

### A. 相关文件

| 文件 | 说明 |
|------|------|
| `deploy/helm/templates/rps-hpa.yaml` | RPS 自定义指标 HPA 模板（P2-T9 新增） |
| `deploy/helm/values-prod-rps-hpa.yaml` | RPS HPA 启用 overlay（P2-T9 新增） |
| `deploy/monitoring/prometheus-adapter-rules.yaml` | Prometheus Adapter 规则（P2-T9 新增） |
| `deploy/scripts/verify-ha.sh` | HA 验证脚本（P2-T9 新增） |
| `deploy/helm/charts/*/templates/hpa.yaml` | CPU/内存 HPA 模板（P2-T2） |
| `deploy/helm/charts/*/templates/pdb.yaml` | PDB 模板（P2-T2） |
| `deploy/helm/charts/*/templates/deployment.yaml` | Deployment 模板（P2-T2） |
| `deploy/helm/values-prod.yaml` | prod 环境 values（P2-T2） |

### B. HPA 扩缩行为参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| targetRPS | 1000 | 每 Pod 目标 RPS |
| scaleUpStabilizationSeconds | 60 | 扩容稳定窗口 |
| scaleDownStabilizationSeconds | 300 | 缩容稳定窗口 |
| scaleUpPodsPerPeriod | 4 | 每 60s 最多扩 4 Pod |
| scaleDownPodsPerPeriod | 1 | 每 60s 最多缩 1 Pod |

### C. 参考文档

- [K8s HPA 官方文档](https://kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/)
- [K8s PDB 官方文档](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/)
- [K8s topologySpreadConstraints](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/)
- [Prometheus Adapter](https://github.com/kubernetes-sigs/prometheus-adapter)
- [Custom Metrics API](https://github.com/kubernetes-sigs/custom-metrics-apiserver)