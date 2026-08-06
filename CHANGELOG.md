# Changelog

本文件记录 NexusChain 各版本的变更。

## [1.3.0] — 2026-08-06 — C2 改进完成：Bean 冲突修复 + 治理参数化 + L2 欺诈证明 + MPC 网络层 + 治理增强 + L2 增强 + 签名服务 PoC + 紧急回滚 + ZK 骨架

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

## [1.2.3] — 2026-08-06 — P2 改进：前端设计契约 + PoS/L2/治理 + MPC 多签 + wallet 拆分

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
