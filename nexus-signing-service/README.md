# nexus-signing-service

> **P2 方向5「签名服务独立部署 PoC」新建模块骨架**
>
> 当前阶段为 **PoC（概念验证）**，仅定义服务边界与接口骨架，不迁移实现代码。

## 定位

未来从 `nexus-exchange-wallet` 拆分出的**签名服务**独立部署单元，承载以下职责：

- **平台热钱包签名**：`PlatformKeystore` 接口（原 `org.nexus.wallet.signing.keystore.PlatformKeystore`）
- **MPC 多签流程**：`MpcService` 接口（原 `org.nexus.wallet.signing.mpc.*` 子包）
  - `barrier` / `persistence` / `router` / `security` / `transport` / `wal`
  - `ColdWalletMultiSigService` / `MpcSigner` / `MpcSignatureAggregator` / `MpcSigningSession`
  - `MpcKeyGeneration` / `MpcKeyShare` / `MpcParticipant` / `ThresholdPolicy`
- **链上交易构造 + 广播 REST 端点**：`TxController`（原 `org.nexus.wallet.signing.controller.TxController`）
  - `POST /ClientToTransferAccount`（legacy form-POST）
  - `POST /api/v1/transfers/sign`（平台密钥库签名 + 广播）
  - `GET /api/v1/signing/health`（健康检查，本骨架已实现）
  - `GET /api/v1/signing/capability`（签名能力查询，本骨架已实现）

## 当前状态（PoC）

| 子项 | 状态 | 说明 |
|------|------|------|
| 模块骨架（build.gradle + settings.gradle） | ✅ 已完成 | composite build 接入根构建 |
| `PlatformKeystore` 接口 + `DefaultPlatformKeystore` 骨架实现 | ✅ 已完成 | 默认实现返回未加载状态 |
| `MpcService` 接口 + `DefaultMpcService` 骨架实现 | ✅ 已完成 | 默认实现返回固定 3-of-5 阈值 |
| `TxController` REST 端点骨架 | ✅ 已完成 | 仅暴露 health / capability 端点 |
| `SigningServiceApplication` 启动类 | ✅ 已完成 | 验证模块可独立编译装配 |
| mpc 子包实现迁移 | ⏳ 未迁移 | 仍由 exchange-wallet 进程内提供 |
| TxController 签名 + 广播逻辑迁移 | ⏳ 未迁移 | 仍由 exchange-wallet 进程内提供 |

## 迁移计划（未来完整阶段）

1. **第二阶段**：将 `org.nexus.wallet.signing.mpc.*` 全部子包代码迁入本模块
   `org.nexus.signing.mpc.*`，更新 import 引用
2. **第三阶段**：将 `TxController` 签名 + 广播逻辑迁入本模块，保留 exchange-wallet
   端的薄壳控制器委托给本模块（或直接删除 exchange-wallet 端控制器）
3. **第四阶段**：启用独立 Spring Boot 打包（`bootJar.enabled = true`），
   通过 Nacos 服务发现注册，gateway 切换为 HTTP 调用 `SigningServiceClient`

## 与其他模块的关系

- **依赖**：`nexus-sdk`（共享 DTO：`ApprovalPolicy` / `WalletTransactionRequest` / `WalletTransactionResult`）
- **被消费**：`nexus-gateway`（经 composite build 依赖替换，通过 `SigningServiceClient` 接口调用）
- **进程内调用**：当前仍委托 `nexus-exchange-wallet` 的 signing 子包，未来切换为 HTTP

## 编译

```bash
gradle.bat :nexus-signing-service:build -x test
```

或在根目录执行全量构建：

```bash
gradle.bat build -x test
```