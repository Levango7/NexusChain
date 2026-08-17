# NexusChain Helm Chart

NexusChain 区块链支付编排平台 Helm Umbrella Chart，聚合 5 个 Spring Boot 服务子 chart。

## 目录结构

```
deploy/helm/
├── Chart.yaml                    # umbrella chart
├── values.yaml                   # 全局默认 values（基线对齐 staging）
├── values-dev.yaml               # dev 环境覆盖
├── values-staging.yaml           # staging 环境覆盖
├── values-prod.yaml              # prod 环境覆盖
├── templates/
│   ├── _helpers.tpl              # 模板辅助函数
│   ├── namespace.yaml            # Namespace
│   ├── networkpolicy.yaml        # NetworkPolicy（零信任东西向）
│   └── secret-registry.yaml      # 镜像拉取 Secret（可选）
├── charts/                       # 子 chart
│   ├── nexus-gateway/            # 支付网关 :8080
│   ├── nexus-bridge/             # 跨链桥 :8084
│   ├── nexus-signing-service/    # 签名服务 :8082
│   ├── nexus-wallet-service/     # 钱包服务 :8083
│   └── nexus-api-gateway/        # API 统一入口 :8085（Spring Cloud Gateway）
└── README.md
```

每个服务子 chart 包含 7 个模板：`deployment` / `service` / `configmap` / `secret` / `hpa` / `pdb` / `servicemonitor`。

## 服务架构

| 服务 | 端口 | 依赖 | 说明 |
|------|------|------|------|
| nexus-gateway | 8080 | nacos + seata + zipkin | 支付网关，外部入口 |
| nexus-bridge | 8084 | nacos + zipkin | 跨链桥，不参与 Seata 事务 |
| nexus-signing-service | 8082 | nacos + seata + zipkin | 签名服务 |
| nexus-wallet-service | 8083 | nacos + seata + zipkin | 钱包服务 |
| nexus-api-gateway | 8085 | nacos + zipkin + redis | API 统一入口（Spring Cloud Gateway，WebFlux 非阻塞） |

基础设施（Nacos/Sentinel/Seata/Zipkin/Postgres/Redis）由独立 chart 或运维平台提供，本 chart 仅注入指向它们的服务地址环境变量。

## 前置条件

- Kubernetes 1.21+
- Helm 3.8+
- Prometheus Operator（kube-prometheus-stack，用于 ServiceMonitor CRD）
- 基础设施服务已部署（Nacos/Sentinel/Seata/Zipkin/Postgres/Redis）

## 快速开始

### 安装

```bash
# dev 环境
helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-dev.yaml \
  -n nexus-dev --create-namespace

# staging 环境
helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-staging.yaml \
  -n nexus-staging --create-namespace

# prod 环境
helm upgrade --install nexus-chain deploy/helm/ \
  -f deploy/helm/values-prod.yaml \
  -n nexus
```

### 渲染检查

```bash
# 渲染 dev 环境清单（不安装）
helm template nexus-chain deploy/helm/ -f deploy/helm/values-dev.yaml

# lint 检查
helm lint deploy/helm/
```

### 卸载

```bash
helm uninstall nexus-chain -n nexus
```

## 多环境差异

| 维度 | dev | staging | prod |
|------|-----|---------|------|
| 副本数 | 1 | 2 | 3 |
| 资源 requests | 256Mi / 500m | 512Mi / 1 | 1Gi / 2 |
| 资源 limits | 512Mi / 1 | 1Gi / 2 | 2Gi / 4 |
| HPA | 禁用 | 2-4 | 3-10 |
| PDB | 禁用 | minAvailable=1 | minAvailable=2 |
| 镜像 tag | latest | staging | 2.1.0（固定） |
| imagePullPolicy | Always | IfNotPresent | IfNotPresent |
| ServiceMonitor | 关闭 | 开启 | 开启 |
| NetworkPolicy | 关闭 | 开启 | 开启 |
| topologySpread | 无 | 无 | 多 AZ |
| Spring profile | dev | staging | prod |

## 关键配置

### 环境变量注入

每个服务的 Deployment 自动注入以下环境变量（根据 `dependencies` 标志）：

| 环境变量 | 来源 | 说明 |
|----------|------|------|
| `SPRING_PROFILES_ACTIVE` | global.springProfile / global.env | Spring profile |
| `NEX_NACOS_SERVER` | nacos.host:httpPort | Nacos 配置中心地址 |
| `NEX_NACOS_GRPC_SERVER` | nacos.host:grpcPort | Nacos gRPC 服务发现地址 |
| `NEX_SEATA_SERVER` | seata.host:tcPort | Seata TC 地址 |
| `NEX_SENTINEL_SERVER` | sentinel.host:port | Sentinel 控制台地址 |
| `NEX_ZIPKIN_ENDPOINT` | zipkin.host:port | Zipkin 链路追踪端点 |
| `SPRING_DATASOURCE_URL` | postgres.host:port/db | JDBC 数据源 URL |
| `SPRING_REDIS_HOST/PORT` | redis.host:port | Redis 地址 |

### 探针

- **livenessProbe**：HTTP GET `/actuator/health`，period 30s，initialDelay 40s
- **readinessProbe**：HTTP GET `/actuator/health/readiness`，period 10s，initialDelay 20s
- **startupProbe**：默认禁用，可在 values 中开启（适用于慢启动 JVM）

### 安全上下文

- Pod 级：`runAsNonRoot: true`，`runAsUser: 65532`（distroless nonroot），`seccompProfile: RuntimeDefault`
- 容器级：`allowPrivilegeEscalation: false`，`capabilities.drop: [ALL]`
- `automountServiceAccountToken: false`（服务不调用 K8s API）

### ConfigMap / Secret

- **ConfigMap**（`<service>-config`）：挂载 `application.yml` 到 `/app/config/application.yml`，Spring Boot 自动合并
- **Secret**（`<service>-secret`）：通过 `envFrom` 注入敏感配置

⚠️ **生产安全须知**：Secret 的 `stringData` 明文仅占位，生产环境必须使用 sealed-secrets / external-secrets / SOPS 注入，避免明文入仓。

### 滚动更新

Deployment 注入 `checksum/config` 和 `checksum/secret` 注解，ConfigMap/Secret 内容变更时自动触发滚动重启。

更新策略：`RollingUpdate`，`maxUnavailable: 0`，`maxSurge: 1`（滚动期间始终保持全部副本可用）。

## 自定义示例

### 覆盖镜像仓库

```yaml
# values 自定义覆盖
global:
  imageRegistry: registry.internal.corp/nexus
nexus-gateway:
  image:
    tag: "1.9.7"
```

### 配置私有仓库拉取 Secret

```yaml
global:
  imagePullSecrets:
    - name: ghcr-pull-secret
      registry: ghcr.io
      username: "ci-puller"
      password: "ghp_xxxxxxxx"
      email: ""
```

### 注入服务专属 Secret

```yaml
nexus-gateway:
  secrets:
    stringData:
      NEX_DB_USERNAME: "nexus"
      NEX_DB_PASSWORD: "CHANGE_ME"
      NEX_MASTER_KEY: "BASE64_32_BYTE_AES_KEY"
      NEX_WEBHOOK_SECRET: "CHANGE_ME"
```

### 自定义 application.yml

```yaml
nexus-gateway:
  config:
    applicationYml: |-
      management:
        endpoints:
          web:
            exposure:
              include: health,info,prometheus,metrics
      nexus:
        gateway:
          rate-limit:
            enabled: true
            rps: 100
```

### 启用 startupProbe（慢启动 JVM）

```yaml
nexus-gateway:
  probes:
    startup:
      enabled: true
      periodSeconds: 10
      failureThreshold: 30   # 10s × 30 = 5 分钟启动宽限
```

## 故障排查

| 问题 | 原因 | 解决 |
|------|------|------|
| Pod CrashLoopBackOff | Nacos/Seata 未就绪 | 检查基础设施 Service DNS 是否可达 |
| ServiceMonitor 无 target | 应用未暴露 `/actuator/prometheus` | 引入 micrometer-registry-prometheus 并配置 exposure.include |
| 镜像拉取失败 | 私有仓库凭证缺失 | 配置 global.imagePullSecrets |
| HPA 不生效 | 缺少 metrics-server | 安装 metrics-server |

## 与旧 K8s YAML 的关系

本 Helm Chart 替代 `deploy/k8s/` 下的静态 YAML（10-gateway.yml 等），提供参数化、多环境、版本化的部署能力。旧 YAML 保留作为参考，不再用于部署。

基础设施层（Nacos/Seata/Zipkin/Postgres/Redis，对应 30-infrastructure.yml）不在本 chart 范围内，由独立 chart 或运维平台管理。
## nexus-api-gateway 子 chart 说明（REQ-41/P2）

`charts/nexus-api-gateway/` 是 Spring Cloud Gateway 统一 API 入口子 chart，聚合下游 nexus-gateway / nexus-bridge 的路由与限流。

### 关键 values

| 路径 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 是否启用本子 chart |
| `replicaCount` | `2` | 副本数（HPA 启用时作为初始值） |
| `image.repository` | `""` | 镜像 repository（留空回退 `global.imageRegistry/nexus-api-gateway`） |
| `image.tag` | `""` | 镜像 tag（留空回退 `global.env`） |
| `service.port` | `8085` | Service 端口 |
| `service.targetPort` | `8085` | 容器端口 |
| `springProfile` | `""` | Spring profile 覆盖（留空回退 `global.springProfile` / `global.env`） |
| `dependencies.nacos` | `true` | 注入 `NEX_NACOS_SERVER` / `NEX_NACOS_GRPC_SERVER` |
| `dependencies.seata` | `false` | API Gateway 不参与 TCC 事务 |
| `dependencies.sentinel` | `false` | 不依赖 Sentinel |
| `dependencies.zipkin` | `true` | 注入 `NEX_ZIPKIN_ENDPOINT` |
| `dependencies.postgres` | `false` | 无业务数据库 |
| `dependencies.redis` | `true` | 注入 `SPRING_REDIS_HOST/PORT`（限流用） |
| `extraEnv` | `{}` | 额外环境变量（如 `NEX_AUTH_ENABLED`、`NEX_API_KEYS`） |
| `resources.requests` | `256Mi / 250m` | 资源 requests（WebFlux IO 密集，内存占用低） |
| `resources.limits` | `512Mi / 1000m` | 资源 limits |
| `hpa.enabled` | `false` | HPA 开关（默认随 `global.hpaEnabled`） |
| `pdb.enabled` | `true` | PDB 开关（`minAvailable: 1`） |
| `serviceMonitor.enabled` | `true` | ServiceMonitor 开关（Prometheus 抓取 `/actuator/prometheus`） |
| `topologySpreadConstraints` | `[]` | 跨 AZ 打散（prod values 中开启） |
| `routes` | 见下 | Spring Cloud Gateway 路由配置 |

### 路由配置示例

`yaml
nexus-api-gateway:
  routes:
    - id: gateway
      uri: lb://nexus-gateway
      predicates:
        - Path=/api/v1/**
      filters:
        - StripPrefix=0
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 100
            redis-rate-limiter.burstCapacity: 200
    - id: bridge
      uri: lb://nexus-bridge
      predicates:
        - Path=/api/v1/bridge/**
`

### 与 umbrella values 的关系

本子 chart 的 values 通过 umbrella `.Values.nexus-api-gateway.*` 覆盖，`global.*` 透传。详见 `charts/nexus-api-gateway/values.yaml` 注释。