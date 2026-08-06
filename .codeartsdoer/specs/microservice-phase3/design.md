# NexusChain Phase 3 微服务化深度架构分析与方案设计

> 文档定位：为 NexusChain 在 Phase 1 + Phase 2 微服务化（signing-service / wallet-service / bridge 独立部署 + Nacos + Sentinel + OpenFeign）基础上，补齐「分布式事务 / 链路追踪 / fallback 绑定 / 运行时验证」四大遗留项，提供深度需求分析与方案设计。**只做分析和设计，不写代码**。
>
> 适用版本：v1.4.0 → v1.5.0（Phase 3）
> 撰写日期：2026-08-07
> 作者：架构分析师
> 前置文档：`.codeartsdoer/specs/microservice-phase1-2/design.md`（Phase 1 + Phase 2 方案）

## 第1章 背景与目标

### 1.1 当前架构快照（v1.4.0，commit 5dbfbc2）

表：Phase 1 + Phase 2 交付现状

| 维度 | 现状 |
|------|------|
| 语言 / 框架 | Java 17 + SpringBoot 3.2.5 + Spring Cloud 2023.0.3 + SCA 2023.0.1.0 |
| 独立部署服务 | gateway（8080）/ signing-service（8082）/ wallet-service（8083）/ bridge（8084）|
| 服务发现 / 配置中心 | Nacos 2.3.2（docker-compose 单节点，嵌入式 derby）|
| 熔断 / 限流 | Sentinel 1.8.8（Dashboard 8858）+ Resilience4j 2.2.0（共存，Resilience4j 管理链节点 HTTP，Sentinel 管理 Feign）|
| 跨服务调用 | OpenFeign 4.1.0 + Spring Cloud LoadBalancer 4.1.0（gateway → signing/wallet/bridge，wallet → signing）|
| exchange-wallet | 已移除（源码归档至 `nexus-exchange-wallet.archived/`）|
| 分布式事务 | **无**（Phase 1+2 明确不含 Seata）|
| 链路追踪 | **仅 gateway 单点 TracingConfig**（X-NexusChain-Trace-Id header + MDC，无跨服务传播）|
| Feign fallback | **3 个 fallback 类已实现但未绑定到 @FeignClient**（CHANGELOG v1.4.0 明确记录）|
| 运行时验证 | **仅编译验证**（`gradle build -x test`），无端到端集成验证 |

### 1.2 Phase 3 目标边界

表：Phase 3 目标与不含

| 阶段 | 目标 | 不含 |
|------|------|------|
| Phase 3 | ① Seata 分布式事务接入（跨服务支付/退款/提现一致性）<br>② 链路追踪增强（Micrometer Tracing + Zipkin，跨服务 traceId 串联）<br>③ Feign fallback 绑定（3 个 fallback 类生效）<br>④ 运行时集成验证（docker-compose 全链路 + 集成测试）<br>⑤ 配置一致性修复 + 优雅停机 | 服务网格（Istio/Linkerd）、APM 全量接入、多活容灾 |

### 1.3 设计原则

1. **最小侵入**：优先用 SpringBoot 3.2.x 内置能力（Micrometer Tracing），不引入 Java agent（排除 Skywalking agent 方案）。
2. **SCA 版本对齐**：Seata 版本必须与 SCA 2023.0.1.0 + SpringBoot 3.2.5 兼容，严格按版本矩阵选型。
3. **fallback 零 SDK 改动**：fallback 绑定方案不改 nexus-sdk（Feign 接口定义模块），在 gateway 侧解决。
4. **渐进式事务**：只对真正需要跨服务一致性的业务方法标注 `@GlobalTransactional`，不为所有 @Transactional 加分布式事务（避免性能损耗）。
5. **可回滚**：每项增强通过配置开关控制，可快速回退到 Phase 2 状态。

## 第2章 交付物1：当前架构问题罗列

### 2.1 问题一：分布式事务缺失

#### 2.1.1 @Transactional 分布全景

表：@Transactional 注解分布（32 处）

| 模块 | 类 | 方法数 | 跨服务调用 | 是否需要分布式事务 |
|------|---|--------|-----------|------------------|
| gateway | `PaymentServiceImpl` | 3（initiatePayment / confirmPayment / refund） | refund：walletMgmtClient + signingServiceClient | **refund 需要**（签名成功但本地回滚 → 资金丢失）|
| gateway | `SubscriptionServiceImpl` | 4（createSubscription / charge / cancel / processDueSubscriptions） | charge：walletMgmtClient + signingServiceClient | **charge 需要**（扣款成功但本地回滚 → 重复扣款）|
| gateway | `DefaultRefundApprovalService` | 4（requestRefund / approveRefund / rejectRefund / executeRefund） | executeRefund：executionChannel.execute()（进程内 composite build） | **暂不需要**（OnChainExecutionChannel 是进程内调用，非跨服务；但若未来改为 Feign 调 signing-service 则需要）|
| gateway | `OrchestrationService` | 2（createPayment / refreshStatus） | createPayment：connector.createPayment()（外部 PSP HTTP） | **暂不需要**（外部 PSP 不参与 Seata 事务；用幂等 + 对账兜底）|
| gateway | `OrderServiceImpl` | 2 | 无 | 不需要 |
| gateway | `MerchantServiceImpl` | 4 | 无 | 不需要 |
| bridge | `BridgeServiceImpl` | 4（lock / mint / burn / unlock） | 无（纯本地 txRepository.save） | 不需要（bridge 内部数据库操作）|
| bridge | `DefaultEmergencyPauseService` | 3 | 无 | 不需要 |
| bridge | `DefaultInsuranceFund` | 3 | 无 | 不需要 |
| consortium | `BlockRepositoryService` | 1 | 无 | 不需要 |

#### 2.1.2 跨服务事务边界识别

图：需要分布式事务的跨服务调用链

```
场景1：支付退款（PaymentServiceImpl.refund，@Transactional）
  gateway 本地事务 {
    orderRepository.save(order)          // 订单状态 → REFUNDED
    refundRepository.save(refund)        // 退款记录 → COMPLETED
  }
  跨服务调用 {
    walletMgmtClient.addressToPubkeyHash()  // Feign → wallet-service（查询，幂等）
    signingServiceClient.signTransfer()     // Feign → signing-service（签名+广播，不可逆）
  }
  风险：signTransfer 成功但本地事务回滚 → 链上交易已发生但数据库无记录 → 资金丢失

场景2：订阅扣款（SubscriptionServiceImpl.charge，@Transactional）
  gateway 本地事务 {
    subscriptionRepository.save(sub)     // 订阅 chargedCount+1, nextChargeAt 更新
  }
  跨服务调用 {
    walletMgmtClient.addressToPubkeyHash()  // Feign → wallet-service（查询，幂等）
    signingServiceClient.transfer()         // Feign → signing-service（签名+广播，不可逆）
  }
  风险：transfer 成功但本地事务回滚 → 链上扣款已发生但订阅状态未更新 → 下周期重复扣款

场景3：提现执行（wallet-service DefaultWithdrawalApprovalService.executeApprovedWithdrawal）
  wallet-service 内存操作 {
    requests.put(requestId, request)     // ConcurrentHashMap（当前无 @Transactional）
  }
  跨服务调用 {
    signingServiceClient.signTransfer()     // Feign → signing-service（签名+广播）
  }
  风险：signTransfer 成功但服务重启 → 内存状态丢失 → 重复执行提现
  注意：当前为内存存储，生产环境替换为持久化存储后，此场景才真正需要分布式事务
```

#### 2.1.3 问题小结

- **2 个确认需要分布式事务的场景**：`PaymentServiceImpl.refund` + `SubscriptionServiceImpl.charge`，均在 gateway，跨服务调用 signing-service 的不可逆签名广播操作。
- **1 个潜在场景**：`DefaultWithdrawalApprovalService.executeApprovedWithdrawal`（wallet-service → signing-service），当前内存存储不需要，持久化后需要。
- **不需要分布式事务的场景**：bridge 的 lock/mint/burn/unlock（纯本地事务）、OrchestrationService.createPayment（外部 PSP，用幂等 + 对账兜底）、DefaultRefundApprovalService.executeRefund（进程内 composite build 调用）。

### 2.2 问题二：链路追踪缺失

#### 2.2.1 现状分析

gateway 存在 `TracingConfig`（`org.nexus.gateway.observability.TracingConfig`），实现：

- 入口 filter 生成 / 透传 `X-NexusChain-Trace-Id` + `X-NexusChain-Span-Id` header
- MDC put traceId / spanId（供结构化日志）
- 响应 header 回写 traceId / spanId

**缺失项**：

| 缺失项 | 影响 |
|--------|------|
| 无 Feign RequestInterceptor 传播 traceId | gateway → signing/wallet/bridge 调用不携带 traceId，下游服务日志无法关联 |
| signing-service / wallet-service / bridge 无 traceId 接收逻辑 | 下游服务各自生成新 traceId，跨服务日志断裂 |
| 无 Zipkin / Jaeger / OTel 后端 | 无法可视化调用链路、无 span 依赖图、无慢调用定位 |
| 无 Micrometer Tracing 依赖 | gateway build.gradle 仅有 `micrometer-registry-prometheus`（指标），无 `micrometer-tracing` + `zipkin-reporter-brave` |
| 注释声明"In production, replaced by Micrometer Tracing + Zipkin/Jaeger auto-instrumentation" | 未实施 |

#### 2.2.2 排障困难场景

表：跨服务排障困难场景

| 场景 | 现状排障流程 | 理想排障流程（Phase 3 后）|
|------|------------|----------------------|
| 退款失败：gateway refund 报错，signing-service 签名异常 | gateway 日志看 traceId=A，signing-service 日志看 traceId=B（无关联），需人工比对时间戳 + 参数猜关联 | Zipkin 查 traceId=A，一键看到 gateway → signing-service 完整 span 树，定位 signing-service span 的异常栈 |
| 提现失败：wallet-service 调 signing-service 超时 | wallet-service 日志看 Feign 超时，signing-service 日志看请求到达但处理慢，无法确认是否同一请求 | Zipkin 查 traceId，看到 wallet-service span（client）+ signing-service span（server），对比 duration 定位瓶颈 |
| 支付链路：gateway → signing-service → chain node | 三段日志各自独立，无串联 | Zipkin 一条 trace 串联三段 span，含每段耗时 + tag（订单号、金额、txHash）|

### 2.3 问题三：fallback 未绑定

#### 2.3.1 现状确认

表：3 个 Feign 接口的 @FeignClient 注解现状

| Feign 接口 | 所在模块 | @FeignClient 注解 | fallback 属性 | fallback 类 |
|-----------|---------|------------------|--------------|------------|
| `SigningServiceFeignClient` | nexus-sdk | `@FeignClient(name="nexus-signing-service", path="/api/v1", contextId="signingServiceFeignClient")` | **未指定** | `org.nexus.gateway.fallback.SigningServiceFallback`（@Component，已实现）|
| `WalletMgmtFeignClient` | nexus-sdk | `@FeignClient(name="nexus-wallet-service", path="/api/v1/wallet", contextId="walletMgmtFeignClient")` | **未指定** | `org.nexus.gateway.fallback.WalletMgmtFallback`（@Component，已实现）|
| `BridgeServiceFeignClient` | nexus-sdk | `@FeignClient(name="nexus-bridge", path="/api/v1/bridge", contextId="bridgeServiceFeignClient")` | **未指定** | `org.nexus.gateway.fallback.BridgeServiceFallback`（@Component，已实现）|

**CHANGELOG v1.4.0 明确记录**（第 25 行）：

> fallback 类保留 @Component 注解，不绑定 @FeignClient（编译通过，运行时降级在后续完善）

#### 2.3.2 根因分析

Feign 接口定义在 `nexus-sdk`（`org.nexus.sdk.client.feign` 包），fallback 类定义在 `nexus-gateway`（`org.nexus.gateway.fallback` 包）。**nexus-sdk 不依赖 nexus-gateway**（依赖方向是 gateway → sdk），因此无法在 nexus-sdk 的 `@FeignClient` 注解中直接引用 gateway 的 fallback 类（编译不通过）。

#### 2.3.3 运行时影响

- signing-service 不可用时，gateway 的 `SigningServiceFeignClient.signTransfer()` 调用**直接抛 FeignException**，不路由到 `SigningServiceFallback.signTransfer()`。
- `PaymentServiceImpl.refund()` 的 `executeRefundTransfer` 方法有 try-catch 兜底（返回 null），但 `SubscriptionServiceImpl.charge()` 的 `executeSubscriptionCharge` 也有 try-catch——**当前 try-catch 兜底掩盖了 fallback 未绑定问题**，但行为与 fallback 语义不一致（fallback 应由 Spring Cloud OpenFeign 框架路由，而非调用方 try-catch）。
- Sentinel 熔断 / 限流触发时，**不路由到 fallback 类**，而是抛 `BlockException`，调用方需额外处理。

### 2.4 问题四：运行时验证缺失

#### 2.4.1 现状

- Phase 1+2 验收标准包含"gateway 通过 Feign 调通 signing-service 端点"，但实际验收仅做了 `gradle build -x test` 编译验证（CHANGELOG 未记录端到端验证结果）。
- `docker-compose.yml` 仅包含 `nexus-gateway` / `nexus-core` / `nexus-bridge` / `nacos` / `sentinel-dashboard`，**缺少 signing-service / wallet-service 的 docker-compose 条目**。
- `docker-compose.yml` 第 17 行端口约定注释"gateway : 8080 / core : 19585 / bridge : 8082"——**与实际不符**（signing-service 8082 / wallet-service 8083 / bridge 8084）。
- 无集成测试脚本验证：Nacos 服务注册、Feign 跨服务调用、Sentinel 限流触发、fallback 降级触发。

#### 2.4.2 缺失的验证项

表：Phase 3 需补的运行时验证项

| 验证项 | 验证方式 | 优先级 |
|--------|---------|--------|
| 4 服务启动 + Nacos 注册 | docker-compose up + Nacos 控制台查 4 实例 | P0 |
| gateway → signing-service Feign 调通 | curl gateway 端点触发签名，查 signing-service 日志 | P0 |
| gateway → wallet-service Feign 调通 | curl gateway 端点触发地址转换 | P0 |
| gateway → bridge Feign 调通 | curl gateway 端点触发跨链查询 | P0 |
| wallet-service → signing-service Feign 调通 | curl wallet-service 提现端点 | P1 |
| Sentinel 限流触发 | jmeter 压测 signing-service 签名端点，查 Sentinel Dashboard | P1 |
| fallback 降级触发 | 停 signing-service，curl gateway 退款端点，查日志确认 fallback 生效 | P0 |
| Seata 全局事务回滚 | 模拟签名成功但本地事务异常，查 undo_log 回滚 | P0 |
| 链路追踪 traceId 串联 | curl gateway 端点，查 Zipkin UI 看 trace 跨服务 span | P1 |

### 2.5 问题五：测试覆盖

#### 2.5.1 现状

| 模块 | 测试文件数 | 跨服务集成测试 | 备注 |
|------|-----------|--------------|------|
| nexus-gateway | 多个（PaymentServiceTest / OrchestrationE2ETest / PaymentFlowIntegrationTest 等）| 无（Mock Feign 客户端，非真实跨服务）| 593 个测试（Phase 1+2 design.md 记）|
| nexus-signing-service | 2（TxControllerTest / ScratchGsonExperimentTest）| 无 | 仅单元测试 |
| nexus-wallet-service | 0 | 无 | **无任何测试** |
| nexus-bridge | 有（Phase 1+2 未详查）| 无 | — |

#### 2.5.2 Phase 3 测试补充需求

- **wallet-service 单元测试**：补 `DefaultWithdrawalApprovalService` / `DefaultCustodyService` / `DefaultAddressWhitelistService` 测试（Mock `SigningServiceFeignClient`）。
- **跨服务集成测试**：新增 `nexus-gateway/src/test/java/.../integration/MicroserviceIntegrationTest.java`，用 SpringBoot Test + Nacos embedded（或 Testcontainers Nacos）启动 4 服务，验证 Feign 调通。
- **Seata 事务回滚测试**：模拟签名成功但本地事务异常，验证 undo_log 回滚 + 全局事务回滚。
- **链路追踪测试**：验证 Feign 调用携带 traceId header + Zipkin 上报 span。

### 2.6 问题六：其他问题

#### 2.6.1 配置一致性

| 问题 | 位置 | 影响 |
|------|------|------|
| `transport-mode` 配置冲突 | `nexus-common.yaml`（Nacos 共享）：`transport-mode: ${NEX_TRANSPORT_MODE:http}`；`gateway application.yml`：`transport-mode: feign` | 共享配置与 gateway 私有配置语义冲突（http vs feign）|
| docker-compose 端口约定错误 | `docker-compose.yml` 第 17 行注释"bridge : 8082"，实际 bridge 8084 / signing-service 8082 | 误导开发 |
| docker-compose 缺 signing-service / wallet-service | `docker-compose.yml` 仅有 gateway / core / bridge | 无法 `docker-compose up` 全链路验证 |
| `application.yml` 注释过时 | gateway application.yml 第 89 行"fallback 类 Phase 2 #61 补全"——Phase 2 已完成但 fallback 仍未绑定 | 文档误导 |

#### 2.6.2 服务健康检查

- 4 服务均有 actuator health 端点（`management.endpoints.web.exposure.include: health`）。
- `docker-compose.yml` 仅 gateway / core / bridge 有 healthcheck，**signing-service / wallet-service 无 healthcheck**（因 docker-compose 缺这两个服务条目）。
- **无服务间健康检查**：gateway 无 SigningServiceHealthIndicator / WalletServiceHealthIndicator（主动探测下游服务健康状态，影响 LoadBalancer 路由）。

#### 2.6.3 优雅停机

- **4 服务均未配置优雅停机**：
  - 无 `server.shutdown: graceful`
  - 无 `spring.lifecycle.timeout-per-shutdown-phase`
- **影响**：服务重启 / 滚动更新时，正在处理的 Feign 请求被强制中断，可能导致签名操作执行一半（签名完成但广播未完成）。

#### 2.6.4 wallet-service 持久化缺失

- `DefaultWithdrawalApprovalService` 用 `ConcurrentHashMap` 存储提现请求（代码注释"请求存储为进程内内存表；生产环境需替换为持久化存储"）。
- **影响**：wallet-service 重启丢失所有提现请求状态，已 PENDING / APPROVED 的提现无法继续处理。
- **Phase 3 建议**：虽持久化本身可独立为 Phase 4 任务，但分布式事务设计需考虑 wallet-service 持久化后的跨服务事务边界。

## 第3章 交付物2：需求分析

### 3.1 分布式事务需求分析

#### 3.1.1 业务场景与一致性要求

表：需要分布式事务的业务场景与一致性要求

| 场景 | 涉及服务 | 操作序列 | 一致性要求 | 模式选择 |
|------|---------|---------|-----------|---------|
| 支付退款 | gateway + signing-service | ① gateway 本地：order → REFUNDED, refund → COMPLETED<br>② signing-service：签名 + 广播（不可逆链上交易）| 强一致：本地事务与签名操作要么都成功，要么都回滚 | **TCC**（签名操作不可逆，需 Try-Confirm-Cancel；Try 阶段预锁定 nonce，Confirm 阶段签名广播，Cancel 阶段释放 nonce）|
| 订阅扣款 | gateway + signing-service | ① gateway 本地：subscription chargedCount+1<br>② signing-service：签名 + 广播 | 强一致 | **TCC**（同退款）|
| 提现执行 | wallet-service + signing-service | ① wallet-service：withdrawal → EXECUTED<br>② signing-service：签名 + 广播 | 强一致 | **TCC**（持久化后）|

#### 3.1.2 TCC vs AT 模式选择

表：TCC 与 AT 模式对比

| 维度 | AT 模式 | TCC 模式 |
|------|---------|---------|
| 侵入性 | 低（仅需 `@GlobalTransactional` + undo_log 表）| 高（需自定义 Try/Confirm/Cancel 三个方法）|
| 适用场景 | 仅关系型数据库操作（有 undo_log 回滚）| 包含非数据库操作（链上交易、Nonce 池等不可逆操作）|
| 回滚机制 | 自动生成 undo_log，反向 SQL 回滚 | 业务自定义 Cancel 逻辑 |
| 性能 | 高（一阶段本地事务提交，二阶段异步回滚）| 中（三阶段均需网络调用）|
| **NexusChain 适用性** | **不适用**：签名 + 广播是链上不可逆操作，无 undo_log 可回滚（链上交易无法"反向 SQL"）| **适用**：Try 阶段预锁定 nonce + 预记录签名意图，Confirm 阶段签名广播，Cancel 阶段释放 nonce + 标记签名意图取消 |

**决策**：采用 **TCC 模式**。理由：

1. 签名 + 广播是链上不可逆操作，AT 模式的 undo_log 反向 SQL 无法回滚链上交易。
2. TCC 的 Try 阶段可预锁定 nonce（NoncePool 已在 signing-service），Confirm 阶段签名广播，Cancel 阶段释放 nonce——与现有 NoncePool 机制天然契合。
3. gateway 本地数据库操作（order/refund/subscription 表）用 AT 模式自动回滚，signing-service 的 nonce + 签名操作用 TCC 自定义 Cancel——**混合模式**：gateway 侧 AT（自动 undo_log），signing-service 侧 TCC（自定义 Cancel）。

#### 3.1.3 Seata Server 部署需求

| 需求项 | 说明 |
|--------|------|
| Seata Server 版本 | 2.0.0（与 SCA 2023.0.1.0 内置 seata-spring-boot-starter 版本对齐，支持 SpringBoot 3.x）|
| 部署模式 | 开发：docker-compose 单节点 + 内嵌存储；生产：集群 3 节点 + MySQL/Redis 持久化 |
| 注册中心 | 复用 Nacos 2.3.2（Seata Server 注册到 Nacos，Seata Client 通过 Nacos 发现 Seata Server）|
| 配置中心 | 复用 Nacos 2.3.2（Seata Server 配置存 Nacos，dataId = `seataServer.properties`）|
| 事务组 | `nexus-tx-group`（所有 NexusChain 微服务统一事务组）|
| 存储模式 | 开发：file；生产：db（MySQL，复用 Nacos 的 MySQL 或独立 MySQL）|

### 3.2 链路追踪需求分析

#### 3.2.1 技术选型对比

表：链路追踪技术选型对比

| 维度 | Skywalking + Java agent | Micrometer Tracing + Zipkin | OpenTelemetry + OTLP |
|------|------------------------|---------------------------|---------------------|
| 侵入性 | 零侵入（Java agent 字节码增强）| 低侵入（依赖 + 配置，SpringBoot 3.x 内置）| 低侵入（依赖 + 配置）|
| SpringBoot 3.2.x 集成 | 需额外挂 agent（部署复杂）| **原生集成**（spring-boot-starter-actuator 内含 micrometer-tracing）| 需 opentelemetry-spring-boot-starter |
| Feign 自动埋点 | agent 自动 | **自动**（micrometer-tracing-integration-testissue + feign-micrometer）| 自动 |
| 后端 | Skywalking OAP 自带 UI | Zipkin Server + UI | Zipkin / Jaeger / Tempo 任选 |
| SCA 兼容 | 独立于 SCA | **与 SCA 无冲突**（纯 SpringBoot 生态）| 与 SCA 无冲突 |
| 维护成本 | 中（agent 版本管理）| 低（SpringBoot BOM 管理）| 中（OTel SDK 版本独立）|
| **NexusChain 适用性** | 不推荐（部署复杂，与"最小侵入"原则冲突）| **推荐**（SpringBoot 3.2.x 原生集成，零 agent）| 备选（未来多语言生态扩展时考虑）|

**决策**：采用 **Micrometer Tracing + Zipkin**。理由：

1. SpringBoot 3.2.5 内置 Micrometer Tracing 1.2.5，仅需添加 `zipkin-reporter-brave` 依赖 + 配置 zipkin endpoint，零 agent。
2. 与 SCA 2023.0.1.0 无冲突（纯 SpringBoot 生态）。
3. Feign / RestTemplate / WebMVC 自动埋点（micrometer-tracing-integration-feign-micrometer）。
4. gateway 已有 `micrometer-registry-prometheus`，Zipkin 与 Prometheus 共用 Micrometer 体系，统一可观测。

#### 3.2.2 采样率与上报需求

| 需求项 | 开发环境 | 生产环境 |
|--------|---------|---------|
| 采样率 | 100%（全采样，便于排障）| 10%（按吞吐量调整，避免 Zipkin 存储压力）|
| 上报方式 | HTTP POST zipkin/api/v2/spans | HTTP POST zipkin/api/v2/spans（或 Kafka 异步上报）|
| 上报地址 | `http://localhost:9411`（docker-compose Zipkin）| `http://zipkin:9411`（k8s Service）|
| traceId 传播格式 | W3C Trace Context（`traceparent` header）| W3C Trace Context |
| baggage 传播 | 业务字段（orderId / refundNo / subscriptionNo）放入 baggage，跨服务透传 | 同左 |

### 3.3 fallback 绑定需求分析

#### 3.3.1 约束条件

- **不改 nexus-sdk**：Feign 接口定义在 `nexus-sdk/java/src/main/java/org/nexus/sdk/client/feign/`，nexus-sdk 是被多服务依赖的底层模块，不宜引入 gateway 特定的 fallback 类。
- **fallback 类已在 gateway**：`SigningServiceFallback` / `WalletMgmtFallback` / `BridgeServiceFallback` 均在 `nexus-gateway/src/main/java/org/nexus/gateway/fallback/`，有 @Component 注解，实现对应 Feign 接口。
- **需支持 FallbackFactory**：未来需在 fallback 中获取触发降级的异常（如 `BlockException` / `FeignException`），`FallbackFactory<T>` 比 `fallback = T.class` 更灵活。

#### 3.3.2 方案对比

表：fallback 绑定方案对比

| 方案 | 描述 | 优点 | 缺点 | 评估 |
|------|------|------|------|------|
| A. nexus-sdk @FeignClient 直接指定 fallback | 在 nexus-sdk 的 @FeignClient 注解加 `fallback = SigningServiceFallback.class` | 简单直接 | nexus-sdk 需依赖 gateway 的 fallback 类（依赖方向反转，编译不通过）| **不可行** |
| B. fallback 类移到 nexus-sdk | 把 3 个 fallback 类从 gateway 移到 nexus-sdk | @FeignClient 可直接指定 | fallback 类依赖 gateway 特定逻辑（如告警上报），移到 sdk 不合理；且 wallet-service 也用 SigningServiceFeignClient，其 fallback 语义与 gateway 不同 | **不推荐** |
| C. gateway 用 @FeignClient 重定义 | 在 gateway 重新定义 3 个 @FeignClient 接口，指定 fallback，覆盖 nexus-sdk 的定义 | 不改 nexus-sdk | 重复定义接口，维护两份契约；需 @Primary 消歧义 | **不推荐** |
| D. FeignClientBuilder 动态创建 | 用 `FeignClientBuilder.create().fallback(...).build()` 动态创建 Feign 代理 | 灵活 | 复杂，失去 @FeignClient 声明式优势 | **不推荐** |
| E. application.yml 配置 fallback | `spring.cloud.openfeign.client.config.{service}.fallback-class` 配置 fallback 类名 | 不改代码，配置驱动 | Spring Cloud OpenFeign 4.x **不支持**此配置项（无 fallback-class 配置）| **不可行** |
| F. @FeignClient fallbackFactory + gateway 配置类 | 在 gateway 新建 Feign 配置类，用 `FeignClientFactoryBean` 设置 fallbackFactory | 不改 nexus-sdk | 实现复杂 | 备选 |
| G. **nexus-sdk @FeignClient 用 fallbackFactory 占位 + gateway 提供 Bean** | nexus-sdk 的 @FeignClient 指定 `fallbackFactory = SigningServiceFallbackFactory.class`，fallbackFactory 接口在 nexus-sdk 定义，实现类在 gateway | 不改 nexus-sdk 接口签名，fallback 实现可按服务定制 | 需在 nexus-sdk 定义 3 个 FallbackFactory 接口 | **推荐** |
| H. **@FeignClient 不指定 fallback + gateway 用 @Primary Bean 覆盖** | nexus-sdk @FeignClient 不指定 fallback；gateway 的 fallback 类用 @Component + @Primary 注册为 Feign 接口的实现 Bean，当 Feign 调用失败时 Spring Cloud OpenFeign 自动用 @Primary fallback Bean | 不改 nexus-sdk，最简单 | 需验证 Spring Cloud OpenFeign 4.1.0 是否支持此模式 | **需验证** |

**决策**：采用 **方案 G**（fallbackFactory 占位 + gateway 提供实现）。理由：

1. 不改 nexus-sdk 的 Feign 接口方法签名（仅加 fallbackFactory 属性，指向 nexus-sdk 内定义的 FallbackFactory 接口）。
2. fallbackFactory 比 fallback 更灵活：可在 fallback 方法中获取触发异常（`cause`），区分限流 / 熔断 / 服务不可用，分别处理。
3. gateway / wallet-service 可各自提供 fallbackFactory 实现（gateway 的 SigningServiceFallback 与 wallet-service 的 SigningServiceFallback 语义不同：gateway 侧返回 null 标记支付失败，wallet-service 侧返回 null 标记提现失败）。

### 3.4 运行时验证需求分析

#### 3.4.1 验证环境需求

| 需求项 | 说明 |
|--------|------|
| docker-compose 全链路 | 补 signing-service / wallet-service / bridge / Seata Server / Zipkin 的 docker-compose 条目 |
| 端口约定统一 | gateway 8080 / signing 8082 / wallet 8083 / bridge 8084 / Nacos 8848 / Sentinel 8858 / Seata 8091 / Zipkin 9411 |
| 集成测试脚本 | 一键启动 + 健康检查 + 端到端调用验证 + 清理 |
| 验证报告 | 每项验证项输出 pass/fail + 证据（日志 / 截图 / curl 响应）|

#### 3.4.2 验证流程需求

图：Phase 3 运行时验证流程

```
1. docker-compose up（Nacos + Seata + Zipkin + 4 服务）
2. 等待健康检查通过（healthcheck）
3. Nacos 控制台验证 4 服务注册
4. curl gateway 退款端点 → 验证 gateway → wallet-service → signing-service 链路
5. 模拟 signing-service 不可用（docker stop）→ 验证 fallback 触发
6. 模拟签名成功但本地事务异常 → 验证 Seata 全局事务回滚
7. Zipkin UI 查 trace → 验证跨服务 traceId 串联
8. jmeter 压测 → 验证 Sentinel 限流触发
9. docker-compose down（清理）
```

## 第4章 交付物3：设计方案

### 4.1 版本对齐矩阵

表：Phase 3 版本矩阵（SpringBoot 3.2.5 + SCA 2023.0.1.0 兼容）

| 组件 | 版本 | 来源 | 兼容性说明 |
|------|------|------|-----------|
| Spring Boot | 3.2.5 | 已定 | — |
| Spring Cloud | 2023.0.3 | 已定 | — |
| Spring Cloud Alibaba | 2023.0.1.0 | 已定 | — |
| **Seata Server** | **2.0.0** | seata.io | Seata 2.0.0 支持 SpringBoot 3.x + Spring Cloud 2023.0.x；SCA 2023.0.1.0 内置 seata-spring-boot-starter 2.0.0 |
| **Seata Spring Boot Starter** | **2.0.0** | SCA 2023.0.1.0 BOM 管理 | `io.seata:seata-spring-boot-starter:2.0.0` |
| **Micrometer Tracing** | **1.2.5** | SpringBoot 3.2.5 BOM 管理 | `io.micrometer:micrometer-tracing-bridge-brave` |
| **Zipkin Reporter Brave** | **2.16.4** | SpringBoot 3.2.5 BOM 管理 | `io.zipkin.reporter2:zipkin-reporter-brave` |
| **Zipkin Server** | **3.4** | docker `openzipkin/zipkin:3.4` | 独立部署，MySQL/Elasticsearch 持久化可选 |
| Nacos | 2.3.2 | 已定 | Seata Server 复用 Nacos 作注册/配置中心 |
| Sentinel | 1.8.8 | 已定 | — |
| OpenFeign | 4.1.0 | 已定 | micrometer-tracing 自动埋点 Feign |

**关键风险**：Seata 2.0.0 与 SCA 2023.0.1.0 的 seata-spring-boot-starter 版本需实测确认（SCA BOM 声明版本可能与 Seata 官方最新版有差异）。**建议 T1 任务先做版本 POC**。

### 4.2 Seata 分布式事务接入方案

#### 4.2.1 Seata Server 部署

代码示例：docker-compose Seata Server 片段（YAML）

```yaml
seata-server:
  image: seataio/seata-server:2.0.0
  container_name: nexus-seata-server
  ports:
    - '8091:8091'    # Seata Server TC 端口
    - '7091:7091'    # Seata Server Web 控制台
  environment:
    - SEATA_PORT=8091
    - STORE_MODE=file
    - SEATA_CONFIG_NAME=file:/root/seata-config/registry
    # 注册到 Nacos（复用 nexus-nacos）
    - SEATA_REGISTRY_NAME=nacos
    - SEATA_REGISTRY_NACOS_SERVER_ADDR=nexus-nacos:8848
    - SEATA_REGISTRY_NACOS_NAMESPACE=dev
    - SEATA_REGISTRY_NACOS_GROUP=SEATA_GROUP
    - SEATA_REGISTRY_NACOS_CLUSTER_NAME=default
    # 配置中心指向 Nacos
    - SEATA_CONFIG_NAME=nacos
    - SEATA_CONFIG_NACOS_SERVER_ADDR=nexus-nacos:8848
    - SEATA_CONFIG_NACOS_NAMESPACE=dev
    - SEATA_CONFIG_NACOS_GROUP=SEATA_GROUP
  depends_on:
    nacos:
      condition: service_healthy
  healthcheck:
    test: ['CMD', 'curl', '-f', 'http://localhost:7091/health']
    interval: 10s
    timeout: 5s
    retries: 10
    start_period: 30s
  restart: unless-stopped
```

#### 4.2.2 Seata Client 接入（gateway + signing-service + wallet-service）

代码示例：gateway build.gradle Seata 依赖片段（Gradle）

```gradle
dependencies {
    // === Seata 分布式事务（Phase 3） ===
    // SCA 2023.0.1.0 BOM 管理 seata-spring-boot-starter 版本（2.0.0）
    implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-seata'
    // 或直接声明（若 SCA BOM 未管理）：
    // implementation 'io.seata:seata-spring-boot-starter:2.0.0'
}
```

代码示例：gateway application.yml Seata 配置（YAML）

```yaml
seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: nexus-tx-group
  service:
    vgroup-mapping:
      nexus-tx-group: default
    grouplist:
      default: ${NEX_SEATA_SERVER:localhost:8091}
  registry:
    type: nacos
    nacos:
      server-addr: ${NEX_NACOS_SERVER:localhost:8848}
      namespace: ${NEX_NACOS_NAMESPACE:dev}
      group: SEATA_GROUP
  config:
    type: nacos
    nacos:
      server-addr: ${NEX_NACOS_SERVER:localhost:8848}
      namespace: ${NEX_NACOS_NAMESPACE:dev}
      group: SEATA_GROUP
  data-source-proxy-mode: AT    # gateway 本地数据库用 AT 模式（自动 undo_log）
  client:
    rm:
      report-success-enable: true
    tm:
      commit-retry-count: 3
      rollback-retry-count: 3
      default-global-transaction-timeout: 60000
```

#### 4.2.3 @GlobalTransactional 标注

代码示例：PaymentServiceImpl.refund 改造（Java）

```java
import io.seata.spring.annotation.GlobalTransactional;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Override
    @GlobalTransactional(name = "refund-tx", timeoutMills = 60000, rollbackFor = Exception.class)
    @Transactional  // 本地事务（AT 模式，自动 undo_log）
    public Refund refund(Long orderId, BigDecimal amount, String reason) {
        // ... 原有逻辑 ...
        // walletMgmtClient.addressToPubkeyHash() → Feign 调 wallet-service（TCC Try）
        // signingServiceClient.signTransfer()    → Feign 调 signing-service（TCC Try + Confirm）
        // orderRepository.save(order)            → 本地 AT（自动 undo_log）
        // refundRepository.save(refund)          → 本地 AT（自动 undo_log）
    }
}
```

#### 4.2.4 TCC 模式实现（signing-service 侧）

代码示例：SigningTccAction 接口定义（Java）

```java
package org.nexus.signing.tcc;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TccAction;
import io.seata.rm.tcc.api.TccActionMethod;

import java.math.BigDecimal;

/**
 * 签名服务 TCC 接口（TwoPhaseBusinessAction）。
 *
 * <p>Try：预锁定 nonce + 预记录签名意图（不广播）。
 * Confirm：签名 + 广播（使用 Try 锁定的 nonce）。
 * Cancel：释放 nonce + 标记签名意图取消。</p>
 */
@LocalTCC
public interface SigningTccAction {

    @TccActionMethod(
        name = "prepareSignTransfer",
        commitMethod = "confirmSignTransfer",
        rollbackMethod = "cancelSignTransfer"
    )
    boolean prepareSignTransfer(BusinessActionContext actionContext,
                                String fromPubkey,
                                String toPubkeyHash,
                                BigDecimal amount);

    boolean confirmSignTransfer(BusinessActionContext actionContext);

    boolean cancelSignTransfer(BusinessActionContext actionContext);
}
```

#### 4.2.5 undo_log 表（AT 模式，gateway 侧）

SQL：gateway 数据库 undo_log 建表

```sql
-- Seata AT 模式 undo_log 表（每个参与全局事务的数据库均需创建）
CREATE TABLE IF NOT EXISTS `undo_log` (
    `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
    `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo log context, such as serialization',
    `rollback_info` LONGBLOB     NOT NULL COMMENT 'undo log data',
    `log_status`    INT(11)      NOT NULL COMMENT '0:normal status,1:defense status',
    `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';
```

### 4.3 链路追踪方案

#### 4.3.1 依赖添加

代码示例：gateway build.gradle 链路追踪依赖（Gradle）

```gradle
dependencies {
    // === Micrometer Tracing + Zipkin（Phase 3） ===
    // SpringBoot 3.2.5 BOM 管理版本（micrometer-tracing 1.2.5 + zipkin-reporter-brave 2.16.4）
    implementation 'org.springframework.boot:spring-boot-starter-actuator'  // 已有
    implementation 'io.micrometer:micrometer-tracing-bridge-brave'          // Brave 桥接
    implementation 'io.zipkin.reporter2:zipkin-reporter-brave'              // Zipkin 上报
    // Feign 自动埋点（micrometer-tracing 已含 feign-micrometer 集成）
}
```

signing-service / wallet-service / bridge 依赖同构。

#### 4.3.2 配置

代码示例：gateway application.yml 链路追踪配置（YAML）

```yaml
management:
  tracing:
    sampling:
      probability: ${NEX_TRACE_SAMPLING:1.0}   # 开发 100% 采样，生产 0.1
    propagation:
      type: w3c                                  # W3C Trace Context（traceparent header）
  zipkin:
    tracing:
      endpoint: ${NEX_ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}
      connect-timeout: 5s
      read-timeout: 10s

logging:
  pattern:
    level: "%5p [traceId=%mdc{traceId:-},spanId=%mdc{spanId:-}]"  # 日志注入 traceId/spanId
```

#### 4.3.3 TracingConfig 改造

gateway 现有 `TracingConfig`（手动 X-NexusChain-Trace-Id）改为**删除**（Micrometer Tracing 自动接管，用 W3C `traceparent` header）。

代码示例：TracingConfig 改造后（Java）

```java
package org.nexus.gateway.observability;

import org.springframework.context.annotation.Configuration;

/**
 * 链路追踪配置（Phase 3 改造）。
 *
 * <p>Phase 1+2 的手动 X-NexusChain-Trace-Id filter 已删除，
 * 改由 Micrometer Tracing + Brave 自动埋点：
 * <ul>
 *   <li>入口：HttpServerTracingHandler 自动生成 / 透传 traceparent header（W3C）</li>
 *   <li>Feign 调用：FeignClientTracingHandler 自动注入 traceparent header</li>
 *   <li>日志：logging.pattern.level 注入 traceId/spanId 到 MDC</li>
 *   <li>上报：ZipkinReporter 异步上报 span 到 Zipkin Server</li>
 * </ul></p>
 *
 * <p>本类保留为占位，未来如需自定义 baggage 传播（orderId 等）在此扩展。</p>
 */
@Configuration
public class TracingConfig {
    // Micrometer Tracing 自动配置，无需手动 Bean。
    // 未来如需自定义 baggage：
    // @Bean
    // public BaggageManager baggageManager() { ... }
}
```

#### 4.3.4 Zipkin Server 部署

代码示例：docker-compose Zipkin 片段（YAML）

```yaml
zipkin:
  image: openzipkin/zipkin:3.4
  container_name: nexus-zipkin
  ports:
    - '9411:9411'    # Zipkin API + UI
  environment:
    - STORAGE_TYPE=mem   # 开发：内存存储；生产：mysql 或 elasticsearch
    # 生产 MySQL 持久化：
    # - STORAGE_TYPE=mysql
    # - MYSQL_HOST=zipkin-mysql
    # - MYSQL_USER=zipkin
    # - MYSQL_PASS=zipkin
  healthcheck:
    test: ['CMD', 'curl', '-f', 'http://localhost:9411/health']
    interval: 10s
    timeout: 5s
    retries: 10
    start_period: 20s
  restart: unless-stopped
```

### 4.4 fallback 绑定方案

#### 4.4.1 方案 G 实现：nexus-sdk 定义 FallbackFactory 占位

代码示例：nexus-sdk SigningServiceFallbackFactory 占位接口（Java）

```java
package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * SigningServiceFeignClient 的 FallbackFactory 占位接口。
 *
 * <p>本接口在 nexus-sdk 定义，实现类由各消费方模块提供：
 * <ul>
 *   <li>gateway：org.nexus.gateway.fallback.SigningServiceFallbackFactory（退款/支付降级）</li>
 *   <li>wallet-service：org.nexus.walletsvc.fallback.SigningServiceFallbackFactory（提现降级）</li>
 * </ul></p>
 */
public interface SigningServiceFallbackFactory extends FallbackFactory<SigningServiceFeignClient> {
}
```

代码示例：nexus-sdk SigningServiceFeignClient 改造（Java）

```java
@FeignClient(
        name = "nexus-signing-service",
        path = "/api/v1",
        contextId = "signingServiceFeignClient",
        fallbackFactory = SigningServiceFallbackFactory.class  // Phase 3 绑定
)
public interface SigningServiceFeignClient {
    // ... 方法不变 ...
}
```

#### 4.4.2 gateway 提供 FallbackFactory 实现

代码示例：gateway SigningServiceFallbackFactory 实现（Java）

```java
package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * gateway 侧 SigningServiceFeignClient 的 FallbackFactory 实现。
 *
 * <p>复用现有 SigningServiceFallback 的降级逻辑，包装为 FallbackFactory
 * 以获取触发降级的异常（cause），区分限流/熔断/服务不可用。</p>
 */
@Component
public class GatewaySigningServiceFallbackFactory implements SigningServiceFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(GatewaySigningServiceFallbackFactory.class);

    @Override
    public SigningServiceFeignClient create(Throwable cause) {
        log.error("SigningServiceFeignClient 降级触发, cause={}", cause.getClass().getSimpleName(), cause);
        return new SigningServiceFallback();  // 复用现有 fallback 类
    }
}
```

#### 4.4.3 wallet-service 提供 FallbackFactory 实现

代码示例：wallet-service SigningServiceFallbackFactory 实现（Java）

```java
package org.nexus.walletsvc.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * wallet-service 侧 SigningServiceFeignClient 的 FallbackFactory 实现。
 *
 * <p>降级语义与 gateway 不同：提现执行失败需标记 PENDING_RETRY，
 * 而非直接返回 null（gateway 侧退款返回 null 标记 FAILED）。</p>
 */
@Component
public class WalletSvcSigningServiceFallbackFactory implements SigningServiceFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(WalletSvcSigningServiceFallbackFactory.class);

    @Override
    public SigningServiceFeignClient create(Throwable cause) {
        log.error("wallet-service → signing-service 降级触发, cause={}", cause.getClass().getSimpleName(), cause);
        return new SigningServiceFeignClient() {
            @Override
            public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
                log.error("提现签名降级: signing-service 不可用, to={}, amount={}", toPubkeyHash, amount);
                return null;  // DefaultWithdrawalApprovalService 收到 null 标记 FAILED
            }
            @Override
            public String transfer(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey) {
                return null;
            }
            @Override
            public boolean canSignViaMpc(BigDecimal amount) {
                return false;
            }
            @Override
            public Object getNoncePool(String address) {
                return null;
            }
        };
    }
}
```

#### 4.4.4 WalletMgmt / Bridge FallbackFactory 同构

`WalletMgmtFallbackFactory` / `BridgeServiceFallbackFactory` 按相同模式定义（nexus-sdk 占位接口 + gateway 实现），此处略。

### 4.5 运行时验证方案

#### 4.5.1 docker-compose 全链路补全

代码示例：docker-compose.yml 补全 signing-service / wallet-service / Seata / Zipkin（YAML）

```yaml
services:
  # ... 保留 nacos / sentinel-dashboard / nexus-gateway / nexus-core ...

  nexus-signing-service:
    build: ./nexus-signing-service
    ports:
      - '127.0.0.1:8082:8082'
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - NEX_NACOS_SERVER=nexus-nacos:8848
      - NEX_SEATA_SERVER=nexus-seata-server:8091
      - NEX_ZIPKIN_ENDPOINT=http://nexus-zipkin:9411/api/v2/spans
    depends_on:
      nacos:
        condition: service_healthy
      nexus-seata-server:
        condition: service_healthy
    healthcheck:
      test: ['CMD', 'curl', '-f', 'http://localhost:8082/actuator/health']
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  nexus-wallet-service:
    build: ./nexus-wallet-service
    ports:
      - '127.0.0.1:8083:8083'
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - NEX_NACOS_SERVER=nexus-nacos:8848
      - NEX_SEATA_SERVER=nexus-seata-server:8091
      - NEX_ZIPKIN_ENDPOINT=http://nexus-zipkin:9411/api/v2/spans
    depends_on:
      nacos:
        condition: service_healthy
      nexus-seata-server:
        condition: service_healthy
    healthcheck:
      test: ['CMD', 'curl', '-f', 'http://localhost:8083/actuator/health']
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  # nexus-bridge 端口修正为 8084
  nexus-bridge:
    build: ./nexus-bridge
    ports:
      - '127.0.0.1:8084:8084'   # 修正：原 8082 → 8084
    # ... 其余不变 ...

  # seata-server / zipkin 见 §4.2.1 / §4.3.4
```

#### 4.5.2 集成测试脚本

代码示例：Phase 3 集成验证脚本（Bash）

```bash
#!/bin/bash
# phase3-integration-verify.sh
# Phase 3 运行时集成验证脚本

set -e

echo "=== Phase 3 集成验证开始 ==="

# 1. 启动全链路
echo "[1/9] docker-compose up..."
docker-compose up -d nacos sentinel-dashboard seata-server zipkin
sleep 30  # 等待基础设施就绪
docker-compose up -d nexus-signing-service nexus-wallet-service nexus-bridge nexus-gateway
sleep 60  # 等待服务注册

# 2. 健康检查
echo "[2/9] 健康检查..."
for svc in gateway:8080 signing:8082 wallet:8083 bridge:8084; do
  name=${svc%:*}; port=${svc#*:}
  curl -sf http://localhost:$port/actuator/health | jq -e '.status == "UP"' || { echo "FAIL: $name health"; exit 1; }
done

# 3. Nacos 注册验证
echo "[3/9] Nacos 服务注册验证..."
for svc in nexus-gateway nexus-signing-service nexus-wallet-service nexus-bridge; do
  count=$(curl -sf "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=$svc" | jq '.instances | length')
  [ "$count" -ge 1 ] || { echo "FAIL: $svc not registered"; exit 1; }
done

# 4. Feign 跨服务调用验证
echo "[4/9] gateway → signing-service Feign 调用..."
# 触发退款端点（需先创建订单 + 支付）
# curl -X POST http://localhost:8080/api/v1/payments/{orderId}/refund ...

# 5. fallback 降级验证
echo "[5/9] fallback 降级验证..."
docker-compose stop nexus-signing-service
sleep 10
# curl 退款端点，查 gateway 日志确认 "SigningServiceFeignClient 降级触发"
docker-compose start nexus-signing-service
sleep 30

# 6. Seata 全局事务回滚验证
echo "[6/9] Seata 全局事务回滚验证..."
# 模拟签名成功但本地事务异常，查 gateway 日志 "Global transaction rollback"
# 查 signing-service 日志 "cancelSignTransfer"（TCC Cancel）

# 7. Zipkin 链路追踪验证
echo "[7/9] Zipkin traceId 串联验证..."
# curl http://localhost:9411/api/v2/traces?serviceName=nexus-gateway
# 验证 trace 含 gateway + signing-service 两个 span

# 8. Sentinel 限流验证
echo "[8/9] Sentinel 限流验证..."
# ab -n 200 -c 50 http://localhost:8082/api/v1/transfers/sign
# curl Sentinel Dashboard API 查限流事件

# 9. 清理
echo "[9/9] docker-compose down..."
docker-compose down

echo "=== Phase 3 集成验证通过 ==="
```

### 4.6 配置一致性修复 + 优雅停机

#### 4.6.1 配置一致性修复

| 修复项 | 动作 |
|--------|------|
| `transport-mode` 冲突 | `nexus-common.yaml` 删除 `transport-mode`（由各服务私有配置决定：gateway=feign，signing/wallet/bridge 不需要此配置）|
| docker-compose 端口约定 | 注释修正为"gateway 8080 / signing 8082 / wallet 8083 / bridge 8084 / core 19585"|
| gateway application.yml 过时注释 | 第 89 行注释改为"fallback 类 Phase 3 绑定（fallbackFactory 模式）"|

#### 4.6.2 优雅停机配置

代码示例：4 服务统一优雅停机配置（YAML）

```yaml
server:
  shutdown: graceful   # 优雅停机：等待在途请求处理完成
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # 优雅停机等待时长（签名+广播允许 30s）
```

#### 4.6.3 服务健康检查增强

代码示例：gateway SigningServiceHealthIndicator（Java）

```java
package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * signing-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 signing-service 状态，
 * 影响LoadBalancer 路由（不健康实例自动摘除）。</p>
 */
@Component("signingServiceHealth")
public class SigningServiceHealthIndicator implements HealthIndicator {

    private final SigningServiceFeignClient signingServiceClient;

    public SigningServiceHealthIndicator(SigningServiceFeignClient signingServiceClient) {
        this.signingServiceClient = signingServiceClient;
    }

    @Override
    public Health health() {
        try {
            // 用 canSignViaMpc 轻量探测（不触发签名）
            signingServiceClient.canSignViaMpc(BigDecimal.ONE);
            return Health.up().withDetail("service", "nexus-signing-service").build();
        } catch (Exception e) {
            return Health.down().withDetail("service", "nexus-signing-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
```

## 第5章 交付物4：任务拆分建议

### 5.1 任务拆分原则

- 每个任务 **2-4 小时**粒度。
- 任务间依赖关系明确（`blocked_by`）。
- 优先级：fallback 绑定（最快见效）> 链路追踪 > Seata > 运行时验证。
- 每个任务有明确验收标准。

### 5.2 任务清单

表：Phase 3 任务拆分（共 14 个任务）

| ID | 任务 | 粒度 | 优先级 | blocked_by | target_files |
|----|------|------|--------|-----------|-------------|
| T1 | Seata 版本兼容 POC：空 SpringBoot 3.2.5 + SCA 2023.0.1.0 + seata-spring-boot-starter 2.0.0 启动验证 + Seata Server 2.0.0 docker-compose 单节点 | 2h | P0 | — | docker-compose.yml |
| T2 | Seata Server docker-compose 部署 + Nacos 注册/配置 + nexus-tx-group 事务组配置 | 2h | P0 | T1 | docker-compose.yml, nacos-config/ |
| T3 | gateway + signing-service + wallet-service build.gradle 添加 Seata 依赖 + application.yml Seata 配置 | 2h | P0 | T1 | nexus-gateway/build.gradle, nexus-signing-service/build.gradle, nexus-wallet-service/build.gradle |
| T4 | gateway 数据库 undo_log 表 Flyway migration + @GlobalTransactional 标注 PaymentServiceImpl.refund + SubscriptionServiceImpl.charge | 3h | P0 | T3 | nexus-gateway/src/main/resources/db/migration/, nexus-gateway/src/main/java/org/nexus/gateway/service/ |
| T5 | signing-service TCC 接口实现：SigningTccAction（Try 预锁定 nonce + Confirm 签名广播 + Cancel 释放 nonce）+ NoncePool 改造支持预锁定 | 4h | P0 | T3 | nexus-signing-service/src/main/java/org/nexus/signing/tcc/, nexus-signing-service/src/main/java/org/nexus/signing/pool/ |
| T6 | nexus-sdk FallbackFactory 占位接口定义（3 个）+ @FeignClient fallbackFactory 属性绑定 | 2h | P0 | — | nexus-sdk/java/src/main/java/org/nexus/sdk/client/feign/ |
| T7 | gateway FallbackFactory 实现类（3 个，复用现有 fallback 类）+ 删除现有 @Component fallback 的冗余注解 | 2h | P0 | T6 | nexus-gateway/src/main/java/org/nexus/gateway/fallback/ |
| T8 | wallet-service FallbackFactory 实现类（SigningService 1 个，提现降级语义） | 1h | P1 | T6 | nexus-wallet-service/src/main/java/org/nexus/walletsvc/fallback/ |
| T9 | Micrometer Tracing + Zipkin 依赖添加（4 服务）+ application.yml tracing/zipkin 配置 + TracingConfig 改造（删除手动 filter） | 3h | P1 | — | nexus-gateway/build.gradle, nexus-signing-service/build.gradle, nexus-wallet-service/build.gradle, nexus-bridge/build.gradle |
| T10 | Zipkin Server docker-compose 部署 + 跨服务 traceId 串联验证 | 1h | P1 | T9 | docker-compose.yml |
| T11 | docker-compose.yml 补全 signing-service / wallet-service 条目 + bridge 端口修正 8084 + 优雅停机配置（4 服务）+ 配置一致性修复 | 2h | P0 | T2, T10 | docker-compose.yml, nexus-*/src/main/resources/application.yml |
| T12 | gateway SigningServiceHealthIndicator + WalletServiceHealthIndicator 健康检查增强 | 2h | P1 | T7 | nexus-gateway/src/main/java/org/nexus/gateway/health/ |
| T13 | wallet-service 单元测试补充（DefaultWithdrawalApprovalService / DefaultCustodyService / DefaultAddressWhitelistService，Mock Feign） | 3h | P1 | T8 | nexus-wallet-service/src/test/java/ |
| T14 | Phase 3 集成验证脚本 + 端到端验证执行（9 项验证项）+ 验收报告 | 4h | P0 | T4, T5, T7, T10, T11, T12 | scripts/phase3-integration-verify.sh |

**任务总数**：14 个
**预估总工时**：约 33 小时（Seata 13h + fallback 5h + 链路追踪 4h + 配置/健康 4h + 测试 3h + 验证 4h）

### 5.3 任务依赖图

图：Phase 3 任务依赖关系

```
T1 (Seata POC) ──→ T2 (Seata Server) ──┬──→ T11 (docker-compose 补全) ──→ T14 (集成验证)
                                        └──→ T3 (Seata Client 依赖) ──┬──→ T4 (gateway @GlobalTransactional) ──→ T14
                                                                      └──→ T5 (signing TCC) ──→ T14

T6 (sdk FallbackFactory) ──┬──→ T7 (gateway FallbackFactory) ──→ T12 (健康检查) ──→ T14
                           └──→ T8 (wallet FallbackFactory) ──→ T13 (wallet 测试)

T9 (Tracing 依赖) ──→ T10 (Zipkin 部署) ──→ T11 ──→ T14
```

### 5.4 关键路径

**关键路径**：T1 → T2 → T3 → T5 → T14（Seata 全链路，约 14h）
**次关键路径**：T6 → T7 → T12 → T14（fallback + 健康检查，约 8h）

## 第6章 交付物5：风险点与回滚方案

### 6.1 风险点

表：Phase 3 风险点清单

| # | 风险 | 影响 | 概率 | 缓解措施 |
|---|------|------|------|---------|
| R1 | Seata 2.0.0 与 SCA 2023.0.1.0 的 seata-spring-boot-starter 版本不兼容 | 编译失败 / 启动失败 / 全局事务不生效 | 中 | T1 先做版本 POC：空项目启动 + 全局事务回滚验证；若不兼容则降级 Seata 1.8.0 + SpringBoot 2.x 兼容方案（不推荐，破坏版本统一）|
| R2 | Seata TCC 模式 Try 阶段预锁定 nonce 与现有 NoncePool 机制冲突 | Nonce 重复 / 丢失 | 中 | T5 时 NoncePool 改造需保持向后兼容：TCC Try 预锁定标记，非 TCC 路径走原逻辑；增加 NoncePool 并发测试 |
| R3 | Seata 全局事务超时（60s）与签名 + 广播耗时（可能 > 30s）不匹配 | 全局事务超时回滚但签名已上链 | 中 | T4 时 `@GlobalTransactional(timeoutMills=120000)` 允许 120s；监控签名广播 P99 耗时，动态调整 |
| R4 | Micrometer Tracing 采样率 100% 在生产环境导致 Zipkin 存储压力 | Zipkin OOM / 查询慢 | 低 | 生产环境采样率 10%（`management.tracing.sampling.probability=0.1`）；Zipkin 用 Elasticsearch 持久化 + TTL 自动清理 |
| R5 | fallback 绑定后 Spring Cloud OpenFeign 4.1.0 fallbackFactory 行为与预期不符 | 降级不触发 / 触发但异常 | 低 | T7 时单元测试验证：Mock Feign 抛异常，确认 fallbackFactory.create(cause) 被调用 |
| R6 | Seata Server 单节点宕机导致全局事务无法提交/回滚 | 事务悬挂 | 中 | 生产环境 Seata Server 集群 3 节点 + Nacos 注册；开发环境单节点可接受 |
| R7 | 链路追踪 traceId 与现有 X-NexusChain-Trace-Id header 不兼容 | 依赖 X-NexusChain-Trace-Id 的下游系统（如有）断裂 | 低 | grep 确认无外部系统依赖 X-NexusChain-Trace-Id（nexus-consortium / nexus-core 不依赖）；W3C traceparent 是标准格式 |
| R8 | docker-compose 全链路启动内存不足（4 服务 + Nacos + Sentinel + Seata + Zipkin） | 容器 OOM / 启动失败 | 中 | 开发环境限制 JVM 内存（-Xmx512m 各服务）；生产环境 k8s 资源限制 |
| R9 | wallet-service 持久化缺失（ConcurrentHashMap）与 Seata 事务不兼容 | 提现执行的全局事务在 wallet-service 侧无法回滚（内存操作无 undo_log） | 高 | Phase 3 仅对 gateway 侧 refund/charge 接入 Seata（gateway 本地数据库有 undo_log）；wallet-service 持久化 + Seata 接入留待 Phase 4 |
| R10 | Seata AT 模式 undo_log 表与 gateway Flyway migration 冲突 | Flyway 校验失败 | 低 | T4 时 undo_log 建表作为 Flyway migration 脚本（V{next}__add_undo_log.sql），与现有 migration 顺序一致 |

### 6.2 回滚方案

**总体回滚策略**：每项增强通过配置开关控制，可快速回退到 Phase 2 状态。

| 回滚场景 | 动作 |
|---------|------|
| Seata 全局事务引入后退款/扣款失败率飙升 | ① `seata.enabled=false` 关闭 Seata<br>② 移除 `@GlobalTransactional` 注解（或用 `@GlobalTransactional(enabled=false)` 条件关闭）<br>③ 回退到 Phase 2 的 try-catch 兜底模式 |
| Seata Server 不可用 | ① Seata Client 自动降级为本地事务（Seata 默认行为：TC 不可用时全局事务失败，本地事务仍可提交）<br>② 紧急时 `seata.enabled=false` |
| 链路追踪引入后性能下降 | ① 降低采样率 `management.tracing.sampling.probability=0.01`<br>② 或关闭 Zipkin 上报 `management.zipkin.tracing.endpoint=`（空值禁用上报）|
| fallback 绑定后降级误触发 | ① 移除 `fallbackFactory` 属性（回退到无 fallback，调用方 try-catch 兜底）<br>② 或修复 fallback 类逻辑（fallback 不应误触发，需排查 Sentinel 规则是否过严）|
| Zipkin Server 不可用 | ① Zipkin Reporter 自动丢弃 span（不阻塞业务）<br>② 业务无感知，仅丢失链路追踪数据 |

**关键回滚保留点**：

- `@GlobalTransactional` 注解**不删除**，通过 `seata.enabled=false` 配置开关关闭（保留代码，便于再次启用）。
- TracingConfig 改造后**不删除原 filter 代码**，git 历史可追溯；如需回退用 `git revert`。
- fallback 绑定**不删除 fallback 类**，仅移除 `fallbackFactory` 属性即可回退。

## 第7章 关键决策点总结

表：Phase 3 关键决策点

| # | 决策点 | 选择 | 理由 |
|---|-------|------|------|
| D1 | 分布式事务框架 | Seata 2.0.0 | SCA 2023.0.1.0 内置，支持 SpringBoot 3.x，与 Nacos 复用 |
| D2 | 事务模式 | TCC（signing-service 侧）+ AT（gateway 侧）混合 | 签名+广播不可逆，AT 无法回滚链上交易；TCC Try 预锁定 nonce 与 NoncePool 契合 |
| D3 | 需要分布式事务的方法 | PaymentServiceImpl.refund + SubscriptionServiceImpl.charge | 仅这 2 个方法跨服务调用不可逆操作（签名+广播）；其余 @Transactional 为纯本地事务或进程内调用 |
| D4 | 链路追踪技术 | Micrometer Tracing + Zipkin | SpringBoot 3.2.x 原生集成，零 agent，与 SCA 无冲突 |
| D5 | 采样率 | 开发 100% / 生产 10% | 开发全采样便于排障，生产按吞吐量调整避免 Zipkin 压力 |
| D6 | fallback 绑定方式 | 方案 G：nexus-sdk FallbackFactory 占位 + 各服务提供实现 | 不改 nexus-sdk 接口签名，fallback 语义可按服务定制 |
| D7 | traceId 传播格式 | W3C Trace Context（traceparent） | W3C 标准，与未来多语言生态兼容 |
| D8 | wallet-service Seata 接入 | **Phase 3 不接入**（仅 gateway 接入）| wallet-service 当前用 ConcurrentHashMap 无 undo_log，无法参与 AT；持久化 + Seata 留待 Phase 4 |
| D9 | Seata Server 部署 | docker-compose 单节点（开发）+ 集群 3 节点（生产）| 复用 Nacos 注册/配置，开发环境最小依赖 |
| D10 | 优雅停机 timeout | 30s | 签名+广播最长允许 30s（与 Feign read-timeout 15s + 链上确认 12s 留余量）|

## 第8章 验收标准

### 8.1 Phase 3 验收标准

1. ✅ Seata Server 2.0.0 启动，注册到 Nacos，控制台可见。
2. ✅ `PaymentServiceImpl.refund` 标注 `@GlobalTransactional`，模拟签名成功但本地事务异常时，全局事务回滚（undo_log 反向 SQL + TCC Cancel 释放 nonce）。
3. ✅ `SubscriptionServiceImpl.charge` 同上。
4. ✅ signing-service TCC 接口实现：Try 预锁定 nonce / Confirm 签名广播 / Cancel 释放 nonce，单元测试覆盖。
5. ✅ 3 个 Feign 接口绑定 fallbackFactory，停 signing-service 后 gateway 调用路由到 fallback 类（日志可见"降级触发"）。
6. ✅ 4 服务均添加 Micrometer Tracing + Zipkin 依赖，Zipkin UI 可见跨服务 trace（gateway → signing-service span 树）。
7. ✅ docker-compose up 启动 4 服务 + Nacos + Sentinel + Seata + Zipkin，全部健康检查通过。
8. ✅ Nacos 控制台可见 4 服务注册实例。
9. ✅ 优雅停机配置生效（`server.shutdown=graceful`），滚动更新时在途请求处理完成。
10. ✅ wallet-service 单元测试补充（DefaultWithdrawalApprovalService / DefaultCustodyService / DefaultAddressWhitelistService）。
11. ✅ 集成验证脚本 `phase3-integration-verify.sh` 9 项验证项全部 pass。
12. ✅ `gradle.bat build -x test` 全量编译通过。

### 8.2 Phase 3 不含项（明确边界）

- ❌ wallet-service 持久化（ConcurrentHashMap → 数据库）：留待 Phase 4。
- ❌ wallet-service Seata 接入（提现执行的全局事务）：依赖持久化，留待 Phase 4。
- ❌ 服务网格（Istio/Linkerd）：不在微服务化路线。
- ❌ APM 全量接入（Prometheus 指标已有，APM 告警 / 仪表盘留待独立可观测性项目）。
- ❌ 多活容灾 / 异地多活：留待 Phase 5+。

---

**文档结束**

> 本方案基于 2026-08-07 代码快照（v1.4.0, commit 5dbfbc2）设计，深入分析了 32 处 @Transactional、3 个 Feign 接口 fallback 绑定现状、gateway TracingConfig 链路追踪现状，给出 Seata 2.0.0 + Micrometer Tracing + Zipkin + FallbackFactory 方案。
>
> 实施时建议按任务清单（第5章）逐任务推进，关键路径为 T1 → T2 → T3 → T5 → T14（Seata 全链路）。每个任务完成后更新本文档的验收状态。
>
> **关键决策点**：D2（TCC+AT 混合模式）、D3（仅 refund/charge 接入 Seata）、D6（FallbackFactory 占位方案）、D8（wallet-service Seata 留待 Phase 4）。