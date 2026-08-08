# NexusChain

NexusChain 是一个**基于自研区块链的支付编排平台（Payment Orchestration Platform）**。它在自研结算链之上构建统一支付网关、跨链桥、交易所钱包与清结算/合规/分析中间服务层，面向中小电商与 SaaS 提供多渠道、多链的支付受理能力。

> **定位**：区块链是底层结算基础设施，不是产品本身。产品价值在于统一支付 API、智能路由与清结算。

## 快速开始

### 环境要求

- JDK 17+（nexus-consortium 亦已升级至 17）
- Gradle 8.5（随 wrapper 提供）
- 可选：Docker + docker-compose（一键起全栈）

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
| `nexus-oracle` | 预言机：多源价格聚合、链上治理、可验证随机数 | 完整 |
| `nexus-signing-service` | 签名服务：交易签名编排、MPC 传输层 | 编排完整，MPC 为内存桩 |
| `nexus-wallet-service` | 钱包服务：白名单、冷热托管、审批流 | 白名单/审批完整，托管为模拟 |
| `mpc-engine` | Rust gRPC MPC 密码学引擎 | **骨架（DKG/Sign/Aggregate 未实现）** |
| `nexus-explorer` | React + TS 区块浏览器 | 可用 |
| `nexus-sdk` | Java SDK：RPC、钱包、支付编排、跨链/稳定币客户端 | 较完整 |
| `nexus-rpc-doc` | RPC API 文档 | 参考 |

## 成熟度声明（重要）

为避免"宣称能力 >> 实际能力"，以下组件的真实状态如实标注：

- **MPC 多方签名**：当前为**接口骨架**。Rust `mpc-engine` 的 DKG/Sign/Aggregate 均未实现（待接入 tss-lib / multi-party-ecdsa）；Java 侧 MPC 传输为内存桩。涉及资金签名处使用 **n-of-n 独立 ECDSA 多签**（BouncyCastle，真实可验签），**非门限密码学**。
- **ZK 证明**：当前为**模拟实现**。`Groth16ProofSystem` 实际基于 Schnorr + Pedersen 承诺（secp256k1 无双线性配对，无法构成真实 Groth16）。**不具备零知识证明安全属性**，真实 ZK 待接入 halo2 / Plonk / gnark。
- **L2 Rollup**：Optimistic 路线的欺诈证明单步验证为真实逻辑，但 L1 默认内存模拟、未集成进网关；ZK 路线依赖上述模拟证明系统。
- **PoS 共识**：`propose()` 权益加权出块逻辑已实现，但运行时调度器与真实 Ed25519 验签待补全（默认共识仍为 DPoS）。

上述组件在达到生产级安全属性前，**不应用于承载真实资产**。

## 研究层冻结（ADR-001）

依据 [ADR-001](docs/adr/ADR-001-research-layer-freeze.md)，以下研究层模块**冻结进一步开发**，仅维护诚实声明，工程预算集中到产品层（gateway/settlement/compliance）：

- **mpc-engine**：Rust gRPC MPC 引擎骨架，DKG/Sign/Aggregate 未接入真实密码学库
- **nexus-core L2 ZK 证明系统**：Groth16 简化版（Schnorr 协议），非完整配对
- **nexus-core L2 L1 合约交互**：Web3j 实现完成，但未在真实 L1 节点测试
- **nexus-oracle 治理执行**：PARAMETER_CHANGE 已接线，SOFTWARE_UPGRADE/TREASURY_SPEND 为占位

解冻条件：产品层达到生产可用 + 专门的密码学团队 + 真实 L1 测试环境。

## 许可证

[Apache License 2.0](LICENSE)

## 文档

- [PRD](PRD.md) — 产品需求
- [ARCHITECTURE](ARCHITECTURE.md) — 架构与模块地图
- [CHANGELOG](CHANGELOG.md) — 版本变更与勘误
- [docs/decisions/ADR-020-version-strategy.md](docs/decisions/ADR-020-version-strategy.md) — 版本治理策略
- [docs/adr/ADR-001-research-layer-freeze.md](docs/adr/ADR-001-research-layer-freeze.md) — 研究层冻结决策
