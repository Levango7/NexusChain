# ADR-026: Nacos 配置中心 HA 部署决策

- **状态**：Accepted（2026-08-09）
- **决策人**：DevOps 工程师，依据 Phase 3 用户授权「自行决策并执行」
- **关联任务**：P3-T6（配置中心迁移评估与执行）
- **关联文档**：[config-migration-assessment.md](../config-migration-assessment.md)
- **前置条件**：P3-T1（Istio 服务网格已部署）

---

## 1. 背景（Context）

NexusChain v2.0.0 Phase 3（架构演进）推进中，配置中心面临演进决策：

### 1.1 当前状态

- **配置中心**：Nacos 单节点（仅适用于开发环境）
- **服务发现**：Nacos（4 个核心服务全部接入）
- **Istio**：已部署（P3-T1），接管流量管理
- **4 个核心服务**：nexus-gateway / nexus-bridge / nexus-signing-service / nexus-wallet-service

### 1.2 触发因素

1. **生产化需求**：Nacos 单节点无法满足生产 SLA（单点故障 → 集群不可用 → 4 服务全部失联）
2. **Istio 共存**：Istio 已接管流量管理，需明确 Nacos 在新架构下的定位
3. **K8s 原生化趋势**：Phase 3 推进 K8s 原生集成，需评估是否将配置中心一并迁移到 ConfigMap

### 1.3 Nacos 当前承担的核心能力

| 能力 | 使用频率 | 是否可被 ConfigMap 替代 |
|------|----------|------------------------|
| 动态配置推送（<1s） | 高 | 否（ConfigMap 延迟 5-30s） |
| 服务发现 | 高 | 是（Istio + K8s Service 可替代） |
| 多环境管理（namespace） | 中 | 部分（K8s namespace 可替代但复杂度高） |
| 配置灰度发布 | 中 | 否（ConfigMap 不原生支持） |
| Sentinel 规则持久化 | 高 | 否（需迁移到文件 DataSource，延迟增大） |
| Seata 配置中心 | 高 | 否（需迁移到其他配置源，风险高） |

---

## 2. 决策（Decision）

**保留 Nacos，部署 3 节点 HA 集群**（方案 B）。

### 2.1 部署架构

```
K8s Cluster (nexus namespace)
├── Nacos HA StatefulSet (3 副本)
│   ├── nacos-0 (PVC 20Gi data + 5Gi log)
│   ├── nacos-1 (PVC 20Gi data + 5Gi log)
│   └── nacos-2 (PVC 20Gi data + 5Gi log)
├── nacos-headless (Headless Service, 集群发现)
├── nacos (ClusterIP Service, 客户端访问)
└── PostgreSQL (复用 deploy/k8s/30-infrastructure.yml, Nacos 外接存储)
```

### 2.2 部署配置文件

| 文件 | 用途 |
|------|------|
| `deploy/nacos/nacos-ha-statefulset.yaml` | 3 节点 StatefulSet + PDB + HPA |
| `deploy/nacos/nacos-ha-service.yaml` | Headless + ClusterIP + NodePort Service |
| `deploy/nacos/nacos-configmap.yaml` | Nacos 配置 + cluster.conf |
| `deploy/nacos/hot-reload-verify.yaml` | 配置热更新验证 |

### 2.3 关键参数

- **副本数**：3（Raft 多数派 = 2，容忍 1 节点故障）
- **资源配置**：每节点 2 GiB memory + 1 CPU（Nacos 推荐）
- **持久化**：每节点 PVC 20Gi（数据）+ 5Gi（日志）
- **后端存储**：外接 PostgreSQL（复用现有 postgres 实例）
- **反亲和**：3 节点强制分散到不同 Node（防单 Node 故障）
- **PodDisruptionBudget**：minAvailable=2（维护时至少 2 节点可用）

---

## 3. 理由（Rationale）

### 3.1 主理由

1. **Nacos 动态配置推送是核心能力**
   - NexusChain 大量使用动态配置：Sentinel 限流规则、Seata 事务参数、链节点 RPC 切换、白名单/黑名单
   - 迁移到 ConfigMap 会丢失实时推送能力（延迟从 <1s 退化到 5-30s）
   - **支付场景对限流规则实时性要求高**：双 11 / 节假日流量峰值时需秒级调整限流阈值，5-30s 延迟不可接受

2. **HA 部署成本低（2 PD vs 8 PD）**
   - 方案 B 仅需 2 PD，方案 A（全迁 ConfigMap）需 8 PD
   - Phase 3 还有其他任务（P3-T7+），工程预算应优先用于业务能力而非基础设施迁移

3. **Istio 可以与 Nacos 共存**
   - Istio 管流量路由（VirtualService / DestinationRule / AuthorizationPolicy）
   - Nacos 管配置中心 + 服务发现元数据
   - 两者职责正交，无冲突
   - 业界（蚂蚁、阿里、字节）普遍采用 Istio + Nacos 共存架构

4. **后续可在 Phase 4 评估是否需要迁移**
   - Phase 3 优先保证生产可用（HA 是刚需，迁移是优化）
   - Phase 4 可在业务稳定后，评估是否引入 Spring Cloud Kubernetes 或 K8s Operator 替代 Nacos
   - 保留迁移可能性，避免一次性大改

5. **Sentinel/Seata 无需改造**
   - 现有 `nexus-sentinel-rules.yaml` / `seata-server.properties` 直接复用
   - 避免引入 Sentinel DataSource 适配器 / Seata 配置源迁移的额外风险

### 3.2 排除其他方案的理由

- **排除方案 A（全迁 ConfigMap）**：支付场景对限流规则实时性要求高，ConfigMap 5-30s 延迟不可接受；Seata 配置迁移风险高，可能影响资金安全
- **排除方案 C（混合模式）**：治标不治本，仍需维护 Nacos HA，且增加 ConfigMap 复杂度；双 starter 共存可能存在 Bean 冲突

### 3.3 加权评分对比

| 维度 | 权重 | 方案 A | 方案 B（推荐） | 方案 C |
|------|------|--------|----------------|--------|
| 工作量 | 20% | 1 | 5 | 3 |
| 动态配置能力 | 25% | 1 | 5 | 3 |
| 迁移风险 | 20% | 1 | 5 | 3 |
| 运维复杂度 | 15% | 5 | 3 | 1 |
| K8s 原生化 | 10% | 5 | 2 | 3 |
| Sentinel/Seata 兼容 | 10% | 1 | 5 | 5 |
| **加权总分** | 100% | **1.6** | **4.35** | **2.95** |

---

## 4. 后果（Consequences）

### 4.1 正面

- **生产 SLA 满足**：3 节点 HA，容忍 1 节点故障，可用性 ≥ 99.95%
- **动态配置推送保留**：限流规则、Seata 参数、链节点切换可实时推送（<1s）
- **4 服务零改动**：服务发现 + 配置中心机制不变，无代码改动风险
- **Sentinel/Seata 无需改造**：现有配置直接复用
- **Istio 共存架构清晰**：Istio 管流量，Nacos 管配置，职责正交
- **多环境管理保留**：Nacos namespace 机制成熟，dev/test/prod 切分清晰

### 4.2 负面 / 残留

- **需维护 Nacos HA 集群**：3 节点 StatefulSet + PostgreSQL 后端，运维负担增加
- **资源成本**：3 节点 × (2 GiB memory + 1 CPU) = 6 GiB + 3 CPU
- **与 K8s 生态有距离**：Nacos 是外部依赖，不符合纯 K8s 原生理念
- **后续仍可能需要迁移**：若 Phase 4+ 决定全面 K8s 原生化，仍需评估迁移成本
- **PostgreSQL 后端单点**：当前复用单节点 postgres，后续需升级为 HA PostgreSQL

### 4.3 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| Nacos 集群脑裂 | 低 | 高 | K8s StatefulSet 稳定网络标识 + Raft 协议 |
| PostgreSQL 后端单点 | 中 | 严重 | 复用现有 postgres PVC，后续可升级为 HA PostgreSQL |
| Istio 与 Nacos 服务发现冲突 | 低 | 中 | Istio 仅管流量，不接管服务发现注册 |
| Nacos 集群升级复杂 | 中 | 中 | 使用 StatefulSet RollingUpdate，逐节点升级 |

---

## 5. 后续演进路径（Phase 4+ 评估）

本决策不排除后续迁移到 K8s 原生配置中心的可能。记录触发条件供 Phase 4 评估：

### 5.1 触发迁移到 ConfigMap 的条件

- Nacos 集群运维成本持续上升
- K8s 原生配置中心生态成熟（如 Spring Cloud Kubernetes 支持动态推送）
- 业务对动态配置实时性要求降低

### 5.2 触发迁移到 Apollo / Consul 的条件

- 需要更强的配置灰度发布能力（按 IP / 标签灰度）
- 需要配置审计 + 回滚到任意版本
- 多语言微服务（非 Java）接入配置中心

### 5.3 不迁移的条件

- Nacos HA 集群稳定运行，运维成本可控
- 业务对动态配置实时性要求保持高
- Istio + Nacos 共存架构满足需求

---

## 6. 验证清单

部署完成后需验证以下事项：

- [ ] 3 节点 Nacos 全部 Ready（`kubectl get sts nacos -n nexus`）
- [ ] 集群一致性：3 节点配置同步一致（Nacos 控制台 → 集群节点列表）
- [ ] 4 服务可连接 Nacos HA（`kubectl exec` 进服务 Pod，curl `nacos:8848`）
- [ ] 配置热更新：修改配置后 <1s 推送到所有服务（执行 `hot-reload-verify.yaml` 中的 verify.sh）
- [ ] 故障切换：kill 1 节点（`kubectl delete pod nacos-0`），集群仍可读写
- [ ] Sentinel 规则推送：`nexus-sentinel-rules.yaml` 修改后实时生效
- [ ] Seata 配置推送：`nexus-seata.yaml` 修改后实时生效
- [ ] PodDisruptionBudget 生效：`kubectl drain` 单 Node 时 Nacos 至少 2 节点可用

---

## 7. 参考

- [配置中心迁移评估报告](../config-migration-assessment.md)
- [Nacos HA 部署文档](../../deploy/nacos/README.md)
- [Istio 部署文档](../../deploy/istio/README.md)
- [Nacos 配置初始化文档](../../nacos-config/README.md)
- [Nacos 官方 HA 部署文档](https://nacos.io/zh-cn/docs/v2/guide/deployment/)