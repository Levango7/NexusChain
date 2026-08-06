# NexusChain Phase 1 + Phase 2 微服务化方案设计

> 文档定位：为 NexusChain 从「进程内 composite build + HTTP 混合」演进到「签名 / 钱包 / 跨链桥独立部署 + SCA 服务网格」提供深度需求分析与方案设计。**只做分析和设计，不写代码**。
>
> 适用版本：v1.3.0 → v1.4.0（Phase 1 + Phase 2）
> 撰写日期：2026-08-06
> 作者：架构分析师

## 第1章 背景与目标

### 1.1 当前架构快照

| 维度 | 现状 |
|------|------|
| 语言 / 框架 | Java 17 + SpringBoot 3.2.5（已统一） |
| 构建 | Gradle composite build（`includeBuild`）+ `include` 子模块混合 |
| 模块规模 | 12 个 Java 模块、825 文件、约 11.3 万行 Java 代码 |
| 服务部署形态 | gateway / exchange-wallet / bridge / consortium 各自可独立启动，但生产链路以「gateway 进程内 composite build 直连 + HTTP 兜底」为主 |
| 已有 PoC 骨架 | `nexus-signing-service`（7 文件）、`nexus-wallet-service`（10 文件）—— 仅定义边界，未迁移实现 |
| 服务发现 / 配置中心 | 无（gateway `application.yml` 已预留 `nexus.nacos.enabled=false` 占位） |
| 熔断 / 限流 | Resilience4j（gateway 单体内），未跨服务 |
| 跨服务调用 | `RestTemplate` + 硬编码 baseUrl（`HttpSigningServiceClient` / `HttpWalletMgmtClient`） |

### 1.2 Phase 1 + Phase 2 目标边界

| 阶段 | 目标 | 不含 |
|------|------|------|
| Phase 1 | ① signing-service 独立部署（迁移实现代码）<br>② Nacos 注册 / 配置中心接入<br>③ Sentinel 熔断 / 限流替换 Resilience4j | Seata 分布式事务 |
| Phase 2 | ① wallet-service 独立部署（迁移实现代码）<br>② bridge 独立 Spring Boot Application（已具备，补全 SCA 接入）<br>③ OpenFeign 声明式调用改造（gateway → signing / wallet / bridge） | Seata、链路追踪增强（Skywalking / OTel） |

### 1.3 设计原则

1. **边界先行**：先固化服务边界（接口契约），再迁移实现，最后切换传输模式（in-process → HTTP）。
2. **可回滚**：每个迁移步骤保留 in-process 兜底分支，通过 `nexus.service-mesh.transport-mode` 开关切换。
3. **零数据丢失**：NoncePool / WithdrawalRequest / MpcSession 等状态化组件迁移时同步持久化方案。
4. **SCA 版本对齐**：严格按 SpringBoot 3.2.x → Spring Cloud 2023.0.x → SCA 2023.0.1.0 版本矩阵。
5. **接口向下兼容**：legacy 端点（`/ClientToTransferAccount`、`/fromPassword` 等）在迁移期保留，新代码使用 `/api/v1/**` 契约化端点。

## 第2章 交付物1：当前架构分析

### 2.1 nexus-exchange-wallet 类清单与职责

`nexus-exchange-wallet` 当前承担「签名服务」+「钱包管理」双重职责，类清单按包分组如下。

#### 2.1.1 signing 子包（签名服务职责）

表：signing 子包类清单

| 全限定类名 | 职责 | 依赖方向 |
|-----------|------|---------|
| `org.nexus.wallet.signing.controller.TxController` | 链上转账签名 + 广播 REST 端点（`/ClientToTransferAccount`、`/api/v1/transfers/sign`、`/getNoncePool`） | → wallet.pool.NoncePool、wallet.controller.NodeController、keystore.PlatformKeystore |
| `org.nexus.wallet.signing.controller.WalletController` | 钱包工具端点（地址校验、keystore 转换、密码派生）—— **命名误导**：实际是「无状态钱包工具」，不涉及私钥托管 | → sdk.wallet.WalletUtils |
| `org.nexus.wallet.signing.keystore.PlatformKeystore` | 平台热钱包 keystore 加载（`@PostConstruct` 读取 `wallet.keystore.json`），持有 prikey/pubkey | → sdk.wallet.WalletUtils |
| `org.nexus.wallet.signing.mpc.MpcSigner` | GG18/GG20 签名轮次编排（骨架，密码学体 TODO） | → mpc.* |
| `org.nexus.wallet.signing.mpc.MpcSigningSession` | 签名会话状态 | → mpc.ThresholdPolicy、mpc.MpcParticipant |
| `org.nexus.wallet.signing.mpc.MpcSignSession` | 签名会话（轻量 DTO） | — |
| `org.nexus.wallet.signing.mpc.MpcSignatureAggregator` | 签名份额聚合为最终 ECDSA 签名 | — |
| `org.nexus.wallet.signing.mpc.MpcService` / `DefaultMpcService` | MPC 服务接口与默认实现 | → mpc.MpcSigner 等 |
| `org.nexus.wallet.signing.mpc.MpcWallet` | MPC 钱包实体 | — |
| `org.nexus.wallet.signing.mpc.MpcKeyShare` | MPC 密钥份额 | — |
| `org.nexus.wallet.signing.mpc.MpcKeyGeneration` | GG18/GG20 密钥生成轮次 | — |
| `org.nexus.wallet.signing.mpc.MpcParticipant` | MPC 参与者 | — |
| `org.nexus.wallet.signing.mpc.MpcProtocolException` | MPC 协议异常 | — |
| `org.nexus.wallet.signing.mpc.ThresholdPolicy` | 阈值策略（t-of-n） | — |
| `org.nexus.wallet.signing.mpc.MpcApprovalPolicy` | MPC 感知审批策略，实现 `sdk.signing.ApprovalPolicy` | → mpc.MpcParticipant、ThresholdPolicy |
| `org.nexus.wallet.signing.mpc.ColdWalletMultiSigService` | 冷钱包多签转移编排（init → participantSign → aggregateAndBroadcast） | → **wallet.execution.OnChainExecutionClient**（跨边界依赖） |
| `org.nexus.wallet.signing.mpc.wal.WriteAheadLog` | MPC 消息 WAL | — |
| `org.nexus.wallet.signing.mpc.barrier.RoundBarrier` / `RoundTimeoutConfig` / `ReconnectStrategy` / `HealthCheck` | 轮次同步屏障 | — |
| `org.nexus.wallet.signing.mpc.router.MessageRouter` / `MessageDeduplicator` | 消息路由 / 去重 | — |
| `org.nexus.wallet.signing.mpc.security.HmacSigner` / `MpcMessageSecurityService` / `NonceTracker` / `MutualTlsContext` | mTLS + HMAC + nonce 安全层 | — |
| `org.nexus.wallet.signing.mpc.persistence.*`（8 个） | MPC 钱包 / 会话 / 上下文 / 密钥份额仓储（内存实现 + 接口） | — |
| `org.nexus.wallet.signing.mpc.transport.*`（6 个） | MPC 传输层（gRPC stub、InMemory、消息体） | — |

#### 2.1.2 wallet 子包（钱包管理职责）

表：wallet 子包类清单

| 全限定类名 | 职责 | 依赖方向 |
|-----------|------|---------|
| `org.nexus.wallet.wallet.controller.NodeController` | 链节点 RPC 封装（getNonce / sendTransaction / getTransactionConfirmed） | → Utils.HttpRequestUtil |
| `org.nexus.wallet.wallet.pool.NoncePool` / `NonceState` / `PoolTask` | Nonce 池（LevelDB 持久化） | → Leveldb.Leveldb、util.JsonUtil |
| `org.nexus.wallet.wallet.approval.WithdrawalApprovalService` / `DefaultWithdrawalApprovalService` | 提现审批工作流（PENDING → APPROVED → EXECUTED） | → sdk.signing.ApprovalPolicy、**wallet.execution.OnChainExecutionClient** |
| `org.nexus.wallet.wallet.approval.WithdrawalRequest` | 提现请求实体 | — |
| `org.nexus.wallet.wallet.approval.DefaultApprovalPolicy` | 默认分级审批策略，`@Primary` 实现 `sdk.signing.ApprovalPolicy` | — |
| `org.nexus.wallet.wallet.custody.CustodyService` / `DefaultCustodyService` | 冷热钱包托管（depositToCold / withdrawFromCold / rebalance） | → wallet.approval.WithdrawalApprovalService |
| `org.nexus.wallet.wallet.custody.CustodyPolicy` / `WalletTier` | 托管策略实体 / 钱包层级枚举 | — |
| `org.nexus.wallet.wallet.whitelist.AddressWhitelistService` / `DefaultAddressWhitelistService` / `WhitelistEntry` | 地址白名单（首次提币延迟） | — |
| `org.nexus.wallet.wallet.execution.OnChainExecutionClient` / `HttpOnChainExecutionClient` | 钱包端链上执行通道（HTTP 调 gateway `/api/v1/execution`） | → sdk.wallet.WalletTransactionRequest/Result |

#### 2.1.3 根包共享类

表：根包共享类清单

| 全限定类名 | 职责 | 归属判定 |
|-----------|------|---------|
| `org.nexus.wallet.ServerApplication` | SpringBoot 启动类 | 拆分后保留为 exchange-wallet 空壳启动类，或直接删除 |
| `org.nexus.wallet.Leveldb.Leveldb` | LevelDB 封装 | **迁 wallet-service**（仅 NoncePool 使用） |
| `org.nexus.wallet.ApiResult.APIResult` / `ResultSupport` | API 返回包装 | **迁 nexus-sdk**（共享 DTO） |
| `org.nexus.wallet.Utils.HttpRequestUtil` / `BeanToMapUtil` | HTTP / Bean 工具 | **迁 wallet-service**（NodeController 用）；BeanToMapUtil 评估是否删 |
| `org.nexus.wallet.util.JsonUtil` | Gson 单例 | **迁 nexus-sdk** 或两服务各自保留（Gson 已是公共依赖） |

### 2.2 gateway 对 exchange-wallet 的调用点分析

`ExchangeWalletClient`（`org.nexus.gateway.client.ExchangeWalletClient`）是 gateway 对 exchange-wallet 的唯一聚合客户端，**当前已是兼容委托层**，内部委托给 `WalletMgmtClient` + `SigningServiceClient`。

表：ExchangeWalletClient 的 5 处调用方

| # | 调用方类 | 调用方法 | 业务语义 | 拆分后应注入 |
|---|---------|---------|---------|-------------|
| 1 | `ConsortiumConnector` | `addressToPubkeyHash`（2 处）、`signTransfer`（2 处） | consortium 链结算：地址转 pubkeyHash + 签名广播 | `WalletMgmtClient` + `SigningServiceClient` |
| 2 | `ChainConnector` | `addressToPubkeyHash`（2 处）、`signTransfer`（2 处） | core 链结算：同上 | `WalletMgmtClient` + `SigningServiceClient` |
| 3 | `DefaultOnChainExecutionChannel` | `addressToPubkeyHash`（1 处）、`signTransfer`（1 处） | 链上执行通道：构造交易 → 签名广播 | `WalletMgmtClient` + `SigningServiceClient` |
| 4 | `PaymentServiceImpl` | `addressToPubkeyHash`（1 处）、`signTransfer`（1 处） | 支付退款：地址转换 + 签名退款 | `WalletMgmtClient` + `SigningServiceClient` |
| 5 | `SubscriptionServiceImpl` | `addressToPubkeyHash`（1 处）、`transfer`（1 处，legacy 私钥端点） | 订阅扣款：地址转换 + legacy 转账 | `WalletMgmtClient` + `SigningServiceClient` |

**关键观察**：
- 5 处调用方均同时使用 `WalletMgmtClient`（地址类操作）+ `SigningServiceClient`（签名类操作），拆分后建议**直接注入两个客户端**，删除 `ExchangeWalletClient` 兼容层。
- `SubscriptionServiceImpl` 仍使用 legacy `transfer(..., privateKey)` 端点，迁移期保留，新代码强制走 `signTransfer`。

### 2.3 模块依赖图

图：当前模块依赖关系

```
                    ┌──────────────┐
                    │  nexus-core  │  (区块链节点)
                    └──────┬───────┘
                           │ RPC
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
┌──────────────┐  ┌──────────────┐   ┌──────────────┐
│ nexus-gateway│  │nexus-bridge  │   │nexus-consortium│
│  (支付网关)  │  │ (跨链桥)     │   │  (联盟链)     │
└──────┬───────┘  └──────────────┘   └──────────────┘
       │
       │ HTTP (RestTemplate)
       ▼
┌──────────────────────────────┐
│   nexus-exchange-wallet      │
│  ┌────────────┐ ┌──────────┐ │
│  │  signing/  │→│  wallet/  │ │  (signing 依赖 wallet，单向)
│  └────────────┘ └──────────┘ │
└──────────────┬───────────────┘
               │
               ▼
        ┌──────────────┐
        │  nexus-sdk   │  (共享 DTO + WalletUtils/TxUtils)
        └──────────────┘

  gateway 同时 composite build 依赖：
    nexus-settlement / nexus-compliance / nexus-analytics / nexus-oracle / nexus-sdk
  PoC 骨架（未迁移实现）：
    nexus-signing-service / nexus-wallet-service（includeBuild，仅骨架）
```

### 2.4 signing vs wallet 代码边界识别

通过 import 分析（`grep "import org.nexus.wallet.wallet\."` in signing/）：

| 边界方向 | 跨包 import 数 | 具体依赖 | 边界判定 |
|---------|--------------|---------|---------|
| signing → wallet | 4 处 | `TxController` → `NoncePool`、`NonceState`、`NodeController`；`ColdWalletMultiSigService` → `OnChainExecutionClient` | **存在耦合** |
| wallet → signing | 0 处 | 无 | **无反向依赖** |

**结论**：当前 signing → wallet 单向依赖，符合 `application.properties` 注释声明的「Dependency direction: signing -> wallet」。

**但存在 2 类耦合点需在拆分时处理**：
1. `TxController` 依赖 `NoncePool` / `NodeController`：签名时需查询 nonce、广播交易。
2. `ColdWalletMultiSigService` 依赖 `OnChainExecutionClient`：MPC 签名完成后通过 gateway 链上执行通道广播。

**拆分策略**：
- 耦合点 1：将 `NoncePool` / `NodeController` 迁入 **signing-service**（它们本质是「签名前置状态」+「链节点 RPC」），或保留在 wallet-service 由 signing 通过 Feign 调用。**推荐前者**：NoncePool 是签名必需的前置状态，紧耦合签名流程；NodeController 是链节点 RPC 封装，签名广播必需。
- 耦合点 2：`OnChainExecutionClient` 本质是「调用 gateway 的 HTTP 客户端」，signing-service 独立后应改为「signing-service 直接广播」（signing-service 自身注入 `ChainRpcClient`），不再绕行 gateway。**或**：保留 `OnChainExecutionClient` 接口，signing-service 通过 Feign 调 gateway（适用于 gateway 需统一链上执行审计的场景）。**推荐前者**：减少一跳网络延迟，符合「签名服务负责签名 + 广播」的边界。

## 第3章 交付物2：服务拆分方案

### 3.1 nexus-signing-service 迁移清单

表：signing-service 完整迁移清单

| 源类（exchange-wallet） | 目标包路径（signing-service） | 迁移理由 |
|------------------------|---------------------------|---------|
| `org.nexus.wallet.signing.controller.TxController` | `org.nexus.signing.controller.TxController` | 签名 + 广播核心端点 |
| `org.nexus.wallet.signing.controller.WalletController` | `org.nexus.signing.controller.WalletToolController` | 无状态钱包工具端点（重命名消除「WalletController」歧义） |
| `org.nexus.wallet.signing.keystore.PlatformKeystore` | `org.nexus.signing.keystore.PlatformKeystore` | 平台密钥库，签名核心 |
| `org.nexus.wallet.signing.mpc.*`（全部 30+ 类） | `org.nexus.signing.mpc.*` | MPC 阈值签名全套 |
| `org.nexus.wallet.wallet.controller.NodeController` | `org.nexus.signing.chain.NodeRpcClient` | 链节点 RPC 封装，签名广播必需（重命名 + 重包） |
| `org.nexus.wallet.wallet.pool.NoncePool` | `org.nexus.signing.nonce.NoncePool` | Nonce 池，签名前置状态 |
| `org.nexus.wallet.wallet.pool.NonceState` | `org.nexus.signing.nonce.NonceState` | Nonce 状态 |
| `org.nexus.wallet.wallet.pool.PoolTask` | `org.nexus.signing.nonce.PoolTask` | Nonce 池任务 |
| `org.nexus.wallet.Leveldb.Leveldb` | `org.nexus.signing.persistence.Leveldb` | LevelDB 封装（NoncePool 持久化） |
| `org.nexus.wallet.util.JsonUtil` | 复用 `nexus-sdk` 的 `JsonUtil`（见 3.5） | Gson 单例 |
| `org.nexus.wallet.ApiResult.APIResult` | 复用 `nexus-sdk` 的 `APIResult`（见 3.5） | API 返回包装 |
| `org.nexus.wallet.Utils.HttpRequestUtil` | `org.nexus.signing.chain.HttpRequestUtil` | NodeController 依赖（迁移期保留，未来换 WebClient） |

**不迁入 signing-service 的类**：
- `ColdWalletMultiSigService` 对 `OnChainExecutionClient` 的依赖：迁移后 `ColdWalletMultiSigService` 改为直接注入 `NodeRpcClient`（原 `NodeController`）完成广播，删除对 `OnChainExecutionClient` 的依赖。

### 3.2 nexus-wallet-service 迁移清单

表：wallet-service 完整迁移清单

| 源类（exchange-wallet） | 目标包路径（wallet-service） | 迁移理由 |
|------------------------|---------------------------|---------|
| `org.nexus.wallet.wallet.approval.WithdrawalApprovalService` | `org.nexus.walletsvc.approval.WithdrawalApprovalService` | 提现审批接口 |
| `org.nexus.wallet.wallet.approval.DefaultWithdrawalApprovalService` | `org.nexus.walletsvc.approval.DefaultWithdrawalApprovalService` | 提现审批实现 |
| `org.nexus.wallet.wallet.approval.WithdrawalRequest` | `org.nexus.walletsvc.approval.WithdrawalRequest` | 提现请求实体 |
| `org.nexus.wallet.wallet.approval.DefaultApprovalPolicy` | `org.nexus.walletsvc.approval.DefaultApprovalPolicy` | 默认审批策略 |
| `org.nexus.wallet.wallet.custody.CustodyService` | `org.nexus.walletsvc.custody.CustodyService` | 托管接口 |
| `org.nexus.wallet.wallet.custody.DefaultCustodyService` | `org.nexus.walletsvc.custody.DefaultCustodyService` | 托管实现 |
| `org.nexus.wallet.wallet.custody.CustodyPolicy` | `org.nexus.walletsvc.custody.CustodyPolicy` | 托管策略 |
| `org.nexus.wallet.wallet.custody.WalletTier` | `org.nexus.walletsvc.custody.WalletTier` | 钱包层级 |
| `org.nexus.wallet.wallet.whitelist.AddressWhitelistService` | `org.nexus.walletsvc.whitelist.AddressWhitelistService` | 白名单接口 |
| `org.nexus.wallet.wallet.whitelist.DefaultAddressWhitelistService` | `org.nexus.walletsvc.whitelist.DefaultAddressWhitelistService` | 白名单实现 |
| `org.nexus.wallet.wallet.whitelist.WhitelistEntry` | `org.nexus.walletsvc.whitelist.WhitelistEntry` | 白名单条目 |
| `org.nexus.wallet.wallet.execution.OnChainExecutionClient` | `org.nexus.walletsvc.execution.OnChainExecutionClient` | 链上执行通道接口 |
| `org.nexus.wallet.wallet.execution.HttpOnChainExecutionClient` | `org.nexus.walletsvc.execution.HttpOnChainExecutionClient` | 链上执行通道 HTTP 实现 |

**wallet-service 对 signing-service 的依赖**：
- `DefaultWithdrawalApprovalService.executeApprovedWithdrawal` 当前通过 `OnChainExecutionClient` 调 gateway 完成提币。拆分后有 2 种选择：
  - **方案 A**：wallet-service 通过 Feign 调 signing-service 的 `/api/v1/transfers/sign` 完成签名广播（推荐，符合「wallet 管理审批、signing 负责签名」边界）。
  - 方案 B：wallet-service 继续通过 `OnChainExecutionClient` 调 gateway，gateway 再调 signing-service（多一跳，不推荐）。
- **采用方案 A**：`OnChainExecutionClient` 的实现改为 Feign 调 signing-service。

### 3.3 nexus-bridge 独立部署方案

`nexus-bridge` **当前已是独立 Spring Boot Application**（`BridgeApplication.java` + `@SpringBootApplication`），具备 `BridgeController`（`/api/v1/bridge/**`）。

表：bridge 独立部署待补全项

| 项 | 现状 | Phase 2 动作 |
|----|------|-------------|
| 启动类 | `BridgeApplication` 已存在 | 无需改动 |
| build.gradle | 已是独立 Boot plugin | 启用 `bootJar.enabled=true`（当前未显式启用，默认 Boot plugin 行为） |
| settings.gradle | `include 'nexus-bridge'`（include 子模块） | 改为 `includeBuild 'nexus-bridge'`（composite build 独立） |
| 服务发现 | 无 | 接入 Nacos discovery（见 4.2） |
| 配置中心 | 无 | 接入 Nacos config（见 4.2） |
| 熔断限流 | 无 | 接入 Sentinel（见 4.3） |
| gateway 调用 | **gateway 当前不调 bridge**（grep 确认） | Phase 2 新增 `BridgeClient` Feign 接口（gateway → bridge） |
| 持久化 | H2 / MySQL 可选 | 生产用 MySQL，Nacos 下发 datasource 配置 |

### 3.4 exchange-wallet 拆分后处置

**拆分后 exchange-wallet 保留什么？**

| 选项 | 描述 | 推荐 |
|------|------|------|
| A. 完全删除 | exchange-wallet 整模块从 settings.gradle 移除，源码归档到 `archived/` | **推荐**（Phase 2 完成后） |
| B. 保留空壳 | 仅保留 `ServerApplication` + 路由聚合，无业务逻辑 | 过渡期可用，但增加维护负担 |
| C. 保留为「集成测试夹具」 | 仅保留测试代码，验证 signing + wallet 协同 | 可选 |

**推荐路径**：
1. Phase 1：exchange-wallet 保留，signing-service 迁入实现代码后，exchange-wallet 的 signing/ 包删除，wallet/ 包保留。
2. Phase 2：wallet-service 迁入实现代码后，exchange-wallet 的 wallet/ 包删除，exchange-wallet 仅剩 `ServerApplication` 空壳。
3. Phase 2 验收后：从 `settings.gradle` 移除 `include 'nexus-exchange-wallet'`，源码归档。

### 3.5 共享 DTO 迁移至 nexus-sdk

表：nexus-sdk 共享 DTO 迁移清单（在 #51 已迁的基础上补充）

| 源类（exchange-wallet） | 目标包路径（nexus-sdk） | 迁移理由 | 状态 |
|------------------------|----------------------|---------|------|
| `org.nexus.wallet.wallet.approval.ApprovalPolicy` | `org.nexus.sdk.signing.ApprovalPolicy` | 跨服务共享审批策略接口 | **#51 已迁** |
| `org.nexus.wallet.wallet.execution.WalletTransactionRequest` | `org.nexus.sdk.wallet.WalletTransactionRequest` | 跨服务共享交易请求 DTO | **#51 已迁** |
| `org.nexus.wallet.wallet.execution.WalletTransactionResult` | `org.nexus.sdk.wallet.WalletTransactionResult` | 跨服务共享交易结果 DTO | **#51 已迁** |
| `org.nexus.wallet.ApiResult.APIResult` | `org.nexus.sdk.common.ApiResult` | 跨服务共享 API 返回包装 | **待迁** |
| `org.nexus.wallet.ApiResult.ResultSupport` | `org.nexus.sdk.common.ResultSupport` | API 返回辅助 | **待迁**（或删，已无外部依赖） |
| `org.nexus.wallet.util.JsonUtil` | `org.nexus.sdk.common.JsonUtil` | Gson 单例共享 | **待迁**（或各服务自留，Gson 是公共依赖） |
| `org.nexus.wallet.wallet.custody.WalletTier` | `org.nexus.sdk.wallet.WalletTier` | 钱包层级枚举，wallet-service 对外暴露 | **待迁** |
| `org.nexus.wallet.wallet.custody.CustodyPolicy` | `org.nexus.sdk.wallet.CustodyPolicy` | 托管策略，可能跨服务引用 | **待迁**（评估，若仅 wallet-service 内用则不迁） |
| `org.nexus.wallet.wallet.approval.WithdrawalRequest` | `org.nexus.sdk.wallet.WithdrawalRequest` | 提现请求实体，signing-service 需感知（执行提币时） | **待迁** |
| `org.nexus.wallet.wallet.approval.WithdrawalApprovalService`（接口） | `org.nexus.sdk.client.WithdrawalApprovalClient` | 提现审批客户端接口（供 gateway Feign 调 wallet-service） | **新增** |
| `org.nexus.wallet.wallet.custody.CustodyService`（接口） | `org.nexus.sdk.client.CustodyClient` | 托管客户端接口 | **新增** |
| `org.nexus.wallet.wallet.whitelist.AddressWhitelistService`（接口） | `org.nexus.sdk.client.AddressWhitelistClient` | 白名单客户端接口 | **新增** |

## 第4章 交付物3：SCA 集成方案

### 4.1 版本对齐矩阵

表：SpringBoot 3.2.5 对应的 SCA 版本矩阵

| 组件 | 版本 | 来源 |
|------|------|------|
| Spring Boot | 3.2.5 | 已定 |
| Spring Cloud | 2023.0.3（Leyton） | Spring Boot 3.2.x 对应 Spring Cloud 2023.0.x |
| Spring Cloud Alibaba | 2023.0.1.0 | SCA 官方对应 Spring Cloud 2023.0.x |
| Nacos Client | 2.3.2（SCA 2023.0.1.0 内置） | SCA BOM 管理 |
| Sentinel | 1.8.8（SCA 2023.0.1.0 内置） | SCA BOM 管理 |
| OpenFeign | 4.1.0（Spring Cloud 2023.0.3 内置） | Spring Cloud BOM 管理 |
| Spring Cloud LoadBalancer | 4.1.0（Spring Cloud 2023.0.3 内置） | Spring Cloud BOM 管理 |

**关键风险**：SCA 2023.0.1.0 发布时间较新，需验证与 Nacos Server 2.x 的兼容性。**推荐 Nacos Server 2.3.2+**。

### 4.2 Nacos 集成方案

#### 4.2.1 微服务 build.gradle 依赖

代码示例：signing-service build.gradle 依赖片段（Gradle）

```gradle
dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // Spring Cloud Alibaba BOM（管理 Nacos / Sentinel 版本）
    implementation platform('org.springframework.cloud:spring-cloud-dependencies:2023.0.3')
    implementation platform('com.alibaba.cloud:spring-cloud-alibaba-dependencies:2023.0.1.0')

    // Nacos 服务发现 + 配置中心
    implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery'
    implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config'

    // Sentinel
    implementation 'com.alibaba.cloud:spring-cloud-starter-alibaba-sentinel'

    // OpenFeign（signing-service 作为 Feign 客户端调其他服务时需要）
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    implementation 'org.springframework.cloud:spring-cloud-starter-loadbalancer'

    // 共享 SDK
    implementation 'org.nexus:nexus-sdk:1.4.0'
}
```

**wallet-service / bridge / gateway** 依赖同构，按需增减。

#### 4.2.2 bootstrap.yml 配置

代码示例：signing-service bootstrap.yml

```yaml
spring:
  application:
    name: nexus-signing-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  cloud:
    nacos:
      discovery:
        server-addr: ${NEX_NACOS_SERVER:127.0.0.1:8848}
        namespace: ${NEX_NACOS_NAMESPACE:public}
        group: ${NEX_NACOS_GROUP:NEXUS_GROUP}
        cluster-name: ${NEX_NACOS_CLUSTER:DEFAULT}
        # 服务元数据：版本、区域，用于灰度发布
        metadata:
          version: ${NEX_SERVICE_VERSION:1.4.0}
          region: ${NEX_REGION:cn-east-1}
      config:
        server-addr: ${NEX_NACOS_SERVER:127.0.0.1:8848}
        namespace: ${NEX_NACOS_NAMESPACE:public}
        group: ${NEX_NACOS_GROUP:NEXUS_GROUP}
        # 共享配置（所有微服务共享）：数据源、日志、Sentinel 规则
        shared-configs:
          - data-id: nexus-common.yaml
            group: NEXUS_GROUP
            refresh: true
          - data-id: nexus-sentinel-rules.yaml
            group: NEXUS_GROUP
            refresh: true
        # 服务私有配置
        file-extension: yaml
        refresh-enabled: true
```

表：各微服务 bootstrap.yml 服务名与端口

| 服务 | spring.application.name | 默认端口 | Nacos dataId |
|------|------------------------|---------|-------------|
| signing-service | `nexus-signing-service` | 8082 | `nexus-signing-service.yaml` |
| wallet-service | `nexus-wallet-service` | 8083 | `nexus-wallet-service.yaml` |
| bridge | `nexus-bridge` | 8084 | `nexus-bridge.yaml` |
| gateway | `nexus-gateway` | 8080 | `nexus-gateway.yaml` |

#### 4.2.3 配置迁移清单

表：从 application.yml 迁到 Nacos config 的配置项

| 配置项 | 当前位置 | 迁移目标 | 理由 |
|-------|---------|---------|------|
| `spring.datasource.*` | gateway application.yml | `nexus-common.yaml`（共享） | 所有服务共享 datasource（或各自 datasource） |
| `nexus.chain.rpc-url` | gateway application.yml | `nexus-common.yaml` | 链节点地址全局共享 |
| `nexus.consortium.rpc-url` | gateway application.yml | `nexus-common.yaml` | consortium 地址全局共享 |
| `nexus.exchange-wallet.*` | gateway application.yml | **删除** | 拆分后改为服务发现，不再硬编码 baseUrl |
| `nexus.service-mesh.*` | gateway application.yml | `nexus-gateway.yaml` | gateway 私有 |
| `nexus.webhook.*` | gateway application.yml | `nexus-gateway.yaml` | gateway 私有 |
| `nexus.routing.*` | gateway application.yml | `nexus-gateway.yaml` | gateway 私有 |
| `wallet.keystore.json` / `wallet.keystore.password` | exchange-wallet application.properties | `nexus-signing-service.yaml`（**加密**） | 签名服务私有，敏感配置 |
| `nodeNet` | exchange-wallet application.properties | `nexus-common.yaml`（重命名 `nexus.chain.node-rpc`） | 链节点 RPC |
| `nexus.gateway.base-url` | exchange-wallet（wallet.execution） | **删除** | wallet-service 改为 Feign + 服务发现调 signing-service |
| `resilience4j.*` | gateway application.yml | **删除** | 替换为 Sentinel |

#### 4.2.4 Nacos 部署方式

| 环境 | 部署方式 | 说明 |
|------|---------|------|
| 开发 / 单元测试 | 嵌入式 Nacos（`nacos.embedded.enabled=true`）或 docker-compose 单节点 | 不引入外部依赖 |
| 集成测试 | docker-compose 单节点 Nacos 2.3.2 | 见 `docker-compose.yml` |
| 生产 | Nacos 集群 3 节点 + MySQL 持久化 + Nginx 负载均衡 | 高可用 |

### 4.3 Sentinel 集成方案

#### 4.3.1 关键接口熔断 / 限流规则清单

表：Sentinel 规则清单

| 服务 | 资源名 | 规则类型 | 阈值 | 降级策略 |
|------|-------|---------|------|---------|
| signing-service | `POST:/api/v1/transfers/sign` | 限流（QPS） | 100 QPS（单机） | `SignTransferFallback`：返回 null + 告警 |
| signing-service | `POST:/api/v1/transfers/sign` | 熔断（异常比） | 50% / 10s / 5 次最小 | `SignTransferFallback` |
| signing-service | `POST:/ClientToTransferAccount`（legacy） | 限流 | 10 QPS | `TransferFallback` |
| signing-service | `MpcSigner.runSigningRounds` | 熔断（慢调用） | RT > 30s / 50% / 10s | `MpcSignFallback`：返回失败，session 标记 FAILED |
| wallet-service | `POST:/api/v1/wallet/withdrawal/request` | 限流 | 50 QPS | `WithdrawalFallback`：返回 PENDING_RETRY |
| wallet-service | `POST:/api/v1/wallet/withdrawal/approve` | 限流 | 20 QPS | `ApproveFallback` |
| wallet-service | `DefaultCustodyService.withdrawFromCold` | 熔断 | 30% / 20s | `CustodyFallback`：fail-closed |
| bridge | `POST:/api/v1/bridge/lock` | 限流 | 30 QPS | `BridgeLockFallback` |
| bridge | `POST:/api/v1/bridge/mint` | 限流 | 30 QPS | `BridgeMintFallback` |
| gateway | `POST:/api/v1/payments` | 限流 | 500 QPS（集群） | `PaymentFallback`：返回 429 |
| gateway | `ChainConnector.createPayment` | 熔断 | 50% / 30s | `ChainConnectorFallback`：返回 fail |
| gateway | `ConsortiumConnector.createPayment` | 熔断 | 50% / 30s | `ConsortiumConnectorFallback` |

#### 4.3.2 降级 fallback 类设计

代码示例：SignTransferFallback（Java）

```java
package org.nexus.signing.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 签名服务降级策略。
 *
 * <p>设计原则：
 * <ul>
 *   <li>限流降级：返回 null + 告警日志，调用方按 null 处理失败</li>
 *   <li>熔断降级：返回 null + 告警 + 上报 Prometheus</li>
 *   <li>不抛异常：避免调用方需额外 try-catch</li>
 * </ul></p>
 */
public class SignTransferFallback {
    private static final Logger log = LoggerFactory.getLogger(SignTransferFallback.class);

    public static String signTransferFallback(String fromPubkey, String toPubkeyHash,
                                              java.math.BigDecimal amount, BlockException ex) {
        String reason = classify(ex);
        log.error("signTransfer 降级触发: reason={}, from={}, to={}, amount={}",
                reason, fromPubkey, toPubkeyHash, amount);
        // TODO: 上报 Prometheus + 告警
        return null;
    }

    private static String classify(BlockException ex) {
        if (ex instanceof FlowException) return "FLOW_LIMIT";
        if (ex instanceof DegradeException) return "CIRCUIT_OPEN";
        return "UNKNOWN";
    }
}
```

#### 4.3.3 Sentinel 规则持久化

- **开发环境**：规则在代码中硬编码（`@SentinelResource` 注解 + 代码规则加载）。
- **生产环境**：规则推送至 Nacos config（`nexus-sentinel-rules.yaml`），Sentinel 数据源订阅 Nacos，规则变更实时生效。

### 4.4 OpenFeign 集成方案

#### 4.4.1 Feign 接口定义清单

表：Feign 接口清单

| Feign 接口 | 所属模块（@FeignClient 定义方） | 调用方 → 服务 | 方法 |
|-----------|------------------------------|--------------|------|
| `SigningServiceFeignClient` | nexus-sdk | gateway → signing-service | `signTransfer`、`transfer`、`canSignViaMpc`、`getNoncePool` |
| `WalletMgmtFeignClient` | nexus-sdk | gateway → wallet-service | `addressToPubkeyHash`、`verifyAddress`、`isAddressWhitelisted`、`getCustodyTier`、`requestWithdrawal`、`approveWithdrawal` |
| `BridgeFeignClient` | nexus-sdk | gateway → bridge | `lock`、`mint`、`burn`、`unlock`、`getTransaction` |
| `SigningInternalFeignClient` | nexus-sdk | wallet-service → signing-service | `signTransfer`（提现执行时签名广播） |

代码示例：SigningServiceFeignClient（Java）

```java
package org.nexus.sdk.client.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@FeignClient(
    name = "nexus-signing-service",
    path = "/api/v1",
    fallback = SigningServiceFallback.class
)
public interface SigningServiceFeignClient {

    @PostMapping(value = "/transfers/sign", consumes = "application/x-www-form-urlencoded")
    String signTransfer(@RequestParam("fromPubkey") String fromPubkey,
                        @RequestParam("toPubkeyHash") String toPubkeyHash,
                        @RequestParam("amount") BigDecimal amount);

    @PostMapping(value = "/transfers", consumes = "application/x-www-form-urlencoded")
    String transfer(@RequestParam("fromPubkey") String fromPubkey,
                    @RequestParam("toPubkeyHash") String toPubkeyHash,
                    @RequestParam("amount") BigDecimal amount,
                    @RequestParam("prikey") String privateKey);

    @GetMapping("/signing/capability")
    boolean canSignViaMpc(@RequestParam("amount") BigDecimal amount);
}
```

#### 4.4.2 超时 / 重试配置

代码示例：gateway application.yml Feign 配置

```yaml
feign:
  client:
    config:
      default:
        connect-timeout: 3000
        read-timeout: 10000
        logger-level: BASIC
      nexus-signing-service:
        connect-timeout: 2000
        read-timeout: 8000   # 签名 + 广播，允许较长
      nexus-wallet-service:
        connect-timeout: 2000
        read-timeout: 5000
      nexus-bridge:
        connect-timeout: 3000
        read-timeout: 15000  # 跨链桥操作，允许最长
  circuitbreaker:
    enabled: true   # 启用 Sentinel 作为 Feign 的熔断器
  compression:
    request:
      enabled: true
    response:
      enabled: true

# Spring Cloud LoadBalancer 配置
spring:
  cloud:
    loadbalancer:
      ribbon:
        enabled: false   # 关闭 Ribbon（已废弃）
      cache:
        enabled: true
        ttl: 30s
```

## 第5章 交付物4：代码迁移影响分析

### 5.1 gateway 中 ExchangeWalletClient 5 处调用方改造

表：5 处调用方改造明细

| # | 调用方 | 改造动作 |
|---|-------|---------|
| 1 | `ConsortiumConnector` | ① 删除 `ExchangeWalletClient` 注入<br>② 注入 `WalletMgmtClient` + `SigningServiceClient`（Feign 实现）<br>③ `walletClient.addressToPubkeyHash(x)` → `walletMgmtClient.addressToPubkeyHash(x)`<br>④ `walletClient.signTransfer(...)` → `signingServiceClient.signTransfer(...)` |
| 2 | `ChainConnector` | 同 ConsortiumConnector |
| 3 | `DefaultOnChainExecutionChannel` | 同上；额外：`exchangeWalletClient` 字段重命名为 `walletMgmtClient` + `signingServiceClient` |
| 4 | `PaymentServiceImpl` | 同上 |
| 5 | `SubscriptionServiceImpl` | 同上；`walletClient.transfer(..., privateKey)` → `signingServiceClient.transfer(..., privateKey)`（legacy 保留） |

**统一改造模式**：
1. 删除 `import org.nexus.gateway.client.ExchangeWalletClient;`
2. 新增 `import org.nexus.sdk.client.WalletMgmtClient;` + `import org.nexus.sdk.client.SigningServiceClient;`
3. 构造函数参数替换。
4. 方法调用按表 5.1 替换。
5. **最后**：删除 `ExchangeWalletClient` 兼容类。

### 5.2 exchange-wallet 内部 signing ↔ wallet 调用处理

表：拆分后跨服务调用处理

| 调用点 | 当前（进程内） | 拆分后（跨服务） |
|-------|--------------|----------------|
| `TxController` → `NoncePool` | `@Autowired NoncePool` | **同服务内**（NoncePool 迁入 signing-service） |
| `TxController` → `NodeController` | `@Autowired NodeController` | **同服务内**（NodeController 迁入 signing-service，重命名 NodeRpcClient） |
| `ColdWalletMultiSigService` → `OnChainExecutionClient` | `@Autowired OnChainExecutionClient` | **改为直接广播**：注入 `NodeRpcClient`，删除 `OnChainExecutionClient` 依赖 |
| `DefaultWithdrawalApprovalService` → `OnChainExecutionClient` | `@Autowired OnChainExecutionClient` | **改为 Feign 调 signing-service**：注入 `SigningServiceFeignClient`，调用 `signTransfer` |
| `DefaultCustodyService` → `WithdrawalApprovalService` | `@Autowired WithdrawalApprovalService` | **同服务内**（均在 wallet-service） |

### 5.3 现有测试代码影响

表：测试代码影响

| 测试类 | 模块 | 影响 | 改造 |
|-------|------|------|------|
| `ServerApplicationTests` | exchange-wallet | contextLoads 验证 Spring 上下文 | 拆分后删除（exchange-wallet 空壳） |
| `TxControllerTest`（2 个） | exchange-wallet | 签名端点测试 | 迁入 signing-service，改包路径 |
| `ScratchGsonExperimentTest` | exchange-wallet | Gson 实验 | 迁入 signing-service 或删除 |
| `DefaultAddressWhitelistServiceTest` | exchange-wallet | 白名单测试 | 迁入 wallet-service |
| `DefaultWithdrawalApprovalServiceTest` | exchange-wallet | 提现审批测试 | 迁入 wallet-service，Mock `SigningServiceFeignClient` |
| `DefaultCustodyServiceTest` | exchange-wallet | 托管测试 | 迁入 wallet-service |
| gateway 测试（593 个） | gateway | `ExchangeWalletClient` 相关测试 | 改为 Mock `WalletMgmtClient` + `SigningServiceClient` |

### 5.4 配置文件影响

表：配置文件改造

| 文件 | 改造 |
|------|------|
| `nexus-exchange-wallet/src/main/resources/application.properties` | 拆分后删除（配置迁 Nacos） |
| `nexus-gateway/src/main/resources/application.yml` | ① 删除 `nexus.exchange-wallet.*`<br>② 删除 `resilience4j.*`<br>③ 新增 `feign.*`、`spring.cloud.nacos.*`、`spring.cloud.sentinel.*`<br>④ `nexus.service-mesh.transport-mode: http` |
| `nexus-signing-service/src/main/resources/bootstrap.yml` | **新增**（见 4.2.2） |
| `nexus-wallet-service/src/main/resources/bootstrap.yml` | **新增** |
| `nexus-bridge/src/main/resources/bootstrap.yml` | **新增** |
| `nexus-gateway/src/main/resources/bootstrap.yml` | **新增** |

### 5.5 settings.gradle / build.gradle 影响

#### 5.5.1 settings.gradle 改造

代码示例：settings.gradle 改造后

```gradle
rootProject.name = 'nexus'

// === 基础协议层 ===
include 'nexus-rpc-doc'

// === 核心层 ===
include ':nexus-core:nexus-core'

// === SDK 层 ===
include 'nexus-sdk:java'

// === 服务层（全部改为 includeBuild 独立） ===
includeBuild 'nexus-gateway'
includeBuild 'nexus-signing-service'   // Phase 1
includeBuild 'nexus-wallet-service'    // Phase 2
includeBuild 'nexus-bridge'            // Phase 2（从 include 改为 includeBuild）
includeBuild 'nexus-consortium'

// === 中间服务层 ===
includeBuild 'nexus-settlement'
includeBuild 'nexus-compliance'
includeBuild 'nexus-analytics'
includeBuild 'nexus-oracle'

// === Phase 2 完成后删除 ===
// include 'nexus-exchange-wallet'  // 已删除
```

#### 5.5.2 build.gradle 改造

| 模块 | 改造 |
|------|------|
| `nexus-signing-service/build.gradle` | ① 启用 `bootJar.enabled=true`（独立部署）<br>② 添加 SCA / Nacos / Sentinel / OpenFeign 依赖<br>③ 添加 `implementation project(':nexus-sdk:java')` 或 composite build 依赖 |
| `nexus-wallet-service/build.gradle` | 同 signing-service |
| `nexus-bridge/build.gradle` | 添加 SCA / Nacos / Sentinel 依赖 |
| `nexus-gateway/build.gradle` | ① 删除 `resilience4j` 依赖<br>② 添加 SCA / Nacos / Sentinel / OpenFeign 依赖<br>③ 删除对 exchange-wallet 的间接依赖 |
| `nexus-exchange-wallet/build.gradle` | Phase 2 完成后从 settings.gradle 移除，build.gradle 归档 |

## 第6章 交付物5：任务拆分建议

### 6.1 任务拆分原则

- 每个任务 **2-4 小时**粒度。
- 任务间依赖关系明确（`blocked_by`）。
- 优先级：Phase 1 任务 > Phase 2 任务。
- 每个任务有明确的验收标准（编译通过 / 测试通过 / 配置生效）。

### 6.2 任务清单

表：Phase 1 + Phase 2 任务拆分（共 18 个任务）

| ID | 阶段 | 任务 | 粒度 | 优先级 | blocked_by | target_files |
|----|------|------|------|--------|-----------|-------------|
| T1 | P1 | 共享 DTO 补迁 nexus-sdk：APIResult / JsonUtil / WalletTier / WithdrawalRequest + 新增 Feign 接口骨架 | 3h | P0 | — | nexus-sdk/java/src/main/java/org/nexus/sdk/ |
| T2 | P1 | Nacos / Sentinel 开发环境部署：docker-compose 新增 Nacos 2.3.2 + Sentinel Dashboard 1.8.8 | 2h | P0 | — | docker-compose.yml |
| T3 | P1 | signing-service build.gradle 改造：SCA BOM + Nacos discovery/config + Sentinel + OpenFeign 依赖 + bootJar 启用 | 2h | P0 | T1 | nexus-signing-service/build.gradle |
| T4 | P1 | signing-service 迁入实现代码：TxController / WalletController / PlatformKeystore / mpc/* 全套 + NoncePool / NodeController / Leveldb / HttpRequestUtil | 4h | P0 | T1, T3 | nexus-signing-service/src/main/java/org/nexus/signing/ |
| T5 | P1 | signing-service bootstrap.yml + application.yml 配置：Nacos discovery/config + Sentinel 规则 + wallet.keystore 配置迁移 | 2h | P0 | T3 | nexus-signing-service/src/main/resources/ |
| T6 | P1 | signing-service ColdWalletMultiSigService 解耦：删除 OnChainExecutionClient 依赖，改注入 NodeRpcClient 直接广播 | 2h | P1 | T4 | nexus-signing-service/src/main/java/org/nexus/signing/mpc/ColdWalletMultiSigService.java |
| T7 | P1 | signing-service 测试迁入：TxControllerTest / ScratchGsonExperimentTest 改包路径 + Mock 适配 | 2h | P1 | T4 | nexus-signing-service/src/test/java/ |
| T8 | P1 | exchange-wallet signing/ 包删除（保留 wallet/ 包待 Phase 2） | 1h | P1 | T4, T7 | nexus-exchange-wallet/src/main/java/org/nexus/wallet/signing/ |
| T9 | P1 | gateway build.gradle 改造：删除 resilience4j + 添加 SCA / Nacos / Sentinel / OpenFeign 依赖 | 2h | P0 | T1 | nexus-gateway/build.gradle |
| T10 | P1 | gateway ExchangeWalletClient 5 处调用方改造：注入 WalletMgmtClient + SigningServiceClient（Feign），删除 ExchangeWalletClient | 3h | P0 | T1, T9 | nexus-gateway/src/main/java/org/nexus/gateway/ |
| T11 | P1 | gateway bootstrap.yml + application.yml 改造：Nacos + Sentinel + Feign 配置 + 删除 exchange-wallet / resilience4j 配置 | 2h | P0 | T9 | nexus-gateway/src/main/resources/ |
| T12 | P1 | Sentinel 规则定义 + fallback 类实现：signing-service / gateway 关键接口降级 | 3h | P1 | T5, T11 | nexus-signing-service/src/main/java/org/nexus/signing/fallback/, nexus-gateway/src/main/java/org/nexus/gateway/fallback/ |
| T13 | P1 | Phase 1 集成验证：Nacos 启动 + signing-service 注册 + gateway 通过 Feign 调通签名端点 + Sentinel 限流生效 | 2h | P0 | T5, T11, T12 | — |
| T14 | P2 | wallet-service build.gradle 改造：同 signing-service | 1h | P1 | T13 | nexus-wallet-service/build.gradle |
| T15 | P2 | wallet-service 迁入实现代码：approval/* + custody/* + whitelist/* + execution/* 全套 | 3h | P1 | T1, T14 | nexus-wallet-service/src/main/java/org/nexus/walletsvc/ |
| T16 | P2 | wallet-service DefaultWithdrawalApprovalService 改造：OnChainExecutionClient 改为 SigningServiceFeignClient（Feign 调 signing-service） | 2h | P1 | T15 | nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultWithdrawalApprovalService.java |
| T17 | P2 | bridge 独立部署改造：settings.gradle include→includeBuild + SCA 依赖 + bootstrap.yml + gateway BridgeFeignClient | 3h | P1 | T13 | settings.gradle, nexus-bridge/build.gradle, nexus-bridge/src/main/resources/ |
| T18 | P2 | Phase 2 集成验证 + exchange-wallet 删除：全链路 Feign 调通 + exchange-wallet 从 settings.gradle 移除 + 全量编译 | 2h | P0 | T16, T17 | settings.gradle, nexus-exchange-wallet/ |

**任务总数**：18 个（Phase 1: 13 个，Phase 2: 5 个）
**预估总工时**：约 43 小时（Phase 1: 28h，Phase 2: 11h，集成验证: 4h）

### 6.3 任务依赖图

图：任务依赖关系

```
T1 (共享DTO) ──┬──→ T3 (signing build) ──→ T4 (signing 迁码) ──→ T6 (解耦) ──→ T8 (删 signing/)
               │                          └──→ T5 (signing 配置) ──→ T12 (Sentinel) ──→ T13 (P1 验证)
               │                          └──→ T7 (signing 测试)
               │
               ├──→ T9 (gateway build) ──→ T10 (gateway 改造) ──→ T11 (gateway 配置) ──→ T12
               │                          └──→ T11
               │
               └──→ T14 (wallet build) ──→ T15 (wallet 迁码) ──→ T16 (wallet 改造) ──→ T18 (P2 验证)
               
T2 (Nacos 部署) ──→ T13

T13 (P1 验证) ──→ T14, T17

T17 (bridge 独立) ──→ T18
```

## 第7章 风险点与回滚方案

### 7.1 风险点

表：风险点清单

| # | 风险 | 影响 | 概率 | 缓解措施 |
|---|------|------|------|---------|
| R1 | SCA 2023.0.1.0 与 SpringBoot 3.2.5 版本不兼容 | 编译失败 / 启动失败 | 中 | T3 前先做最小 POC：空 SpringBoot 3.2.5 + SCA 2023.0.1.0 启动验证 |
| R2 | Nacos Server 2.x 与 SCA 2023.0.1.0 客户端不兼容 | 服务注册失败 | 低 | 使用 Nacos 2.3.2+，参考 SCA 官方兼容矩阵 |
| R3 | signing-service 迁移后 NoncePool LevelDB 路径变化 | Nonce 丢失，交易重复 | 中 | T4 时保留原 LevelDB 路径（`System.getProperty("user.dir")/leveldb`），或迁移时复制 LevelDB 数据 |
| R4 | Feign 替换 RestTemplate 后 HTTP 语义差异（form-urlencoded 编码） | 签名调用失败 | 中 | T10 时对比 HTTP 请求报文，确保 Feign 编码与原 RestTemplate 一致 |
| R5 | Sentinel 替换 Resilience4j 后熔断行为差异 | 熔断误触发 / 不触发 | 中 | T12 时逐接口对比 Resilience4j 与 Sentinel 规则语义 |
| R6 | wallet-service → signing-service Feign 调用循环依赖 | Spring 启动失败 | 低 | signing-service 不依赖 wallet-service，单向调用，无循环 |
| R7 | exchange-wallet 删除后 gateway 测试失败（593 个测试） | CI 阻塞 | 高 | T10 时同步改造 gateway 测试，Mock Feign 客户端 |
| R8 | Nacos config 配置回滚不及时导致生产事故 | 配置错误下发 | 低 | Nacos config 开启灰度发布 + 配置版本历史 + 回滚机制 |
| R9 | MPC 网络层（gRPC）在独立部署后节点发现失败 | MPC 签名无法完成 | 中 | T4 时验证 mTLS + gRPC 在容器化环境的网络配置 |
| R10 | `wallet.keystore.json` 迁移到 Nacos 后明文泄露 | 私钥泄露 | **高** | Nacos config 加密配置（Nacos 2.x 支持 AES 加密）+ IAM 严格控制访问 |

### 7.2 回滚方案

**总体回滚策略**：通过 `nexus.service-mesh.transport-mode` 开关在 `in-process` / `http` 间切换。

| 回滚场景 | 动作 |
|---------|------|
| Phase 1 signing-service 独立部署失败 | ① `transport-mode` 切回 `in-process`<br>② gateway 重新通过 composite build 直连 exchange-wallet<br>③ signing-service 实例下线 |
| Nacos 不可用 | ① Nacos discovery 切到本地缓存（Spring Cloud LoadBalancer 默认行为）<br>② Nacos config 切到本地 application.yml 兜底 |
| Sentinel 规则误配 | ① Sentinel 规则从 Nacos 删除，回退到代码默认规则<br>② 或临时关闭 Sentinel（`spring.cloud.sentinel.enabled=false`） |
| Feign 调用失败率飙升 | ① Feign fallback 兜底返回 null/false<br>② 紧急切回 RestTemplate + 硬编码 baseUrl（保留 ExchangeWalletClient 兼容层至 Phase 2 验收后删除） |
| exchange-wallet 删除后发现问题 | ① git revert settings.gradle 改动<br>② 恢复 `include 'nexus-exchange-wallet'`<br>③ exchange-wallet 源码从 `archived/` 恢复 |

**关键回滚保留点**：
- `ExchangeWalletClient` 兼容层**保留至 Phase 2 验收后**才删除（T18）。
- exchange-wallet 源码**归档而非删除**（移到 `archived/nexus-exchange-wallet/`）。
- in-process 传输模式代码路径**保留**，通过开关切换。

## 第8章 关键决策点总结

| # | 决策点 | 选择 | 理由 |
|---|-------|------|------|
| D1 | NoncePool / NodeController 归属 | 迁入 signing-service | 紧耦合签名流程，签名必需前置状态 |
| D2 | ColdWalletMultiSigService 广播方式 | 直接注入 NodeRpcClient 广播 | 减少一跳网络延迟，符合「签名服务负责签名+广播」边界 |
| D3 | wallet-service 提现执行方式 | Feign 调 signing-service | 符合「wallet 管理审批、signing 负责签名」边界 |
| D4 | exchange-wallet 最终处置 | Phase 2 验收后删除，源码归档 | 避免维护负担，保留回滚可能 |
| D5 | SCA 版本 | 2023.0.1.0 + Spring Cloud 2023.0.3 | SpringBoot 3.2.x 官方对应 |
| D6 | 熔断框架 | Sentinel 替换 Resilience4j | SCA 生态统一，规则可 Nacos 动态下发 |
| D7 | `wallet.keystore.json` 存储 | Nacos config 加密配置 | 敏感配置集中管理 + 加密 |
| D8 | ExchangeWalletClient 删除时机 | T18（Phase 2 验收后） | 保留回滚兼容层 |
| D9 | bridge settings.gradle 改造 | include → includeBuild | 真正独立 composite build |
| D10 | Feign fallback 语义 | 返回 null/false，不抛异常 | 调用方无需额外 try-catch |

## 第9章 验收标准

### 9.1 Phase 1 验收标准

1. ✅ `nexus-signing-service` 可独立 `java -jar` 启动，注册到 Nacos。
2. ✅ `nexus-gateway` 启动后通过 Feign 调通 signing-service 的 `/api/v1/transfers/sign` 端点。
3. ✅ Nacos 控制台可见 signing-service / gateway 两个实例注册。
4. ✅ Sentinel Dashboard 可见 signing-service 资源监控，限流规则生效。
5. ✅ `gradle.bat build -x test` 全量编译通过。
6. ✅ exchange-wallet 的 signing/ 包已删除，wallet/ 包保留。
7. ✅ `ExchangeWalletClient` 仍存在（兼容层，Phase 2 后删）。

### 9.2 Phase 2 验收标准

1. ✅ `nexus-wallet-service` 可独立启动，注册到 Nacos。
2. ✅ `nexus-bridge` 可独立启动，注册到 Nacos。
3. ✅ gateway 通过 Feign 调通 wallet-service / bridge 全部端点。
4. ✅ wallet-service 通过 Feign 调通 signing-service（提现执行链路）。
5. ✅ `nexus-exchange-wallet` 从 settings.gradle 移除，源码归档至 `archived/`。
6. ✅ `ExchangeWalletClient` 兼容层删除。
7. ✅ `gradle.bat build -x test` 全量编译通过。
8. ✅ 端到端测试：支付 → 风控 → 合规 → 链上结算（gateway → signing-service → chain）全链路通。

---

**文档结束**

> 本方案基于 2026-08-06 代码快照设计，后续若 exchange-wallet 包结构变化需同步更新。
> 实施时建议按任务清单（第6章）逐任务推进，每个任务完成后更新本文档的验收状态。