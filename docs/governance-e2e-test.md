# 链上治理端到端集成测试

## 1. 概述

本文档描述 NexusChain 链上治理（On-Chain Governance）端到端集成测试的设计、运行步骤与配置说明。

### 1.1 测试目标

验证 Java 侧 `GovernanceExecutor` → `OnChainGovernanceClient` → `NexusGovernor.sol` 全链路治理流程，包括：

- 提案创建（propose）
- 投票（castVote）
- 排队到 Timelock（queue）
- 执行（execute）
- 取消（cancel）
- 内存版与链上版双轨一致性
- 链上失败时内存版 fallback

### 1.2 测试范围

| 测试类 | 路径 | 用例数 | 说明 |
|--------|------|--------|------|
| `OnChainGovernanceIntegrationTest` | `nexus-core/nexus-core/src/test/java/org/nexus/governance/` | 6 | 验证 `OnChainGovernanceClient` 直接调用链上合约 |
| `GovernanceExecutorOnChainIntegrationTest` | 同上 | 6 | 验证 `GovernanceExecutor` 与链上集成 / fallback |
| `AbstractHardhatIntegrationTest` | `nexus-core/nexus-core/src/test/java/org/nexus/integration/` | — | Hardhat 环境基类 |

## 2. 前置条件

### 2.1 软件依赖

- **JDK 17**：编译与运行 Java 测试
- **Node.js ≥ 18**：运行 Hardhat
- **npm**：安装 Hardhat 依赖
- **Hardhat**：通过 `npx hardhat` 调用，无需全局安装

### 2.2 项目依赖

l1-test 目录（`nexus-core/nexus-core/src/test/resources/l1-test/`）需包含：

- `package.json` + `node_modules/`（首次运行自动 `npm install`）
- `hardhat.config.js`
- `contracts/NexusGovernor.sol`、`contracts/TimelockController.sol`、`contracts/GovernanceTargetMock.sol`
- `scripts/deploy-governance.js`

### 2.3 端口可用

- 本地端口 **8545** 必须可用（Hardhat 默认 RPC 端口）
- 若 8545 已被占用且为 Hardhat 节点，测试将复用该节点

## 3. 运行步骤

### 3.1 方式一：自动启动 Hardhat（推荐）

直接运行 Gradle 测试任务，基类 `AbstractHardhatIntegrationTest` 会自动启动 Hardhat 子进程：

命令示例：运行治理端到端测试

```bash
./gradlew :nexus-core:nexus-core:test \
  --tests "org.nexus.governance.OnChainGovernanceIntegrationTest" \
  --tests "org.nexus.governance.GovernanceExecutorOnChainIntegrationTest" \
  --no-daemon
```

### 3.2 方式二：手动启动 Hardhat 节点

在另一个终端启动 Hardhat 节点，然后运行测试（基类会检测并复用 8545 端口）：

命令示例：启动 Hardhat 节点

```bash
cd nexus-core/nexus-core/src/test/resources/l1-test
npx hardhat node
```

命令示例：运行测试（复用已有节点）

```bash
./gradlew :nexus-core:nexus-core:test \
  --tests "*OnChainGovernanceIntegrationTest" \
  --no-daemon
```

### 3.3 方式三：CI 环境（无 Hardhat）

在无 Node.js / Hardhat 的 CI 环境中，测试会通过 `assumeTrue` 优雅跳过（标记为 skipped），不会失败。

## 4. 配置说明

### 4.1 Hardhat 节点配置

| 参数 | 值 | 来源 |
|------|-----|------|
| RPC URL | `http://127.0.0.1:8545` | `hardhat.config.js` |
| Chain ID | `31337` | `hardhat.config.js` |
| 部署者私钥 | `0xac0974...` (Hardhat 账户 #0) | 公开测试账户 |
| 部署者地址 | `0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` | Hardhat 默认 |

### 4.2 治理合约参数

| 参数 | 值 | 说明 |
|------|-----|------|
| `minDelay` | 3600 秒 | TimelockController 最小延迟（1 小时） |
| `votingPeriodBlocks` | 100 区块 | NexusGovernor 投票期 |
| `quorumThreshold` | 5000 ETH | 法定人数（绝对票数） |

### 4.3 测试中使用的模拟投票权重

由于 Hardhat 测试账户的 ether 余额虽为 10000 ETH，但 `NexusGovernor` 默认基于 `account.balance` 计算权重。测试中通过 `setVotingWeight` 启用模拟权重模式，设置部署者权重为 100 ETH（≥ quorum），简化测试场景。

## 5. 测试用例清单

### 5.1 OnChainGovernanceIntegrationTest

| 序号 | 用例 | 验证点 |
|------|------|--------|
| 1 | `testProposeOnChain` | `proposeOnChain` 返回正数 proposalId；`proposalCount` 递增；状态 Active；ProposalCreated 事件 emitted |
| 2 | `testCastVoteOnChain` | `castVoteOnChain` 返回 true；VoteCast 事件 emitted；forVotes 增加 |
| 3 | `testQueueOnChain` | 投票期结束后 `queueOnChain` 返回 true；ProposalQueued 事件 emitted；状态 Queued |
| 4 | `testExecuteOnChain` | timelock 到期后 `executeOnChain` 返回 true；ProposalExecuted 事件 emitted；目标合约 value 变更；状态 Executed |
| 5 | `testCancelOnChain` | `cancelOnChain` 返回 true；ProposalCanceled 事件 emitted；状态 Canceled |
| 6 | `testGovernanceExecutorOnChainIntegration` | GovernanceExecutor.schedule/execute 同步链上 queue/execute |

### 5.2 GovernanceExecutorOnChainIntegrationTest

| 序号 | 用例 | 验证点 |
|------|------|--------|
| 1 | `testScheduleSyncsOnChainQueue` | schedule 同步触发链上 ProposalQueued 事件 |
| 2 | `testExecuteSyncsOnChainExecute` | execute 同步触发链上 ProposalExecuted 事件 + 目标状态变更 |
| 3 | `testCancelSyncsOnChainCancel` | cancel 同步触发链上 cancel（或内存版 fallback） |
| 4 | `testOnChainDisabledBackwardCompatible` | `onChainExecutionEnabled=false` 时仅内存版，链上不同步 |
| 5 | `testOnChainFailureFallbackToInMemory` | 链上调用失败时内存版仍成功（fallback） |
| 6 | `testMapOnChainProposalId` | UUID 型 localId 通过 `mapOnChainProposalId` 注册后正确解析 |

## 6. 测试流程详解

### 6.1 完整执行链路（propose → vote → queue → execute）

图：治理端到端流程图

```
[Java: GovernanceExecutor]
        │
        ├─ schedule(proposal)
        │   ├─ [内存] TimelockController.schedule
        │   └─ [链上] OnChainGovernanceClient.queueOnChain
        │       └─ NexusGovernor.queue(proposalId)
        │           └─ emit ProposalQueued
        │
        └─ execute(proposal)
            ├─ [内存] 应用参数变更
            └─ [链上] OnChainGovernanceClient.executeOnChain
                └─ NexusGovernor.execute(proposalId)
                    └─ TimelockController.executeById
                        └─ GovernanceTargetMock.setValue
                            └─ emit ValueChanged
```

### 6.2 区块 / 时间推进

| 操作 | Hardhat RPC | 用途 |
|------|-------------|------|
| 推进 1 区块 | `evm_mine` | 使交易上链 |
| 推进 N 区块 | `evm_mine` × N | 结束投票期（100+ 区块） |
| 推进时间 | `evm_increaseTime` | 使 timelock 到期（3600+ 秒） |
| 快照 | `evm_snapshot` | 隔离测试副作用（可选） |
| 回滚 | `evm_revert` | 恢复快照（可选） |

### 6.3 proposalId 类型映射

- `GovernanceProposal.proposalId`：String（通常为 UUID）
- `OnChainGovernanceClient` 方法：接受 `long proposalId`
- 桥接方式：
  - 数字字符串：`resolveOnChainProposalId` 自动解析
  - UUID：通过 `mapOnChainProposalId(uuid, onChainId)` 注册映射

## 7. 跳过策略

当以下任一条件不满足时，测试通过 `Assumptions.assumeTrue(false, "Hardhat not available")` 优雅跳过：

- l1-test 目录未找到
- `npx` 命令不可用
- `npm install` 失败
- Hardhat 节点启动失败
- RPC 连接超时（15 秒）
- 合约部署失败

跳过的测试在测试报告中标记为 `skipped`，不计入失败。

## 8. 故障排查

### 8.1 测试全部 skipped

原因：Hardhat 环境不可用。检查：

- Node.js 是否安装：`node --version`
- l1-test 目录是否存在：`ls nexus-core/nexus-core/src/test/resources/l1-test/`
- 手动启动 Hardhat 验证：`cd l1-test && npx hardhat node`

### 8.2 部署失败

原因：合约编译错误或部署脚本异常。检查：

- 手动运行部署：`npx hardhat run scripts/deploy-governance.js --network localhost`
- 查看 `deployed-governance.json` 是否生成

### 8.3 proposalState 断言失败

原因：投票期或 timelock 未正确推进。检查：

- `advanceBlocks(VOTING_PERIOD_BLOCKS + 1)` 是否足够（需 > 100）
- `advanceTimeAndMine(TIMELOCK_MIN_DELAY_SECONDS + 1)` 是否足够（需 > 3600 秒）

### 8.4 链上交易 revert

原因：权限不足或状态不符。常见：

- `setVotingWeight` 仅 owner 可调用 → 使用 deployer 私钥
- `cancel` 仅 proposer 或 owner 可调用 → 使用 deployer 创建提案
- `queue` 要求 Succeeded 状态 → 先 vote + advanceBlocks
- `execute` 要求 timelock 到期 → 先 advanceTimeAndMine

## 9. 相关文档

- 合约源码：`nexus-core/nexus-core/src/test/resources/l1-test/contracts/`
- 部署脚本：`nexus-core/nexus-core/src/test/resources/l1-test/scripts/deploy-governance.js`
- JS 单元测试：`nexus-core/nexus-core/src/test/resources/l1-test/test/governance.test.js`
- Java 主代码：
  - `org.nexus.governance.GovernanceExecutor`
  - `org.nexus.governance.OnChainGovernanceClient`
  - `org.nexus.governance.TimelockController`
- L2L1 端到端测试（参考）：`org.nexus.l2.integration.L2L1EndToEndTest`

## 10. 变更历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.1 | 2026-08-19 | 初始版本：P0-3b 链上治理集成验证 |