# NexusChain

NexusChain 是一个**基于自研区块链的支付编排平台（Payment Orchestration Platform）**。它在自研结算链之上构建统一支付网关、跨链桥、交易所钱包与清结算/合规/分析中间服务层，面向中小电商与 SaaS 提供多渠道、多链的支付受理能力。

> **定位**：区块链是底层结算基础设施，不是产品本身。产品价值在于统一支付 API、启发式路由与清结算。

**当前版本**：v2.1.0（2026-08-10，最新稳定版；[Unreleased] 节含 Phase 2 修复进行中）

## 快速开始

### 环境要求

- JDK 17+（nexus-consortium 亦已升级至 17）
- Gradle 8.5（随 wrapper 提供）
- 可选：Docker + docker-compose（一键起全栈）
- 可选：Rust toolchain（编译 mpc-engine，需 C 编译器）

### 构建环境要求（Rust / mpc-engine）

`mpc-engine` 为 Rust gRPC MPC 密码学引擎，`cargo check` / `cargo build` 需要以下环境之一：

- **MinGW 工具链（Windows 默认 GNU）**：安装 MinGW-w64，确保 `gcc.exe` 与 `dlltool.exe` 在 `PATH` 中。Rust 默认 `x86_64-pc-windows-gnu` target 依赖二者。
- **MSVC 工具链（推荐 Windows 生产环境）**：安装 Visual Studio Build Tools（含 C++ 桌面工作负载），并切换 Rust 默认 target：
  ```bash
  rustup target add x86_64-pc-windows-msvc
  rustup default stable-x86_64-pc-windows-msvc
  ```
  此路径不依赖 `gcc` / `dlltool`，与 NexusChain Java 侧同享 MSVC ABI。
- **Linux / macOS**：系统 `gcc` / `clang` 即可，无需额外配置。

> 若仅构建 Java 侧（`gradle build`），无需 Rust 环境。Rust 仅 `mpc-engine` 模块需要。

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

### 本地联调（容器化 Postgres + 原生 core）

core 的持久化层绑定 Postgres 方言，真机联调的正确姿势是 **Docker 起 Postgres、core 走 local profile 原生运行**。一键脚本会自检 Docker 引擎（未启动时自动拉起 Docker Desktop）并幂等保证 `127.0.0.1:55432` 上有健康的 PG（已有健康容器则复用，不重复创建）：

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1        # 仅确保 PG 就绪
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1 -StartCore   # PG 就绪后前台起 core

# Linux / CI（原生 Docker daemon）
./scripts/dev-pg-up.sh
START_CORE=1 ./scripts/dev-pg-up.sh

# 停掉 compose 管理的 PG（数据卷保留）
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-down.ps1
```

等价的手动流程：`docker compose up -d nexus-pgsql` 起库，再 `gradlew :nexus-core:nexus-core:run --args="--spring.profiles.active=local"` 跑 core（凭据与端口见 `nexus-core/nexus-core/src/main/resources/application-local.properties`，与 `docker-compose.yml` 的 `nexus-pgsql` 服务严格一致）。

## 模块清单

| 模块 | 职责 | 成熟度 |
|------|------|--------|
| `nexus-core` | 结算链节点：共识（DPoS 默认 / PoS 可选）、P2P、存储、RPC、合约引擎 | 较完整（PoS 基础层 + NexFinality 最终性层已闭环，多节点共识 2026-08 真机验证） |
| `nexus-gateway` | 商户支付网关：订单、路由、Webhook、清结算/风控/合规关卡接入 | 较完整 |
| `nexus-bridge` | 跨链桥：锁定/铸造/销毁/解锁状态机、Relayer 网络、流动性管理 | 核心完整，Solana/Avalanche 适配器已交付（含测试），其余外部链为骨架 |
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

### 治理执行（真实化完成，2026-08）

- **`GovernanceExecutionDispatcher`**：监听 `ProposalStatusChangedEvent`，按提案类型分发（软件升级/资金支出/验证者集变更）。
- **执行流程真实**：解析 payload → 校验 → 执行 → 审计日志（JPA 持久化）→ 发布事件 → 状态回写。
- **安全已补**：事件源白名单认证、审计日志持久化、软件升级 Nacos 配置发布（Open API）、转账哈希真实计算（fail-closed，无 `hashCode()` 占位）。

### PoS 共识（NexFinality 最终性已闭环，2026-08）

- 基础层闭环（ADR-029 审计基线）：权益加权出块提案、区块 8 步校验（真实 Ed25519 验签）、质押管理、罚没、验证者注册、出块奖励、P2P 同步。
- **NexFinality 最终性层（ADR-030/031）已实现并真机闭环**：BFT 双轮投票、2/3 质押权重判定、双签证据惩罚链、验证者集变更走治理（VALIDATOR_SET_CHANGE）、BLS 接口层（M3 阻塞项：blst 库绑定待环境解锁）。
- **多节点共识全链路**（PLAN-001~013b）：验证人 P2P 同步、单 proposer 轮换、共享链幂等写、分叉重组——双节点真机 51/52 交替出块 + epoch 最终化 100%。

### L2 Rollup（ZK 真实化进展，2026-08）

- **ZK 真实 Groth16 全链路**（方案 C）：zk-groth16-service（Rust arkworks，BN254 配对验证）+ Java 对接（fail-closed）+ Rollup 状态转换电路桥接 + setup 持久化/可信设置仪式外部注入 + MOCK 证明默认拒绝。
- **L2-L1 端到端**：Hardhat 并行冲突已修复（testAll 首次全绿）。

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
| P0（严重） | 8 | 8 | 0 |
| P1（高危） | 8 | 8 | 0 |
| P2（中危） | 15 | 10 | 5（已记录/降级） |

**发布建议**：v2.1.0 已完成全部 P0/P1 修复，可作为生产候选版本发布。

## 许可证

[Apache License 2.0](LICENSE)

## 文档

- [PRD](PRD.md) — 产品需求
- [ARCHITECTURE](ARCHITECTURE.md) — 架构与模块地图
- [CHANGELOG](CHANGELOG.md) — 版本变更与勘误
- [安全审计报告 v2.0.0-rc1](docs/audit/v2.0.0-rc1-security-audit.md) — Phase 5 安全审计
- [docs/adr/ADR-020-version-strategy.md](docs/adr/ADR-020-version-strategy.md) — 版本治理策略
- [docs/adr/ADR-001-research-layer-freeze.md](docs/adr/ADR-001-research-layer-freeze.md) — 研究层冻结与解冻决策
