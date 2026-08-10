# nexus-wallet-service

> **P2 方向5「签名服务独立部署 PoC」新建模块骨架**
>
> 当前阶段为 **PoC（概念验证）**，仅定义服务边界与接口骨架，不迁移实现代码。

## 定位

未来从 `nexus-exchange-wallet` 拆分出的**钱包管理服务**独立部署单元，承载以下职责：

- **提现审批流程**（`approval/*`）
  - `ApprovalPolicy`（已迁至 `nexus-sdk`）
  - `WithdrawalApprovalService` / `DefaultWithdrawalApprovalService` / `WithdrawalRequest`
- **钱包托管策略**（`custody/*`）：`CustodyService` / `CustodyPolicy` / `WalletTier`
- **地址白名单**（`whitelist/*`）：`AddressWhitelistService` / `WhitelistEntry`
- **Nonce 池管理**（`pool/*`）：`NoncePool` / `NonceState` / `PoolTask`
- **链上执行通道**（`execution/*`）：`OnChainExecutionClient` / `HttpOnChainExecutionClient`
  - 引用的 `WalletTransactionRequest` / `WalletTransactionResult` 已迁至 `nexus-sdk`
- **钱包管理 REST 端点**：`WalletController`（原 `NodeController` + `WalletController`）
  - `GET /api/v1/wallet/health`（健康检查，本骨架已实现）
  - `GET /api/v1/wallet/whitelist/check`（白名单查询，本骨架已实现）
  - `GET /api/v1/wallet/custody`（托管查询，本骨架已实现）

## 包名说明

本模块使用 `org.nexus.walletsvc.*` 包名而非 `org.nexus.wallet.*`，以避免与现有
exchange-wallet 的 `org.nexus.wallet.*` 包冲突，便于迁移过程中两套代码并存。

## 当前状态（PoC）

| 子项 | 状态 | 说明 |
|------|------|------|
| 模块骨架（build.gradle + settings.gradle） | ✅ 已完成 | composite build 接入根构建 |
| `WithdrawalApprovalService` 接口 + 骨架实现 | ✅ 已完成 | 默认实现返回 PENDING 请求 |
| `CustodyService` 接口 + 骨架实现 | ✅ 已完成 | 默认实现返回 HOT 托管 |
| `AddressWhitelistService` 接口 + 骨架实现 | ✅ 已完成 | 默认实现使用内存 Set |
| `WalletController` REST 端点骨架 | ✅ 已完成 | 暴露 health / whitelist / custody 端点 |
| `WalletServiceApplication` 启动类 | ✅ 已完成 | 验证模块可独立编译装配 |
| approval / custody / whitelist / pool / execution 实现迁移 | ⏳ 未迁移 | 仍由 exchange-wallet 进程内提供 |

## 迁移计划（未来完整阶段）

1. **第二阶段**：将 `org.nexus.wallet.wallet.approval.*` 迁入本模块
   `org.nexus.walletsvc.approval.*`，更新 import 引用
2. **第三阶段**：将 `custody` / `whitelist` / `pool` / `execution` 子包迁入本模块
3. **第四阶段**：将 `NodeController` + `WalletController` 端点迁入本模块
4. **第五阶段**：启用独立 Spring Boot 打包（`bootJar.enabled = true`），
   通过 Nacos 服务发现注册，gateway 切换为 HTTP 调用 `WalletMgmtClient`

## 与其他模块的关系

- **依赖**：`nexus-sdk`（共享 DTO：`ApprovalPolicy` / `WalletTransactionRequest` / `WalletTransactionResult`）
- **被消费**：`nexus-gateway`（经 composite build 依赖替换，通过 `WalletMgmtClient` 接口调用）
- **进程内调用**：当前仍委托 `nexus-exchange-wallet` 的 wallet 子包，未来切换为 HTTP

## 编译

```bash
gradle.bat :nexus-wallet-service:build -x test
```

或在根目录执行全量构建：

```bash
gradle.bat build -x test
```