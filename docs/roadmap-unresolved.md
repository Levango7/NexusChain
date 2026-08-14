# NexusChain 未解决问题全景基线（Roadmap-Unresolved）

- **创建**：2026-08-14
- **状态**：Active（追踪基线，逐项治理）
- **原则**：诚实标注，不粉饰。修复一项标记一项，附带证据。
- **重要修正**：初版基线照抄 README/审计报告的"未修"表述，逐项核实后发现
  **多项实为"已修复但文档过时"**。本文档以代码证据为准。

---

## 核实结论（2026-08-14 逐项代码核实）

| # | 原列项 | 真实状态 | 证据 |
|---|---|---|---|
| 4 | 治理事件源无认证 | ✅ **已修复**（README 过时） | `GovernanceExecutionDispatcher:126` isTrustedSource 白名单实际调用 |
| 5 | 治理审计日志无持久化 | ✅ **已修复**（README 过时） | `GovernanceAuditLog` + `GovernanceAuditLogRepository`（JPA 内存+DB 双写） |
| 7 | MPC 可信协调器模型 | ⚠️ 架构限制（真实） | 所有份额同进程，门限失效（README 自述） |
| 8 | MPC 密钥明文无 zeroize | ✅ **已修复**（README 过时） | `mpc/util/ZeroizingByteArray.java` 存在 |
| 9 | MPC gRPC 无 TLS/无认证 | ✅ **已修复**（README 过时） | `GrpcMpcTransportStub` mTLS 实现（usePlaintext=false + SslContext） |

**结论**：README/审计报告的 P0/P1"未修"表述大量过时——**项目真实完成度高于文档**。

---

## 🔴 真正未解决：共识/最终性

| # | 问题 | 现状 | 修复路径 |
|---|---|---|---|
| 1 | 多节点共识出块协调 | 仅单节点 FINALIZED 闭环实证（epoch 1-10 稳定 100%） | 单链多节点拓扑 + proposer 轮换 |
| 2 | 跨节点投票汇聚 | P2P 传输通，但 B 拒绝 A 投票（验证人集合未同步） | 验证人集合跨节点广播/同步 |
| 3 | LevelDB 多节点隔离标准化 | 本机双 cache-dir 验证可行 | deploy/ 脚本化 |

## 🟠 真实研究层/架构限制（诚实声明范畴）

| # | 问题 | 状态 |
|---|---|---|
| 6 | ZK 证明非真实 Groth16（Schnorr 降级）+ MOCK 占位证明 7 处 | 研究层冻结，诚实声明（README 已标注） |
| 10 | L2-L1 真实节点验证缺失（Hardhat EDR，5 用例失败） | 环境问题，基线既有 |
| 11 | Rust mpc-engine 未编译验证（缺 C 编译器） | 环境问题 |
| 21 | P2P 消息复用 TRANSACTIONS 通道（protoc 工具链缺位） | 架构权衡，已有 codec 隔离 |

## 🟡 生产代码模拟/占位（需逐项决策）

| # | 位置 | 内容 | 建议 |
|---|---|---|---|
| 12 | DefaultOnChainExecutionChannel | sandbox SIMULATED- 假哈希 | 沙箱语义，生产禁用需文档化 |
| 13 | DefaultRefundApprovalService | 退款 sandbox 假哈希 | 已 fail-closed 正确，sandbox 模拟可接受 |
| 14 | Adyen/StripeConnector | 无 key dry-run 模拟 | 需真实 key 验收 |
| 16 | BridgeService/Channel/PaymentChannel | placeholder 零字节地址 | 需真实签名路径接入 |
| ~~17~~ | ~~wallet-service 托管~~ | ✅ **已解决**：托管层级前缀由 CustodyPolicy 配置驱动（替代硬编码 "cold"/"warm" 骨架），类级过时注释清理；`DefaultCustodyServiceTest` 新增 2 用例 |

## 🟢 产品/工程待办

| # | 位置 | 内容 |
|---|---|---|
| 18 | ChainConnector/ConsortiumConnector:210 | 退款方向策略未定 |
| 19 | DefaultSettlementService:38 | 商户费率（per-merchant FeeSchedule）未实现 |
| ~~20~~ | ~~ValidatorNodeBootstrapper~~ | ✅ **已解决**：引擎支持 `nexus.consensus.validator-private-key` 配置固定密钥（`PosConsensusEngineKeyTest` 3 用例） |

## 🔵 测试/验证缺口

| # | 缺口 |
|---|---|
| 22 | L2L1EndToEndTest 5 用例失败（Hardhat EDR） |
| 23 | 9 个跳过测试（环境依赖） |
| 24 | gateway 全量回归未在本轮改动后复验 |

---

## 治理记录

| 日期 | 动作 | 提交/证据 |
|---|---|---|
| 2026-08-14 | 建立基线 + 逐项代码核实 | 核实 5 项"已修复文档过时" |
