# NexusChain Gateway

NexusChain 支付编排平台的核心模块：面向商户的统一支付入口，提供收单、收银台、多通道路由（编排）、渠道接入、对账、资金管理与订阅计费能力。

> **版本口径**：本模块随根构建发布，无独立版本号（当前 `2.51.0`）。版本单一真源为仓库根
> `build.gradle` 的 `version`，受 CI 门禁 `scripts/check-version-consistency.sh` 保护；
> 发布说明见根 [CHANGELOG](../CHANGELOG.md)。最后更新：2026-09-28。

## 模块定位

Gateway 是 NexusChain 的商户侧入口与支付编排执行核心：

- **对外**：统一 REST API —— 基础订单/退款/收银台/订阅（`/api/v1`、`/api/v2`）+ 编排 API（`/api/v1/payments`）；
- **对内**：订单生命周期、多通道路由（priority / weight / cost / explicit / 多目标策略，含降级与 A/B 实验）、
  渠道适配器，以及清结算 / 合规 / 分析 / 预言机等中间服务层的关卡接入。

## 模块关系

### 中间服务层（nexus-settlement / nexus-compliance / nexus-analytics / nexus-oracle）

均为**库模块**（`bootJar.enabled=false`），由本模块经 Gradle composite build（`includeBuild`）**进程内消费**，无 HTTP 开销。

### 链节点（nexus-core / nexus-consortium）

**HTTP RPC** 远程调用（独立进程），经 `orchestration/connectors` 下的
`ChainConnector` / `ConsortiumConnector` 与链交互。双链结算是产品有意设计：
`nexus-core` 为公链结算主网，`nexus-consortium` 为许可制联盟/侧链。

### 签名 / 钱包服务（nexus-signing-service / nexus-wallet-service）

**HTTP REST** 调用（`client/HttpSigningServiceClient`、`client/HttpWalletMgmtClient`）。
Gateway 不持有私钥：链上交易（支付、退款、订阅授权）由签名服务签名后广播。
原 `nexus-exchange-wallet` 已于 v1.4.0 拆分移除。

### nexus-sdk

本模块依赖 `:nexus-sdk:java`（签名/钱包服务客户端接口与 DTO，见 `client/`）。
SDK 对外提供 Java / TypeScript / Python / Go 四语言接入。

### nexus-api-gateway

生产拓扑中的统一流量入口（Spring Cloud Gateway + Nacos 服务发现），经 `lb://` 路由至本模块。

## 核心能力（Payment Orchestration Wave 1-16）

| 能力域 | 关键包 | 说明 | Wave |
|--------|--------|------|------|
| 支付核心 | `qr` `split` `settlement` `limit` | 扫码支付；分账/分润（FIXED/RATIO、阶梯、延迟分账）；结算周期 T0-T3/CUSTOM；单笔/日/月与渠道级限额 | W1/W8 |
| 风控体系 | `risk` `risk.link` | 评分引擎、设备指纹、风控事件流、规则链与动态阈值；账户联动（冻结/限制）、余额告警、大额拦截 | W2/W8/W11 |
| 渠道接入 | `orchestration.connectors` | WeChatPay、Alipay、Adyen、Stripe、HttpPsp（含动态）、Chain、Consortium、Mock、Oracle 喂价适配器；渠道回调验签 | W3/W7/W13 |
| 商户服务 | `dashboard` `onboarding` `sandbox` | 门户仪表盘；入驻申请与审核；沙箱模拟 / 配置自检 / 回调模拟 | W4/W7/W14 |
| 对账体系 | `reconciliation` | 对账文件（CSV/JSON）、自动对账引擎、差错处理与挂账、规则配置、账单下载（微信/支付宝）、自动补偿、T+1 报表 | W4/W7/W9/W15 |
| 运维可靠性 | `alert` `ops` `sla` `logging` `resilience` `fallback` | 告警（聚合/抑制/升级）、运维 API、SLA/SLO 监控、结构化日志（脱敏/采样）、熔断降级 | W5/W9 |
| 开放平台 | `apikey` `webhook` `developer` `apiversion` `export` | API Key 生命周期；Webhook 订阅与投递（重试/死信/追踪）；开发者门户；版本治理；数据导出 | W6/W8 |
| 资金管理 | `account` `transaction` `fundtransfer` `fundreport` `reserve` `voidreversal` `escrow` | 三类型账户（BALANCE/FROZEN/RESERVE）；TCC/Saga 分布式事务；资金调拨与归集；备付金；自动提现；资金报表；撤销冲正；担保交易/预授权 | W10/W11 |
| 支付安全 | `security` | 字段加密、重放防护、3DS、支付密码 | W12 |
| 智能路由 | `orchestration.routing` | 多目标策略、渠道健康度、降级与跨渠道补偿、A/B 实验、决策审计、商户画像、异常监控 | W16 |

## API 概览

所有接口均为 RESTful 风格，使用 JSON 交互；除公开例外（见「安全模型」），均要求商户 API Key。

> **说明**：生产主链路为订单模型 API（`/api/v1/orders`，由 `PaymentController` 提供）；
> 编排 API（`/api/v1/payments`）面向编排场景，含 connector 与 routing-rules 管理。两者并存。

### 基础接口

#### 商户接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/merchants/register` | 商户注册（公开入口） |
| POST | `/api/v1/merchants/{id}/verify` | 商户认证（ADMIN） |
| POST | `/api/v1/merchants/{id}/api-keys` | 生成 API 密钥（ADMIN） |
| DELETE | `/api/v1/merchants/{id}/api-keys` | 撤销 API 密钥（ADMIN） |
| GET | `/api/v1/merchants/{id}` | 查询商户信息 |

#### 支付接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/orders` | 创建支付订单 |
| GET | `/api/v1/orders/{id}` | 查询订单 |
| POST | `/api/v1/orders/{id}/pay` | 发起支付 |
| POST | `/api/v1/orders/{id}/confirm` | 确认支付 |
| POST | `/api/v1/orders/{id}/refund` | 发起退款 |
| GET | `/api/v1/checkout/{token}` | 收银台页面跳转（公开） |

#### 订阅接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/subscriptions` | 创建订阅（经签名服务提交链上 SUBSCRIPTION_AUTH 授权，失败回退为未授权订阅） |
| GET | `/api/v1/subscriptions/{id}` | 查询订阅 |
| POST | `/api/v1/subscriptions/{id}/charge` | 手动扣款 |
| POST | `/api/v1/subscriptions/{id}/cancel` | 取消订阅 |

#### Webhook 接口

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/v1/webhooks/chain-events` | 链上事件接收（公开，验签保护） |

### 扩展接口分组索引（Wave 1-16，全模块共 64 个控制器）

| 分组 | 路径前缀 | 说明 |
|------|----------|------|
| 编排与路由 | `/api/v1/payments`、`/api/v1/routing/*` | 编排 API；路由配置（`strategy-configs`）、健康度（`health`）、降级（`fallback-configs`）、补偿（`compensation-routes`）、实验（`experiments`）、审计（`decisions`）、画像（`profiles`）、监控（`monitor`） |
| 渠道回调 | `/api/v1/callbacks`、`/api/v1/webhooks`、`/api/v1/webhook-subscriptions`、`/api/v1/webhook-admin` | 微信/支付宝回调统一入口；链事件；订阅管理；投递管理 |
| 资金与结算 | `/api/v1/accounts`、`/api/v1/fund-transfers`、`/api/v1/fund-reports`、`/api/v1/auto-withdraw`、`/api/v1/fund-dashboard`、`/api/v1/reserve`、`/api/v1/settlement-confirmations`、`/api/v1/execution` | 账户；调拨；报表；自动提现；资金仪表盘；备付金；链上结算确认；链上执行通道 |
| 对账与差错 | `/api/v1/reconciliation/*`、`/api/v1/suspense-accounts`、`/api/reconciliation/link` | 规则配置（`rules`）；账单下载（`bills`）；补偿（`compensations`）；T+1 报表（`reports`）；差错（`discrepancies`）；挂账；对账联动（JWT 鉴权） |
| 风控与限额 | `/api/v1/risk/*`、`/api/v1/limits` | 风控事件（`events`）；账户联动（`link`）；余额告警（`balance-alert`）；限额 |
| 支付安全 | `/api/v1/security/*` | 3DS（`3ds`）；支付密码（`payment-password`）；加密与重放保护端点 |
| 运维可观测 | `/api/v1/alerts`、`/api/v1/ops`、`/api/v1/sla`、`/api/v1/resilience` | 告警；运维；SLA；熔断指标 |
| 商户服务 | `/api/v1/merchants/{id}/dashboard`、`/api/v1/onboarding`、`/api/v1/qr`、`/api/v1/split`、`/api/v1/escrow`、`/api/v1/voidreversal`、`/api/v1/sandbox`、`/api/sandbox/*` | 仪表盘；入驻；扫码；分账；担保/预授权；撤销冲正；沙箱工具（`/api/sandbox/{health,config-check,callback-simulator}` 不在 API Key 拦截范围） |
| 开放平台治理 | `/api/v1/api-keys`、`/api/v1/api-version`、`/api/v1/data-exports`、`/api/v1/developer` | Key 管理；版本策略；数据导出；开发者门户 |
| v2 版本 | `/api/v2/orders`、`/api/v2/payments`、`/api/v2/merchants`、`/api/v2/tenants` | v2 订单/支付/商户/租户 |

> **破坏性变更（Wave 15，2026-09-28）**：对账端点 `/api/v1/reconciliation/{rules,bills,compensations,reports}`
> 从 `/api/reconciliation/**` 迁入 `/api/v1` 并纳入商户 API Key 认证（迁移前因不在拦截范围而实际不可达）。
> `/api/reconciliation/link` 保持原路径（JWT + `@PreAuthorize` 鉴权），不迁移。

## 安全模型

| 机制 | 覆盖范围 | 实现 |
|------|----------|------|
| 商户 API Key | `/api/v1/**`、`/api/v2/**`；公开例外：`checkout`、`webhooks`（链事件接收）、`merchants/register` | `interceptor/ApiKeyInterceptor`（`config/WebConfig` 注册） |
| 管理员鉴权 | 商户管理写端点（`verify`、`api-keys`） | JWT + `@PreAuthorize("hasRole('ADMIN')")` |
| HMAC 请求签名 | `/api/v1/payments/**`、`/api/v1/refunds/**`、`/api/v1/orders/**` | `security/RequestSignatureInterceptor`；v2 长度前缀 canonical（`NXC2`），服务端 v1/v2 兼容期 |
| 商户归属校验（IDOR） | 商户资源端点 | `security/MerchantOwnershipGuard` |
| Webhook 签名 | 投递（出）/ 链事件接收（入） | v2 绑定 deliveryId + timestamp（`NXCW`）；接收侧 v1/v2 双接受（`nexus.webhook.require-v2`） |
| 多租户 | `/api/v1/**`、`/api/v2/**`（排除公开与租户管理端点） | `TenantApiKeyInterceptor` + 租户级限流 |
| 支付安全 | 支付/退款链路 | 字段加密、重放防护、3DS、支付密码（Wave 12） |

## 数据库与迁移

- **Flyway** 管理 schema：`src/main/resources/db/migration/`（MySQL 方言，77 个脚本，版本号至 **V90**）。
- 近期 Wave 新增（V81-V90）：`V81 reconciliation_rule_configs`、`V82 compensation_records`、
  `V83 reconciliation_report_records`、`V84 routing_strategy_configs`、`V85 channel_health_history`、
  `V86 fallback_route_configs`、`V87 compensation_routing_records`、`V88 routing_experiments`、
  `V89 routing_decision_records`、`V90 merchant_routing_profiles`。
- `src/main/resources/db/migration-pg/`（PostgreSQL 方言）**仅至 V17，滞后于主迁移目录**（如实声明）。
- 生产 profile `ddl-auto=validate`（schema 由 Flyway 管理，禁止 Hibernate 自动改表）。

## 技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| Java | 17（Gradle toolchain） |
| Spring Boot | 4.0.8 |
| 构建 | Gradle 8.14（wrapper；随根构建编译，见「构建」） |
| Web / 持久化 | Spring Web、Spring Data JPA、Spring Security、Validation、Actuator、Data Redis |
| 数据库 | H2（dev/sandbox 内存）、MySQL 8 方言（默认配置）、PostgreSQL（prod profile） |
| 迁移 | Flyway 10.x（`flyway-mysql`、`flyway-database-postgresql`） |
| 其他 | Jackson、Caffeine（有界缓存）、ZXing（二维码）、springdoc-openapi |
| Tomcat | 11.0.25（BOM 属性覆盖，修复 CVE-2026-65182 / CVE-2026-65905 / CVE-2026-68525） |

## 构建

> **本模块须在仓库根目录构建**：`nexus-gateway/build.gradle` 引用根构建子项目
> （`:nexus-common`、`:nexus-sdk:java`），而 `nexus-gateway/settings.gradle` 未 include 这些依赖，
> 在 `nexus-gateway/` 目录内独立执行 Gradle 会在配置阶段直接失败。

```bash
# 仓库根目录执行
./gradlew :nexus-gateway:build            # 编译 + 单元测试 + 覆盖率门禁
./gradlew :nexus-gateway:test             # 仅单元测试（默认排除 @Tag("integration")）
./gradlew :nexus-gateway:integrationTest  # 集成测试（需 Nacos / Redis 等基础设施）
./gradlew :nexus-gateway:bootJar          # 构建可执行 jar（nexus-gateway/build/libs/）
```

容器化：`nexus-gateway/Dockerfile`（多阶段构建，distroless 非 root 运行时）；
构建上下文必须是仓库根目录（`docker build -f nexus-gateway/Dockerfile .`），
或直接使用根 `docker-compose.yml` 的 `nexus-gateway` 服务。

## 运行

```bash
# 沙箱模式：零外部依赖（H2 内存库、Flyway 关闭），端口 8080
./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"
```

| profile | 数据源 | 说明 |
|---------|--------|------|
| `sandbox` | H2 内存（`create-drop`，Flyway 关闭） | 零依赖本地运行 / 集成测试同款 profile |
| `dev` | H2 内存（MySQL 兼容模式，Flyway 开启） | 本地开发 |
| `prod` | PostgreSQL（`NEX_DB_URL` 注入，`ddl-auto=validate`） | 生产，schema 由 Flyway 管理 |

默认端口 8080（`application.yml`）。全栈编排（PostgreSQL / Nacos / Seata / Zipkin 等）见根 `docker-compose.yml` 与根 [README](../README.md)。

## 测试与质量门禁

- 规模：**211 个测试类 / 约 2,480 个用例**（`src/test`）。
- 集成测试策略：默认 `test` 任务排除 `@Tag("integration")`（依赖外部基础设施），经 `:nexus-gateway:integrationTest` 单独执行。
- JaCoCo 门禁：BUNDLE 指令覆盖率 ≥ **0.30**；`org.nexus.gateway.orchestration` 包 ≥ **0.60**。
- CI：根 `ci.yml` 的 `build-and-test` job 以 `gradlew check` 全量执行（含覆盖率门禁）。

## 相关文档

- [根 README](../README.md) — 快速开始、全栈编排、模块清单
- [ARCHITECTURE](../ARCHITECTURE.md) — 架构分层与 gateway 包结构
- [CHANGELOG](../CHANGELOG.md) — 版本变更（Payment Orchestration Wave 1-16 详情）
- [docs/wave13-sandbox-configuration-guide.md](docs/wave13-sandbox-configuration-guide.md) — 渠道沙箱配置指南
- [docs/ops/sandbox-semantics.md](../docs/ops/sandbox-semantics.md) — sandbox SIMULATED 语义与生产红线
