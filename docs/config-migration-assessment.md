# Nacos 配置中心迁移评估报告

- **任务编号**：P3-T6
- **评估范围**：NexusChain v2.0.0 Phase 3 配置中心演进路线决策
- **当前状态**：Nacos 单节点（开发）→ 生产 HA 演进
- **评估日期**：2026-08-09
- **评估人**：DevOps 工程师
- **关联 ADR**：[ADR-026-nacos-ha-decision](./adr/ADR-026-nacos-ha-decision.md)
- **关联任务**：P3-T1（Istio 服务网格已部署）

---

## 1. 评估背景

### 1.1 当前架构状态

NexusChain 在 v2.0.0 Phase 3（架构演进）阶段已完成以下基础设施改造：

| 组件 | 状态 | 部署位置 |
|------|------|----------|
| Istio 服务网格 | 已部署（P3-T1） | `deploy/istio/` |
| Nacos 配置中心 | 单节点（开发用） | `docker-compose.yml` |
| 4 个核心服务 | 全部接入 Nacos | gateway / bridge / signing-service / wallet-service |

### 1.2 4 个核心服务的 Nacos 依赖

| 服务 | 配置 dataId | 依赖能力 |
|------|-------------|----------|
| nexus-gateway | `nexus-gateway.yaml` + `nexus-common.yaml` | 配置中心 + 服务发现 |
| nexus-bridge | `nexus-bridge.yaml` + `nexus-common.yaml` | 配置中心 + 服务发现 |
| nexus-signing-service | `nexus-signing-service.yaml` + `nexus-common.yaml` + `nexus-seata.yaml` | 配置中心 + 服务发现 + Seata 配置 |
| nexus-wallet-service | `nexus-wallet-service.yaml` + `nexus-common.yaml` | 配置中心 + 服务发现 |

### 1.3 评估触发因素

1. **生产化需求**：Nacos 单节点无法满足生产 SLA（单点故障）
2. **Istio 共存问题**：Istio 已接管流量管理，需评估 Nacos 在新架构下的定位
3. **K8s 原生化趋势**：Phase 3 推进 K8s 原生集成，需评估是否将配置中心一并迁移

### 1.4 Nacos 当前承担的核心能力

| 能力 | 使用频率 | 是否可替代 |
|------|----------|------------|
| **动态配置推送** | 高（限流规则、Seata 参数、链节点切换） | ConfigMap 需 `kubectl apply` + Pod 重启，**无法实时推送** |
| **服务发现** | 高（4 服务互相调用） | Istio + K8s Service 可替代 |
| **多环境管理** | 中（dev/test/prod namespace） | ConfigMap 需多 namespace 维护，复杂度高 |
| **配置灰度发布** | 中（按 group/namespace 切分） | ConfigMap 不原生支持 |
| **Sentinel 规则持久化** | 高（`nexus-sentinel-rules.yaml`） | 需迁移到 Sentinel DataSource 适配器 |

---

## 2. 方案 A：迁移到 K8s ConfigMap + Spring Cloud Kubernetes

### 2.1 方案描述

完全移除 Nacos，将配置中心和服务发现全部迁移到 K8s 原生组件：

- **配置中心**：K8s ConfigMap + Secret + Spring Cloud Kubernetes Config
- **服务发现**：K8s Service + Istio ServiceEntry / DestinationRule
- **配置热更新**：Spring Cloud Kubernetes 通过 K8s API watch ConfigMap 变更

### 2.2 优势

1. **减少外部依赖**：移除 Nacos 集群，降低运维负担
2. **K8s 原生集成**：与 K8s RBAC、Namespace、Label 体系一致
3. **Istio 配置热更新**：Istio 可通过 Envoy filter watch ConfigMap 实现配置热推送
4. **统一 GitOps**：所有配置通过 `kubectl apply` 或 ArgoCD 同步，符合 GitOps 实践
5. **资源成本降低**：无需维护 3 节点 Nacos 集群（节省约 6 GiB 内存 + 3 CPU）

### 2.3 劣势

1. **失去 Nacos 动态推送**：ConfigMap 变更需 `kubectl apply`，且 Spring Cloud Kubernetes 通过 K8s API watch 的延迟约 5-30s，远不如 Nacos 推送（<1s）
2. **配置变更需 kubectl apply**：无法通过控制台 UI 即时修改配置，运维体验下降
3. **多环境管理复杂**：每个环境需独立 ConfigMap + Namespace，配置漂移风险高
4. **Sentinel 规则迁移成本**：需引入 Sentinel DataSource 适配器（如 `sentinel-datasource-nacos` → `sentinel-datasource-file` 或自定义 K8s 适配器）
5. **Seata 配置迁移**：Seata Server 配置当前在 Nacos（`seataServer.properties`），需迁移到 Seata 支持的另一种配置源（file / Apollo / Consul）
6. **回滚风险高**：迁移后若发现配置丢失或行为不一致，回滚需重新部署 Nacos + 导入配置

### 2.4 迁移成本明细

| 工作项 | 单服务工作量 | 4 服务总工作量 |
|--------|--------------|----------------|
| 替换依赖：`spring-cloud-starter-alibaba-nacos-config` → `spring-cloud-kubernetes-config` | 0.5 PD | 2 PD |
| 替换依赖：`spring-cloud-starter-alibaba-nacos-discovery` → `spring-cloud-kubernetes-discovery` | 0.3 PD | 1.2 PD |
| 改造 `bootstrap.yml`：移除 Nacos 配置，添加 K8s ConfigMap 引用 | 0.5 PD | 2 PD |
| 将 Nacos 配置导出为 ConfigMap YAML | 0.3 PD | 1.2 PD |
| Sentinel 规则持久化迁移 | — | 1 PD（共享改造） |
| Seata 配置迁移 | — | 0.6 PD（共享改造） |
| 联调 + 回归测试 | — | 1 PD |
| **合计** | **2 PD** | **8 PD** |

### 2.5 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 动态推送能力丢失导致限流规则无法实时调整 | 高 | 高 | 保留 Sentinel Dashboard + 文件 DataSource，但延迟增大 |
| Seata 配置迁移后事务回滚异常 | 中 | 严重 | 需完整回归 Seata AT/TCC 全链路 |
| Spring Cloud Kubernetes 与 Spring Boot 3.2.5 兼容性问题 | 中 | 中 | 需 POC 验证 |

---

## 3. 方案 B：保留 Nacos 但配置 HA（3 节点集群）

### 3.1 方案描述

保留 Nacos 作为配置中心 + 服务发现，将其从单节点升级为 3 节点 HA 集群：

- **部署方式**：K8s StatefulSet（3 副本）+ Headless Service + PVC 持久化
- **后端存储**：外接 PostgreSQL（复用 `deploy/k8s/30-infrastructure.yml` 中的 postgres）
- **集群发现**：`cluster.conf` 显式声明 3 节点地址
- **Istio 共存**：Nacos 管配置 + 服务发现元数据，Istio 管流量路由

### 3.2 优势

1. **保留动态配置推送**：Nacos 核心能力不丢失，限流规则、Seata 参数可实时推送（<1s 延迟）
2. **多环境管理方便**：Nacos namespace 机制成熟，dev/test/prod 切分清晰
3. **服务发现不变**：4 服务无需任何代码改动，零迁移风险
4. **HA 部署成本低**：仅需 2 PD（部署 + 验证）
5. **配置灰度发布**：Nacos 原生支持按 group/namespace 灰度
6. **Sentinel/Seata 无需改造**：现有 `nexus-sentinel-rules.yaml` / `seata-server.properties` 直接复用
7. **Istio 可共存**：Istio 管流量（VirtualService / DestinationRule），Nacos 管配置，职责清晰

### 3.3 劣势

1. **额外维护 Nacos 集群**：3 节点 StatefulSet + PostgreSQL 后端，运维负担增加
2. **与 K8s 生态有距离**：Nacos 是外部依赖，不符合纯 K8s 原生理念
3. **资源成本**：3 节点 × (2 GiB memory + 1 CPU) = 6 GiB + 3 CPU
4. **后续仍可能需要迁移**：若 Phase 4+ 决定全面 K8s 原生化，仍需评估迁移

### 3.4 工作量明细

| 工作项 | 工作量 |
|--------|--------|
| 编写 Nacos HA StatefulSet + Service + ConfigMap | 0.5 PD |
| 部署到 K8s 集群 + 集群健康验证 | 0.5 PD |
| 配置热更新验证（4 服务联调） | 0.5 PD |
| 文档 + ADR | 0.5 PD |
| **合计** | **2 PD** |

### 3.5 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| Nacos 集群脑裂 | 低 | 高 | 使用 K8s StatefulSet 稳定网络标识 + Raft 协议 |
| PostgreSQL 后端单点 | 中 | 严重 | 复用现有 postgres PVC，后续可升级为 HA PostgreSQL |
| Istio 与 Nacos 服务发现冲突 | 低 | 中 | Istio 仅管流量，不接管服务发现注册 |

---

## 4. 方案 C：混合模式（Nacos 保留服务发现，ConfigMap 管理静态配置）

### 4.1 方案描述

渐进式迁移：

- **服务发现**：保留 Nacos（4 服务零改动）
- **静态配置**（数据库连接、线程池等）：迁移到 ConfigMap
- **动态配置**（限流规则、Seata 参数、链节点切换）：保留 Nacos

### 4.2 优势

1. **渐进式迁移**：降低一次性迁移的风险
2. **保留动态推送**：核心动态配置能力不丢失
3. **静态配置 GitOps 化**：静态配置通过 ConfigMap 纳入 GitOps

### 4.3 劣势

1. **两套配置管理增加复杂度**：开发需同时理解 Nacos 和 ConfigMap，心智负担重
2. **配置来源不统一**：排查问题时需在两处查找，运维体验下降
3. **Spring Cloud Kubernetes + Nacos 双 starter 共存**：可能存在 Bean 冲突或配置优先级问题
4. **未根本解决问题**：Nacos HA 仍需部署，且 ConfigMap 仍需维护

### 4.4 工作量明细

| 工作项 | 工作量 |
|--------|--------|
| 拆分静态/动态配置（4 服务） | 1 PD |
| 静态配置迁移到 ConfigMap | 1 PD |
| Spring Cloud Kubernetes + Nacos 双 starter 联调 | 1 PD |
| 文档 + 验证 | 1 PD |
| **合计** | **4 PD** |

### 4.5 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 双 starter Bean 冲突 | 中 | 中 | 需 POC 验证配置优先级 |
| 配置来源混乱导致排查困难 | 高 | 中 | 需严格文档化配置归属 |
| 后续仍需全量迁移 | 高 | 中 | 治标不治本 |

---

## 5. 方案对比

### 5.1 综合对比矩阵

| 维度 | 方案 A（全迁 ConfigMap） | 方案 B（Nacos HA） | 方案 C（混合） |
|------|--------------------------|--------------------|-----------------|
| **工作量** | 8 PD | 2 PD | 4 PD |
| **动态配置推送** | 丢失（延迟 5-30s） | 保留（<1s） | 部分保留 |
| **服务发现改动** | 全改 | 零改动 | 零改动 |
| **Sentinel/Seata 改造** | 需要 | 不需要 | 不需要 |
| **运维复杂度** | 低（纯 K8s） | 中（Nacos 集群） | 高（双系统） |
| **回滚成本** | 高 | 低 | 中 |
| **K8s 原生化** | 完全 | 部分 | 部分 |
| **GitOps 友好度** | 高 | 中 | 中 |
| **风险** | 高 | 低 | 中 |

### 5.2 加权评分

评分规则：每维度 1-5 分（5 分最优），权重总和 100%。

| 维度 | 权重 | 方案 A | 方案 B | 方案 C |
|------|------|--------|--------|--------|
| 工作量（越低越好） | 20% | 1 | 5 | 3 |
| 动态配置能力 | 25% | 1 | 5 | 3 |
| 迁移风险（越低越好） | 20% | 1 | 5 | 3 |
| 运维复杂度（越低越好） | 15% | 5 | 3 | 1 |
| K8s 原生化 | 10% | 5 | 2 | 3 |
| Sentinel/Seata 兼容 | 10% | 1 | 5 | 5 |
| **加权总分** | 100% | **1.6** | **4.35** | **2.95** |

---

## 6. 推荐方案

### 6.1 推荐：方案 B（保留 Nacos，部署 3 节点 HA 集群）

### 6.2 推荐理由

1. **Nacos 动态配置推送是核心能力**
   - NexusChain 大量使用动态配置：Sentinel 限流规则（`nexus-sentinel-rules.yaml`）、Seata 事务参数（`nexus-seata.yaml`）、链节点 RPC 切换、白名单/黑名单
   - 迁移到 ConfigMap 会丢失实时推送能力（延迟从 <1s 退化到 5-30s），在支付场景下不可接受（限流规则调整需即时生效）

2. **HA 部署成本低（2 PD vs 8 PD）**
   - 方案 B 仅需 2 PD，方案 A 需 8 PD
   - Phase 3 还有其他任务（P3-T7+），工程预算应优先用于业务能力而非基础设施迁移

3. **Istio 可以与 Nacos 共存**
   - Istio 管流量路由（VirtualService / DestinationRule / AuthorizationPolicy）
   - Nacos 管配置中心 + 服务发现元数据
   - 两者职责正交，无冲突（详见 `deploy/istio/README.md` 与 `nacos-config/README.md`）
   - 业界（蚂蚁、阿里、字节）普遍采用 Istio + Nacos 共存架构

4. **后续可在 Phase 4 评估是否需要迁移**
   - Phase 3 优先保证生产可用（HA 是刚需，迁移是优化）
   - Phase 4 可在业务稳定后，评估是否引入 Spring Cloud Kubernetes 或 K8s Operator 替代 Nacos
   - 保留迁移可能性，避免一次性大改

5. **Sentinel/Seata 无需改造**
   - 现有 `nexus-sentinel-rules.yaml` / `seata-server.properties` 直接复用
   - 避免引入 Sentinel DataSource 适配器 / Seata 配置源迁移的额外风险

### 6.3 不推荐方案 A 的关键原因

- **支付场景对限流规则实时性要求高**：双 11 / 节假日流量峰值时，需秒级调整限流阈值，ConfigMap 的 5-30s 延迟不可接受
- **Seata 配置迁移风险高**：Seata Server 配置迁移可能导致分布式事务回滚异常，影响资金安全

### 6.4 不推荐方案 C 的关键原因

- **治标不治本**：仍需维护 Nacos HA，且增加 ConfigMap 复杂度
- **双 starter 共存风险**：Spring Cloud Kubernetes + Nacos 双 starter 可能存在 Bean 冲突

---

## 7. 执行计划（方案 B）

### 7.1 部署清单

| 文件 | 用途 |
|------|------|
| `deploy/nacos/nacos-ha-statefulset.yaml` | 3 节点 Nacos StatefulSet（PVC 20Gi/节点，2 GiB memory + 1 CPU） |
| `deploy/nacos/nacos-ha-service.yaml` | Headless Service（集群发现）+ ClusterIP Service（客户端访问） |
| `deploy/nacos/nacos-configmap.yaml` | Nacos 配置 ConfigMap（application.properties + cluster.conf 模板） |
| `deploy/nacos/hot-reload-verify.yaml` | 配置热更新验证 ConfigMap 示例 |
| `deploy/nacos/README.md` | 部署和使用文档 |
| `docs/adr/ADR-026-nacos-ha-decision.md` | 决策记录 |

### 7.2 部署步骤

1. 创建 namespace `nexus`（若不存在）
2. 部署 PostgreSQL 后端数据库（复用 `deploy/k8s/30-infrastructure.yml`）
3. 应用 `nacos-configmap.yaml`（Nacos 配置 + cluster.conf）
4. 应用 `nacos-ha-service.yaml`（Headless + ClusterIP Service）
5. 应用 `nacos-ha-statefulset.yaml`（3 节点 StatefulSet）
6. 等待 3 节点全部 Ready，验证集群一致性
7. 应用 `hot-reload-verify.yaml`，验证配置热更新

### 7.3 验证清单

- [ ] 3 节点 Nacos 全部 Ready
- [ ] 集群一致性：3 节点配置同步一致
- [ ] 4 服务（gateway/bridge/signing-service/wallet-service）可连接 Nacos HA
- [ ] 配置热更新：修改配置后 <1s 推送到所有服务
- [ ] 故障切换：kill 1 节点，集群仍可读写
- [ ] Sentinel 规则推送：`nexus-sentinel-rules.yaml` 修改后实时生效
- [ ] Seata 配置推送：`nexus-seata.yaml` 修改后实时生效

---

## 8. 后续演进路径（Phase 4+ 评估）

虽然本任务推荐方案 B，但记录后续可能的演进路径供 Phase 4 评估：

### 8.1 触发迁移到 ConfigMap 的条件

- Nacos 集群运维成本持续上升
- K8s 原生配置中心生态成熟（如 Spring Cloud Kubernetes 支持动态推送）
- 业务对动态配置实时性要求降低

### 8.2 触发迁移到 Apollo / Consul 的条件

- 需要更强的配置灰度发布能力（按 IP / 标签灰度）
- 需要配置审计 + 回滚到任意版本
- 多语言微服务（非 Java）接入配置中心

### 8.3 不迁移的条件

- Nacos HA 集群稳定运行，运维成本可控
- 业务对动态配置实时性要求保持高
- Istio + Nacos 共存架构满足需求

---

## 9. 参考

- [ADR-026: Nacos 配置中心 HA 部署决策](./adr/ADR-026-nacos-ha-decision.md)
- [Nacos HA 部署文档](../deploy/nacos/README.md)
- [Istio 部署文档](../deploy/istio/README.md)
- [Nacos 配置初始化文档](../nacos-config/README.md)
- [K8s 部署文档](./k8s-deployment.md)
- [HA 验证文档](./ha-verification.md)