# MPC 分散式部署指南（K8s StatefulSet）

## 第1章 概述

### 1.1 背景

P0-1 Task 239 将 MPC 密码学引擎从「单节点 docker-compose」升级到「3 节点分散式部署」，实现 2-of-3 阈值签名（GG18/GG20 协议）。本文档描述 Kubernetes 环境下的完整部署流程。

### 1.2 架构

图：MPC 分散式部署架构图

```
┌─────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                    │
│                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ mpc-engine-0 │  │ mpc-engine-1 │  │ mpc-engine-2 │  │
│  │ party_index=0│  │ party_index=1│  │ party_index=2│  │
│  │   :50051     │  │   :50051     │  │   :50051     │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                  │                  │          │
│         └───────── mTLS gRPC (Headless Svc) ─┘          │
│                    Pod 间 P2P 互连                       │
│                         ▲                               │
│                         │                               │
│  ┌──────────────────────┴──────────────────────┐       │
│  │        nexus-signing-service                 │       │
│  │   MpcEngineRouter 按 partyIndex 路由         │       │
│  │   endpoints: mpc-engine-0/1/2:50051         │       │
│  └─────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────┘
```

### 1.3 组件说明

表：MPC 分散式部署组件说明表

| 组件 | 类型 | 职责 | 副本数 |
|------|------|------|--------|
| mpc-engine | StatefulSet | Rust gRPC 密码学引擎（DKG/Sign/Aggregate） | 3 |
| mpc-engine-headless | Headless Service | Pod 间 gRPC + mTLS 互连 | - |
| mpc-engine (Client Svc) | ClusterIP Service | signing-service 健康检查 / 调试 | - |
| mpc-engine-config | ConfigMap | node.json 配置模板 + peers 生成脚本 | - |
| mpc-engine-secret | Secret | storage_key / auth_token | - |
| mpc-engine-tls | Secret | TLS 证书（每副本独立） | - |
| mpc-engine PDB | PodDisruptionBudget | 保证 ≥2 副本可用（满足 t=2） | - |

## 第2章 前置条件

### 2.1 集群要求

- Kubernetes ≥ 1.24（支持原生 gRPC probe）
- 每节点 ≥ 2 CPU / 4GiB 内存（mpc-engine 密码学运算资源）
- ≥ 3 个 worker 节点（podAntiAffinity 强制 3 副本调度到不同 Node）
- StorageClass（PVC 持久化会话快照 / WAL）
- Prometheus Operator（ServiceMonitor 抓取指标）

### 2.2 工具

- `helm` ≥ 3.12
- `kubectl` ≥ 1.24
- `openssl` ≥ 1.1.1（证书生成）

## 第3章 证书生成

### 3.1 生成 mTLS 证书

MPC 节点间通过 mTLS 互连，需为每个节点签发独立证书。

命令示例：生成 3 节点 mTLS 证书

```bash
# 使用项目自带脚本生成
bash mpc-engine/scripts/generate-certs.sh -o ./mpc-certs-generated

# 或手动生成（3 节点 + CA）
# 1. 生成 CA
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
    -subj "/C=CN/O=NexusChain/OU=MPC/CN=NexusChain-MPC-CA" -sha256

# 2. 为每个节点签发证书（SAN 含 Pod DNS 名）
for i in 0 1 2; do
    openssl genrsa -out node-${i}.key 2048
    openssl req -new -key node-${i}.key -out node-${i}.csr \
        -subj "/C=CN/O=NexusChain/OU=MPC/CN=mpc-engine-${i}" -sha256
    openssl x509 -req -days 825 -in node-${i}.csr \
        -CA ca.crt -CAkey ca.key -CAcreateserial \
        -out node-${i}.crt -sha256 \
        -addext "subjectAltName = DNS:mpc-engine-${i}.mpc-engine-headless.nexus.svc.cluster.local,DNS:localhost,IP:127.0.0.1"
    rm -f node-${i}.csr
done
```

### 3.2 创建 Kubernetes Secret

代码示例：创建 TLS 证书 Secret（Shell）

```bash
kubectl create secret generic mpc-engine-tls \
    --namespace nexus \
    --from-file=ca.crt=./ca.crt \
    --from-file=node-0/tls.crt=./node-0.crt \
    --from-file=node-0/tls.key=./node-0.key \
    --from-file=node-1/tls.crt=./node-1.crt \
    --from-file=node-1/tls.key=./node-1.key \
    --from-file=node-2/tls.crt=./node-2.crt \
    --from-file=node-2/tls.key=./node-2.key
```

### 3.3 创建敏感配置 Secret

代码示例：创建 storage_key / auth_token Secret（Shell）

```bash
kubectl create secret generic mpc-engine-secret \
    --namespace nexus \
    --from-literal=storage-key=<64-char-hex> \
    --from-literal=auth-token=<your-bearer-token>
```

## 第4章 Helm 部署

### 4.1 Chart 结构

表：mpc-engine Helm Chart 文件结构

| 文件 | 用途 |
|------|------|
| `Chart.yaml` | Chart 元数据 |
| `values.yaml` | 默认配置 |
| `templates/statefulset.yaml` | StatefulSet（3 副本，pod ordinal = party_index） |
| `templates/service.yaml` | Headless Service + Client Service |
| `templates/configmap.yaml` | node.json 配置模板 + peers 生成脚本 |
| `templates/secret.yaml` | Secret（storage_key / auth_token / TLS 证书） |
| `templates/pdb.yaml` | PodDisruptionBudget（minAvailable=2） |
| `templates/servicemonitor.yaml` | Prometheus ServiceMonitor |

### 4.2 自定义 values

代码示例：生产环境 values 覆盖（YAML）

```yaml
# values-prod-mpc.yaml
mpc-engine:
  replicaCount: 3
  threshold: 2
  usePlaintext: false
  rustLog: info

  # 通过现有 Secret 注入（非明文）
  storageKey: ""  # 留空，从 mpc-engine-secret 读取
  authToken: ""   # 留空，从 mpc-engine-secret 读取

  resources:
    requests:
      memory: 512Mi
      cpu: 1000m
    limits:
      memory: 1Gi
      cpu: 2000m

  podAntiAffinity:
    enabled: true
    mode: hard  # 强制 3 副本调度到不同 Node

  pdb:
    enabled: true
    minAvailable: 2  # 保证 ≥2 副本可用（满足 t=2 阈值）

  global:
    env: prod
    namespace: nexus
    imageRegistry: ghcr.io/nexus
```

### 4.3 部署命令

命令示例：Helm 部署 mpc-engine

```bash
# 部署
helm upgrade --install mpc-engine deploy/helm/charts/mpc-engine \
    --namespace nexus \
    --values deploy/helm/charts/mpc-engine/values.yaml \
    --values values-prod-mpc.yaml

# 验证
kubectl get statefulset -n nexus mpc-engine
kubectl get pods -n nexus -l app.kubernetes.io/name=mpc-engine
kubectl get svc -n nexus | grep mpc-engine
```

### 4.4 期望输出

```
NAME          READY   STATUS    RESTARTS   AGE
mpc-engine-0  1/1     Running   0          2m
mpc-engine-1  1/1     Running   0          2m
mpc-engine-2  1/1     Running   0          2m
```

## 第5章 ConfigMap 注入

### 5.1 node.json 配置模板

每个 Pod 通过 initContainer 从 ConfigMap 读取 `node-json-template`，替换占位符后写入 `/app/config/node.json`。

表：node.json 占位符说明表

| 占位符 | 替换值 | 说明 |
|--------|--------|------|
| `${POD_ORDINAL}` | 0/1/2 | 从 Pod 名解析（mpc-engine-N → N） |
| `${PEERS_JSON}` | JSON 数组 | 由 gen-peers-sh 生成（排除本节点的其他参与方） |
| `${STORAGE_KEY}` | 64-char hex | 从 Secret 读取 |

### 5.2 peers 自动生成

initContainer 调用 `gen-peers-sh` 脚本，根据 `replicaCount` 和 `POD_ORDINAL` 生成 peers JSON 数组：

代码示例：mpc-engine-0 的 peers（JSON）

```json
[
    {"party_index":1,"party_id":"party-1","endpoint":"https://mpc-engine-1.mpc-engine-headless:50051"},
    {"party_index":2,"party_id":"party-2","endpoint":"https://mpc-engine-2.mpc-engine-headless:50051"}
]
```

## 第6章 signing-service 多端点路由

### 6.1 配置

signing-service 通过 `MpcEngineRouter` 按 `partyIndex` 路由到对应 mpc-engine 节点。

代码示例：signing-service application.yml 配置（YAML）

```yaml
mpc:
  engine:
    # 多端点配置（逗号分隔，优先于 host:port）
    endpoints: mpc-engine-0.mpc-engine-headless:50051,mpc-engine-1.mpc-engine-headless:50051,mpc-engine-2.mpc-engine-headless:50051
    # 向后兼容：endpoints 为空时使用 host:port
    host: localhost
    port: 50051
    deadline-timeout: 30000
    use-plaintext: false
    tls:
      trust-cert-path: /etc/mpc/certs/ca/CA.pem
      client-cert-path: /etc/mpc/certs/node-A/cert.pem
      client-key-path: /etc/mpc/certs/node-A/key.pem
    auth-token: ${NEX_MPC_ENGINE_AUTH_TOKEN}
```

### 6.2 路由逻辑

- `partyIndex=0` → `mpc-engine-0.mpc-engine-headless:50051`
- `partyIndex=1` → `mpc-engine-1.mpc-engine-headless:50051`
- `partyIndex=2` → `mpc-engine-2.mpc-engine-headless:50051`
- `partyIndex` 超出范围 → 回退到 endpoint 0（容错）
- `endpoints` 为空 → 回退到 `host:port` 单端点（向后兼容）

## 第7章 跨 Pod gRPC + mTLS 验证

### 7.1 验证 Pod 间互连

命令示例：验证 Pod 间 gRPC + mTLS 互连

```bash
# 1. 检查 Headless Service DNS 解析
kubectl run dns-test --rm -it --image=busybox --namespace nexus -- \
    nslookup mpc-engine-headless

# 期望输出：3 个 Pod IP
# Name:    mpc-engine-headless.nexus.svc.cluster.local
# Address: 10.244.0.10
# Address: 10.244.0.11
# Address: 10.244.0.12

# 2. 检查 Pod DNS 名解析
kubectl run dns-test --rm -it --image=busybox --namespace nexus -- \
    nslookup mpc-engine-0.mpc-engine-headless

# 期望输出：单个 Pod IP
# Name:    mpc-engine-0.mpc-engine-headless.nexus.svc.cluster.local
# Address: 10.244.0.10

# 3. 从 Pod 内部验证 gRPC + mTLS 互连
kubectl exec -n nexus mpc-engine-0 -- \
    grpcurl -cacert /etc/mpc/tls/ca.crt \
    -cert /etc/mpc/tls/tls.crt \
    -key /etc/mpc/tls/tls.key \
    mpc-engine-1.mpc-engine-headless:50051 \
    nexus.mpc.MpcCryptoService/HealthCheck
```

### 7.2 验证 DKG 2-of-3

命令示例：触发 DKG 2-of-3

```bash
# 通过 signing-service REST API 触发 DKG
curl -X POST http://<signing-service>:8082/api/v1/mpc/dkg \
    -H "Content-Type: application/json" \
    -d '{
        "sessionId": "test-dkg-001",
        "threshold": 2,
        "totalParties": 3,
        "curve": "secp256k1"
    }'

# 期望响应：{"success":true,"publicKey":"...","keyShare":"..."}
```

### 7.3 验证签名

命令示例：触发 Sign 并验证

```bash
# 触发签名
curl -X POST http://<signing-service>:8082/api/v1/mpc/sign \
    -H "Content-Type: application/json" \
    -d '{
        "sessionId": "test-dkg-001",
        "messageHash": "a3f5e8d2b1c4f7e9..."
    }'

# 期望响应：{"success":true,"signature":"...","r":"...","s":"..."}
```

## 第8章 监控与运维

### 8.1 Prometheus 指标

ServiceMonitor 自动配置 Prometheus 抓取 mpc-engine gRPC 指标：

- `mpc_dkg_total`：DKG 操作总数
- `mpc_sign_total`：签名操作总数
- `mpc_sign_duration_seconds`：签名耗时
- `mpc_health_status`：节点健康状态

### 8.2 PDB 与滚动维护

PDB（minAvailable=2）确保节点维护时至少 2 副本可用，满足 2-of-3 阈值签名要求。

命令示例：检查 PDB

```bash
kubectl get pdb -n nexus mpc-engine
# 期望：minAvailable=2, allowedDisruptions=1
```

### 8.3 故障恢复

- 单节点故障：剩余 2 节点仍可签名（满足 t=2）
- 双节点故障：无法签名（t=2 不满足），需恢复至少 1 个故障节点
- StatefulSet 滚动更新：`partition` 策略控制更新速率，避免同时下线多个副本

## 第9章 端到端验证脚本

使用 `scripts/verify-mpc-distributed.sh` 执行完整端到端验证：

命令示例：端到端验证

```bash
# 完整验证（启动→健康检查→DKG→Sign→验证签名）
bash scripts/verify-mpc-distributed.sh

# 仅健康检查
bash scripts/verify-mpc-distributed.sh --health-only

# 跳过启动（假设集群已运行）
bash scripts/verify-mpc-distributed.sh --skip-start

# 验证后清理
bash scripts/verify-mpc-distributed.sh --cleanup
```

## 第10章 安全注意事项

1. **mTLS 必须启用**：生产环境 `usePlaintext=false`，配置完整 mTLS 证书
2. **Bearer Token**：`MPC_AUTH_TOKEN` 通过 Secret 注入，禁止明文入仓
3. **storage_key**：AES-256-GCM 加密密钥通过 Secret / KMS 注入
4. **证书轮换**：节点证书有效期 825 天，CA 证书 10 年，需定期轮换
5. **NetworkPolicy**：启用 `global.networkPolicy.enabled` 限制只有 signing-service 可访问 mpc-engine
6. **podAntiAffinity**：`mode=hard` 强制 3 副本调度到不同 Node，避免单 Node 故障丢多方