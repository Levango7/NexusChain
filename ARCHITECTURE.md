# NexusChain Architecture

## Product Vision

NexusChain is a **Payment Orchestration Platform** built on blockchain infrastructure. It provides multi-chain, multi-channel routing, smart settlement, and subscription management for merchants and enterprises.

Blockchain is the foundational settlement layer — not the product itself. On top of it, we build:
- Unified payment gateway (acquiring)
- Cross-chain asset bridge
- Recurring/subscription billing engine
- IoT device payment protocols (smart city long-term vision)

## Product Philosophy

- Open-source products start less polished than commercial ones — this is normal
- Direction correctness matters more than short-term completeness
- All modules are retained and mature at their own pace; `nexus-java-sdk` / `nexus-js-sdk` are explicitly **Deprecated** (migrated to `nexus-sdk`) and excluded from the build
- Each module matures at its own pace, guided by real usage

## Module Map

| Module | Role | Maturity |
|--------|------|----------|
| nexus-core | Blockchain node (consensus, P2P, storage, RPC) | Core — active |
| nexus-gateway | Merchant payment gateway (orders, checkout, webhooks) | Core — active |
| nexus-bridge | Cross-chain asset bridge (lock/mint/burn/unlock) | Core — active |
| nexus-explorer | Block explorer (React + TypeScript) | Active |
| nexus-sdk | Multi-language SDK (Java, JS) | Active |
| nexus-consortium | Consortium / sidechain — complete PoA chain (consensus, gRPC/WS P2P, 国密 SM2/3/4) | Active — complete chain |
| nexus-exchange-wallet | Exchange/custodial wallet（已归档至 .archived，拆分到 signing/wallet-service） | Archived |
| nexus-signing-service | 签名服务独立部署（PoC，HTTP 调用） | Active — PoC |
| nexus-wallet-service | 钱包管理服务独立部署（PoC，HTTP 调用） | Active — PoC |
| mpc-engine | Rust gRPC MPC 密码学引擎 | **已实现 — GG20 门限 ECDSA（DKG/Sign/Aggregate/Verify），3/3 E2E 测试通过**（见 README 成熟度声明） |
| nexus-settlement | 清结算与风控（复式记账、对账、资金归集、风控规则链） | Active — 库（gateway 进程内消费） |
| nexus-compliance | 合规与身份（KYC/AML/DID/信誉评分） | Active — 库（gateway 进程内消费） |
| nexus-analytics | 数据智能（交易图谱、链上监控、告警、BI、导出） | Active — 库（gateway 进程内消费，事件驱动） |
| nexus-oracle | 预言机与治理（价格聚合、VRF、提案、国库） | Active — 库（gateway 进程内消费，ChainConnector 喂价） |
| nexus-devtools | Developer tools (CLI, testnet faucet) | Skeleton |
| nexus-rpc-doc | RPC API documentation | Reference |
| nexus-java-sdk | Legacy Java SDK (migrated to nexus-sdk) | Deprecated |
| nexus-js-sdk | Legacy JS SDK (migrated to nexus-sdk) | Deprecated |

## Consensus

- **nexus-core**: DPoS (Delegated Proof of Stake) — validators are elected by stake-weighted voting (`ProposersState`). Block proposers rotate via time-window scheduling; lightweight hash verification substitutes for traditional PoW.
  - Minimum proposer mortgage: 100,000 NEX
  - Maximum active proposers: 15
  - Vote decay: 10% per 2,160 eras
  - **PoS 共识（自 1.2.3）**：`ValidatorRegistry`/`StakingServiceImpl`/`PosProposer`/`PosRewardDistributor`/`SlashingService`/`PosConsensusEngine`
- **nexus-consortium**: PoA (Proof of Authority) — permissioned validator set, proposer round-robin (`PoAMiner`), 国密 SM2/3/4 crypto stack.

## On-Chain Governance（自 1.3.0）

- **参数化治理核心**（`governance/`）：12 个可治理参数集中登记，分级 timelock，quorum 双门槛，多版本快照回滚，参数冲突检测
- **commit-reveal 投票**（`governance/voting/`）：防跟票
- **委托加权投票**（`governance/delegation/`）：投票权委托与锁定
- **守护人审核**（`governance/guardian/`）：m-of-n 守护人 veto/approve
- **提案保证金**（`governance/deposit/`）：通过退还/失败罚没
- **紧急回滚通道**（`governance/emergency/`）：m-of-n 守护人批准即生效，跳过 timelock，独立审计日志
- **守护人罢免**（`governance/recall/`）：走正常治理投票流程，通过后移除作恶守护人

## Signing Approval Flow（自 2.15.0）

签名审批服务（`nexus-signing-service`）的双人审批闭环已完整化：

- **审批人通知**（`ApprovalNotifier` 接口 + `LoggingApprovalNotifier` 实现）：审批创建时通知审批人，通知异常不影响审批主流程
- **审批记录持久化**（`ApprovalStore` 接口）：
  - `MapApprovalStore`：内存 `ConcurrentHashMap`（单实例默认）
  - `FileBasedApprovalStore`：JSON Lines 文件持久化（`data/approval-records.jsonl`，重启可恢复）
  - 通过 `nexus.approval.use-database=true` 切换为文件持久化（默认 false = 内存）
- **审批记录 DTO**（`ApprovalRecordDto`）：审批记录序列化载体

## L2 Rollup（自 1.3.0）

- **Optimistic Rollup**（`l2/`）：欺诈证明 + 挑战窗口 + slashing + challenge bond
  - MPT 状态根（`MerklePatriciaTrie`）+ 单步二分欺诈证明
  - EIP-4844 blob 承载（`l2/blob/`）降低 L1 caldata 成本
  - 多挑战者冲突解决（`l2/challenge/`，first-valid-wins）
  - 排序策略（`l2/sequencer/`，account nonce 升序 + priority fee 降序）
  - Gas 估算（`l2/gas/`）
- **ZK Rollup**（`l2/` + `l2/zk/`）：ZK 证明即最终性
  - `ZkProofSystem` 抽象（setup/prove/verify），支持未来接入 halo2/Plonk/Groth16
  - `ZkCircuit` 电路抽象 + `RollupStateTransitionCircuit` 状态转换电路骨架
  - `TrustedSetup` 可信设置多版本管理
  - 当前为骨架实现，真实 ZK 接入仅需替换 ZkProofSystem 实现

## Technology Stack

- **Language**: Java 17
- **Framework**: Spring Boot — unified to `3.2.5` across all modules (managed via Spring Boot BOM and io.spring.dependency-management plugin)
- **Build**: Gradle 7.6+ with toolchain auto-provisioning
- **Database**: PostgreSQL (production), H2 (dev/sandbox)
- **P2P**: gRPC + Protobuf
- **Smart Contracts**: WASM (Chicory 纯 Java 解释器) + EVM 子集解释器
- **Frontend**: React + TypeScript + Tailwind CSS
- **Observability**: Micrometer + Prometheus + structured logging
- **Resilience**: Resilience4j (circuit breaker, retry, rate limiter)

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                       Application Layer                          │
│   nexus-explorer │ nexus-devtools │ Merchant Portal             │
├─────────────────────────────────────────────────────────────────┤
│                       Service Layer                              │
│   nexus-gateway │ nexus-bridge │ nexus-exchange-wallet          │
├─────────────────────────────────────────────────────────────────┤
│              Mid-Service Layer (编排支撑，进程内库)               │
│   nexus-settlement │ nexus-compliance │ nexus-analytics │ nexus-oracle │
├─────────────────────────────────────────────────────────────────┤
│                       SDK / Integration                          │
│   nexus-sdk (Java/JS) │ nexus-rpc-doc                            │
├─────────────────────────────────────────────────────────────────┤
│                    Infrastructure Layer                          │
│   nexus-core (consensus, P2P, storage, VM)                       │
│   nexus-consortium (governance, permissioning)                   │
└─────────────────────────────────────────────────────────────────┘
```

### 集成方式

- `nexus-gateway` ← `nexus-settlement` / `nexus-compliance` / `nexus-analytics` / `nexus-oracle`：**进程内 composite build**（Gradle `includeBuild`，gateway 直接消费库的 Service Bean，无 HTTP 开销）
- `nexus-gateway` → `nexus-core` / `nexus-consortium`：**HTTP RPC**（独立进程，链节点远程调用）
- `nexus-gateway` → `nexus-exchange-wallet`：**HTTP REST**（独立服务，钱包托管/兑换接口）— **已归档**：拆分到 `nexus-signing-service` / `nexus-wallet-service`
- `nexus-bridge`：**独立 Spring Boot 应用**（链上执行，Web3j 适配多链）

## Payment Orchestration Roadmap

### 已交付（Delivered）

- 双链 acquiring：gateway 路由至 `nexus-core`（公链结算主网）与 `nexus-consortium`（联盟侧链）
- 多通道路由（5 connector）：core / consortium / exchange-wallet / bridge / on-chain execution
- 合约引擎：WASM（Chicory 纯 Java 解释器）+ EVM 子集解释器
- 跨链桥（链上执行）：Web3j 3 链适配器，lock/mint/burn/unlock + EmergencyPause/InsuranceFund
- 清结算 + 风控：复式记账、对账、资金归集、风控规则链（`nexus-settlement`）
- 合规（KYC/AML/DID）：身份认证、反洗钱、去中心化身份、信誉评分（`nexus-compliance`）
- 数据智能：交易图谱、链上监控、告警、BI、导出（`nexus-analytics`）
- 预言机：价格聚合喂价 ChainConnector（`nexus-oracle`）
- 前端认证：OrchestrationDashboard HMAC-SHA256 签名
- SDK：4 语言（Java/JS/Python/Go）

### 进行中（In Progress）

- PoS 共识（替换/增强现有 DPoS）
- L2 Rollup（扩容方案）
- 链上治理执行（提案 → 国库 → 链上动作）
- MPC 多签协议（GG18/GG20 阈值签名）— **已完成 — GG20 可信协调器模型**
- ZK Rollup 真实 ZK 库接入（halo2/Plonk）
- 签名服务/钱包服务独立部署（PoC → 生产）

### 规划中（Planned）

- IoT 微支付（device-to-device，智慧城市场景）
- 多 PSP 生产接入（300+ connector 路线）
- A/B testing 路由（实验驱动编排策略）
- 微服务化（SCA 路线，按需拆分 gateway）

## Key Design Decisions

- Gateway communicates with Core via HTTP RPC (not embedded)
- Sandbox profile enables zero-dependency local development
- All state transitions are event-driven (Spring ApplicationEvent)
- Idempotency enforced at API layer (request fingerprint)
- Webhook delivery is async with exponential backoff retry
- **Dual-chain settlement (deliberate)**: `nexus-core` is the public settlement mainnet; `nexus-consortium` is a permissioned consortium/sidechain. Both are first-class chains — the product intentionally supports dual-chain, not a single settlement chain. `nexus-consortium` is consolidated into the unified build via Gradle composite build (`includeBuild`).

## Security Hardening（v2.26.0）

第 16 轮质量保证工作完成的安全加固（不引入新功能，仅修复与加固）：

### 静态扫描修复（SpotBugs+FindSecBugs，5 个 SECURITY HIGH）

- `HashUtil` / `PeersCache`：`Random` → `SecureRandom`（CSPRNG 替换弱随机数源）
- `AESManage` / `SerializableUtil` / `SecurityConfig`：添加 `@SuppressFBWarnings` 抑制注解（已审计确认安全的误报/受控使用）
- 新增 `spotbugs-annotations` 依赖，统一抑制注解引入
- 所有 SECURITY category 的 HIGH bug 已清除

### SAST 安全审计修复（3 个问题）

- `JwtTokenProvider` / `FeignJwtRequestInterceptor`：硬编码 JWT 密钥 → `SecureRandom` 动态生成
- `WalletController`：`System.out.println` → `logger.debug`（消除敏感信息 stdout 泄露）

### 性能调优

- `NonceTracker`：`synchronized` → `ConcurrentHashMap.putIfAbsent`，无锁化改造，多线程 nonce 申请争用显著降低

### 组件装配增强

- `InMemoryChainDidStore`：添加 `@Component` 注解，支持 Spring 容器自动装配

### 全量回归测试基线

- 2491 个测试用例，63 个失败（全部在 gateway 集成测试，已通过 `ConnectorRegistry` 防御性 null 检查修复），6 个跳过，其余所有模块测试全部通过

## Running Locally

```bash
# Prerequisites: JDK 17+
cd nexus-gateway
./gradlew bootRun --args="--spring.profiles.active=sandbox"

# Or use the demo orchestrator:
cd demo
npm install && npm start   # Mock chain on :3000
node run-demo.js           # Full payment flow
```