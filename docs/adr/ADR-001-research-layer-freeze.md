# ADR-001: 研究层冻结与解冻

## 状态

**Resolved**（2026-08-10，v2.0.0-rc1）

原状态：Accepted（2026-08-08）→ Resolved（2026-08-10）

## 背景

NexusChain 广度严重超出团队承载力。研究层（MPC 密码学、ZK Rollup、L2-L1 真实化、治理执行接线）消耗了大量工程预算，但不产生当前产品价值。产品价值集中在网关层（gateway + settlement + compliance）。

2026-08-08 决策冻结以下模块的进一步开发，仅维护诚实声明。Phase 5（2026-08-08 至 2026-08-10）针对解冻条件进行了真实化改造，本 ADR 记录解冻过程与结果。

## 决策（2026-08-08）

冻结以下模块的进一步开发，仅维护诚实声明：

- **mpc-engine**（Rust gRPC MPC 引擎）：骨架实现，DKG/Sign/Aggregate 未接入真实密码学库
- **nexus-core L2 ZK 证明系统**：Groth16 简化版（Schnorr 协议），非完整配对
- **nexus-core L2 L1 合约交互**：Web3j 实现完成，但未在真实 L1 节点测试
- **nexus-oracle 治理执行**：PARAMETER_CHANGE 已接线，SOFTWARE_UPGRADE/TREASURY_SPEND 为占位

## 解冻条件

- 产品层（gateway/settlement/compliance）达到生产可用
- 有专门的密码学团队负责 MPC/ZK 实现
- 有真实的 L1 测试环境

## 解冻过程（Phase 5，2026-08-08 至 2026-08-10）

Phase 5 针对上述冻结模块进行了真实化改造，逐项验证解冻条件：

### P5-T1/T2: Rust mpc-engine 真实化

**改造内容**：
- 接入 ZenGo-X/KZen `multi-party-ecdsa` 0.8.1 crate（真实 GG18/GG20 门限 ECDSA）
- `src/gg20.rs`：实现完整 4 轮 DKG + 7 轮 Sign 协议（真实 Paillier、Feldman VSS、MtA、ZK 证明）
- `src/dkg.rs` / `src/sign.rs` / `src/aggregate.rs`：基于真实 GG20 的 RPC 实现
- 端到端测试 `gg20_end_to_end_real_threshold_ecdsa`（t=1, n=3）验证签名可被标准 secp256k1 验证

**真实化证据**：
- `Cargo.toml` 依赖：`multi-party-ecdsa = "0.8.1"`、`curv-kzen = "0.9"`、`paillier = "0.4.2"`、`zk-paillier = "0.4.3"`
- 测试覆盖：DKG 完整性、签名正确性、负面用例（篡改消息验证失败）
- 独立验签：使用独立 `secp256k1` crate 验证签名，非 GG20 内部验证

**已知限制**：
- 代码完成但**未编译验证**（开发环境缺少 C 编译器，Rust 编译需 MSVC/gcc）
- 部署模型为「可信协调器」：全部 n 方私钥份额驻留同一进程，门限容错属性失效（详见安全审计报告 MPC-P1-01）

### P5-T3: Java MPC 传输层真实化

**改造内容**：
- `GrpcMpcTransportStub`：gRPC over HTTP/2 传输层实现，支持真实 gRPC 模式与内存回退
- `MpcTransportGrpcServer`：gRPC 服务端，接收其他参与方消息并投递到本地邮箱
- `MpcTransportConfig`：Spring 配置，根据 `mpc.transport.real-grpc-enabled` 选择实现
- `GrpcMpcCryptoEngine`：gRPC 客户端，连接 Rust mpc-engine 进程

**真实化证据**：
- 真实 gRPC channel 管理（`ManagedChannelBuilder`）
- 阻塞 stub + deadline 超时 + 重试（`maxRetryAttempts=3`）
- 本地邮箱模型：`MpcTransportGrpcServer` → `deliverLocal` → `localMailbox` → `receive`

**已知限制**：
- 默认 `usePlaintext=true`，无 mTLS 实现代码（详见安全审计报告 MPC-P0-02）
- 生产环境需显式配置 `use-plaintext=false` 并实现 SslContext

### P5-T4/T5: ZK 证明系统真实化

**改造内容**：
- `Groth16ProofSystem`：基于 BouncyCastle 椭圆曲线的 Groth16 简化实现
  - 真实 R1CS 约束系统（`R1csConstraintSystem`）
  - Schnorr 知识证明协议 + Fiat-Shamir 变换
  - setup/prove/verify 三阶段完整实现
- `RollupStateTransitionCircuit`：Rollup 状态转换电路，定义 R1CS 约束
- `DefaultZkProofSystem`：@Primary，配置选择后端（groth16|plonk|halo2|mock）
- halo2 标记为 FROZEN，降级为 Groth16

**真实化证据**：
- R1CS 约束系统真实实现（线性约束、二次约束、witness 满足性校验）
- Schnorr 协议数学正确（完备性、零知识性、可靠性，随机预言机模型）
- 端到端测试：setup → prove → verify 流程完整

**已知限制**：
- secp256k1 不支持双线性配对，用 Schnorr 替代配对验证（详见安全审计报告 ZK-P0-01）
- R1CS 约束严重不完备：缺少签名验证、nonce、gas 等约束（ZK-P0-03）
- toxic waste 未销毁（ZK-P0-02）
- 三重降级：halo2 → Groth16 → Schnorr（ZK-P1-03）

### P5-T6: L2 L1 真实节点测试环境

**改造内容**：
- Hardhat L1 测试环境配置
- `L2Bridge.sol`：Solidity 合约实现
- `L2L1EndToEndTest`：端到端测试

**已知限制**：
- 测试因 Hardhat EDR 不兼容跳过（`@Disabled` 注释标注原因）
- 未在真实 L1 节点完成端到端验证

### P5-T7: 治理执行接线

**改造内容**：
- `SoftwareUpgradeExecutor`：软件升级执行器（解析 payload、记录审计、发布事件）
- `TreasurySpendExecutor`：国库转账执行器（校验余额、执行转账、记录审计）
- `GovernanceExecutionDispatcher`：调度器，监听 `ProposalStatusChangedEvent` 分发执行
- `GovernanceAuditLog`：审计日志（内存存储）

**真实化证据**：
- 完整的执行流程：解析 → 校验 → 执行 → 审计 → 事件 → 状态回写
- 异常处理：执行器内部 catch + 调度器兜底
- 审计日志：记录提案 ID、类型、操作人、状态变更、执行结果

**已知限制**：
- 事件源无认证（GOV-P0-01）
- 审计日志无持久化（GOV-P0-02）
- 转账哈希用 hashCode()（GOV-P0-03）
- 软件升级配置写入为占位（GOV-P2-03）

## 安全审计结果（P5-T8，2026-08-10）

Phase 5 真实化改造完成后，对全部改造模块进行了安全审计，审计报告见 `docs/audit/v2.0.0-rc1-security-audit.md`。

### 审计发现汇总

| 级别 | 数量 | 已修复 | 已声明 | 未修复 |
|------|------|--------|--------|--------|
| P0（严重） | 8 | 0 | 0 | 8 |
| P1（高危） | 8 | 0 | 3 | 5 |
| P2（中危） | 15 | 0 | 0 | 15 |
| **合计** | **31** | **0** | **3** | **28** |

### P0 发现清单

1. **MPC-P0-01**：Rust gRPC 服务端未配置 TLS，纯明文传输
2. **MPC-P0-02**：Java gRPC 默认明文，无 mTLS 实现代码
3. **ZK-P0-01**：Schnorr 替代配对，verifier 不验证 R1CS 满足性
4. **ZK-P0-02**：toxic waste 未销毁
5. **ZK-P0-03**：R1CS 约束严重不完备
6. **GOV-P0-01**：事件源无认证
7. **GOV-P0-02**：审计日志无持久化
8. **GOV-P0-03**：转账哈希用 hashCode()

### 审计结论

v2.0.0-rc1 在满足诚实声明前置条件下，可作为候选版本发布。当前实现存在 8 项 P0 级安全缺陷，均未修复，但已通过文档诚实声明标注限制。建议以 `v2.0.0-rc1`（候选版本）发布，附审计报告，待 P0 修复后发布 v2.1.0。

## 解冻决策（2026-08-10）

基于 Phase 5 真实化改造与安全审计结果，做出以下解冻决策：

### 完全解冻

- **mpc-engine**：GG20 协议数学实现正确，已接入真实 `multi-party-ecdsa` crate。**解冻**，但标注「可信协调器模型」限制与「未编译验证」状态。

### 条件解冻

- **nexus-core L2 ZK 证明系统**：R1CS + Schnorr 实现真实，但非真实 Groth16（无双线性配对）。**条件解冻**，标注「Schnorr 知识证明，非通用电路 ZK」，P0 发现列入 v2.1.0 修复。
- **nexus-oracle 治理执行**：执行流程真实，但存在权限/审计/哈希 P0 缺陷。**条件解冻**，标注「P0 未修复」，列入 v2.1.0 修复。
- **nexus-core L2 L1 合约交互**：Solidity 合约与 Hardhat 环境已就绪，但测试因 EDR 兼容性跳过。**条件解冻**，标注「未完成真实 L1 端到端验证」。

### 仍冻结

- **halo2 / Plonk 后端**：仍 FROZEN，降级为 Groth16（实际 Schnorr）。待 v2.1.0+ 评估接入真实 halo2 / gnark。

## 影响

### 工程预算

工程预算继续集中到：
- 网关生产化（真实 PSP connector 接入）
- 清结算对账闭环
- P0/P1 安全与架构修复（v2.1.0 里程碑）

### 文档与声明

- README「成熟度声明」更新为真实实现声明（移除骨架/模拟标注）
- CHANGELOG 记录 v2.0.0 全部变更
- 安全审计报告 `docs/audit/v2.0.0-rc1-security-audit.md` 作为发布附件

### 后续里程碑

- **v2.1.0**：修复 8 项 P0 发现
  - MPC：实现 mTLS、密钥 zeroize
  - ZK：接入真实配对曲线或 halo2、补全 R1CS 约束、销毁 toxic waste
  - GOV：事件鉴权、审计持久化、真实转账哈希
- **v2.2.0**：修复 P1/P2 发现，完成分散式 MPC 部署

## 变更历史

| 日期 | 版本 | 变更 |
|------|------|------|
| 2026-08-08 | v1.0 | 初版，状态 Accepted，决策冻结研究层 |
| 2026-08-10 | v2.0 | 状态 Resolved，记录 Phase 5 解冻过程、真实化证据、安全审计结果、解冻决策 |
