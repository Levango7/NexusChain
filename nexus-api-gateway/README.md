# NexusChain API Gateway

NexusChain 平台统一 API 入口模块，基于 Spring Cloud Gateway 实现。

## 模块定位

NexusChain API Gateway 是平台对外的唯一 HTTP 入口，承担统一鉴权、限流、日志、CORS、
路由转发职责，将外部请求按路径前缀分发至下游 4 个 Spring Boot 业务服务。

P3-T2（架构演进）引入，作为 Istio 服务网格的应用层补充：
- Istio 处理东西向（服务间）流量治理（mTLS、熔断、重试）
- API Gateway 处理南北向（外部 → 平台）流量治理（鉴权、限流、CORS）

## 核心功能

| 功能 | 实现类 | 说明 |
|------|--------|------|
| 路由转发 | `GatewayConfig` | 按路径前缀路由至下游 4 个服务（Java DSL） |
| 统一鉴权 | `AuthenticationFilter` | API Key + HMAC-SHA256 签名验证 + 时间戳防重放 |
| 统一限流 | `RateLimitFilter` | Redis 令牌桶，按 API Key / IP 维度限流 |
| 请求日志 | `RequestLogFilter` | method + path + status + latency + requestId |
| CORS 跨域 | `CorsFilter` | 可配置 Origin 白名单 + 预检处理 |

## 路由表

| 路径前缀 | 下游服务 | 端口 | 说明 |
|----------|----------|------|------|
| `/api/v1/payments/**` | nexus-gateway | 8080 | 支付网关 API |
| `/api/v1/bridge/**` | nexus-bridge | 8084 | 跨链桥 API |
| `/api/v1/signing/**` | nexus-signing-service | 8082 | 签名服务 API |
| `/api/v1/wallet/**` | nexus-wallet-service | 8083 | 钱包服务 API |

路由通过 Nacos 服务发现以 `lb://<service-name>` 协议解析实例，由 Spring Cloud
LoadBalancer 完成客户端负载均衡。

## 过滤器链顺序

```
请求 → CorsFilter(-400) → RequestLogFilter(-300) → AuthenticationFilter(-200)
     → RateLimitFilter(-150) → 路由匹配 → 转发至下游服务
```

| 顺序 | 过滤器 | Order | 职责 |
|------|--------|-------|------|
| 1 | CorsFilter | -400 | CORS 预检 / 跨域头注入 |
| 2 | RequestLogFilter | -300 | 记时开始 / requestId 注入 |
| 3 | AuthenticationFilter | -200 | API Key + HMAC 鉴权 |
| 4 | RateLimitFilter | -150 | Redis 令牌桶限流 |

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Spring Cloud 2023.0.3（Spring Cloud Gateway）
- Spring Cloud Alibaba 2023.0.1.0（Nacos 服务发现 + 配置中心）
- WebFlux + Reactor Netty（非阻塞响应式）
- Reactive Redis（限流令牌桶）
- Micrometer Tracing + Zipkin（链路追踪）

> **注意**：Spring Cloud Gateway 基于 WebFlux，禁止引入 `spring-boot-starter-web`
> （Tomcat 阻塞容器与 Reactive 编程模型冲突）。

## 配置项

### 鉴权配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nexus.api-gateway.auth.enabled` | `true` | 鉴权总开关 |
| `nexus.api-gateway.auth.api-keys` | `nexus-internal-api-key` | 合法 API Key 列表（逗号分隔） |
| `nexus.api-gateway.auth.hmac-secret` | `nexus-hmac-secret` | HMAC 共享密钥 |
| `nexus.api-gateway.auth.max-timestamp-skew-seconds` | `300` | 时间戳偏差上限（秒） |

### 限流配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nexus.api-gateway.ratelimit.enabled` | `true` | 限流总开关 |
| `nexus.api-gateway.ratelimit.capacity` | `100` | 令牌桶容量（突发上限） |
| `nexus.api-gateway.ratelimit.refill-rate` | `10` | 令牌补充速率（令牌/秒） |
| `nexus.api-gateway.ratelimit.fail-closed` | `false` | Redis 不可用时是否拒绝 |

### CORS 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nexus.api-gateway.cors.allowed-origins` | `*` | 允许的 Origin 列表 |
| `nexus.api-gateway.cors.allowed-methods` | `GET,POST,PUT,DELETE,PATCH,OPTIONS` | 允许的 HTTP 方法 |
| `nexus.api-gateway.cors.allow-credentials` | `false` | 是否允许携带凭证 |

## 鉴权协议

### 请求头

| 请求头 | 必填 | 说明 |
|--------|------|------|
| `X-Nexus-Api-Key` | 是 | 商户 API Key |
| `X-Nexus-Signature` | 是 | HMAC-SHA256 签名（Base64） |
| `X-Nexus-Timestamp` | 是 | 请求时间戳（Unix epoch 秒） |

### 签名算法

```
签名串 = method + "\n" + path + "\n" + timestamp
签名 = Base64(HMAC-SHA256(hmacSecret, 签名串))
```

> 注：API Gateway 不读取请求体（reactive 流只能消费一次），签名串不含 body 摘要。
> 如需 body 签名，应改用 GatewayFilter 在 body 缓存后校验。

### 放行路径

- `/actuator/**`：健康检查 / 指标暴露
- `OPTIONS` 方法：CORS 预检

## 限流算法

基于 Redis Lua 脚本实现的原子令牌桶：

1. 按 API Key 限流（`X-Nexus-Api-Key` 头），未携带时按客户端 IP 兜底
2. 同一身份的所有请求共享一个令牌桶
3. 限流触发返回 HTTP 429，响应头 `X-RateLimit-Remaining: 0`
4. Redis 不可用时降级为放行（fail-open），下游 Sentinel 兜底

## 构建

```bash
# 在仓库根目录执行
./gradlew.bat :nexus-api-gateway:bootJar
```

## 运行

```bash
# 本地开发（dev profile，鉴权关闭）
java -jar nexus-api-gateway/build/libs/nexus-api-gateway.jar

# 指定 profile
java -jar nexus-api-gateway/build/libs/nexus-api-gateway.jar --spring.profiles.active=prod
```

## Docker 构建

```bash
# 在仓库根目录执行（构建上下文必须是根目录）
docker build -t nexus-api-gateway:2.0.0 -f nexus-api-gateway/Dockerfile .
```

## 依赖服务

| 依赖 | 用途 | 必需 |
|------|------|------|
| Nacos | 服务发现 + 配置中心 | 是 |
| Redis | 限流令牌桶 | 是 |
| Zipkin | 链路追踪 | 否（可关） |

## Helm 部署

```bash
# umbrella chart 部署（包含 API Gateway）
helm install nexus-chain deploy/helm -f deploy/helm/values-prod.yaml

# 仅部署 API Gateway 子 chart
helm install nexus-api-gateway deploy/helm/charts/nexus-api-gateway
```

## 模块结构

```
nexus-api-gateway/
├── build.gradle                          # 构建配置
├── settings.gradle                       # composite build settings
├── Dockerfile                            # 多阶段构建
├── README.md                             # 本文档
└── src/main/
    ├── java/org/nexus/apigateway/
    │   ├── ApiGatewayApplication.java    # 启动类
    │   ├── config/
    │   │   └── GatewayConfig.java        # 路由配置（Java DSL）
    │   └── filter/
    │       ├── AuthenticationFilter.java # 统一鉴权
    │       ├── RateLimitFilter.java      # 统一限流
    │       ├── RequestLogFilter.java     # 请求日志
    │       └── CorsFilter.java           # CORS 配置
    └── resources/
        ├── application.yml               # 基础配置
        ├── application-dev.yml           # 开发环境
        └── bootstrap.yml                 # Nacos 配置
```