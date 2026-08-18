# L2→L1 端到端集成测试

## 第1章 概述

### 1.1 测试目标

本文档描述 NexusChain L2→L1 提款端到端集成测试的设计与运行方式。该测试在真实 Hardhat 本地 L1 节点上验证 Bridge 合约的 `submitWithdrawals` + `finalizeWithdrawal` 完整流程，包括 Merkle 证明验证与 ERC20 代币实际转账。

### 1.2 测试范围

- **Merkle 树构建一致性**：Java 侧 `MerkleProofBuilder` 与 Solidity `MerkleLib` 哈希方案完全一致
- **提款提交流程**：`submitWithdrawals` 提交 Merkle root 到 L1
- **挑战期时间锁**：等待挑战期结束后才能 `markBatchVerified`
- **Merkle proof 验证**：`finalizeWithdrawsWithProof` 验证单笔提款的 Merkle proof
- **ERC20 实际转账**：最终化后 ERC20 代币从 Bridge 合约转移到 recipient

## 第2章 测试架构

### 2.1 文件清单

表：测试文件清单

| 文件 | 用途 |
|------|------|
| `nexus-core/src/test/java/org/nexus/integration/AbstractHardhatIntegrationTest.java` | Hardhat 集成测试基类（Task 241 创建） |
| `nexus-core/src/test/java/org/nexus/l2/integration/L2L1EndToEndTest.java` | L2→L1 端到端测试（6 个测试方法） |
| `nexus-core/src/test/java/org/nexus/l2/integration/MerkleProofBuilder.java` | Merkle 树构建与 proof 生成工具 |
| `nexus-core/src/test/java/org/nexus/l2/integration/MockERC20.java` | MockERC20 合约的 Web3j wrapper |
| `nexus-core/src/test/resources/l1-test/scripts/deploy-bridge.js` | 合约部署脚本（L2Bridge + MockERC20 + BridgeSource + BridgeTarget + ERC20Mock） |
| `nexus-core/src/test/resources/l1-test/contracts/L2Bridge.sol` | L2↔L1 桥合约（含 Merkle 验证） |
| `nexus-core/src/test/resources/l1-test/contracts/MockERC20.sol` | 测试用 ERC20 mock 合约 |

### 2.2 类继承关系

```
AbstractHardhatIntegrationTest (基类)
  ├── OnChainGovernanceIntegrationTest
  ├── GovernanceExecutorOnChainIntegrationTest
  └── L2L1EndToEndTest (本测试)
```

### 2.3 测试流程

图：L2→L1 提款端到端测试流程

```
@BeforeAll (基类)
  │
  ├─ 定位 l1-test 目录
  ├─ 检查 npx 可用
  ├─ npm install（首次）
  ├─ 启动 Hardhat 节点（或复用已有 8545 节点）
  ├─ hardhat_reset 重置链状态
  ├─ 运行 deploy-bridge.js 部署合约
  └─ 初始化 Web3j 客户端

@Test 方法
  │
  ├─ assumeHardhatAvailable()  ← 不可用时优雅跳过
  ├─ ensureL1Client()          ← 延迟初始化 Web3jL1ContractClient
  └─ 测试逻辑

@AfterAll
  │
  ├─ 关闭 l1Client
  └─ 基类关闭 web3j + 停止 Hardhat 节点
```

## 第3章 测试用例

### 3.1 测试方法列表

表：L2L1EndToEndTest 测试方法

| # | 方法名 | 验证内容 |
|---|--------|----------|
| 1 | `testSubmitStateRoot` | 提交状态根到 L1，验证事件 emitted |
| 2 | `testMarkBatchVerified` | 标记批次验证通过（挑战期结束后） |
| 3 | `testFinalizeWithdraws` | 最终化提款（向后兼容版本，仅置标志位） |
| 4 | `testChallengeBatch` | 欺诈证明挑战 |
| 5 | `testFraudProofChallenge_InvalidStateRoot_ChallengedAndInvalid` | 完整欺诈证明场景 |
| 6 | `testSubmitWithdrawalsAndFinalizeWithProof` | **P0-3c 新增**：submitWithdrawals + finalizeWithdrawsWithProof 完整流程 |

### 3.2 核心测试：testSubmitWithdrawalsAndFinalizeWithProof

#### 3.2.1 测试流程

1. **获取合约地址**：从部署产物 `deployed-bridge.json` 读取 `L2Bridge` 与 `MockERC20` 地址
2. **设置授权 Sequencer**：owner 调用 `setAuthorizedSequencer(deployerAddress)`，使 deployer 成为授权 Sequencer
3. **mint ERC20**：向 L2Bridge 合约地址 mint 足够的 MockERC20 代币（供提款转出）
4. **构造提款列表**：3 笔提款，分别给 Hardhat 预置账户 #1/#2/#3
5. **构建 Merkle 树**：使用 `MerkleProofBuilder.build()` 计算 Merkle root 与各笔提款的 proof
6. **提交状态根**：Sequencer 调用 `submitStateRoot(stateRoot, batchId)`
7. **提交提款根**：Sequencer 调用 `submitWithdrawals(batchId, withdrawals, withdrawalRoot)`
8. **等待挑战期**：`advanceTimeAndMine(61)` 推进链上时间 61 秒（挑战期 60 秒）
9. **标记批次验证**：调用 `markBatchVerified(batchId)`
10. **最终化每笔提款**：对每笔提款调用 `finalizeWithdrawsWithProof`，提供 Merkle proof
11. **验证 ERC20 转账**：检查各 recipient 的 `balanceOf` 等于提款金额
12. **验证最终化标志**：检查 `isWithdrawalFinalized(batchId, index)` 为 true
13. **验证 Bridge 余额**：所有提款完成后 L2Bridge 的 ERC20 余额应为 0

#### 3.2.2 Merkle 树构建方案

与 Solidity `MerkleLib` 完全一致：

- **叶节点哈希**：`keccak256(abi.encode(token, recipient, amount, index))`
  - `token`：address（ABI 编码右对齐到 32 字节）
  - `recipient`：address（同上）
  - `amount`：uint256（32 字节大端）
  - `index`：uint256（32 字节大端，叶子在批次中的位置）
  - 共 128 字节输入 → 32 字节 keccak256 哈希

- **内部节点哈希**：`keccak256(abi.encodePacked(left, right))` = `keccak256(left || right)`
  - 64 字节输入 → 32 字节 keccak256 哈希

- **奇数叶子处理**：最后一个节点与自身配对（`right = left`）

## 第4章 前置条件

### 4.1 环境要求

- **JDK**：17 或以上
- **Node.js**：16 或以上
- **npm**：可用
- **npx**：可用（Hardhat 通过 npx 启动）
- **Gradle**：8.5（项目自带 gradle wrapper）

### 4.2 Hardhat 节点

- 测试自动启动 `npx hardhat node`（监听 `127.0.0.1:8545`，链 ID 31337）
- 若 8545 端口已有 Hardhat 节点运行，测试复用之（不启动新进程）
- Hardhat 标准测试账户 #0 私钥：`0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80`
- 对应地址：`0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266`

### 4.3 npm 依赖

首次运行时自动执行 `npm install`（在 `l1-test` 目录），安装 Hardhat 工具链。后续运行跳过（检测到 `node_modules` 存在）。

## 第5章 运行方式

### 5.1 运行全部 L2 E2E 测试

命令示例：运行 L2L1EndToEndTest

```bash
# 在 nexus-core/nexus-core 目录下
"F:\Program Files (x86)\Gradle\gradle-8.5\bin\gradle.bat" test --tests "org.nexus.l2.integration.L2L1EndToEndTest" --no-daemon
```

### 5.2 仅运行 P0-3c 新增测试

命令示例：运行单个测试方法

```bash
"F:\Program Files (x86)\Gradle\gradle-8.5\bin\gradle.bat" test --tests "org.nexus.l2.integration.L2L1EndToEndTest.testSubmitWithdrawalsAndFinalizeWithProof" --no-daemon
```

### 5.3 手动部署合约（调试用）

命令示例：手动运行部署脚本

```bash
# 在 l1-test 目录下，先启动 Hardhat 节点
npx hardhat node

# 另一终端，运行部署脚本
npx hardhat run scripts/deploy-bridge.js --network localhost
```

部署产物写入 `deployed-bridge.json`，包含以下合约地址：

```json
{
  "contracts": {
    "ERC20Mock": "0x...",
    "BridgeSource": "0x...",
    "BridgeTarget": "0x...",
    "MockERC20": "0x...",
    "L2Bridge": "0x..."
  }
}
```

## 第6章 跳过策略

### 6.1 优雅跳过

若以下任一条件不满足，测试通过 JUnit5 `assumeTrue` 优雅跳过（标记为 skipped，非失败）：

- `l1-test` 目录存在
- `npx` 可用
- `npm install` 成功
- Hardhat 节点启动成功
- RPC 连接成功
- 合约部署成功

### 6.2 跳过消息

所有跳过场景的消息前缀为 `Hardhat not available`，后接具体原因。

## 第7章 合约参数

表：L2Bridge 合约参数

| 参数 | 值 | 说明 |
|------|----|------|
| `challengePeriod` | 60 秒 | 挑战期（测试用，缩短等待时间） |
| `authorizedSequencer` | deployer 地址 | 测试中通过 `setAuthorizedSequencer` 设置 |
| `sequencerBondAmount` | 0（默认） | 本测试不涉及罚没机制 |
| `challengerBondAmount` | 0（默认） | 本测试不涉及挑战者质押 |

表：MockERC20 合约参数

| 参数 | 值 |
|------|----|
| `name` | Mock Withdrawal Token |
| `symbol` | MWT |
| `decimals` | 18 |

## 第8章 验证要点

### 8.1 Merkle 一致性验证

`MerkleProofBuilder.verifyAllProofs()` 在本地验证所有 proof 能恢复出 root。这是 Java 侧自检，确保 proof 生成正确。

### 8.2 链上验证

- `callWithdrawalRoot(batchId)`：验证链上提款根与提交的一致
- `callIsBatchVerified(batchId)`：验证批次已 VERIFIED
- `callIsWithdrawalFinalized(batchId, index)`：验证单笔提款已最终化
- `token.balanceOf(recipient)`：验证 ERC20 已实际转移

### 8.3 余额守恒

所有提款完成后：
- 各 recipient 余额 = 各自提款金额
- L2Bridge 合约余额 = 0（全部转出）
- 总 mint 量 = 总提款量

## 第9章 故障排查

### 9.1 常见问题

表：常见问题与解决方案

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `Hardhat not available: npx not found` | Node.js 未安装或不在 PATH | 安装 Node.js 16+ |
| `Hardhat not available: npm install failed` | 依赖安装失败 | 手动在 `l1-test` 目录运行 `npm install` |
| `Hardhat not available: RPC timeout` | 8545 端口被占用或节点启动慢 | 检查端口占用，增大 `NODE_STARTUP_WAIT_MS` |
| `Hardhat not available: contract deployment failed` | 部署脚本失败 | 手动运行 `npx hardhat run scripts/deploy-bridge.js --network localhost` 查看错误 |
| `submitWithdrawals tx failed` | 未设置授权 Sequencer | 确认 `setAuthorizedSequencer` 已调用 |
| `markBatchVerified receipt not OK` | 挑战期未结束 | 确认 `advanceTimeAndMine(61)` 已调用 |
| `finalizeWithdrawsWithProof tx failed: invalid withdrawal proof` | Merkle proof 不一致 | 检查 `MerkleProofBuilder` 哈希方案与合约一致 |

### 9.2 日志

测试使用 SLF4J 日志，关键步骤输出 INFO 级别日志：
- 合约部署地址
- Merkle 树 root
- 每笔提款最终化进度
- 测试通过/失败结果