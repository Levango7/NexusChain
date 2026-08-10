# NexusChain Kubernetes 部署文档

本文档描述 NexusChain 区块链支付编排平台在 Kubernetes 上的部署流程，覆盖 dev / staging / prod 三环境，使用 kind 本地集群或托管 K8s 集群均可。

## 第1章 部署架构概览

### 1.1 服务拓扑

NexusChain v2.0.0 由 4 个 Spring Boot 微服务组成，统一通过 Helm Umbrella Chart（`deploy/helm/`）部署：

图：NexusChain 服务拓扑示意图

```
                       ┌─────────────────────┐
                       │   Ingress / LB      │
                       └──────────┬──────────┘
                                  │
                  ┌───────────────┼───────────────┐
                  │               │               │
            ┌─────▼─────┐  ┌──────▼──────┐  ┌─────▼──────┐
            │  gateway  │  │   bridge    │  │  signing   │
            │  :8080    │  │  :8084      │  │  :8082     │
            └─────┬─────┘  └──────┬──────┘  └─────┬──────┘
                  │               │               │
                  └───────────────┼───────────────┘
                                  │
                  ┌───────────────┼───────────────┐
                  │               │               │
            ┌─────▼─────┐  ┌──────▼──────┐  ┌─────▼──────┐
            │  wallet   │  │   seata     │  │  nacos     │
            │  :8083    │  │  TC :8091   │  │  :8848     │
            └───────────┘  └─────────────┘  └────────────┘
                                  │
                  ┌───────────────┼───────────────┐
                  │               │               │
            ┌─────▼─────┐  ┌──────▼──────┐  ┌─────▼──────┐
            │ postgres  │  │   redis     │  │  zipkin    │
            │  :5432    │  │  :6379      │  │  :9411     │
            └───────────┘  └─────────────┘  └────────────┘
```

### 1.2 服务清单

表：NexusChain 服务端口与依赖对照表

| 服务 | 端口 | 依赖 | 副本（dev/staging/prod） | 说明 |
|------|------|------|--------------------------|------|
| nexus-gateway | 8080 | nacos + seata + zipkin + postgres + redis | 1 / 2 / 3 | 支付网关，外部入口 |
| nexus-bridge | 8084 | nacos + zipkin + postgres + redis | 1 / 2 / 3 | 跨链桥，不参与 Seata 事务 |
| nexus-signing-service | 8082 | nacos + seata + zipkin + postgres + redis | 1 / 2 / 3 | 签名服务 |
| nexus-wallet-service | 8083 | nacos + seata + zipkin + postgres + redis | 1 / 2 / 3 | 钱包服务 |

### 1.3 基础设施依赖

表：基础设施服务端口与用途对照表

| 组件 | 端口 | 用途 | 部署方式 |
|------|------|------|---------|
| Nacos | 8848（HTTP）/ 9848（gRPC） | 配置中心 + 服务发现 | 独立 chart / 运维平台 |
| Sentinel | 8858 | 流控控制台 | 独立 chart |
| Seata | 8091（TC）/ 7091（Web） | 分布式事务协调器 | 独立 chart（见第6章） |
| Zipkin | 9411 | 链路追踪 | 独立 chart |
| PostgreSQL | 5432 | 业务数据库 | StatefulSet / 云 RDS |
| Redis | 6379 | 缓存 / 幂等键 | StatefulSet / 云 Redis |

> NexusChain Helm Chart 仅注入指向基础设施的环境变量，不部署基础设施本身。

## 第2章 前置条件

### 2.1 工具安装

表：部署所需工具及版本要求

| 工具 | 最低版本 | 必需 | 用途 |
|------|---------|------|------|
| Docker | 20.10+ | 是（kind）/ 否（托管） | 容器运行时 / kind 依赖 |
| kind | 0.20+ | 否（仅本地） | 本地 K8s 集群 |
| kubectl | 1.27+ | 是 | 集群操作 |
| helm | 3.8+ | 是 | 部署 NexusChain Chart |

#### 2.1.1 安装命令

命令示例：macOS 工具安装

```bash
brew install kind kubectl helm
```

命令示例：Linux amd64 工具安装

```bash
# kind
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.22.0/kind-linux-amd64
chmod +x ./kind && sudo mv ./kind /usr/local/bin/kind

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# helm
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
chmod +x get_helm.sh && ./get_helm.sh
```

命令示例：Windows 工具安装（PowerShell + scoop）

```powershell
scoop install kind kubectl helm
```

### 2.2 集群要求

- Kubernetes 1.21+（推荐 1.27+）
- 集群内 CoreDNS 正常运行（服务发现依赖）
- 若启用 HPA：需安装 metrics-server
- 若启用 ServiceMonitor：需安装 Prometheus Operator（kube-prometheus-stack）
- 若启用 NetworkPolicy：需 CNI 支持（Calico / Cilium，kind 默认 kindnet 仅基础支持）

### 2.3 镜像准备

表：4 个服务镜像及构建路径

| 镜像 | 路径 | 端口 |
|------|------|------|
| ghcr.io/nexus/nexus-gateway | `nexus-gateway/Dockerfile` | 8080 |
| ghcr.io/nexus/nexus-bridge | `nexus-bridge/Dockerfile` | 8084 |
| ghcr.io/nexus/nexus-signing-service | `nexus-signing-service/Dockerfile` | 8082 |
| ghcr.io/nexus/nexus-wallet-service | `nexus-wallet-service/Dockerfile` | 8083 |

构建并推送到集群可访问的 registry（kind 场景见第3.3节）：

命令示例：构建并推送镜像

```bash
docker build -t ghcr.io/nexus/nexus-gateway:2.0.0 ./nexus-gateway
docker push ghcr.io/nexus/nexus-gateway:2.0.0
# 其余 3 个服务同理
```

## 第3章 kind 集群创建

### 3.1 集群配置

集群配置文件位于 `deploy/kind/kind-config.yaml`，定义 3 节点拓扑（1 control-plane + 2 worker）与端口映射。详见 `deploy/kind/README.md`。

### 3.2 创建集群

命令示例：创建 kind 集群

```bash
kind create cluster --config deploy/kind/kind-config.yaml
```

预期输出：

```
Creating cluster "nexus-chain" ...
 ✓ Ensuring node image (kindest/node:v1.29.2) 🖼
 ✓ Preparing nodes 📦 📦 📦
 ✓ Writing configuration 📜
 ✓ Starting control-plane 🕹️
 ✓ Installing CNI 🔀
 ✓ Installing StorageClass 💾
 ✓ Joining worker nodes 🚜
Set kubectl context to "kind-nexus-chain"
You can now use your cluster with:

kubectl cluster-info --context kind-nexus-chain
```

### 3.3 启动本地镜像 registry

kind 默认从公网拉取镜像。为加速本地联调，启动一个 registry 容器并通过 containerd 配置重定向 `ghcr.io/nexus` 到本地 registry：

命令示例：启动并联通本地 registry

```bash
# 启动 registry
docker run -d --restart=always --name kind-registry -p 5000:5000 registry:2

# 与 kind 网络连通
docker network connect kind kind-registry

# 构建并推送（tag 用 registry:5000 前缀）
docker build -t registry:5000/nexus-gateway:2.0.0 ./nexus-gateway
docker push registry:5000/nexus-gateway:2.0.0
```

`kind-config.yaml` 已注入 containerd 镜像重定向配置，集群内 `ghcr.io/nexus/nexus-gateway` 的拉取会自动转发到 `registry:5000/nexus-gateway`。

### 3.4 验证集群就绪

命令示例：验证节点与 CoreDNS

```bash
# 节点就绪
kubectl get nodes -o wide
# 预期：3 节点 Ready，zone-a / zone-b 标签可见

# CoreDNS 运行
kubectl get pods -n kube-system -l k8s-app=kube-dns
# 预期：1/1 Running

# StorageClass
kubectl get sc
# 预期：standard (default) → rancher.io/local-path
```

### 3.5 安装 metrics-server（HPA 依赖）

命令示例：安装 metrics-server

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
# 等待就绪
kubectl rollout status deployment metrics-server -n kube-system
```

## 第4章 Helm 部署

### 4.1 Chart 结构

```
deploy/helm/
├── Chart.yaml                    # umbrella chart（v2.0.0）
├── values.yaml                   # 全局默认（基线对齐 staging）
├── values-dev.yaml               # dev 覆盖
├── values-staging.yaml           # staging 覆盖
├── values-prod.yaml              # prod 覆盖
├── templates/                    # namespace / networkpolicy / secret-registry
└── charts/                       # 4 个子 chart
    ├── nexus-gateway/
    ├── nexus-bridge/
    ├── nexus-signing-service/
    └── nexus-wallet-service/
```

### 4.2 渲染检查（不部署）

部署前先用 `helm template` 渲染清单，确认 values 正确：

命令示例：渲染三环境清单

```bash
# 渲染 dev
helm template nexus-chain deploy/helm/ \
  -f deploy/helm/values-dev.yaml \
  -n nexus-dev > /tmp/nexus-dev.yaml

# 渲染 staging
helm template nexus-chain deploy/helm/ \
  -f deploy/helm/values-staging.yaml \
  -n nexus-staging > /tmp/nexus-staging.yaml

# 渲染 prod
helm template nexus-chain deploy/helm/ \
  -f deploy/helm/values-prod.yaml \
  -n nexus > /tmp/nexus-prod.yaml
```

命令示例：lint 检查

```bash
helm lint deploy/helm/
# 预期：==> Lint OK，chart has passed all necessary checks
```

### 4.3 部署 dev 环境

dev 环境特征：单副本、低资源（256Mi/500m）、HPA 禁用、镜像 tag=latest、NetworkPolicy 关闭、ServiceMonitor 关闭。

命令示例：部署 dev 环境

```bash
# 拉取子 chart 依赖（file:// 协议本地引用，首次或 Chart.yaml 变更时执行）
helm dependency update deploy/helm/

# 部署
helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-dev.yaml \
  -n nexus-dev --create-namespace
```

或一键脚本：

```bash
./deploy/scripts/deploy-dev.sh
```

### 4.4 部署 staging 环境

staging 环境特征：双副本、中资源（512Mi/1）、HPA 2-4、PDB minAvailable=1、镜像 tag=staging、NetworkPolicy 开启、ServiceMonitor 开启。

命令示例：部署 staging 环境

```bash
helm dependency update deploy/helm/

helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-staging.yaml \
  -n nexus-staging --create-namespace
```

### 4.5 部署 prod 环境

prod 环境特征：三副本、高资源（1Gi/2）、HPA 3-10、PDB minAvailable=2、固定 tag=2.0.0、多 AZ topologySpreadConstraints、NetworkPolicy 开启、ServiceMonitor 开启。

命令示例：部署 prod 环境

```bash
helm dependency update deploy/helm/

helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-prod.yaml \
  -n nexus
```

> ⚠️ 生产部署前必须：
> 1. 用真实密钥替换 Secret 占位值（sealed-secrets / external-secrets / SOPS）
> 2. 配置 `global.imagePullSecrets`（私有仓库凭证）
> 3. 确认集群节点带 `topology.kubernetes.io/zone` 标签（topologySpread 生效前提）

### 4.6 多环境差异速查

表：三环境关键配置差异对照表

| 维度 | dev | staging | prod |
|------|-----|---------|------|
| namespace | nexus-dev | nexus-staging | nexus |
| 副本数 | 1 | 2 | 3 |
| 资源 requests | 256Mi / 500m | 512Mi / 1 | 1Gi / 2 |
| 资源 limits | 512Mi / 1 | 1Gi / 2 | 2Gi / 4 |
| HPA | 禁用 | 2-4 | 3-10 |
| PDB | 禁用 | minAvailable=1 | minAvailable=2 |
| 镜像 tag | latest | staging | 2.0.0（固定） |
| imagePullPolicy | Always | IfNotPresent | IfNotPresent |
| ServiceMonitor | 关闭 | 开启 | 开启 |
| NetworkPolicy | 关闭 | 开启 | 开启 |
| topologySpread | 无 | 无 | 多 AZ |
| Spring profile | dev | staging | prod |

## 第5章 服务发现验证（K8s DNS）

### 5.1 DNS 解析原理

K8s Service 通过 CoreDNS 提供稳定域名：`<service>.<namespace>.svc.cluster.local`。NexusChain 服务间调用通过 Nacos 服务发现（gRPC 9848），但基础设施地址通过 K8s DNS 注入环境变量。

表：基础设施 K8s DNS 名与环境变量对照表

| 环境变量 | K8s DNS 名 | 注入到 |
|----------|-----------|--------|
| NEX_NACOS_SERVER | nacos.nexus.svc.cluster.local:8848 | 所有 4 服务 |
| NEX_NACOS_GRPC_SERVER | nacos.nexus.svc.cluster.local:9848 | 所有 4 服务 |
| NEX_SEATA_SERVER | seata.nexus.svc.cluster.local:8091 | gateway / signing / wallet |
| NEX_SENTINEL_SERVER | sentinel.nexus.svc.cluster.local:8858 | 所有 4 服务 |
| NEX_ZIPKIN_ENDPOINT | http://zipkin.nexus.svc.cluster.local:9411/api/v2/spans | 所有 4 服务 |
| SPRING_DATASOURCE_URL | jdbc:postgresql://postgres.nexus.svc.cluster.local:5432/... | 所有 4 服务 |
| SPRING_REDIS_HOST | redis.nexus.svc.cluster.local | 所有 4 服务 |

### 5.2 验证 DNS 解析

命令示例：验证基础设施 DNS 解析

```bash
# 启动临时 debug Pod
kubectl run dnsutils --image=registry.k8s.io/e2e-test-images/jessie-dnsutils:1.3 -n nexus-dev --rm -it -- bash

# 在 Pod 内执行
nslookup nacos.nexus.svc.cluster.local
# 预期：Address: 10.96.x.x（ClusterIP）

nslookup seata.nexus.svc.cluster.local
nslookup postgres.nexus.svc.cluster.local
nslookup redis.nexus.svc.cluster.local
```

命令示例：验证业务服务 DNS

```bash
# 在同一 debug Pod 内
nslookup nexus-gateway.nexus-dev.svc.cluster.local
nslookup nexus-bridge.nexus-dev.svc.cluster.local
nslookup nexus-signing-service.nexus-dev.svc.cluster.local
nslookup nexus-wallet-service.nexus-dev.svc.cluster.local
```

### 5.3 验证服务间连通

命令示例：从 gateway Pod 内 curl 其他服务

```bash
GATEWAY_POD=$(kubectl get pod -n nexus-dev -l app.kubernetes.io/name=nexus-gateway -o jsonpath='{.items[0].metadata.name}')

kubectl exec -n nexus-dev "$GATEWAY_POD" -- \
  curl -sS -o /dev/null -w "%{http_code}\n" \
  http://nexus-signing-service.nexus-dev.svc.cluster.local:8082/actuator/health
# 预期：200
```

## 第6章 Seata Server 部署验证

### 6.1 Seata 角色

Seata 是 NexusChain 的分布式事务协调器（TC），gateway / signing / wallet 三个服务参与全局事务（bridge 不参与）。Seata Server 需独立部署，Helm Chart 仅注入 `NEX_SEATA_SERVER=seata.nexus.svc.cluster.local:8091`。

### 6.2 部署 Seata Server（kind / 自管集群）

命令示例：部署 Seata Server 单节点（开发/测试）

```bash
kubectl apply -n nexus-dev -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: seata
spec:
  replicas: 1
  selector:
    matchLabels:
      app: seata
  template:
    metadata:
      labels:
        app: seata
    spec:
      automountServiceAccountToken: false
      containers:
        - name: seata
          image: seataio/seata-server:2.0.0
          ports:
            - containerPort: 8091
              name: tc
            - containerPort: 7091
              name: web
          env:
            - name: SEATA_PORT
              value: "8091"
            - name: STORE_MODE
              value: file
          livenessProbe:
            httpGet:
              path: /health
              port: 8091
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /health
              port: 8091
            initialDelaySeconds: 10
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: seata
spec:
  selector:
    app: seata
  ports:
    - name: tc
      port: 8091
      targetPort: 8091
    - name: web
      port: 7091
      targetPort: 7091
EOF
```

> 生产环境推荐：Seata 集群 3 节点 + store.mode=db（PostgreSQL 持久化）+ Nacos 注册中心。

### 6.3 验证 Seata 就绪

命令示例：验证 Seata Server

```bash
# Pod 就绪
kubectl wait --for=condition=Ready pod -l app=seata -n nexus-dev --timeout=120s

# TC 端口可达
kubectl run seata-check --rm -it --image=curlimages/curl --restart=Never -- \
  curl -sS -o /dev/null -w "%{http_code}\n" \
  http://seata.nexus-dev.svc.cluster.local:8091/health
# 预期：200

# Web 控制台可达
kubectl port-forward svc/seata -n nexus-dev 7091:7091
# 浏览器访问 http://localhost:7091
```

### 6.4 验证业务服务注册到 Seata

业务服务启动后会在 Seata 注册事务分支。检查 Seata 控制台 → 事务管理页面，应看到来自 `nexus-gateway` / `nexus-signing-service` / `nexus-wallet-service` 的分支注册。

命令示例：检查业务服务 Seata 连接日志

```bash
for svc in nexus-gateway nexus-signing-service nexus-wallet-service; do
  POD=$(kubectl get pod -n nexus-dev -l app.kubernetes.io/name=$svc -o jsonpath='{.items[0].metadata.name}')
  echo "=== $svc ==="
  kubectl logs -n nexus-dev "$POD" | grep -iE "seata|tc-client" | head -5
done
# 预期：日志含 "register TC" / "connected to seata" 等字样
```

## 第7章 健康检查验证

### 7.1 探针配置

每个服务子 chart 注入三类探针：

表：探针配置参数说明表

| 探针 | 路径 | 端口 | initialDelay | period | failureThreshold | 用途 |
|------|------|------|-------------|--------|------------------|------|
| livenessProbe | /actuator/health | http（8080/8082/8083/8084） | 40s | 30s | 3 | 重启不健康 Pod |
| readinessProbe | /actuator/health/readiness | http | 20s | 10s | 3 | 流量就绪门控 |
| startupProbe | /actuator/health | http | 0s | 10s | 30（5min 宽限） | 慢启动 JVM，默认禁用 |

### 7.2 验证 Pod 就绪

命令示例：等待所有 Pod 就绪

```bash
kubectl wait --for=condition=Ready pod -n nexus-dev -l app.kubernetes.io/part-of=nexus \
  --timeout=300s

kubectl get pods -n nexus-dev -o wide
# 预期：4 个业务 Pod 全部 1/1 Running
```

### 7.3 验证健康端点

命令示例：逐服务验证 actuator/health

```bash
declare -A SVC_PORT=(
  [nexus-gateway]=8080
  [nexus-bridge]=8084
  [nexus-signing-service]=8082
  [nexus-wallet-service]=8083
)

for svc in "${!SVC_PORT[@]}"; do
  port=${SVC_PORT[$svc]}
  echo "=== $svc (port $port) ==="
  kubectl run "health-check-$svc" --rm -it --image=curlimages/curl --restart=Never -- \
    curl -sS http://$svc.nexus-dev.svc.cluster.local:$port/actuator/health
done
# 预期：每服务返回 {"status":"UP"}
```

### 7.4 验证 readiness 端点

命令示例：验证 readiness 端点

```bash
GATEWAY_POD=$(kubectl get pod -n nexus-dev -l app.kubernetes.io/name=nexus-gateway -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n nexus-dev "$GATEWAY_POD" -- \
  curl -sS http://localhost:8080/actuator/health/readiness
# 预期：{"status":"UP"}
```

### 7.5 验证 Prometheus 指标端点

staging / prod 启用 ServiceMonitor，需验证 `/actuator/prometheus` 暴露：

命令示例：验证 Prometheus 端点

```bash
GATEWAY_POD=$(kubectl get pod -n nexus-staging -l app.kubernetes.io/name=nexus-gateway -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n nexus-staging "$GATEWAY_POD" -- \
  curl -sS http://localhost:8080/actuator/prometheus | head -5
# 预期：返回 # HELP / # TYPE / jvm_memory_used_bytes{...} 等指标行
```

### 7.6 验证 HPA（staging/prod）

命令示例：验证 HPA 状态

```bash
kubectl get hpa -n nexus-staging
# 预期：4 个 HPA，TARGETS 列显示 CPU/Memory 利用率，REF 列指向对应 Deployment
```

### 7.7 验证 PDB（staging/prod）

命令示例：验证 PDB 状态

```bash
kubectl get pdb -n nexus-staging
# 预期：4 个 PDB，MIN AVAILABLE=1，ALLOWED DISRUPTIONS=1
```

## 第8章 常见问题排查

### 8.1 Pod CrashLoopBackOff

**现象**：Pod 反复重启，状态 `CrashLoopBackOff`。

**排查**：

命令示例：查看 Pod 日志与事件

```bash
kubectl describe pod <pod-name> -n nexus-dev
kubectl logs <pod-name> -n nexus-dev --previous
```

**常见原因与解决**：

表：CrashLoopBackOff 常见原因与解决对照表

| 原因 | 日志特征 | 解决 |
|------|---------|------|
| Nacos 不可达 | `com.alibaba.nacos.client.config.impl...timeout` | 检查 nacos Pod 与 Service DNS |
| Seata 不可达 | `io.seata.core.rpc.netty...connection refused` | 检查 seata Pod 与 8091 端口 |
| 数据库连接失败 | `Connection refused: postgres.nexus.svc...:5432` | 检查 postgres StatefulSet 与 Secret |
| Redis 连接失败 | `Unable to connect to Redis` | 检查 redis Pod 与 6379 端口 |
| Spring profile 错误 | `The following profiles are active: xxx` 但无对应配置 | 检查 SPRING_PROFILES_ACTIVE 环境变量 |
| 镜像拉取失败 | `ImagePullBackOff` | 检查镜像 tag / registry 凭证 / containerd 重定向 |

### 8.2 Pod Pending

**现象**：Pod 状态 `Pending`，无法调度。

命令示例：查看调度失败原因

```bash
kubectl describe pod <pod-name> -n nexus-dev | grep -A 20 "Events:"
```

表：Pending 常见原因与解决对照表

| 原因 | 事件特征 | 解决 |
|------|---------|------|
| 资源不足 | `Insufficient memory` / `Insufficient cpu` | 扩容节点或降低 requests |
| 没有可用节点 | `0/3 nodes are available` | 检查节点污点 / 标签 |
| PVC 待绑定 | `pod has unbound immediate PersistentVolumeClaims` | 检查 StorageClass |
| topologySpread 不可满足 | `node(s) didn't match pod anti-affinity rules` | 检查 zone 标签是否齐全 |

### 8.3 Service DNS 解析失败

**现象**：Pod 内 `nslookup <svc>` 返回 `NXDOMAIN`。

**排查**：

命令示例：DNS 排查

```bash
# CoreDNS 是否运行
kubectl get pods -n kube-system -l k8s-app=kube-dns

# Service 是否存在
kubectl get svc <svc-name> -n nexus-dev

# Endpoint 是否有后端
kubectl get endpoints <svc-name> -n nexus-dev
# 若 ENDPOINTS 列为 <none>，说明 selector 没匹配到 Pod
```

### 8.4 ServiceMonitor 无 target

**现象**：Prometheus targets 页面看不到 NexusChain 服务。

**排查**：

命令示例：ServiceMonitor 排查

```bash
# ServiceMonitor 是否创建
kubectl get servicemonitor -n nexus-staging

# 标签是否匹配 Prometheus selector
kubectl get servicemonitor -n nexus-staging -o yaml | grep -A 5 labels

# Service 是否有 prometheus 端口
kubectl get svc nexus-gateway -n nexus-staging -o yaml | grep -A 10 ports
```

**解决**：确保 ServiceMonitor 的 `labels.release` 与 kube-prometheus-stack 的 release 名一致（默认 `kube-prometheus-stack`）。

### 8.5 HPA 不生效

**现象**：`kubectl get hpa` 的 TARGETS 列显示 `<unknown>`。

**原因**：metrics-server 未安装或未就绪。

**解决**：

命令示例：安装并验证 metrics-server

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl rollout status deployment metrics-server -n kube-system
kubectl top nodes
# 预期：显示节点 CPU/Memory 利用率
```

### 8.6 滚动更新卡住

**现象**：`kubectl rollout status` 长时间不返回。

**原因**：`maxUnavailable: 0` + 新 Pod readiness 失败。

**排查**：

命令示例：滚动更新排查

```bash
kubectl rollout status deployment/nexus-gateway -n nexus-dev --timeout=30s
# 若超时，检查新 ReplicaSet 的 Pod 日志
kubectl get rs -n nexus-dev -l app.kubernetes.io/name=nexus-gateway
```

### 8.7 NetworkPolicy 阻断流量

**现象**：staging/prod 启用 NetworkPolicy 后，服务间调用超时。

**排查**：检查 `deploy/helm/templates/networkpolicy.yaml` 的 ingress 规则是否放行了所有需要的来源（namespace 内互通 + 基础设施入站）。

命令示例：NetworkPolicy 排查

```bash
kubectl get networkpolicy -n nexus-staging
kubectl describe networkpolicy -n nexus-staging
```

## 第9章 清理步骤

### 9.1 卸载 Helm Release

命令示例：卸载三环境 release

```bash
# dev
helm uninstall nexus-chain -n nexus-dev
kubectl delete namespace nexus-dev --ignore-not-found

# staging
helm uninstall nexus-chain -n nexus-staging
kubectl delete namespace nexus-staging --ignore-not-found

# prod（谨慎）
helm uninstall nexus-chain -n nexus
kubectl delete namespace nexus --ignore-not-found
```

或一键脚本（仅 dev）：

```bash
./deploy/scripts/destroy-dev.sh
```

### 9.2 清理基础设施

命令示例：清理 Seata / Nacos 等基础设施

```bash
kubectl delete deployment seata -n nexus-dev --ignore-not-found
kubectl delete svc seata -n nexus-dev --ignore-not-found
# 其余基础设施同理
```

### 9.3 销毁 kind 集群

命令示例：销毁 kind 集群与本地 registry

```bash
kind delete cluster --name nexus-chain
docker rm -f kind-registry 2>/dev/null || true
```

### 9.4 清理本地镜像（可选）

命令示例：清理本地构建镜像

```bash
docker rmi $(docker images --filter=reference='registry:5000/nexus-*' -q) 2>/dev/null || true
```

## 第10章 部署速查卡

### 10.1 一键 dev 部署

命令示例：一键 dev 部署全流程

```bash
# 1. 创建集群
kind create cluster --config deploy/kind/kind-config.yaml

# 2. 启动本地 registry
docker run -d --restart=always --name kind-registry -p 5000:5000 registry:2
docker network connect kind kind-registry

# 3. 构建并推送镜像（4 个服务）
for svc in nexus-gateway nexus-bridge nexus-signing-service nexus-wallet-service; do
  docker build -t registry:5000/$svc:latest ./$svc
  docker push registry:5000/$svc:latest
done

# 4. 部署基础设施（Nacos / Seata / Zipkin / Postgres / Redis，按需）

# 5. 部署 NexusChain
./deploy/scripts/deploy-dev.sh

# 6. 验证
kubectl get pods -n nexus-dev
```

### 10.2 一键清理

命令示例：一键清理全流程

```bash
./deploy/scripts/destroy-dev.sh
kind delete cluster --name nexus-chain
docker rm -f kind-registry 2>/dev/null || true
```

### 10.3 关键命令速查

表：常用 kubectl 命令速查表

| 场景 | 命令 |
|------|------|
| 查看所有 Pod | `kubectl get pods -A -l app.kubernetes.io/part-of=nexus` |
| 查看某服务日志 | `kubectl logs -f -n nexus-dev -l app.kubernetes.io/name=nexus-gateway` |
| 进入 Pod | `kubectl exec -it <pod> -n nexus-dev -- sh` |
| 端口转发 | `kubectl port-forward svc/nexus-gateway -n nexus-dev 8080:8080` |
| 查看 Helm release | `helm list -A` |
| 渲染清单 | `helm template nexus-chain deploy/helm/ -f deploy/helm/values-dev.yaml` |
| 升级 release | `helm upgrade nexus-chain deploy/helm/ -f deploy/helm/values-dev.yaml -n nexus-dev` |
| 回滚 | `helm rollback nexus-chain <revision> -n nexus-dev` |
| 查看历史 | `helm history nexus-chain -n nexus-dev` |