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
| nexus-exchange-wallet | Exchange/custodial wallet | Active |
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
- **nexus-consortium**: PoA (Proof of Authority) — permissioned validator set, proposer round-robin (`PoAMiner`), 国密 SM2/3/4 crypto stack.

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
- `nexus-gateway` → `nexus-exchange-wallet`：**HTTP REST**（独立服务，钱包托管/兑换接口）
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
- MPC 多签协议（GG18/GG20 阈值签名）

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