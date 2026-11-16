# Local K3s Demo — 跑一次生命周期

> **目的**：在同一台 Windows 机器（含 WSL2 Ubuntu 24.04）上跑一套最小的「nexus-gateway-only」集群，验证：
> - k3s 部署机制工作正常；
> - pod 能起来；
> - pod 能被服务访问、被探活；
> - 这台机器就绪足以后续把 `charts/nexus-chain-*.tgz` 装进 local k3s（完整应用）。
>
> **不是生产演练**，**不是 prod 部署**。真生产部署仍走 `deploy/helm/` + GHCR + 部署 target。

---

## 0. 本机条件

| 项 | 实测（2026-11-16） |
|---|---|
| Windows 磁盘 | `F: 11 GB 空闲` — 放源码，`C: 70 GB 空闲` — 系统。能跑 Docker + k3s |
| WSL2 Ubuntu-24.04 | ✅ 已安装，运行中（含 k3s `v1.37.2+k3s1` 单节点 `vanguardlea`，节点 CPU 32核、RAM 16 GB、磁盘 882 GB 可用，节点可访问集群 API 通过 127.0.0.1:6443） |
| WSL docker | `docker info` → Server `29.1.3` |
| WSL helm/kubectl | `/usr/local/bin/helm`（v3.16.4）+ `/usr/local/bin/kubectl` |
| Windows Docker Desktop | ❌ 不可用——没用到，全在 WSL 内 |

## 1. 步骤

### 1.1 构建镜像（WSL 内）

```bash
# 从 Windows 侧可访问的 delivery bundle 里拿到 nexus-gateway jar：
# /mnt/c/Users/winge/AppData/Local/Temp/opencode/p085/delivery-2.78.0/jars/nexus-gateway-2.78.0.jar
cp /mnt/c/Users/winge/AppData/Local/Temp/opencode/p085/delivery-2.78.0/jars/nexus-gateway-2.78.0.jar /tmp/demo-gw/jars/
# 用 /tmp/demo-gw/Dockerfile（本仓库附带）:
# FROM alpine:3.20 + apk add openjdk17-jre-headless + 复制 jar + ENTRYPOINT ["java","-jar","/app/app.jar"]
cd /tmp/demo-gw
docker build -t nexus-gateway:local .
```

### 1.2 把镜像送到 k3s containerd

```bash
docker save nexus-gateway:local -o /tmp/nexus-gateway-local.tar
k3s ctr -n k8s.io images import /tmp/nexus-gateway-local.tar
```

### 1.3 应用 demo Deployment

```bash
kubectl --kubeconfig=/etc/rancher/k3s/k3s.yaml apply -f gw-deploy.yaml
```

### 1.4 验证

```bash
kubectl -n nexus-demo get pod -w
```

应观察到：
- 10s ~ 60s: `startupProbe: tcpSocket: port: 8080` 通过 → `Running`
- `readinessProbe: tcpSocket` 通过 → `READY 1/1`

### 1.5 端口转发 + 访问

```bash
kubectl -n nexus-demo port-forward pod/<pod> 18099:8080
curl -v http://127.0.0.1:18099/actuator/health
```

预期看到 `{"groups":["liveness","readiness"],"status":"DOWN"}` —— **sandbox profile 默认混合指标未就绪，某些 indicator 报 DOWN 是正确的（利润 / 结算地址 / 私钥为空）**，下游业务接口 HTTP 200。

## 2. 为什么不用 `values-staging.yaml` / `values-prod.yaml`

- 这些 chart 默认 `pullPolicy: Always`——拉 GHCR 镜像（私有，且未在 WSL 里配 pull secret）
- GHCR 私有，无 pull secret ⇒ k3s 会 `ErrImagePull`
- 演示环境采用本地 `docker build` + `docker save` + `k3s ctr import`，跳过 Registry
- 生产仍需 chart 加 GITHUB pull-secret（`spec.imagePullSecrets`），**不在此处 scope**

## 3. 为什么不用 ingress

本机 k3s 内部无 Ingress 套件，直接 ClusterIP + port-forward 够用。生产 ingress 由客户自定义。

## 4. 清理

```bash
kubectl -n nexus-demo delete deployment nexus-gateway
kubectl -n nexus-demo delete service nexus-gateway
kubectl delete namespace nexus-demo
# WSL 内清理镜像:
docker rmi nexus-gateway:local
k3s ctr -n k8s.io images rm docker.io/library/nexus-gateway:local
```

## 5. 真 prod / 真 staging

走 `docs/delivery/prod-config-prerequisites.md` + `release.yml` 的 workflow_dispatch + `deploy_prod=true`；

**镜像必须从 GHCR 拉取**（需要 `ghcr-pull-secret-prod.yaml` 中的 `server / username / password`）；

本地演示**不涉及** prod ownership。
