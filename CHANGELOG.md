# Changelog

本文件记录 NexusChain 各版本的变更。

## [Unreleased]

## [2.5.0] - 2026-08-19

### 第2轮 L2 端到端集成测试 Bug 修复

本次发布修复 L2→L1 端到端集成测试（`L2L1EndToEndTest`）中发现的 5 个 Bug，使全部 6 个 L2 集成测试通过。所有改动均通过全量编译验证（BUILD SUCCESSFUL）。

#### Fixed（Bug 修复）

##### Bug 1：Web3jL1ContractClient.submitStateRootToL1 参数顺序错误
- **问题**：Java 侧编码顺序为 `(uint256 batchId, bytes32 stateRoot)`，但 `L2Bridge.sol` 合约定义为 `submitStateRoot(bytes32 stateRoot, uint256 batchId)`，参数顺序相反导致 function selector 不匹配
- **修复**：调换 `Bytes32` 与 `Uint256` 的顺序，与合约保持一致

##### Bug 2：Web3jL1ContractClient.challengeBatchOnL1 参数类型错误
- **问题**：Java 侧使用 `DynamicBytes(proofData)`（对应 Solidity `bytes`），但 `L2Bridge.sol` 的 `challengeBatch` 参数为 `bytes32[] calldata proof`，导致 function selector 不匹配
- **修复**：将 `byte[]` 按 32 字节分块转换为 `DynamicArray<Bytes32>`，每 32 字节为一个 `Bytes32` 元素，不足 32 字节右侧零填充

##### Bug 3：L2L1EndToEndTest 布尔返回值解析错误
- **问题**：`callIsBatchVerified` / `callIsBatchChallenged` / `callIsWithdrawsFinalized` / `callIsWithdrawalFinalized` 方法直接检查 eth_call 返回值是否等于 `"0x1"`，但 eth_call 返回 32 字节 ABI 编码的 bool（如 `0x000...001` 表示 true），导致总是返回 false
- **修复**：新增 `decodeBoolResult` 方法，提取 32 字节返回值的最后一位 hex 字符判断真假

##### Bug 4：L2L1EndToEndTest 事件过滤器 topic padding 错误
- **问题**：`findEventInRecentBlocks` 方法传入 indexed topic 时未进行 32 字节 padding（如 `"0x3e9"`），但 `EthFilter` 需要 32 字节对齐（`"0x000...3e9"`），导致事件无法匹配
- **修复**：新增 `padTopicTo32Bytes` 方法，将 topic 左侧补零到 64 hex 字符（32 字节）

##### Bug 5：MerkleProofBuilder.hashLeaf ABI 编码不一致
- **问题**：Java 侧使用 `FunctionEncoder.encode` 编码 `(token, recipient, amount, index)` 后去掉 selector 作为 ABI 编码，但与 Solidity `abi.encode(token, recipient, amount, index)` 存在微妙差异，导致 Merkle proof 验证失败（合约 revert "L2Bridge: invalid withdrawal proof"）
- **修复**：改为手动构造 128 字节 ABI 编码（address 右对齐到 32 字节 + uint256 大端 32 字节），新增 `bigIntegerTo32Bytes` 辅助方法，确保与 Solidity `abi.encode` 完全一致

#### Tests（测试验证）

- L2 集成测试：6 / 6 全部通过（此前 5 通过 1 失败）
  - `testSubmitStateRoot` ✅
  - `testMarkBatchVerified` ✅
  - `testFinalizeWithdraws` ✅
  - `testChallengeBatch` ✅
  - `testFraudProofChallenge_InvalidStateRoot_ChallengedAndInvalid` ✅
  - `testSubmitWithdrawalsAndFinalizeWithProof` ✅（本次修复）
- 全量编译：BUILD SUCCESSFUL（10 个模块）

#### Changed（修改文件）

- `nexus-core/nexus-core/src/main/java/org/nexus/l2/Web3jL1ContractClient.java`：修复 `submitStateRootToL1` 参数顺序 + `challengeBatchOnL1` 参数类型
- `nexus-core/nexus-core/src/test/java/org/nexus/l2/integration/L2L1EndToEndTest.java`：修复布尔返回值解析 + 事件 topic padding + 重构为基于 `AbstractHardhatIntegrationTest`
- `nexus-core/nexus-core/src/test/java/org/nexus/l2/integration/MerkleProofBuilder.java`：`hashLeaf` 改用手动 ABI 编码

## [2.4.0] - 2026-08-18

### 第1轮生产就绪改造（v2.3.0 遗留集成 + 覆盖率提升）

本次发布聚焦 v2.3.0 合约层产物的 Java 侧集成，以及 p2p / MPC crypto 两个关键模块的单元测试覆盖率提升。所有改动均通过全量编译与单元测试验证（Task 236）。

#### Added（新增文件与功能）

##### Task 231：GovernanceExecutor 集成 OnChainGovernanceClient
- `GovernanceExecutor`：注入可选 `OnChainGovernanceClient`（`@Autowired(required=false)`），`schedule()` / `execute()` / `cancel()` 增加链上同步调用，保留内存版 `TimelockController` 作为 fallback
- `application.yml`：新增 `nexus.governance.on-chain.enabled` 配置示例（默认 `false`）

##### Task 232：Web3jL1ContractClient 集成 L2Bridge 新 ABI
- 新增 `nexus-core/nexus-core/src/main/java/org/nexus/l2/abi/Withdrawal.java`：继承 `StaticStruct`，映射 Solidity `Withdrawal` 结构体（token / recipient / amount）
- `Web3jL1ContractClient`：新增 `submitWithdrawalsToL1(long batchId, List<Withdrawal> withdrawals, String withdrawalRoot)` 方法，使用 `DynamicArray<Withdrawal>` 编码动态结构体数组；旧方法标记 `@Deprecated`

##### Task 233：BridgeHandler 集成 Bridge 合约
- `AbstractBridgeHandler`：新增 `Credentials` / `KeyVault` / `evmChainId` / `gasPrice` / `gasLimit` 字段；新增 `sendRawTransaction` / `getNonce` / `estimateGas` 方法；`submitContractCall` 改为优先真实交易，fallback 到合成哈希；新增 `toBytes(DynamicBytes)` 和 `signBridgeMessage` 辅助方法
- `EthereumBridgeHandler`：修正 lock / unlock / mint / burn 的 ABI 编码参数列表，对齐 `BridgeSource.sol` / `BridgeTarget.sol` 签名；新增支持 `Credentials` / `KeyVault` 的构造函数

##### Task 234：p2p 包单元测试
- 新增 8 个测试类 + 1 个工具类（`nexus-core/nexus-core/src/test/java/org/nexus/p2p/`）：
  - `PeerTestFixture.java`（工具类）
  - `PeersCacheTest.java`、`PeersCacheWrapperTest.java`、`PayloadTest.java`
  - `UtilTest.java`、`GRPCClientTest.java`、`PeersManagerTest.java`
  - `PeerServerTest.java`、`MerkleHandlerTest.java`
- 154 个测试全部通过

##### Task 235：MPC gRPC 覆盖率测试
- 新增 2 个测试文件（`nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/`）：
  - `MockMpcCryptoStubFactory.java`（工具类）
  - `GrpcMpcCryptoEngineTest.java`（36 个测试，6 个内部类：`DkgTests` / `SignTests` / `AggregateTests` / `HealthCheckTests` / `RequireStubTests` / `ShutdownTests`）
- `GrpcMpcCryptoEngine` 覆盖率 0% → 87.5%

##### 文档
- 新增 `docs/verification-round1-report.md`：第1轮测试验证报告
- 新增 `coverage-plan-p2p-grpc.md`：p2p / gRPC 覆盖率计划

#### Changed（修改文件与功能）

- `nexus-core/nexus-core/build.gradle`：添加 `jacocoTestReport` 配置，排除 protobuf 生成代码（`NexusChainOuterClass` / `NexusChainGrpc`）
- `nexus-signing-service/build.gradle`：添加 `jacocoTestReport` 配置，排除 crypto / grpc 生成代码；添加 `mockito-inline` 依赖

#### Tests（测试与覆盖率）

- p2p 包：154 个测试全部通过（新增 8 个测试类）
- MPC crypto 包：81 个测试全部通过（含新增 36 个）
- 全量编译：BUILD SUCCESSFUL（10 个模块）

#### 覆盖率提升

| 模块 | 改造前 | 改造后 |
|------|--------|--------|
| GrpcMpcCryptoEngine | 0% | 87.5% |
| p2p 包 | — | 154 测试覆盖 |

### 验证结果（Task 236）

- Java 全量编译：BUILD SUCCESSFUL in 31s，10 个模块全部构建成功
- p2p 包单元测试：154 / 154 通过，0 失败
- MPC crypto 包单元测试：81 / 81 通过（含 Task 235 新增 36 个），0 失败
- 验证报告：`docs/verification-round1-report.md`

## [2.3.0] - 2026-08-18

### 弥补4个差距领域

#### 跨链桥链上合约
- 新增 BridgeSource.sol：源链lock/unlock+ERC20+签名验证+幂等
- 新增 BridgeTarget.sol：目标链mint/burn+ERC20+签名验证+幂等
- 新增 ERC20Mock.sol：测试用ERC20
- 新增 deploy-bridge.js：部署脚本
- 新增 bridge.test.js：22个测试全通过

#### L2合约增强
- 增强 L2Bridge.sol为生产级：Merkle验证+挑战期时间锁+Sequencer签名验证(EIP-712)+ERC20提款+罚没机制
- 更新 l2bridge.test.js：41个测试全通过（向后兼容）

#### MPC实战验证
- 新增 start-mpc-cluster.sh：3节点启动脚本
- 新增 generate-certs.sh：mTLS证书生成脚本
- 新增 integration_test.rs：5个集成测试（标注需Linux环境）
- 新增 node1/2/3.toml：节点配置模板
- 新增 tests/README.md：运行说明

#### 治理链上合约
- 新增 NexusGovernor.sol：提案/投票/执行/quorum/timelock
- 新增 TimelockController.sol：延迟执行+紧急回滚
- 新增 GovernanceTargetMock.sol：测试目标合约
- 新增 deploy-governance.js：部署脚本
- 新增 governance.test.js：27个测试全通过
- 新增 OnChainGovernanceClient.java：Java侧链上治理客户端
- 提取合约ABI到Java资源目录

### 验证结果
- Hardhat合约测试：90个通过，0失败
- Java全量编译：BUILD SUCCESSFUL

## [2.2.3] - 2026-08-18

### Performance
- BLS aggregate性能优化：循环内不再重复normalize，仅最后做一次
- BLS hashToScalar用ThreadLocal缓存MessageDigest

### Security
- BLS verify校验message null/空
- BLS aggregate限制签名列表大小(max 1024)
- BLS privateKey字段加transient防序列化泄露
- BLS getPoint改private，新增multiply方法封装
- AuditLogService链式哈希防篡改
- AuditLogService X-Forwarded-For仅信任可信代理IP
- 鉴权失败日志防用户枚举
- MPC SessionManager过期清理+数量上限
- MPC storage_key密钥轮换(版本号)+KMS/环境变量支持
- MPC AuthInterceptor token常量时间比较
- MPC persistence.rs session文件0600权限

### Improved
- SagaInstance加@Version乐观锁
- BridgeSaga recoverIncompleteSagas加ShedLock分布式锁
- BridgeSaga retryFailedSagas指数退避
- SagaState加CANCELLED终态
- IdempotencyKey.result改@Lob
- ThreePhaseExecutionTemplate阶段2超时机制
- CompensationService.handlePendingRefunds加batchSize
- CI ci.yml Rust tests移除continue-on-error+Gradle wrapper校验
- CI security-scan.yml扩展镜像扫描+Trivy action钉sha
- CI release.yml提取对应版本段落
- SDK Go/Python/TypeScript Wallet.Create等标注未实现
- SDK API.md文档更新+Java测试用例修正

## [2.2.2] - 2026-08-18

### Security
- BLS签名rogue-key attack防护：新增aggregateWithCoefficients方法，基于coefficients-based aggregation
- BLS hashToScalar添加域分离因子(DST)："NEXUS_BLS_V1"前缀防止哈希碰撞
- BLS Secp256k1BlsSigner构造函数校验privateKey ∈ [1, N-1]，拒绝零私钥

### Fixed
- Java SDK RpcClient方法名同步：nexus_blockNumber→nexus_getLatestBlocks, nexus_getBlockByNumber→nexus_getBlockByHeight, nexus_chainId→nexus_getNodeStatus
- Java SDK TransactionBuilder.getGasPrice使用nexus_getNodeStatus兜底+默认1gwei
- CompensationService WITHDRAWAL/SETTLEMENT补偿实现：通过Feign调用wallet-service补偿端点 + settlement batch状态回滚

### Improved
- SigningApprovalService过期清理：@Scheduled两阶段清理（标记EXPIRED+释放内存）
- SigningApprovalService审批人白名单：nexus.approval.approver-whitelist配置
- SigningApprovalService多实例支持预留：nexus.approval.use-database开关
- ReconciliationTask分布式锁：ShedLock 5.10.0，防止多实例重复执行对账任务

## [2.2.1] - 2026-08-18

### Fixed
- P2-F4 BLS验签完整接入：Vote.java添加getPublicKeyBytes()方法，SignatureAggregator.verifyAggregate()接入Secp256k1BlsSignature验签，消除TODO占位
- JaCoCo覆盖率验证配置修复：jacocoTestCoverageVerification添加onlyIf条件，build -x test不再触发覆盖率验证
- Rust构建环境要求标注：README.md新增mpc-engine构建环境说明（MinGW/MSVC工具链）

### Documentation
- 新增可选改进建议清单：.codeartsdoer/specs/audit-remediation-v2/optional-improvements.md（43项建议，分高/中/低优先级）

## [2.2.0] - 2026-08-18 - Phase 2 根治修复（7 项架构级问题）

本次发布聚焦 Phase 2 审计根治修复，覆盖 BLS 验签、CI/CD 增强、文档/SDK/UI、签名安全架构、桥 Saga 幂等、事务补偿、MPC 分布式安全共 7 项架构级问题。

### Security（P2-F4 BLS 验签真实化）
- **P2-F4 BLS-like 签名验证实现**：用 BouncyCastle SECP256K1 曲线实现 BLS-like 签名验证（非真正 BLS12-381 配对，而是 EC 点签名验证）
  - 新增 `Secp256k1BlsSigner`：基于 secp256k1 曲线的签名者实现（私钥 sk、公钥 pk=sk*G、签名 σ=sk*H(m)）
  - 新增 `Secp256k1BlsPublicKey`：公钥压缩编码/解码（EC 点序列化）
  - 新增 `Secp256k1BlsSignature`：签名验证（σ == pk * H(m)）+ EC 点加法聚合
  - `BlsSigner.generate()` / `BlsPublicKey.fromBytesCompressed()` 接口从 `UnsupportedOperationException` 改为调用 secp256k1 实现
  - `SignatureAggregator.CollectingAggregator.verifyAggregate()`：保留格式校验 + TODO 注释（Vote 模型缺少 getPublicKeyBytes() 方法，待扩展后接入完整 BLS 验签）
  - NOTE: 纯 Java 环境使用 secp256k1 EC 点实现 BLS-like 签名验证。生产环境应接入 blst 原生库做完整 BLS12-381 配对验签。

### Phase 2 修复（P2-D 文档+SDK+UI 整改）

- **P2-D2 SDK 方法名修正**：Go/Python/TypeScript SDK 的 RPC 方法名从 `conpay_*` 统一为 `nexus_*`，
  对齐 nexus-core 实际支持的 15 个 RPC 方法（`nexus_getBalance` / `nexus_getTransactionCount` /
  `nexus_getBlockByHeight` / `nexus_getLatestBlocks` / `nexus_getTransactionByHash` 等）。
  - Go SDK：`conpay_blockNumber`→`nexus_getLatestBlocks`、`conpay_chainId`→`nexus_getNodeStatus`、
    `conpay_getBalance`→`nexus_getBalance`、`conpay_getTransactionCount`→`nexus_getTransactionCount`、
    `conpay_gasPrice`→`nexus_getNodeStatus`（兜底）、`conpay_sendRawTransaction`→`nexus_sendRawTransaction`、
    `conpay_getBlockByNumber`→`nexus_getBlockByHeight`
  - Python SDK：同上映射；`conpay_getBlockByHash` 标记为不支持（nexus-core 无此方法）
  - TypeScript SDK：`nexus_blockNumber`→`nexus_getLatestBlocks`、`nexus_chainId`→`nexus_getNodeStatus`、
    `nexus_getBlockByNumber`→`nexus_getBlockByHeight`、`nexus_gasPrice`→`nexus_getNodeStatus`（兜底）、
    `nexus_getTransactionReceipt`→`nexus_getTransactionByHash`、`nexus_estimateGas` 标记为不支持
  - 包名 `conpay` 保留作为 deprecated 别名，新增 `NexusClient` 兼容别名（Python）
  - nexus-core 不支持的方法（gasPrice/sendRawTransaction/getBlockByHash/estimateGas）保留 SDK 接口，
    加 @deprecated 注释或改为调用最接近的方法
- **P2-D3 UI 认证闭环**：移除 `VITE_NEXUS_API_SECRET` 构建期 env 注入，改为运行时用户输入。
  - 新增 `nexus-explorer/frontend/src/pages/Settings.tsx`：API Key / API Secret 输入框、
    保存按钮调用 `setCredentials`、从 localStorage 读取已保存凭证
  - `AuthContext.tsx`：移除 `ENV_API_SECRET`，Secret 仅从 localStorage（XOR+base64 编码）读取
  - `App.tsx` 新增 `/settings` 路由；`HomePage.tsx` 导航栏添加 Settings 链接
  - TypeScript 编译验证通过（`npx tsc --noEmit` 零错误）
- **P2-D4 ADR 更新**：ADR-020 状态改为 `Superseded by ADR-032`；
  新建 ADR-032（Spring Boot 3.2.5 统一决策），记录 javax→jakarta 迁移完成、
  SCA 2023.0.1.0 合规要求、所有 Java 微服务统一 Boot 3.2.5；
  补全 ADR-021~025、ADR-028 编号断档说明
- **P2-D5 版本治理整改**：合并两个 `[Unreleased]` 节、版本号规范化、
  README 版本声明与 CHANGELOG 对齐

### Security
- P0(wallet-service): 资金路径 fail-closed —— 消除伪造 `SIMULATED-` 交易哈希（59cb5e1）
  - `DefaultCustodyService.executeOnChainTransfer`：链上执行通道缺失/失败时抛异常触发事务回滚，余额不落库，杜绝"账上有、链上无"
  - `DefaultWithdrawalApprovalService`：签名服务客户端缺失时提币标记 `FAILED`（fail-closed），不把未上链提币记为 `EXECUTED`
  - `HttpOnChainExecutionClient`：非沙箱模式 gateway 不可达返回 `FAILED`，不再静默降级为沙箱假哈希；沙箱降级仅限 `nexus.wallet.execution.sandbox=true`
- 测试：wallet-service 全量 185 测试通过；新增 fail-closed 专项断言，集成测试 `application-test.yml` 显式开 sandbox

### Changed
- fix(core): P2P wire 枚举同步支付扩展类型 —— `NexusChainOuterClass.TransactionType` 补齐 16-26
  - 手工提交的生成类仅含 0-15；P2P 同步路径 `sync/Utils` 以 `forNumber(tx.type)` 映射域 ordinal，
    缺失导致批量转账/支付通道/稳定币/跨链桥等交易在同步序列化时 `forNumber` 返回 null
  - 新增：CHANNEL_OPEN=16 / CHANNEL_UPDATE=17 / CHANNEL_CLOSE=18 / BATCH_TRANSFER=19 /
    MINT_STABLECOIN=20 / REDEEM_STABLECOIN=21 / BRIDGE_LOCK=22 / BRIDGE_MINT=23 /
    BRIDGE_BURN=24 / IDENTITY_REGISTER=25 / SUBSCRIPTION_AUTH=26（与 `Transaction.Type` ordinal 对齐）
  - 注：`ProtocolModel.Transaction.Type`（TCP legacy 路径）是协议消息类型枚举（仅 5 值），非交易分类枚举，
    不参与同步；其 `encode()` 直接 `forNumber(域 ordinal)` 属遗留怪癖（仅 0-4 可往返），不在本次范围
- refactor(oracle): 治理执行 `@Async`+`@Transactional` 混用改为细粒度事务（032a404）
  - 移除 `GovernanceExecutionDispatcher` 方法级 `@Transactional`（异步线程事务上下文错位，国库转账异常可能无法回滚）
  - `SoftwareUpgradeExecutor.execute` / `TreasurySpendExecutor.execute` 加 `@Transactional`，执行期真正持事务边界
- refactor(tracing): 三份逐字节一致的 `BusinessSpan` 合并到新建 `nexus-common` 共享模块
  - 包名统一 `org.nexus.common.tracing`，gateway/bridge/signing-service 7 处 import 迁移
  - 消除跨模块拷贝漂移风险；gateway 709 + bridge 525 + signing 467 测试全绿
- feat(gateway): 支付最终性三层状态模型（NexFinality 网关侧原型）
  - `FinalityStatus`（OPTIMISTIC/FINALIZING/FINALIZED/UNKNOWN）+ `FinalityService`（确认数→最终化推导，阈值 `nexus.finality.blocks-to-finalize` 可配，默认 12）
  - `OrderV2Controller` 新增 `GET /{id}/finality` 端点 + 查询响应默认叠加 `finality` 字段（含 progress_percent 实时进度）
  - 8 个 `FinalityServiceTest` 用例全绿（阈值边界/链不可达/未入块/自定义阈值）
- feat(oracle): 治理执行 `@Async`+`@Transactional` 混用改为细粒度事务

### Dev（真机联调基础设施）
- `docker-compose.yml` 新增 `nexus-pgsql` 服务：postgres:16-alpine，127.0.0.1:55432→5432（仅回环），
  nexus/nexus123/nexuschain，命名卷 `pg-dev-data`（与 prod 的 `pgdata` 隔离），pg_isready healthcheck
  - 解决：core 持久化层绑定 Postgres 方言，但 dev compose 原先无 pg 服务，真机联调只能手工
    `docker run` 起库（无 healthcheck、匿名卷、绑 0.0.0.0）
- 新增 `scripts/dev-pg-up.ps1`（Windows）/ `scripts/dev-pg-up.sh`（Linux/CI）：幂等保证 55432 上有健康 PG
  - Docker 引擎不可达时自动拉起 Docker Desktop 并轮询就绪（最长 180s）
  - 已有健康 PG 容器则复用（不破坏现场），端口空闲才经 compose 创建 `nexus-pgsql`
  - `-StartCore` / `START_CORE=1`：PG 就绪后前台起 core（`--spring.profiles.active=local`）
- 新增 `scripts/dev-pg-down.ps1`：仅停 compose 管理的 `nexus-pgsql`，数据卷保留；不触碰手工容器
- `nexus-core/nexus-core/src/main/resources/application-local.properties` 入库并更新头部说明（指向脚本与 compose 服务）

### Documentation
- ADR-029：PoS 共识现状审计基线（实证出块/验签/罚没/同步已闭环，纠正 README 过时表述）
- ADR-030：NexFinality 创意共识规格（BFT 投票 + BLS 聚合 + 双层确认 + 三条连接轴，零自研密码学纪律）
- README 一致性修正：模块表 nexus-core（PoS 基础层已闭环，最终性层在研）/ nexus-bridge（Solana、Avalanche 适配器已交付）
  PoS 节改写为 ADR-029 结论；新增「本地联调（容器化 Postgres + 原生 core）」小节
- docs/v2.0.0-roadmap.md §7.2 诚实化：v2.0.0 GA 从未发布（rc1 → v2.1.0），清单为历史快照；
  「Phase 1-5 退出条件全部满足」「SDK v2 发布 Maven Central」勾选与事实不符，改为未勾选并注明
- docs/真机构建联调清单.md 第 4 章补充双姿态说明（全容器联调 vs 容器 PG + 原生 core）
- 仓库卫生：清理 nexus-core-local.mv.db / .trace.db（H2 残留，回收站）、根目录 core-start.log / testall-bg.log；
  .gitignore 增补 `*.mv.db` / `*.trace.db` / `.inscode/`；移除未被引用的 nexus-sdk/ts/ 拆留骨架（回收站 + git rm --cached，typescript/ 为正式目录）

### Consensus（多节点共识攻坚：PLAN-001 ~ PLAN-013b 全链路）

- **多节点共享单链 + NexFinality 最终性全链路真机闭环**
  - PLAN-001 验证人同步（P2P 广播 + 落库重放 + 多次重发）
  - PLAN-002 出块抑制（落后对端停出）+ PLAN-003 分叉重组（ReorgManager + 最终化护栏）
  - PLAN-005 区块落 PG（leastConfirms PoS 适配）+ PLAN-006 启动继承共享链
  - PLAN-007 单 proposer 协调（round-robin 地址排序确定性）
  - PLAN-008 引擎密钥 Spring 注入 + PLAN-010 最小验证人集合门槛
  - **PLAN-013b 共享 PG 幂等写（ON CONFLICT）——双节点交替出块 51/52 + epoch 最终化 100%**
  - 真机验证：A 奇数块/B 偶数块交替、区块双向传播、状态对账（MerkleHandler）
- 回退修复：`ON CONFLICT DO NOTHING` 无列名（H2/PG 方言兼容，收尾回归捕获）

### MPC 多进程分布（长期项 #7）

- mpc-engine Rust 编译验证（Docker 方案，GG20 门限 ECDSA 端到端通过）
- 引擎份额持久化（DKG 会话 JSON 落盘/恢复）
- 跨进程端到端验收：3 参与者 t=2 门限签名（Java gRPC ↔ Rust 引擎）
- 多引擎 HA 部署脚本 + 启动级份额门限校验（fail-closed）

### ZK 真实 Groth16（长期项，方案 C 全链路）

- zk-groth16-service：Rust arkworks 真实 BN254 配对验证服务（gRPC + HTTP）
- Java 对接：Groth16ProofSystem.verifyRemote（fail-closed）+ R1csToJsonBridge
- 生产电路接入：Rollup 状态转换电路（真实约束 C1-C5）端到端真实验证
- **setup 持久化**：电路指纹确定性 setup + 幂等 + 0700 权限 + prove/verify 分离模式

### 基础设施

- testAll 首次全绿（L2 Hardhat 并行冲突修复 maxParallelForks=1）
- consortium 测试环境修复（H2 内存库 + consensus=none + BC 1.78 兼容）
- 新增脚本：build-mpc-engine.sh / deploy-mpc-engine.sh / dev-cluster-up.sh / dev-cluster-verify.sh

## [2.1.0] - 2026-08-10

### Security
- P0: 修复 8 项关键安全发现 (commit 203224d)
- P0: Keystore 文件从 git 移除 (commit b0c32f1)
- P1/P2: 修复 17 项安全发现 - governance/MPC/ZK (commit 9fc0aab)

### Changed
- 统一所有模块版本号到 2.1.0
- JaCoCo 覆盖率门禁推广到所有核心模块
- JUnit 4→5 迁移 (signing-service)

### Documentation
- AI 路由引擎降级表述为启发式路由
- CQRS 降级表述为事件溯源+投影读写分离
- 添加跳过测试文档

### v2.1.0 — MPC 端到端测试启用 + 文档更新
- MPC E2E 测试：3/3 通过（DKG→Sign→Aggregate→ECDSA Verify）
- Rust mpc-engine 编译验证成功（rustc 1.97.1 + gcc 16.1.0 MinGW64）
- 修复 session_id 不一致（DKG/Sign/Aggregate 共用同一 session_id）
- 修复阈值参数（t=3→t=2，Rust 引擎要求 threshold < total_parties）
- 修复签名方数（t→t+1，GG20 协议要求 signer_count > threshold）
- 修复 aggregate.rs message_bn 一致性（使用 message_hash_to_bigint）
- 修复 Java 端 verifyEcdsaSignature（z=SHA256(hash) 与 Rust 端一致）
- 添加 .cargo/config.toml（GNU linker 持久化配置）
- 添加 start-engine.bat（后台启动脚本）
- 更新 skipped-tests.md（MPC 测试已启用，跳过数 12→9）
- Keystore 钱包文件从 git 历史中完全移除（仓库重建）

## [2.0.0-rc1] - 2026-08-10 - Phase 5 真实化改造 + 安全审计

> **候选版本**：v2.0.0-rc1（Release Candidate 1）。Phase 5 完成研究层（MPC/ZK/L2/治理）真实化改造与安全审计，ADR-001 状态更新为 Resolved。当前存在 8 项 P0 级安全缺陷（详见 [安全审计报告](docs/audit/v2.0.0-rc1-security-audit.md)），均未修复但已诚实声明，建议以候选版本发布，P0 修复后发布 v2.1.0。

### Phase 5 任务完成情况

| 任务 | 名称 | 状态 |
|------|------|------|
| P5-T1/T2 | Rust mpc-engine 真实 GG20 DKG/Sign/Aggregate | ✅ 代码完成（未编译验证） |
| P5-T3 | Java MPC 传输层真实化（gRPC） | ✅ 完成 |
| P5-T4/T5 | ZK 证明系统 Groth16（R1CS + Schnorr） | ✅ 完成（halo2 FROZEN 降级） |
| P5-T6 | L2 L1 真实节点测试环境（Hardhat） | ✅ 完成（EDR 兼容性跳过） |
| P5-T7 | 治理执行接线 | ✅ 完成 |
| P5-T8 | 安全审计 | ✅ 完成（8 P0 / 8 P1 / 15 P2） |
| P5-T9 | ADR-001 更新 + README + CHANGELOG | ✅ 完成 |

### 新增功能

#### MPC 引擎（Rust mpc-engine）

- **真实 GG20 门限 ECDSA**：接入 ZenGo-X/KZen `multi-party-ecdsa` 0.8.1 crate
  - `src/gg20.rs`：完整 4 轮 DKG + 7 轮 Sign 协议（真实 Paillier、Feldman VSS、MtA、ZK 证明）
  - `src/dkg.rs` / `src/sign.rs` / `src/aggregate.rs`：基于真实 GG20 的 RPC 实现
  - 端到端测试：t=1, n=3，签名可被标准 secp256k1 验证
- **依赖**：`multi-party-ecdsa = "0.8.1"`、`curv-kzen = "0.9"`、`paillier = "0.4.2"`、`zk-paillier = "0.4.3"`、`secp256k1 = "0.20"`

#### Java MPC 传输层（nexus-signing-service）

- **`GrpcMpcTransportStub`**：gRPC over HTTP/2 传输层实现
  - 真实 gRPC channel 管理（`ManagedChannelBuilder`）
  - 阻塞 stub + deadline 超时 + 重试（`maxRetryAttempts=3`）
  - 本地邮箱模型，支持 P2P 消息路由
- **`MpcTransportGrpcServer`**：gRPC 服务端，接收其他参与方消息
- **`MpcTransportConfig`**：Spring 配置，根据 `mpc.transport.real-grpc-enabled` 选择实现
- **`GrpcMpcCryptoEngine`**：gRPC 客户端，连接 Rust mpc-engine 进程

#### ZK 证明系统（nexus-core）

- **`Groth16ProofSystem`**：基于 BouncyCastle 椭圆曲线的 Groth16 简化实现
  - 真实 R1CS 约束系统（`R1csConstraintSystem`）
  - Schnorr 知识证明协议 + Fiat-Shamir 变换
  - setup/prove/verify 三阶段完整实现
- **`RollupStateTransitionCircuit`**：Rollup 状态转换电路，定义 R1CS 约束
- **`DefaultZkProofSystem`**：@Primary，配置选择后端（groth16|plonk|halo2|mock）
- halo2 标记为 FROZEN，降级为 Groth16（实际 Schnorr）

#### L2 L1 真实节点测试环境（nexus-core）

- Hardhat L1 测试环境配置
- `L2Bridge.sol`：Solidity 合约实现
- `L2L1EndToEndTest`：端到端测试（因 Hardhat EDR 不兼容跳过）

#### 治理执行（nexus-oracle）

- **`SoftwareUpgradeExecutor`**：软件升级执行器
  - 解析 payload、记录审计、发布事件、回写状态
  - 支持目标：gateway / bridge / signing / wallet
- **`TreasurySpendExecutor`**：国库转账执行器
  - 校验余额、执行转账、记录审计、发布事件
- **`GovernanceExecutionDispatcher`**：调度器
  - 监听 `ProposalStatusChangedEvent`，按提案类型分发
  - 支持 `@Async` 异步执行 + `@Transactional` 事务一致性
- **`GovernanceAuditLog`**：治理审计日志（内存存储）

#### 安全审计与文档

- **安全审计报告**：`docs/audit/v2.0.0-rc1-security-audit.md`
  - 审计范围：MPC 引擎、Java MPC 传输层、ZK 证明系统、治理执行
  - 审计发现：8 P0 / 8 P1 / 15 P2
  - 审计结论：条件性可发布，P0 列入 v2.1.0 修复

### 破坏性变更

- **ADR-001 状态变更**：`Accepted` → `Resolved`，研究层从「冻结」变为「条件解冻」
- **README 成熟度声明**：移除 MPC/ZK/L2 的骨架/模拟标注，更新为真实实现声明（含限制）
- **MPC 引擎依赖**：`mpc-engine/Cargo.toml` 新增 GPL-3.0 依赖（`multi-party-ecdsa`），进程隔离避免传染
- **ZK 后端配置**：`zk.prover.backend=halo2` 现降级为 Groth16（实际 Schnorr）

### 已知限制

1. **Rust 编译待验证**：`mpc-engine` 代码完成但未编译验证（开发环境缺少 C 编译器，Rust 编译需 MSVC/gcc）
2. **Hardhat EDR 兼容性**：L2 L1 端到端测试因 Hardhat EDR 不兼容跳过，未完成真实 L1 节点验证
3. **可信协调器模型**：MPC 引擎全部 n 方私钥份额驻留同一进程，门限容错属性失效
4. **ZK 证明非真实 Groth16**：secp256k1 不支持双线性配对，用 Schnorr 替代，不具备通用电路 ZK 安全属性
5. **gRPC 传输默认明文**：无 mTLS 实现代码，生产环境需显式配置并实现 SslContext
6. **治理执行 P0 缺陷**：事件源无认证、审计日志无持久化、转账哈希用 hashCode()

### 安全审计发现汇总

| 级别 | 数量 | 已修复 | 已声明 | 未修复 |
|------|------|--------|--------|--------|
| P0（严重） | 8 | 0 | 0 | 8 |
| P1（高危） | 8 | 0 | 3 | 5 |
| P2（中危） | 15 | 0 | 0 | 15 |
| **合计** | **31** | **0** | **3** | **28** |

**P0 发现清单**：

- MPC-P0-01：Rust gRPC 服务端未配置 TLS
- MPC-P0-02：Java gRPC 默认明文，无 mTLS 实现
- ZK-P0-01：Schnorr 替代配对，verifier 不验证 R1CS
- ZK-P0-02：toxic waste 未销毁
- ZK-P0-03：R1CS 约束严重不完备
- GOV-P0-01：事件源无认证
- GOV-P0-02：审计日志无持久化
- GOV-P0-03：转账哈希用 hashCode()

### 变更

- ADR-001 状态更新：Accepted → Resolved，记录 Phase 5 解冻过程与安全审计结果
- README「成熟度声明」更新：移除骨架/模拟标注，更新为真实实现声明（含限制）
- `mpc-engine/Cargo.toml`：新增真实密码学依赖
- `nexus-signing-service`：新增 gRPC 传输层实现
- `nexus-core`：ZK 证明系统真实化（R1CS + Schnorr）
- `nexus-oracle`：治理执行接线（Dispatcher + Executors + AuditLog）

### 后续里程碑

- **v2.1.0**：修复 8 项 P0 发现
  - MPC：实现 mTLS、密钥 zeroize
  - ZK：接入真实配对曲线或 halo2、补全 R1CS 约束、销毁 toxic waste
  - GOV：事件鉴权、审计持久化、真实转账哈希
- **v2.2.0**：修复 P1/P2 发现，完成分散式 MPC 部署

---

## [1.9.7] - 2026-08-08

### Changed
- 统一所有模块版本号到 1.9.7（根 build.gradle、nexus-gateway、4 个 composite build 模块）
- ADR 文档目录统一到 docs/adr/（ADR-020 从 docs/decisions/ 迁入）
- README.md ADR-020 引用路径更新

### Added
- 5 个 composite build 模块（consortium/settlement/compliance/analytics/oracle）添加 JaCoCo 插件和 xml 报告配置

## [1.9.5] - 2026-08-08 - P1 架构缺口修复

### 修复
- PoS 出块调度器（PosMiningScheduler，@Scheduled + @ConditionalOnProperty）
- 治理提案执行接线（oracle DefaultGovernanceService 按 type 分发 + GovernableParameterRegistry）
- 状态持久化（ContractStorage/ValidatorRegistry/StakingServiceImpl JSON 快照）
- Fee market 基本实现（EIP-1559 风格估算）

## [1.9.4] - 2026-08-08 - P0 安全修复

### 修复
- 私钥经 HTTP 传输：transfer(含 privateKey) → signTransfer（不传私钥）
- 模拟路径 fail-open：UUID 伪哈希 → return null（fail-closed）
- 跨链桥熔断器：trip/Reset 实现基本逻辑 + CircuitBreakerTrippedEvent
- Fallback 告警确认：9 个 Fallback 类全部已有日志告警

## [1.9.3] - 2026-08-07 - PoS fail-closed 安全加固 + 仓库清理

### 修复
- PosConsensusEngine 验签 fail-closed（三条路径一律 return false）
- signBlock 签名失败 return false（不再写入哈希指纹 fallback）
- 仓库清理 -67520 行（归档目录、测试数据、设计文档）

## [1.9.2] - 2026-08-07 - 诚实化改造

### 变更
- 文档勘误：对 v1.9.0（ZK 证明）与 v1.8.0（MPC 引擎）的成熟度声明补充勘误标注
- README「成熟度声明」明确标注 MPC/ZK/L2/PoS 的真实状态
- 承认"宣称能力 >> 实际能力"的差距，如实标注骨架/模拟/占位实现

## [1.9.1] - 2026-08-07 - 全量测试修复：975/975 全绿

### 修复
- **FallbackFactory 接口→具体类**（根因修复）
  - SigningServiceFallbackFactory/WalletMgmtFallbackFactory/BridgeServiceFallbackFactory 从接口改为具体类
  - Spring Cloud OpenFeign 的 @FeignClient(fallbackFactory=...) 要求具体类（验证时 newInstance() + create()）
  - 4 个消费方实现类 implements→extends
- **nexus-gateway 34 个测试修复**
  - application-sandbox.yml 禁用 Nacos/Sentinel/Seata
  - SandboxKeyManager 加 @Primary（dev+sandbox 双 profile 下 KeyManager Bean 冲突）
  - GatewayCoreIntegrationTest mock 从 ExchangeWalletClient 改为 SigningServiceFeignClient + WalletMgmtFeignClient
  - PaymentServiceTest/ChainConnectorTest 构造器 mock 更新
- **nexus-wallet-service 3 个测试修复**
  - RepositoryIntegrationTest + CustodyServiceIntegrationTest 加 @Transactional（测试间数据库状态隔离）
- **nexus-bridge 8 个测试修复**
  - application-test.yml 禁用 Sentinel/Nacos/Seata

### 验证
- `gradlew.bat test --continue` BUILD SUCCESSFUL，975 tests, 0 failures, 7 skipped

## [1.9.0] - 2026-08-07 - 审计报告第三批：L2 L1真实化 + ZK证明系统

> ⚠️ **勘误（v1.9.2 补充）**：本条目的"Groth16 简化版"**并非真实 Groth16 零知识证明**。真实 Groth16 需要双线性配对曲线（BN254/BLS12-381），而 secp256k1 不支持配对。实际实现为 **Schnorr + Pedersen 承诺模拟**，不具备零知识证明的安全属性，仅可用于逻辑流程验证。真实 ZK 待接入 halo2 / Plonk / gnark。另，"L1 真实化"的 Web3j 客户端默认未启用（内存模拟为默认），且仓库无 Solidity 合约源码。详见 README「成熟度声明」。

### 新增
- **L2 L1 合约客户端真实化**：Web3j L1 合约交互
  - Web3jL1ContractClient：submitStateRoot/markBatchVerified/finalizeWithdraws/challengeBatch 真实 L1 调用
  - @ConditionalOnProperty 切换真实/内存模拟，失败回退内存
- **ZK 证明系统**：Groth16 简化版（BouncyCastle 椭圆曲线）
  - R1CS 约束系统 + RollupStateTransitionCircuit 真实化
  - Groth16ProofSystem：setup/prove/verify 三阶段，Schnorr 协议验证
  - DefaultZkProofSystem：@Primary，配置选择后端（groth16|mock）
  - ZkProverProperties：zk.prover.enabled/backend 配置

### 变更
- nexus-core build.gradle 添加 Web3j 依赖
- ZkCircuit 接口添加 R1CS 方法
- ZkVerifier 支持 Groth16 证明验证

### 验证
- `gradlew.bat build -x test` BUILD SUCCESSFUL in 1m 48s

## [1.8.0] - 2026-08-07 - 审计报告第二批：MPC 密码学引擎接入

> ⚠️ **勘误（v1.9.2 补充）**：本条目的"MPC 密码学引擎接入"**实际为协议框架占位，非真实门限密码学**。Rust `mpc-engine` 的 DKG/Sign/Aggregate 三个入口函数均直接返回 UNIMPLEMENTED（`Cargo.toml` 中 multi-party-ecdsa/tss-lib 依赖被注释）；Java 侧 gRPC 传输层默认回退内存桩（`realGrpcEnabled=false`）；钱包托管的交易哈希为 SIMULATED-UUID 占位。当前**既非真实门限 ECDSA，也非 n-of-n 多签**。涉及资金签名处的真实 n-of-n ECDSA 多签改造与诚实化见 v1.9.2。详见 README「成熟度声明」。

### 新增
- **MpcCryptoEngine SPI**：解耦 Java 编排层与 Rust 密码学引擎
  - gRPC proto（Dkg/Sign/Aggregate/Ping）+ GrpcMpcCryptoEngine 客户端
  - 6 个 DTO（DkgRequest/Response, SignRequest/Response, AggregateRequest/Response）
- **Rust 引擎项目**：mpc-engine/（tonic gRPC 服务端骨架 + Dockerfile + docker-compose）
  - DKG/Sign/Aggregate 模块骨架（待接入 multi-party-ecdsa/tss-lib）
- **DefaultMpcService 三方法实现**：从 TODO stub 改为真实编排
  - generateKeyShare：DKG 编排（创建session→调引擎dkg→存储keyShare）
  - sign：签名编排（加载keyShare→调引擎sign→广播部分签名→barrier同步）
  - aggregateSignature：聚合编排（调引擎aggregate→ECDSA验证→广播最终签名）
- **DefaultMpcServiceTest**：MPC 协议层单元测试

### 变更
- signing-service build.gradle 添加 gRPC + BouncyCastle 依赖
- docker-compose.yml 添加 mpc-engine 服务 + nexus-net 网络

### 验证
- `gradlew.bat build -x test` BUILD SUCCESSFUL
- DefaultMpcServiceTest 4 个测试全部通过（DKG 成功/失败 + 签名广播 + ECDSA 聚合验证）

## [1.7.0] - 2026-08-07 - 审计报告第一批：L2 测试固化 + PoS 出块接线

### 新增
- **L2 Rollup 测试补全**（从 0 到 190 个测试）
  - FraudProofVerifier 单测：Merkle 证明、二分定位、挑战窗口、bond 罚没、first-valid-wins、恶意提交者场景
  - StateRootManager / RollupSequencer / Eip4844BlobCarrier / MerklePatriciaTrie / OptimisticRollup 单测
  - 端到端测试：submit→challenge→rollback→finalize 全流程（含挑战失败罚没 bond、多挑战者冲突、动态挑战期）
- **PoS 共识集成测试**（7 个测试）
  - propose 产出有效区块（高度、coinbase、签名）
  - validate 完整校验链（提案者∈验证人、质押门槛、时间窗口、罚没状态）
  - 共识切换不破坏现有链（dpos|pos 路由）
  - 连续出块（高度递增、prevHash 匹配）

### 变更
- **PoS 出块主链路**：PosConsensusEngine.propose 从返回 null 改为真实出块（选取提案者→打包→构造→签名→广播）
- **PoS validate 完整校验链**：从恒 true 改为 6 步校验（区块完整性→提案者∈验证人→ACTIVE→质押≥门槛→时间窗口→未罚没→签名）
- **共识切换路由**：ConsensusConfig 增加 nexus.consensus.mode=dpos|pos 配置
- **@Primary 地雷移除**：PosConsensusEngine 改为 @ConditionalOnProperty 按配置启用，消除静默 null 注入风险
- **Eip4844BlobCarrier bug 修复**：kzgCommit/kzgProof 的 substring(0,96) 越界改为 substring(0,64)（SHA-256 输出 32 字节）

### 验证
- `gradlew.bat build -x test` BUILD SUCCESSFUL
- L2 测试 190 个全部通过（185 单测 + 5 端到端）
- PoS 集成测试 7 个全部通过
- 全量 test 无回归

## [1.6.0] - 2026-08-07 - Phase 4 微服务化：wallet-service 数据库持久化 + Seata AT 接入

### 新增
- **wallet-service 数据库持久化**：Spring Data JPA + Flyway，替代所有内存存储
  - 4 张业务表：custody_balances / address_whitelist / withdrawal_requests / withdrawal_approvers
  - 4 个 Entity + 4 个 Repository + WithdrawalRequestMapper
  - Flyway migration V1（业务表）+ V2（seed 余额）+ V3（undo_log）
- **Seata AT 接入**：wallet-service 作为 RM 接入分布式事务
  - executeApprovedWithdrawal 标注 @GlobalTransactional + @Transactional
  - undo_log 表自动回滚
- **集成测试**：Repository IT + Service IT + Seata 回滚测试
  - RepositoryIntegrationTest：4 个 Repository CRUD + Flyway V2 seed 验证
  - FlywayMigrationIT：V1/V2/V3 migration 表存在性验证
  - CustodyServiceIntegrationTest：托管余额完整流程
  - WhitelistServiceIntegrationTest：白名单 add → check → remove → check
  - WithdrawalServiceIntegrationTest：提现 request → approve → execute 完整流程
  - WalletControllerIT：REST 端点 MockMvc 集成测试
  - WithdrawalRollbackTest：signing-service 失败时状态回滚验证
  - SeataIntegrationTest：@GlobalTransactional 事务行为验证
- **DefaultApprovalPolicyTest**：12 个新测试用例

### 变更
- DefaultCustodyService：AtomicReference → CustodyBalanceRepository + @Transactional
- DefaultAddressWhitelistService：ConcurrentHashMap → WhitelistEntryRepository + @Transactional
- DefaultWithdrawalApprovalService：ConcurrentHashMap → WithdrawalRequestRepository + @GlobalTransactional
- DefaultApprovalPolicy：CopyOnWriteArraySet → WhitelistEntryRepository 查询（消除双存储）
- 106 个单元测试改造为 Mock Repository
- build.gradle 添加 JPA/Flyway/H2/MySQL 依赖
- application.yml 添加 datasource/jpa/flyway 配置
- application-test.yml 禁用 Nacos/Sentinel/Seata（集成测试 H2 + Flyway）

### 消除
- 所有 ConcurrentHashMap / AtomicReference / CopyOnWriteArraySet 内存存储（grep 验证 0 匹配）

### 验证
- `gradlew.bat build -x test` BUILD SUCCESSFUL
- 118 个单元测试全部通过（原 106 + 新增 12）
- 集成测试编译通过（H2 + Flyway，seata.enabled=false 退化为本地事务）

## [1.5.0] - 2026-08-07 - Phase 3 微服务化：分布式事务+链路追踪+容错

### 新增
- **Seata 分布式事务**：接入 Seata 2.0.0，AT+TCC 混合模式
  - gateway 侧 AT 模式：PaymentServiceImpl.refund + SubscriptionServiceImpl.charge 标注 @GlobalTransactional，undo_log 表自动回滚
  - signing-service 侧 TCC 模式：SigningTccAction（Try 预锁定 nonce → Confirm 签名广播 → Cancel 释放 nonce）
  - Seata Server 独立部署（Nacos 注册/配置，DB 存储事务日志）
- **链路追踪增强**：Micrometer Tracing 1.2.5 + Zipkin Server 3.4
  - 4 服务全部接入自动 traceId 传播（W3C Baggage + B3）
  - 替换手动 TracingConfig filter 为 Spring Boot 自动配置
  - Zipkin UI 可查看跨服务调用链
- **Feign fallback 绑定**：3 个 FallbackFactory 占位接口 + 4 个实现
  - nexus-sdk 定义占位接口（不改 Feign 接口签名）
  - gateway 实现 SigningService/WalletMgmt/BridgeService 3 个 fallback
  - wallet-service 实现 SigningService 1 个 fallback
- **健康检查**：SigningServiceHealthIndicator + WalletServiceHealthIndicator（用 FeignClient 探测）
- **wallet-service 单元测试**：106 个测试用例（WithdrawalApproval 37 + Custody 38 + Whitelist 31）

### 变更
- 4 服务 build.gradle 添加 Seata + Micrometer Tracing + Zipkin 依赖
- 4 服务 application.yml 添加 Seata + tracing/zipkin + 优雅停机配置
- docker-compose.yml 添加 Seata Server + Zipkin + signing/wallet 服务条目
- nacos-config 新增 seata-server.properties + nexus-seata.yaml + seata-server-db.sql
- NoncePool 改造支持预锁定（lockNonce/confirmNonce/cancelNonce）

### 验证
- `gradlew.bat build -x test` BUILD SUCCESSFUL
- SigningTccActionTest 11 个用例通过
- wallet-service 106 个测试用例通过
- TxControllerTest 回归通过

## v1.4.0 - Phase 1 + Phase 2 微服务化（2026-08-07）

### Phase 1：签名服务独立部署 + Nacos + Sentinel
- signing-service 全套实现（TxController/PlatformKeystore/mpc/* + NoncePool/NodeController/Leveldb）
- Nacos 服务发现 + 配置中心接入（docker-compose Nacos 2.3.2）
- Sentinel 熔断限流接入（Sentinel Dashboard 1.8.8）
- gateway Feign 改造（5 处调用方 + SigningServiceFeignClient/WalletMgmtFeignClient）
- ColdWalletMultiSigService 解耦（删 OnChainExecutionClient，改 NodeController 直接广播）
- exchange-wallet signing/ 子包删除（代码迁入 signing-service）

### Phase 2：钱包服务 + 跨链桥独立部署
- wallet-service 全套实现（approval/custody/whitelist/execution）
- DefaultWithdrawalApprovalService 改造（OnChainExecutionClient 改 SigningServiceFeignClient）
- bridge 独立部署改造（SCA 依赖 + Nacos + Sentinel）
- exchange-wallet 模块完全移除（代码迁入 signing-service + wallet-service）
- resilience4j 保留（与 Sentinel 共存：resilience4j 管理链节点直接 HTTP 调用，Sentinel 管理 Feign 调用）

### 技术决策
- signing-service/wallet-service 从 includeBuild 改为 include 子模块（解决 composite build 依赖替换问题）
- Feign 接口修正：addressToPubkeyHash/verifyAddress 从 SigningServiceFeignClient 移到 WalletMgmtFeignClient（对齐方案 §4.4.1）
- fallback 类保留 @Component 注解，不绑定 @FeignClient（编译通过，运行时降级在后续完善）

## [1.3.0] - 2026-08-06 - C2 改进完成：Bean 冲突修复 + 治理参数化 + L2 欺诈证明 + MPC 网络层 + 治理增强 + L2 增强 + 签名服务 PoC + 紧急回滚 + ZK 骨架

### P0 — Bean 冲突修复（#45）
- 修复 `ApprovalPolicy` Bean 冲突：多个实现类注册同名 Bean 导致 `DefaultWithdrawalApprovalService` 注入失败
- 引入 `@Primary` 标注 `DefaultApprovalPolicy` 为首选实现，消除歧义
- 删除冗余 `ApprovalPolicy` 旧实现，统一审批策略入口

### P1 — 治理参数化核心 + L2 欺诈证明核心（#46, #47）

#### 治理参数化核心（#46）
- `GovernableParameterRegistry`：12 个可治理参数集中登记（类型/范围/默认值/生效策略/敏感度）
- 分级 timelock：按参数敏感度（HIGH/MEDIUM/LOW）分级延迟，HIGH 参数延迟更长
- quorum 双门槛：投票率门槛 + 赞成率门槛，需同时满足才通过
- 多版本快照与回滚：`ConfigSnapshot` 多版本历史，`createVersionedSnapshot`/`restoreVersionedSnapshot` 支持指定版本回滚
- 参数冲突检测：提交提案时扫描待执行提案，拒绝同参数并发修改
- 提案仓储抽象：`GovernanceProposalRepository` 接口 + `InMemoryProposalRepository` 默认实现

#### L2 欺诈证明核心（#47）
- `MerklePatriciaTrie`：MPT 实现，支持 insert/get/getProof/getRoot
- `MerkleProof`：Merkle 包含证明
- 单步二分欺诈证明：`FraudProofVerifier` 支持单步状态转换证明，二分定位错误步骤
- slashing：挑战成功罚没提交者保证金
- `ChallengeBond`：挑战者保证金机制，防恶意挑战

### P2 — MPC 网络层 + 治理增强 + L2 增强 + 签名服务独立部署 PoC（#48, #49, #50, #51）

#### MPC 网络层（#48）
- transport：MPC 节点间通信层（消息路由/重试/超时）
- persistence：MPC 会话与密钥分片持久化
- security：MPC 通信安全（加密/认证/防重放）
- barrier：MPC 同步屏障（阶段同步）
- router：MPC 消息路由策略
- wal：Write-Ahead Log，MPC 会话崩溃恢复

#### 治理增强（#49）
- `CommitRevealVotingService`：commit-reveal 投票，防跟票
- `DelegationService` + `VotingPowerCalculator`：委托加权投票，投票权可委托
- `GuardianService`：守护人多签 veto，m-of-n 守护人批准放行
- `ProposalDepositService`：提案保证金，通过退还/失败罚没

#### L2 增强（#50）
- `Eip4844BlobCarrier`：EIP-4844 blob 数据承载，降低 L1 calldata 成本
- 多挑战者支持：`ChallengeConflictResolver` first-valid-wins 冲突解决
- 挑战期延长：`ChallengePeriodPolicy` 可配置挑战窗口
- 排序策略：`SequencingPolicy` 按 (account nonce 升序, priority fee 降序) 排序
- Gas 估算：`GasCostEstimator` 批次 gas 成本估算

#### 签名服务独立部署 PoC（#51）
- `nexus-signing-service`：签名服务独立 Spring Boot 应用骨架
- `nexus-wallet-service`：钱包管理服务独立应用骨架
- 共享 DTO 迁移至 `nexus-sdk`：`WalletTransactionRequest`/`WalletTransactionResult` 等共享至 SDK
- gateway 通过 `HttpSigningServiceClient`/`HttpWalletMgmtClient` HTTP 调用独立服务

### P3 — 紧急回滚通道 + 守护人罢免 + ZK 路线骨架增强（#52）

#### 紧急回滚通道（governance/emergency/）
- `EmergencyRollbackService`：紧急回滚服务，m-of-n 守护人批准即生效，跳过 timelock
- `EmergencyRollbackRecord`：审计日志实体（who/when/targetVersion/reason/approvals）
- 三阶段流程：`initiateEmergencyRollback` → `approveEmergencyRollback` → `executeEmergencyRollback`
- 一次性便捷接口：`emergencyRollback(targetVersion, approvals, reason)` 链下聚合签名场景
- 取消机制：`cancelEmergencyRollback` 守护人可取消未执行请求

#### 守护人罢免（governance/recall/）
- `GuardianRecallService`：守护人罢免服务，走正常治理投票流程
- `RecallProposal`：罢免提案实体，含目标守护人与关联治理提案
- `RecallEvidence`：罢免证据（MALICIOUS_VETO/COLLUSION/KEY_COMPROMISE/INACTIVITY/OTHER）
- `submitRecallProposal` → 治理投票 → `executeRecallIfPassed` 从 GuardianService 移除
- 幂等执行：重复执行返回已处置状态，不重复移除

#### ZK 路线骨架增强（l2/zk/）
- `ZkProofSystem`：ZK 证明系统抽象接口（setup/prove/verify），支持未来接入 halo2/Plonk/Groth16
- `ZkCircuit`：电路定义抽象接口（defineCircuit/synthesize/getPublicInputSchema）
- `ZkProver`：ZK 证明生成器骨架实现
- `ZkVerifier`：ZK 证明验证器骨架实现
- `TrustedSetup`：可信设置多版本管理（MPC ceremony 产物）
- `ZkProof`/`ZkPublicInput`：证明与公共输入实体
- `RollupStateTransitionCircuit`：Rollup 状态转换电路骨架
- 增强 `ZkRollup`：接入 ZkProofSystem，submitBatch 生成 ZK proof，verifyBatch 验证 ZK proof
- 注释标注骨架，真实 ZK 接入仅需替换 ZkProofSystem 实现，上层无需改动

### 编译验证
- 全量 `gradle build -x test`：BUILD SUCCESSFUL（34 个任务）
- 保持向后兼容：所有现有类公共 API 不破坏，新增字段默认值兼容旧调用方

### 版本治理
- 全仓库版本号统一升级为 1.3.0

## [1.2.3] - 2026-08-06 - P2 改进：前端设计契约 + PoS/L2/治理 + MPC 多签 + wallet 拆分

### P2-1 前端设计契约落地
- 修复 87 处设计违规（硬编码颜色/魔法数字/不一致间距）
- 引入 lucide-react 图标库，替换所有 inline SVG 和 emoji 图标
- 新增 7 个共享组件（Button/Card/Modal/Table/Loading/ErrorBoundary/Badge）
- 创建设计令牌文件 src/styles/tokens.ts（颜色/间距/字体/圆角/阴影/动效/z-index 体系）
- tailwind.config.js 引用 tokens.ts 的 tailwindThemeExtend
- App.tsx 用 ErrorBoundary 包裹路由树
- TypeScript 编译零错误

### P2-2 PoS 共识 + L2 Rollup + 链上治理
- PoS 权益证明共识（6 个类）：ValidatorRegistry/StakingServiceImpl/PosProposer/PosRewardDistributor/SlashingService/PosConsensusEngine
- L2 Rollup 扩容骨架（6 个类）：RollupBatcher/StateRootManager/FraudProofVerifier/L2BridgeContract/DefaultL2BridgeContract/RollupSequencer
- 链上治理执行（4 个新类 + 1 个扩展）：GovernanceVotingService/TimelockController/GovernanceExecutor/GovernanceService + GovernanceProposal 扩展执行期字段

### P2-3 MPC GG18/GG20 多签协议
- MPC 签名协议骨架（org.nexus.wallet.signing.mpc 包）：MpcParticipant/MpcKeyGeneration/MpcSigningSession/MpcSigner/MpcSignatureAggregator/ThresholdPolicy/MpcKeyShare/MpcProtocolException
- MpcApprovalPolicy 集成到现有 ApprovalPolicy 审批流
- ColdWalletMultiSigService 冷钱包多签转移通道（发起转移→参与方签名→聚合签名→广播到链上）

### P2-4 exchange-wallet 包级拆分
- 将 exchange-wallet 双重职责拆分为两个包：
  - org.nexus.wallet.wallet.* — 钱包管理（custody/approval/whitelist/pool/execution/controller）
  - org.nexus.wallet.signing.* — 签名服务（keystore/mpc/controller）
- 依赖方向：signing → wallet（单向），为未来独立部署签名服务打下基础
- 通用工具（ApiResult/util/Utils/Leveldb）保留原位 org.nexus.wallet.*
- gateway 的 ExchangeWalletClient 不变（HTTP 调用，包级拆分不影响外部接口）

### 编译验证
- 全量 gradle build -x test：BUILD SUCCESSFUL（34 个任务全部执行）
- exchange-wallet 测试：41/42 通过（1 个预存在 bean 冲突 ServerApplicationTests.contextLoads，与本次拆分无关）

## v1.2.2 (2026-08-06)

### P0 改进 — 模块通电与集成修复

- analytics/oracle 接入 gateway：支付事件采集 + 价格喂入 ChainConnector
- 前端认证接通：OrchestrationDashboard HMAC-SHA256 签名，移除静默吞错
- 跨链桥链上执行：Web3j 3 链适配器 + lock/mint/burn/unlock + EmergencyPause/InsuranceFund

### P1 改进 — 架构补全

- ConsortiumConnector：双链编排层落地，RoutingEngine 支持小额→consortium/大额→core
- OnChainExecutionChannel：统一链上执行通道，settlement/gateway/wallet 链上转账闭环
- 文档对齐：ARCHITECTURE.md 5 层架构 + Module Map 补全，PRD Out of Scope 更新

## [1.2.0] - 2026-08-06

### 主题：第一类纯逻辑骨架补全（白名单 / 货币转换 / 退款审批 / SDK 客户端）

### 新增

- **钱包地址白名单（nexus-exchange-wallet）**
  - 白名单增删查、按商户过滤、首次提币延迟检查（可配置小时数）
  - 地址格式校验、软删除
- **网关货币转换（nexus-gateway）**
  - USD 基准表交叉汇率、可配置点差（spread-bps）、币种子集管理
  - 恒等短路、汇率缺失保护
- **网关退款审批流（nexus-gateway）**
  - 退款请求 / 审批 / 拒绝 / 执行完整工作流（refund_requests 表 V6）
  - RefundPolicy：可退性校验、最大退款额、退款窗口（可配置天数）
- **SDK 客户端封装（nexus-sdk）**
  - BridgeClient：锁定 / 解锁 / 状态查询 / 支持链 / 手续费
  - PaymentChannelClient：开启 / 关闭 / 状态更新 / 查询 / 争议
  - StableCoinClient：铸造 / 销毁 / 转账 / 抵押率 / 价格 / 总供应量

### 修复

- **Wallet.create 密码超长 bug**：随机密码 16 字节→32 位 hex 恒超 fromPassword 的 8-20 长度上限，改为 8 字节→16 位 hex
- **陈旧 SDK 单元测试**：SdkUnitTest 断言骨架行为（抛 UnsupportedOperationException），SDK 实现后回归失败，按真实行为更新断言

### 测试

- 全量回归：9 个模块共 593 个测试全部通过
  （core 277 / bridge 49 / wallet 31 / gateway 89 / settlement 20 / compliance 30 / analytics 32 / oracle 28 / sdk 37）

## [1.1.0] - 2026-08-06

### 主题：合约引擎落地 + 跨链/钱包骨架补全 + 假集成修复

### 新增

- **合约引擎（nexus-core）**
  - WASM 执行器真实实现：接入 Chicory 纯 Java WASM 解释器（无原生依赖），
    支持部署 / 调用 / 查询，二进制 i64 ABI，gas 按指令计费
  - ChicoryWasmEngine / ChicoryWasmInstance：模块校验、实例化、导出函数调用、
    按地址加载与编译缓存
  - EVM 兼容层：内嵌栈式 EVM 子集解释器（算术 / 栈 / 内存 / 存储 / 跳转 / REVERT），
    256 位字宽，接入 ContractStorage
  - ContractStorage：合约 KV 存储（slot → 32 字节值），快照与写回
  - RPC 接线：nexus_deployContract / nexus_callContract / nexus_queryContract
    三个端点，按 vmType 选取 WASM / EVM 执行器

- **跨链桥（nexus-bridge）**
  - Relayer 网络：中继请求生命周期、信誉×质押加权随机选取、中继证明验证
  - 流动性管理：储备注入 / 抽取、利用率计算、跨链再平衡

- **钱包（nexus-exchange-wallet）**
  - 提币审批流：白名单校验、分级审批人数、审批累计、拒绝、执行
  - 默认审批策略：按金额分级（1 / 2 / 3 审批人）+ 地址白名单
  - 托管服务：热 / 冷钱包余额管理、转账校验、策略再平衡（自动归集 + 下限回补）

### 修复

- **StableCoinService 假集成**：getPrice() 硬编码 1.00 却谎称 source=oracle；
  改为可配置锚定价（peg-price）与来源标识（price-source，默认 PEG），诚实标注
- **Gradle daemon 文件锁**：清理残留的 foojay-resolver jar 过期锁

### 版本治理

- 全仓库版本号统一升级为 1.1.0
- 网关对中间层模块依赖坐标同步为 org.nexus:nexus-settlement:1.1.0 / nexus-compliance:1.1.0

### 测试

- 全量回归：8 个模块共 520 个测试全部通过
  （core 277 / bridge 49 / wallet 20 / gateway 64 / settlement 20 / compliance 30 / analytics 32 / oracle 28）
- 新增测试：WASM 引擎 5、EVM 解释器 6、Relayer 9、流动性 9、审批 10、托管 9

## [1.0.0] - 2026-08-06

### 大版本主题：中间服务层从骨架走向真实实现，支付主链路接入风控与合规关卡

### 新增

- **清结算（nexus-settlement）**
  - 复式记账账本组件（Ledger）：结算落账、归集转账、余额查询
  - 对账服务：本地账本 vs 链上 / 银行渠道记录逐笔比对，四类差错识别（匹配 / 本地独有 / 外部独有 / 金额不符）
  - 资金归集服务：单笔归集、自动归集、热钱包阈值触发冷钱包转移
  - 风控规则真实逻辑：金额阈值、滑动窗口频次、地址黑名单（参数可配置）
  - 单元测试 20 个

- **合规（nexus-compliance）**
  - KYC：申请受理去重、自动审核（证件要素校验）、等级映射（NONE/BASIC/ENHANCED/INSTITUTIONAL）
  - AML：制裁名单筛查（内存名单检查器可注入）、四级风险分级（LOW/MEDIUM/HIGH/CRITICAL）、可疑交易报告（STR）受理登记
  - DID：Ed25519 密钥对生成、DID 文档创建/解析、可验证凭证签发与验签（含有效期校验）
  - 信誉评分：事件驱动加减分、等级重算（A/B/C/D）、历史回溯
  - 单元测试 30 个

- **数据分析（nexus-analytics）**
  - 交易图谱：BFS 子图构建、资金路径发现、启发式地址聚类
  - 链上监控：指标采集端口 + 阈值告警规则（双向比较）+ 定时轮询驱动
  - 告警服务：告警登记、确认、活动查询、按级别过滤
  - 统计服务：日交易量、商户 TopN、失败率、平均时延、综合报告
  - 用户分群：高净值 / 商户 / 长尾 / 沉默四类分群与画像
  - 数据导出：CSV/JSON 异步导出、报告导出、任务取消
  - 单元测试 32 个

- **预言机（nexus-oracle）**
  - 价格聚合：多源并发拉取、中位数偏离异常值剔除（20% 阈值）、加权置信度、价格订阅、历史价格窗口
  - 三个数据源真实实现：Binance / CoinGecko（HTTP 拉取 + 静态注入）、Chainlink（可注入报价）
  - 链上治理：提案生命周期（创建/投票/计票/惰性状态推进/执行延迟）、权重投票、防重投
  - 国库：提案联动校验（仅 TREASURY_SPEND 提案可支出）、余额扣减、支出历史审计
  - 可验证随机数：HMAC-SHA256 VRF 方案，生成/验证/常量时间比对
  - 单元测试 28 个

### 变更

- **网关主链路接入关卡**：发起支付过风控（黑名单/限额/规则链）、确认支付过 AML 筛查、退款过风控评估、编排支付路由前过统一风控
- 网关风控/合规桩实现改为委托中间层模块（composite build 进程内依赖）
- 状态机新增 `PENDING → FAILED` 转移（风控/合规拒绝落点）
- 订单状态枚举与错误码扩充（RISK_REJECTED / COMPLIANCE_REJECTED）

### 修复

- settlement/compliance 模块缺少 `useJUnitPlatform()` 导致测试静默跳过
- 四个中间层模块缺少 `bootJar.enabled = false`，作为库被消费时依赖解析失败
- 缺失的 `risk_profiles` / `settlement_batches` 建表迁移（V5），dev/prod validate 模式无法启动的问题
- DefaultRiskEngine 规则链装配缺口（原无法注入任何规则）

### 版本治理

- 全仓库版本号统一升级为 1.0.0（root / nexus-core version.properties / 四个中间层模块 / bridge / sdk / gateway / OpenAPI 文档 / demo）
- 网关对中间层模块依赖坐标同步为 `org.nexus:nexus-settlement:1.0.0` / `nexus-compliance:1.0.0`

### 测试

- 全量回归：5 个模块共 174 个测试全部通过（gateway 64 / settlement 20 / compliance 30 / analytics 32 / oracle 28）

---

## 版本治理说明

### 版本号断档

以下版本号在序列中未使用（断档），均为开发期间预留给未最终成稿的内部迭代，
正式发布版本跳过这些编号。断档不补齐，保留作为历史记录。

- **1.2.1 / 1.2.2**：预留给前端设计契约修复的内部迭代，后合并入 1.2.3 一起发布。
- **1.4.0**：预留给 Phase 3 微服务化中间版本，后因功能合并入 1.5.0 一起发布。
- **1.9.6**：预留给 P1 架构缺口修复的补丁版本，后合并入 1.9.7 一起发布。

### 发布日期密度说明

2026-08-06 至 2026-08-10 期间发布了 18 个版本（1.0.0 ~ 2.1.0），
密度较高。这是 Phase 1 ~ Phase 5 集中开发期的正常节奏：
- 08-06：1.0.0 ~ 1.3.0（中间服务层真实化 + C2 改进）
- 08-07：1.5.0 ~ 1.9.0（Phase 3/4 微服务化 + 审计报告三批）
- 08-08：1.9.1 ~ 1.9.7（测试修复 + 安全修复 + 架构缺口修复）
- 08-10：2.0.0-rc1 / 2.1.0（Phase 5 真实化改造 + 安全审计 + P0 修复）

后续版本遵循语义化版本（SemVer）规范，发布节奏回归正常（按里程碑发布）。


