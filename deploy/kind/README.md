# NexusChain kind 集群使用说明

本目录提供 NexusChain 本地 Kubernetes 集群（[kind](https://kind.sigs.k8s.io/)）配置，用于 CI 流水线与本地开发联调。kind 在 Docker 容器内运行真实 K8s 控制面，行为接近生产集群，适合验证 Helm Chart 与多环境 values。

## 目录内容

```
deploy/kind/
├── kind-config.yaml   # kind 集群配置（3 节点 + 端口映射 + containerd 镜像重定向）
└── README.md          # 本说明
```

## 集群拓扑

| 节点 | 角色 | zone | 用途 |
|------|------|------|------|
| nexus-chain-control-plane | control-plane | zone-a | 控制面 + 端口映射入口 |
| nexus-chain-worker | worker | zone-a | 业务 Pod（含 zone-a 标签） |
| nexus-chain-worker2 | worker | zone-b | 业务 Pod（含 zone-b 标签，验证 topologySpread） |

3 节点设计可验证 prod 环境 `topologySpreadConstraints`（多 AZ 反亲和）行为。

## 端口映射

控制面节点把以下容器端口映射到宿主机：

| 宿主机端口 | 容器端口 | 用途 |
|-----------|---------|------|
| 8080 | 8080 | nexus-gateway 支付网关 |
| 8082 | 8082 | nexus-signing-service 签名服务 |
| 8083 | 8083 | nexus-wallet-service 钱包服务 |
| 8084 | 8084 | nexus-bridge 跨链桥 |
| 30000-30010 | 30000-30010 | NodePort 段（基础设施控制台 / 临时调试） |

> ⚠️ kind 的 `extraPortMappings` 仅在控制面节点生效。业务 Service 类型为 `ClusterIP` 时，需通过 `kubectl port-forward` 或临时改 `NodePort` 才能从宿主机访问；若 Service 直接用 `NodePort` 且端口落在 30000-30010 段，则可宿主机直连。

## 前置条件

| 工具 | 最低版本 | 用途 |
|------|---------|------|
| Docker | 20.10+ | kind 依赖的容器运行时 |
| kind | 0.20+ | 创建/销毁本地集群 |
| kubectl | 1.27+ | 集群操作 |
| helm | 3.8+ | 部署 NexusChain Chart |

安装 kind：

```bash
# macOS
brew install kind

# Linux（amd64）
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.22.0/kind-linux-amd64
chmod +x ./kind && sudo mv ./kind /usr/local/bin/kind

# Windows（PowerShell + scoop）
scoop install kind
```

## 快速开始

### 1. 创建集群

```bash
kind create cluster --config deploy/kind/kind-config.yaml
```

预期输出包含 `You can now use your cluster with:` 与 `kubectl cluster-info --context kind-nexus-chain`。

### 2. 验证节点就绪

```bash
kubectl get nodes -o wide
```

预期 3 节点均为 `Ready`，且 `zone-a` / `zone-b` 标签可见。

### 3. 启动本地镜像 registry（可选，用于 ghcr.io/nexus 重定向）

```bash
# 启动 registry 容器
docker run -d --restart=always --name kind-registry -p 5000:5000 registry:2

# 与 kind 网络连通
docker network connect kind kind-registry
```

`kind-config.yaml` 已注入 containerd 配置，把 `ghcr.io/nexus` 重定向到 `registry:5000`。本地构建的镜像 `docker tag <image> registry:5000/<name>:<tag> && docker push registry:5000/<name>:<tag>` 后，集群内可拉取。

### 4. 部署 NexusChain（dev 环境）

```bash
./deploy/scripts/deploy-dev.sh
```

或手动：

```bash
helm dependency update deploy/helm/
helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-dev.yaml \
  -n nexus-dev --create-namespace
```

### 5. 验证服务

```bash
kubectl get pods -n nexus-dev
kubectl get svc -n nexus-dev
```

### 6. 清理

```bash
./deploy/scripts/destroy-dev.sh
kind delete cluster --name nexus-chain
```

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| `kind create cluster` 卡在 `Starting Kubernetes` | Docker 资源不足 | 给 Docker 至少 4 CPU / 6 GiB 内存 |
| 端口 8080 等被占用 | 宿主机已有服务监听 | 停止占用进程，或修改 `kind-config.yaml` 的 `hostPort` |
| Pod `ImagePullBackOff` | 镜像未推到本地 registry | `docker push registry:5000/<name>:<tag>` |
| `kubectl get nodes` 仅 1 节点 | 旧集群残留 | `kind delete cluster --name nexus-chain` 后重建 |
| worker 节点 `NotReady` | Docker 重启后网络未恢复 | `docker network connect kind kind-registry` |

## 与 Helm Chart 的关系

本集群配置不修改 `deploy/helm/` 下任何 Chart 文件。`kind-config.yaml` 仅定义集群拓扑与端口映射，Helm Chart 通过 `values-dev.yaml` / `values-staging.yaml` / `values-prod.yaml` 适配集群。

## 与生产集群的差异

| 维度 | kind | 生产 |
|------|------|------|
| 控制面 | 容器内 | 托管 / 多 master |
| 存储 | 无 StorageClass（需手动装 local-path-provisioner） | CSI（EBS / 云盘） |
| LB | 无 CloudProvider，Service `LoadBalancer` 不生效 | Ingress / Cloud LB |
| 节点数 | 3 | 按需 |
| 滚动升级 | 不支持 | 托管集群支持 |

> 生产部署参考 `docs/k8s-deployment.md`，本配置仅用于本地 / CI 验证。