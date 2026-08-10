# NexusChain

NexusChain 是一个**基于自研区块链的支付编排平台（Payment Orchestration Platform）**。它在自研结算链之上构建统一支付网关、跨链桥、交易所钱包与清结算/合规/分析中间服务层，面向中小电商与 SaaS 提供多渠道、多链的支付受理能力。

> **定位**：区块链是底层结算基础设施，不是产品本身。产品价值在于统一支付 API、启发式路由与清结算。

**当前版本**：v2.0.0-rc1（候选版本，2026-08-10）

## 快速开始

### 环境要求

- JDK 17+（nexus-consortium 亦已升级至 17）
- Gradle 8.5（随 wrapper 提供）
- 可选：Docker + docker-compose（一键起全栈）
- 可选：Rust toolchain（编译 mpc-engine，需 C 编译器）

### 本地构建与测试

```bash
# 全量回归测试
./gradlew testAll

# 仅网关（沙箱模式，零外部依赖）
cd nexus-gateway
./gradlew bootRun --args="--spring.profiles.active=sandbox"
```

### Docker 一键启动

```bash
docker-compose up -d
```

启动网关 + 链节点 + 签名服务 + 钱包服务 + 桥，以及 Nacos / Sentinel / Seata / Zipkin 基础设施。

## 模块清单

| 模块 | 职责 | 成熟度 |
|------|------|--------|
| `nexus-core` | 结算链节点：共识（DPoS 默认 / PoS 可选）、P2P、存储、RPC、合约引擎 | 较完整（PoS 调度器待补） |
| `nexus-gateway` | 商户支付网关：订单、路由、Webhook、清结算/风控/合规关卡接入 | 较完整 |
| `nexus-bridge` | 跨链桥：锁定/铸造/销毁/解锁状态机、Relayer 网络、流动性管理 | 核心完整，外部链适配器为骨架 |
| `nexus-consortium` | 联盟链/侧链：完整 PoA 链、国密 SM2/3/4 | 完整 |
| `nexus-settlement` | 清结算：复式账本、对账、风控规则、资金归集 | 完整 |
| `nexus-compliance` | 合规：KYC、AML 筛查、DID、信誉评分 | 完整 |
| `nexus-analytics` | 数据分析：交易图谱、监控告警、统计、导出 | 完整 |
| `nexus-oracle` | 预言机：多源价格聚合、链上治理、可验证随机数 | 完整（治理执行 P0 待修复） |
| `nexus-signing-service` | 签名服务：交易签名编排、MPC 传输层 | 编排完整，MPC 真实 GG20（可信协调器模型） |
| `nexus-wallet-service` | 钱包服务：白名单、冷热托管、审批流 | 白名单/审批完整，托管为模拟 |
| `mpc-engine` | Rust gRPC MPC 密码学引擎 | **真实 GG20（multi-party-ecdsa），可信协调器模型** |
| `nexus-explorer` | React + TS 区块浏览器 | 可用 |
| `nexus-sdk` | Java SDK：RPC、钱包、支付编排、跨链/稳定币客户端 | 较完整 |
| `nexus-rpc-doc` | RPC API 文档 | 参考 |

## 成熟度声明（重要）

为避免"宣称能力 >> 实际能力"，以下组件的真实状态如实标注。**v2.0.0-rc1 已完成 Phase 5 真实化改造**，各组件状态如下：

### MPC 多方签名（v2.0.0-rc1 真实化）

- **Rust `mpc-engine`**：已接入 ZenGo-X/KZen `multi-party-ecdsa` 0.8.1 crate，实现**真实 GG20 门限 ECDSA**（真实 Paillier、Feldman VSS、MtA、ZK 证明，产出可被标准 secp256k1 验证的签名）。
- **Java MPC 传输层**：`GrpcMpcTransportStub` + `MpcTransportGrpcServer` 实现**真实 gRPC over HTTP/2** 传输，支持 P2P 消息路由。
- **部署模型限制（诚实声明）**：当前为「可信协调器」模型——全部 n 方私钥份额驻留同一进程内存，**门限容错属性失效**（进程被攻破即等价单点签名）。完全分散式部署（t-of-n 方被攻破不泄露私钥）为 v2.2.0 演进目标。
- **编译状态**：代码完成但**未编译验证**（开发环境缺少 C 编译器）。
- **传输安全**：gRPC 默认明文，无 mTLS 实现代码（P0，v2.1.0 修复）。

### ZK 证明（v2.0.0-rc1 真实化）

- **`Groth16ProofSystem`**：基于 BouncyCastle secp256k1 + **Schnorr 知识证明 + Fiat-Shamir 变换**，包含真实 R1CS 约束系统。
- **诚实声明**：secp256k1 **不支持双线性配对**，本实现**非真实 Groth16**，用 Schnorr 协议替代配对验证。三重降级：halo2 (FROZEN) → Groth16 (声称) → Schnorr (实际)。
- **安全限制**：verifier 不验证 witness 满足 R1CS（P0），toxic waste 未销毁（P0），R1CS 约束严重不完备（P0，缺少签名验证/nonce/gas 等约束）。
- **不具备通用电路 ZK 安全属性**，仅可用于逻辑流程验证。真实 ZK 待接入 halo2 / Plonk / gnark（v2.1.0+）。

### L2 Rollup（v2.0.0-rc1 真实化）

- **Optimistic 路线**：欺诈证明单步验证为真实逻辑。
- **ZK 路线**：依赖上述 Schnorr 证明系统，不具备 ZK 安全属性。
- **L2-L1 真实化**：Hardhat L1 测试环境 + `L2Bridge.sol` Solidity 合约已就绪，但端到端测试因 Hardhat EDR 不兼容跳过（未完成真实 L1 节点验证）。

### 治理执行（v2.0.0-rc1 真实化）

- **`GovernanceExecutionDispatcher`**：监听 `ProposalStatusChangedEvent`，按提案类型分发到 `SoftwareUpgradeExecutor` / `TreasurySpendExecutor`。
- **执行流程真实**：解析 payload → 校验 → 执行 → 审计日志 → 发布事件 → 状态回写。
- **安全限制（P0）**：事件源无认证、审计日志无持久化、转账哈希用 `hashCode()` 生成。软件升级配置写入为占位（未接入 Nacos）。

### PoS 共识

- `propose()` 权益加权出块逻辑已实现，但运行时调度器与真实 Ed25519 验签待补全（默认共识仍为 DPoS）。

### 资产承载限制

上述组件在达到生产级安全属性前，**不应用于承载真实资产**。详见 [安全审计报告](docs/audit/v2.0.0-rc1-security-audit.md)。

## 研究层冻结与解冻（ADR-001）

依据 [ADR-001](docs/adr/ADR-001-research-layer-freeze.md)，研究层模块经历「冻结 → Phase 5 真实化 → 条件解冻」过程：

- **mpc-engine**：**已解冻**（真实 GG20，可信协调器模型限制）
- **nexus-core L2 ZK 证明系统**：**条件解冻**（Schnorr 知识证明，非真实 Groth16，P0 待修复）
- **nexus-core L2 L1 合约交互**：**条件解冻**（Hardhat 环境就绪，EDR 兼容性待解决）
- **nexus-oracle 治理执行**：**条件解冻**（执行流程真实，P0 待修复）
- **halo2 / Plonk 后端**：**仍冻结**，降级为 Groth16（实际 Schnorr）

ADR-001 状态：**Resolved**（2026-08-10）。

## 安全审计

v2.0.0-rc1 候选版本已完成安全审计，审计报告见 [docs/audit/v2.0.0-rc1-security-audit.md](docs/audit/v2.0.0-rc1-security-audit.md)。

**审计发现汇总**：

| 级别 | 数量 | 已修复 | 未修复 |
|------|------|--------|--------|
| P0（严重） | 8 | 0 | 8 |
| P1（高危） | 8 | 0 | 8 |
| P2（中危） | 15 | 0 | 15 |

**发布建议**：v2.0.0-rc1 在满足诚实声明前置条件下可作为候选版本发布，P0 发现列入 v2.1.0 修复计划。

## 许可证

[Apache License 2.0](LICENSE)

## 文档

- [PRD](PRD.md) — 产品需求
- [ARCHITECTURE](ARCHITECTURE.md) — 架构与模块地图
- [CHANGELOG](CHANGELOG.md) — 版本变更与勘误
- [安全审计报告 v2.0.0-rc1](docs/audit/v2.0.0-rc1-security-audit.md) — Phase 5 安全审计
- [docs/adr/ADR-020-version-strategy.md](docs/adr/ADR-020-version-strategy.md) — 版本治理策略
- [docs/adr/ADR-001-research-layer-freeze.md](docs/adr/ADR-001-research-layer-freeze.md) — 研究层冻结与解冻决策
