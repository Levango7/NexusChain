# Nacos HA 集群部署文档

- **版本**：v2.3.2
- **部署模式**：3 节点 HA 集群（Raft 协议选主）
- **关联任务**：P3-T6（配置中心迁移评估与执行）
- **关联 ADR**：[ADR-026](../../docs/adr/ADR-026-nacos-ha-decision.md)
- **关联评估**：[config-migration-assessment.md](../../docs/config-migration-assessment.md)

---

## 1. 概述

本目录包含 NexusChain 生产环境 Nacos 配置中心 HA 部署配置。Nacos 同时承担**配置中心**和**服务发现**两大职责，是 4 个核心服务（gateway / bridge / signing-service / wallet-service）的关键依赖。

### 1.1 部署架构

```
K8s Cluster (nexus namespace)
│
├── Nacos HA StatefulSet (3 副本, Raft 协议)
│   ├── nacos-0  ─── PVC 20Gi(data) + 5Gi(log) ─── Node-A
│   ├── nacos-1  ─── PVC 20Gi(data) + 5Gi(log) ─── Node-B
│   └── nacos-2  ─── PVC 20Gi(data) + 5Gi(log) ─── Node-C
│         │
│         ├── 8848 (HTTP/REST API)
│         ├── 9848 (gRPC 客户端通信)
│         └── 9849 (gRPC Raft 集群内部)
│
├── nacos-headless (Headless Service)
│       └── 用于 StatefulSet 集群发现（每个 Pod 稳定 DNS）
│
├── nacos (ClusterIP Service)
│       └── 4 个核心服务通过此 Service 访问 Nacos（负载均衡）
│
└── PostgreSQL (复用 deploy/k8s/30-infrastructure.yml)
        └── Nacos 外接存储（配置 + 服务发现数据持久化）
```

### 1.2 与 Istio 共存

| 职责 | 由谁承担 |
|------|----------|
| 流量路由（VirtualService / DestinationRule） | Istio |
| 流量安全（AuthorizationPolicy / PeerAuthentication） | Istio |
| 流量监控（遥测 / Kiali） | Istio |
| 配置中心（动态配置推送） | Nacos |
| 服务发现（服务注册 + 心跳） | Nacos |
| 限流规则持久化（Sentinel） | Nacos |
| 事务配置（Seata） | Nacos |

**关键原则**：Istio 与 Nacos 职责正交，无冲突。Nacos Pod 通过 `sidecar.istio.io/inject: "false"` 注解显式不注入 Sidecar，避免网格化延迟影响 Raft 心跳。

---

## 2. 目录结构

```
deploy/nacos/
├── README.md                      # 本文件
├── nacos-ha-statefulset.yaml      # 3 节点 StatefulSet + PDB + HPA
├── nacos-ha-service.yaml          # Headless + ClusterIP + NodePort Service
├── nacos-configmap.yaml           # Nacos 配置 ConfigMap（application.properties + cluster.conf）
└── hot-reload-verify.yaml         # 配置热更新验证 ConfigMap（含验证脚本）
```

---

## 3. 前置条件

### 3.1 K8s 集群

- K8s 版本 ≥ 1.24
- 已部署 StorageClass `standard`（或修改 YAML 中的 storageClassName）
- 至少 3 个 Node（满足 Pod 反亲和要求）
- 每个 Node 至少 2 GiB 可用内存 + 1 CPU

### 3.2 依赖组件

- **PostgreSQL**：已部署（复用 `deploy/k8s/30-infrastructure.yml` 中的 postgres）
- **nexus namespace**：已创建（`deploy/k8s/00-namespace-config.yml`）
- **nexus-secrets Secret**：已创建，包含 `NEX_DB_USERNAME` / `NEX_DB_PASSWORD`

### 3.3 PostgreSQL 数据库初始化

Nacos 外接 PostgreSQL 需要预先创建数据库 `nacos_config`：

```sql
-- 命令示例：创建 Nacos 配置数据库
CREATE DATABASE nacos_config WITH ENCODING 'UTF8';
```

Nacos 首次启动时会自动创建表结构（`config_info` / `config_info_beta` / `his_config_info` / `tenant_info` 等）。

---

## 4. 部署步骤

### 4.1 部署顺序

```bash
# 命令示例：Nacos HA 部署（按顺序执行）

# 1. 确认 namespace + PostgreSQL 已就绪
kubectl get namespace nexus
kubectl get sts postgres -n nexus

# 2. 部署 Nacos ConfigMap
kubectl apply -f deploy/nacos/nacos-configmap.yaml

# 3. 部署 Nacos Service（Headless + ClusterIP）
kubectl apply -f deploy/nacos/nacos-ha-service.yaml

# 4. 部署 Nacos StatefulSet（3 节点 HA）
kubectl apply -f deploy/nacos/nacos-ha-statefulset.yaml

# 5. 等待 3 节点全部 Ready
kubectl rollout status sts/nacos -n nexus
kubectl get pods -n nexus -l app=nacos -o wide
```

### 4.2 部署验证

```bash
# 命令示例：部署后验证

# 1. 检查 StatefulSet 状态
kubectl get sts nacos -n nexus
# 期望：READY 3/3

# 2. 检查 3 个 Pod 状态
kubectl get pods -n nexus -l app=nacos -o wide
# 期望：3 个 Pod 全部 Running，分布在 3 个 Node

# 3. 检查 PVC 绑定
kubectl get pvc -n nexus -l app=nacos
# 期望：6 个 PVC（3 data + 3 log）全部 Bound

# 4. 检查 Service
kubectl get svc -n nexus -l app=nacos
# 期望：nacos / nacos-headless / nacos-console 三个 Service

# 5. 检查集群一致性（进入任一 Pod 查询集群节点列表）
kubectl exec -n nexus nacos-0 -- curl -s http://localhost:8848/nacos/v1/core/cluster/nodes
# 期望：返回 3 个节点信息
```

---

## 5. 配置热更新验证

### 5.1 执行验证脚本

```bash
# 命令示例：配置热更新验证

# 1. 部署验证 ConfigMap
kubectl apply -f deploy/nacos/hot-reload-verify.yaml

# 2. 在临时 Pod 中执行验证脚本
kubectl run -n nexus nacos-verify --rm -i --tty \
  --image=busybox:1.36 \
  --restart=Never \
  --overrides='{
    "spec": {
      "containers": [{
        "name": "nacos-verify",
        "image": "curlimages/curl:8.7.1",
        "command": ["sleep", "3600"],
        "env": [{"name": "NACOS_AUTH_TOKEN", "valueFrom": {"secretKeyRef": {"name": "nexus-secrets", "key": "NACOS_AUTH_TOKEN"}}}]
      }]
    }
  }' -- bash /verify.sh

# 或者直接在 Nacos Pod 中执行
kubectl exec -n nexus nacos-0 -- bash /home/nacos/conf/verify.sh
```

### 5.2 验证场景

| 场景 | 验证内容 | 期望延迟 |
|------|----------|----------|
| Sentinel 限流规则调整 | 修改 `nexus-sentinel-rules.yaml`，gateway 收到新阈值 | <1s |
| Seata 事务参数调整 | 修改 `nexus-seata.yaml`，signing-service 收到新参数 | <1s |
| 链节点 RPC 切换 | 修改 `nexus-common.yaml` 中链节点地址，bridge 收到新地址 | <1s |
| 白名单/黑名单更新 | 修改白名单配置，gateway 收到新名单 | <1s |

### 5.3 验证用配置示例

`hot-reload-verify.yaml` 中包含 3 个验证用配置示例：

- `sentinel-rules-verify.yaml`：模拟 Sentinel 限流规则动态调整
- `seata-params-verify.yaml`：模拟 Seata 事务参数动态调整
- `chain-rpc-switch-verify.yaml`：模拟链节点 RPC 故障切换

---

## 6. 运维操作

### 6.1 故障切换验证

```bash
# 命令示例：模拟单节点故障，验证集群可用性

# 1. 删除 nacos-0 Pod，模拟节点故障
kubectl delete pod nacos-0 -n nexus

# 2. StatefulSet 会自动重建 nacos-0，期间集群仍可用（2/3 节点满足 Raft 多数派）
kubectl get pods -n nexus -l app=nacos -w

# 3. 验证 4 个核心服务仍可读写配置
kubectl exec -n nexus deployment/nexus-gateway -- \
  curl -s http://nacos:8848/nacos/v1/cs/configs?dataId=nexus-common.yaml
```

### 6.2 集群扩缩容（不建议自动）

Nacos HA 集群节点数应保持奇数（3/5/7），**不建议自动伸缩**。

如需手动扩容到 5 节点：

```bash
# 命令示例：手动扩容到 5 节点（需同步修改 cluster.conf）

# 1. 修改 nacos-ha-statefulset.yaml 中 replicas: 3 → 5
# 2. 修改 nacos-configmap.yaml 中 cluster.conf，添加 nacos-3 / nacos-4 节点
# 3. 应用变更
kubectl apply -f deploy/nacos/nacos-configmap.yaml
kubectl apply -f deploy/nacos/nacos-ha-statefulset.yaml

# 4. 等待新节点加入集群
kubectl rollout status sts/nacos -n nexus
```

### 6.3 升级 Nacos 版本

```bash
# 命令示例：滚动升级 Nacos 镜像版本

# 1. 修改 nacos-ha-statefulset.yaml 中 image: nacos/nacos-server:v2.4.3 → v3.1.2
# 2. 应用变更（StatefulSet RollingUpdate 会逐节点升级）
kubectl apply -f deploy/nacos/nacos-ha-statefulset.yaml

# 3. 监控升级进度
kubectl rollout status sts/nacos -n nexus

# 4. 升级期间集群可用（每次只升级 1 节点，剩余 2 节点满足 Raft 多数派）
```

### 6.4 备份与恢复

```bash
# 命令示例：备份 Nacos 配置（通过 PostgreSQL 备份）

# 1. 备份 PostgreSQL nacos_config 数据库
kubectl exec -n nexus sts/postgres -- \
  pg_dump -U nexus nacos_config > nacos-config-backup-$(date +%Y%m%d).sql

# 2. 恢复
cat nacos-config-backup-YYYYMMDD.sql | \
  kubectl exec -i -n nexus sts/postgres -- psql -U nexus nacos_config
```

---

## 7. 监控

### 7.1 Prometheus 指标

Nacos 暴露 Prometheus 指标端点 `/nacos/actuator/prometheus`，已在 StatefulSet 中通过注解配置：

```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8848"
  prometheus.io/path: "/nacos/actuator/prometheus"
```

### 7.2 关键指标

| 指标 | 含义 | 告警阈值 |
|------|------|----------|
| `nacos_config_count` | 配置总数 | — |
| `nacos_config_push_total` | 配置推送次数 | — |
| `nacos_config_push_latency_seconds` | 配置推送延迟 | p99 > 1s |
| `nacos_naming_service_count` | 注册服务数 | < 4（4 核心服务应全部注册） |
| `nacos_cluster_node_count` | 集群节点数 | < 3（HA 集群应 3 节点） |
| `nacos_cluster_leader_status` | 当前节点是否 Leader | — |
| `jvm_memory_heap_usage` | JVM 堆内存使用率 | > 80% |

### 7.3 Grafana Dashboard

推荐使用 Nacos 官方 Grafana Dashboard：https://github.com/alibaba/nacos/blob/develop/distribution/conf/nacos-grafana-dashboards.json

---

## 8. 端口约定

| 端口 | 用途 | 暴露方式 |
|------|------|----------|
| 8848 | Nacos HTTP 控制台 + REST API | ClusterIP + NodePort(30848) |
| 9848 | Nacos 2.x gRPC 客户端通信 | ClusterIP |
| 9849 | Nacos 2.x gRPC Raft 集群内部 | Headless Service（仅集群内部） |

---

## 9. 资源配置

| 资源 | 配置 | 说明 |
|------|------|------|
| 每节点 memory | 2 GiB request / 2 GiB limit | Nacos 推荐 |
| 每节点 CPU | 1000m request / 1000m limit | Nacos 推荐 |
| 每节点 PVC data | 20 GiB | 配置 + 服务发现数据缓存 |
| 每节点 PVC log | 5 GiB | 30 天日志滚动 |
| 集群总资源 | 6 GiB memory + 3 CPU | 3 节点合计 |

---

## 10. 安全注意事项

### 10.1 鉴权

- **生产环境必须启用鉴权**：`NACOS_AUTH_ENABLE=true`（已在 ConfigMap 中配置）
- **鉴权 token 应通过 Secret 注入**：当前 ConfigMap 中的 `NACOS_AUTH_SECRET_KEY` 为占位值，生产环境应通过 Secret 覆盖
- **Server identity 应通过 Secret 注入**：当前 `NACOS_AUTH_IDENTITY_VALUE` 为占位值

### 10.2 网络隔离

- Nacos Pod 通过 `sidecar.istio.io/inject: "false"` 不注入 Istio Sidecar
- gRPC Raft 端口（9849）仅通过 Headless Service 暴露，不对外
- 建议通过 NetworkPolicy 限制仅 4 个核心服务可访问 Nacos 8848/9848 端口

### 10.3 数据库密码

- PostgreSQL 密码通过 `nexus-secrets` Secret 注入，不在 ConfigMap 明文
- Secret 应通过 Sealed Secrets / External Secrets / Vault 管理

---

## 11. 故障排查

### 11.1 Pod 无法启动

```bash
# 命令示例：排查 Pod 启动失败

# 1. 查看 Pod 事件
kubectl describe pod nacos-0 -n nexus

# 2. 查看 initContainer 日志（PostgreSQL 连接失败）
kubectl logs nacos-0 -n nexus -c wait-for-postgres

# 3. 查看 Nacos 容器日志
kubectl logs nacos-0 -n nexus -c nacos
```

常见原因：
- PostgreSQL 未就绪或网络不通
- `nexus-secrets` Secret 不存在或 key 错误
- PVC 无法绑定（StorageClass 不存在或容量不足）

### 11.2 集群脑裂

```bash
# 命令示例：排查集群脑裂

# 1. 查看每个节点的集群视图
for i in 0 1 2; do
  echo "=== nacos-$i ==="
  kubectl exec -n nexus nacos-$i -- \
    curl -s http://localhost:8848/nacos/v1/core/cluster/nodes
done

# 期望：3 个节点返回的集群节点列表一致
```

### 11.3 配置推送延迟高

```bash
# 命令示例：排查配置推送延迟

# 1. 检查 Nacos 配置推送指标
kubectl exec -n nexus nacos-0 -- \
  curl -s http://localhost:8848/nacos/actuator/prometheus | grep config_push

# 2. 检查客户端长连接
kubectl exec -n nexus deployment/nexus-gateway -- \
  curl -s http://localhost:8080/actuator/metrics/nacos.config.listener
```

---

## 12. 参考

- [ADR-026: Nacos 配置中心 HA 部署决策](../../docs/adr/ADR-026-nacos-ha-decision.md)
- [配置中心迁移评估报告](../../docs/config-migration-assessment.md)
- [Nacos 官方 HA 部署文档](https://nacos.io/zh-cn/docs/v2/guide/deployment/)
- [Nacos 配置初始化文档](../../nacos-config/README.md)
- [Istio 部署文档](../istio/README.md)
- [K8s 部署文档](../../docs/k8s-deployment.md)