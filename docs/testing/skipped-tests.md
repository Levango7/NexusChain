# NexusChain 跳过测试清单

> **文档版本**：v1.0
> **生成日期**：2026-08-10
> **基线版本**：v2.0.0-rc1
> **关联发现**：P2-9（集成测试启用文档）
> **维护说明**：本文件随测试跳过状态变化同步更新。新增跳过测试须在此登记，已启用测试须从本文件移除并在 CHANGELOG 记录。

---

## 1. 概述

NexusChain v2.0.0-rc1 当前共有 **9 个被跳过的测试**，分布在 4 个测试类中。跳过原因集中于两类环境依赖：

1. **外部工具链不兼容**：Hardhat EDR 与 Node v25 不兼容（5 个）
2. **跨平台/协议同步问题**：argon2 native 跨平台不一致（2 个）、protobuf 未同步新交易类型（1 个）、多线程阻塞循环（1 个）

所有跳过均通过 JUnit 5 的 `@Disabled` 或 `Assumptions.assumeTrue(false, ...)` 实现，**不会导致构建失败**，仅在测试报告中标记为 skipped/aborted。

### 1.1 跳过机制说明

| 机制 | 来源 | 行为 | 适用场景 |
|------|------|------|----------|
| `@Disabled("原因")` | JUnit 5 | 测试方法整体不执行，报告中标记 disabled | 永久性跳过（如阻塞循环） |
| `Assumptions.assumeTrue(false, "原因")` | JUnit 5 Assumptions | 运行时判定不满足前提时中止，报告中标记 aborted | 条件性跳过（如外部依赖不可用） |

---

## 2. 跳过测试清单

### 2.1 L2 L1 端到端测试（Hardhat EDR 不兼容）

**所在文件**：`nexus-core/nexus-core/src/test/java/org/nexus/l2/integration/L2L1EndToEndTest.java`
**跳过机制**：`@BeforeAll` 中 `assumeTrue(false, "Hardhat not available: ...")`，Hardhat 不可用时整个测试类的全部方法跳过
**跳过原因**：Hardhat EDR（Ethereum Development Runtime）与 Node.js v25 不兼容，本地环境无法启动 Hardhat 节点完成 L1 合约部署
**启用条件**：将 Node.js 降级至 v20 LTS 或 v22 LTS；或等待 Hardhat EDR 发布支持 Node v25 的版本；启用后须验证 `L2Bridge.sol` 合约部署与 5 项端到端流程

表：L2L1EndToEndTest 跳过测试方法

| # | 测试方法 | 验证内容 |
|---|----------|----------|
| 1 | `testSubmitStateRoot` | L2 状态根提交至 L1 合约 |
| 2 | `testMarkBatchVerified` | 批次标记为已验证 |
| 3 | `testFinalizeWithdraws` | 提现 finalize 流程 |
| 4 | `testChallengeBatch` | 欺诈证明挑战批次 |
| 5 | `testFraudProofChallenge_InvalidStateRoot_ChallengedAndInvalid` | 无效状态根挑战后标记 ChallengedAndInvalid |

### 2.2 MPC 端到端测试（已启用 ✅ — 3/3 通过）

**所在文件**：`nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcEndToEndTest.java`
**状态**：**已启用** — 3 个测试全部通过（2026-08-11 验证）
**启用方式**：通过 `mpc-engine/start-engine.bat` 启动 Rust 引擎（使用 MSYS2 mingw64 工具链），`GrpcMpcCryptoEngine.healthCheck()` 返回 true
**启用修复**：
1. session_id 统一（DKG/Sign/Aggregate 共用同一 session_id）
2. 阈值参数修正（t=3→t=2，Rust 引擎要求 `threshold < total_parties`）
3. 签名方数修正（t→t+1，GG20 协议要求 `signer_count > threshold`）
4. `aggregate.rs` message_bn 一致性（使用 `gg20::message_hash_to_bigint` 与 sign.rs 一致）
5. Java 端 `verifyEcdsaSignature` 修正（`z=SHA256(hash)` 与 Rust 端一致）
6. `.cargo/config.toml` GNU linker 持久化配置

表：MpcEndToEndTest 已启用测试方法

| # | 测试方法 | 验证内容 | 状态 |
|---|----------|----------|------|
| 6 | `testHealthCheck` | Rust mpc-engine gRPC 健康检查 | ✅ 通过 |
| 7 | `testThreePartyDkgSignAggregateVerify` | 3-party-2-threshold DKG→Sign→Aggregate→Verify 完整流程 | ✅ 通过 |
| 8 | `testTwoOfThreeThresholdSignature` | 2-of-3 阈值签名（t<n） | ✅ 通过 |

### 2.3 区块缓存多线程测试（阻塞循环）

**所在文件**：`nexus-core/nexus-core/src/test/java/org/nexus/core/BlocksCacheTest.java`（第 142 行）
**跳过机制**：`@Disabled("多线程读写测试含 while(true) 阻塞循环，会导致测试任务挂起；需手动运行验证")`
**跳过原因**：测试方法含 `while(true)` 阻塞循环，在 CI 中执行会导致测试任务永久挂起
**启用条件**：重构测试移除 `while(true)` 阻塞，改为带超时（`assertTimeoutPreemptively`）的有限轮次并发测试；或保留为手动验证脚本，不纳入自动化套件

表：BlocksCacheTest 跳过测试方法

| # | 测试方法 | 验证内容 |
|---|----------|----------|
| 9 | `testMultiThreadReadWrite` | BlocksCache 多线程并发读写一致性 |

### 2.4 Keystore argon2 跨平台测试

**所在文件**：`nexus-core/nexus-core/src/test/java/org/nexus/keystore/KeystoreTests.java`
**状态更新（2026-08-30，测试体系中期建设）**：`verifyPassword`/`decrypt` 对 fixture 的比对仍按平台差异跳过（见下），但该安全路径已由新增的**往返不变量用例**补全恒可运行覆盖：
`decryptRoundTrip_correctPassword_returnsValidKey` / `verifyPassword_wrongPassword_rejected` /
`decrypt_wrongPassword_throws` / `decrypt_afterMarshalRoundTrip_stillWorks`——本机生成→本机解密，
不依赖跨平台一致的预置密文。
**跳过机制**：`assumeTrue(false, "本平台 argon2 native 计算与 testJson 数据不一致，跳过")`，当本平台 argon2 native 计算结果与测试向量 `testJson` 不一致时跳过该断言
**跳过原因**：`testJson` 中的 mac 与密文由特定平台的 argon2 native 库生成，跨平台（不同 OS/架构）的 argon2 native 计算可能不一致
**启用条件**：统一 argon2 native 实现的跨平台行为（固定参数集/版本）；或改用纯 Java argon2 实现（如 BouncyCastle `Argon2BytesGenerator`）消除 native 差异；或按平台生成对应测试向量

表：KeystoreTests 跳过测试方法

| # | 测试方法 | 验证内容 |
|---|----------|----------|
| 10 | `verifyPassword` | Keystore 密码验证（argon2id KDF）——fixture 比对路径 |
| 11 | `decrypt` | Keystore 解密出预期私钥——fixture 比对路径 |

### 2.5 SDK 交易编码测试（protobuf 未同步）

**所在文件**：`nexus-core/nexus-core/src/test/java/org/nexus/integration/SdkEndToEndTest.java`（第 255 行）
**跳过机制**：`assumeTrue(ProtocolModel.Transaction.Type.forNumber(BATCH_TRANSFER.ordinal()) != null, "protobuf 尚未定义 BATCH_TRANSFER 类型，跳过")`
**跳过原因**：protobuf 定义文件未同步更新支付扩展新交易类型（`BATCH_TRANSFER(19)` 等），`forNumber` 返回 null 导致编码往返测试无法执行
**启用条件**：更新 protobuf `.proto` 定义文件，添加 `BATCH_TRANSFER` 等新交易类型枚举值；重新生成 Java protobuf 代码；验证编码往返一致

表：SdkEndToEndTest 跳过测试方法

| # | 测试方法 | 验证内容 |
|---|----------|----------|
| 12 | `testTransactionEncoding` | 交易 protobuf 编码往返（构造→encode→fromProto→字段一致） |

---

### 2.6 已解除的历史排除（2026-08-30，测试体系中期建设）

**WithdrawalRollbackTest（提现事务回滚，org.nexus.walletsvc.seata）**
**历史状态**：被 nexus-wallet-service/build.gradle `excludeTestsMatching` 排除（未在本台账登记——审计"台账不完整"的实证），排除理由为"需要 Nacos 等外部基础设施"。
**解除依据**：该测试 `@ActiveProfiles("test")` 使用 H2 + `@MockitoBean`（application-test.yml 已禁用 Seata/Nacos/Sentinel），不依赖任何外部基础设施——排除理由已不成立。它是唯一的提现事务回滚安全路径覆盖（signing 失败 → FAILED 状态保留 rejectionReason 供排查）。解除后对断言做了对齐修正（rejectionReason 记录具体错误串而非 'execution failed' 前缀，以生产实现语义为准）。
**当前状态**：已纳入常规 `test` 门禁，本地全绿。
**同步更新（2026-08-30 第二批）**：WalletControllerIT 亦解除排除——同为 test profile 的 H2 自包含 MockMvc 设计。其 403 根因（JWT 过滤器链下 spring-security-test 桥接失效，经诊断用例实证 authInContext=null）以 `@AutoConfigureMockMvc(addFilters=false)` + `@WithMockUser` 组合解决：绕过 servlet filter chain，方法级 @PreAuthorize 由 AOP 承担正常鉴权。鉴权链语义由 SecurityConfig/JwtAuthenticationFilter 单测覆盖，本测试聚焦 HTTP 契约。7 用例全绿，纳入常规门禁。

另：Hardhat L1/L2 E2E（5 测试类/46 用例）已以独立 CI job 纳入（Node 22 LTS，首轮 continue-on-error 观察模式）——本台账 §2.1 的"CI 中不可运行"状态就此变更：CI 环境具备运行条件，跳过仅发生在环境故障时（自适应 assumeTrue）。

---

## 3. 环境驱动的条件测试（非永久跳过）

以下测试依赖外部环境变量（真实链节点），未设置时通过 `assumeTrue` 跳过，**不属于上述 9 个永久/兼容性跳过测试**，设置环境后即可运行，故单独列出。

**所在文件**：`nexus-core/nexus-core/src/test/java/org/nexus/rpc/RPCTest.java`
**跳过机制**：`assumeRpcEnvAvailable()` 检查环境变量 `PRIVATE_KEY` / `HOST` / `PORT`，未设置时跳过
**运行方式**：`PRIVATE_KEY=0x... HOST=127.0.0.1 PORT=8545 ./gradlew test --tests "*RPCTest*"`

表：RPCTest 环境驱动测试方法

| 测试方法 | 验证内容 | 依赖环境 |
|----------|----------|----------|
| `testGetBalance` | 查询账户余额 | HOST/PORT/PRIVATE_KEY |
| `testTransfer` | 转账交易发送 | HOST/PORT/PRIVATE_KEY |
| `testSendTransaction` | 发送事务 | HOST/PORT/PRIVATE_KEY |
| `testGetNonce` | 查询 nonce | HOST/PORT/PRIVATE_KEY |

---

## 4. 启用条件汇总

表：跳过测试启用条件与优先级

| 分组 | 跳过数 | 根因 | 启用条件 | 优先级 |
|------|--------|------|----------|--------|
| L2 L1 E2E | 5 | Hardhat EDR 与 Node v25 不兼容 | Node 降级至 v20/v22 LTS 或 EDR 升级 | 高（L2 真实化关键） |
| Keystore argon2 | 2 | argon2 native 跨平台不一致 | 统一 native 实现或改用纯 Java | 中 |
| BlocksCache 多线程 | 1 | while(true) 阻塞循环 | 重构为带超时并发测试 | 低 |
| SDK protobuf | 1 | protobuf 未定义新交易类型 | 更新 .proto 并重新生成代码 | 中 |

> **已启用**：MPC E2E（3 个）已于 2026-08-11 启用并通过，详见 §2.2。

---

## 5. 维护约定

1. **新增跳过测试**：须在本文件对应章节登记测试名称、文件、跳过原因、启用条件
2. **启用跳过测试**：从本文件移除对应条目，并在 `CHANGELOG.md` 记录"启用 X 测试（修复 Y）"
3. **跳过原因须诚实**：禁止用 `@Disabled("TODO")` 等无信息原因跳过，须说明具体阻塞点与启用路径
4. **CI 须报告跳过数**：构建日志须输出 skipped/aborted 计数，跳过数变化须在 PR 中说明