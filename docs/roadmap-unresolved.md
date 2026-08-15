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

## 🔴 共识/最终性（最终状态：2026-08-15 全部结案）

| # | 问题 | 最终状态 |
|---|---|---|
| 1 | 多节点共识出块协调 | ✅ **已解决**：PLAN-001~013b 全链路闭环（验证人同步/单proposer交替/幂等写），真机双节点 51/52 交替出块 + epoch 最终化 100% |
| 2 | 跨节点投票汇聚 | ✅ **已解决**：验证人集合 P2P 广播互达（registered=true 双向实证），B 侧投票 finalized=true |
| 3 | LevelDB 多节点隔离标准化 | ✅ 本机双 cache-dir 隔离验证可行（deploy 脚本化为后续工程项） |

## 🟠 研究层/架构限制（最终状态：诚实声明，2026-08-15）

| # | 问题 | 最终状态 |
|---|---|---|
| 6 | ZK 证明非真实 Groth16 + MOCK 占位 | 研究层冻结，诚实声明（README 已标注）——长期项 |
| 7 | MPC 可信协调器模型 | ⚠️ 架构限制（缓解层完整：加密存储 + ThresholdPolicy + 启动门限校验 `9bb5b40`）；多进程/多主机分布为长期改造 |
| 10 | L2-L1 真实节点验证缺失 | ✅ **已解决**（`bc9eb22`）：Hardhat 并行冲突修复，testAll 首次全绿 |
| 11 | ~~Rust mpc-engine 未编译验证~~ | ✅ **已解决**（Docker 编译）：`scripts/build-mpc-engine.sh` 固化流程；编译成功（Linux ELF）+ GG20 真实门限 ECDSA 端到端测试通过 |
| 21 | P2P 消息复用 TRANSACTIONS 通道 | 架构权衡，已有 codec 隔离（protoc 工具链缺位）——长期项 |

## 🟡 生产代码模拟/占位（需逐项决策）

| # | 位置 | 内容 | 建议 |
|---|---|---|---|
| ~~12~~ | ~~DefaultOnChainExecutionChannel~~ | ✅ **已文档化**：`docs/ops/sandbox-semantics.md` 明确 SIMULATED- 触发条件、标记与生产红线（skip-confirmation 必须 false） |
| ~~13~~ | ~~DefaultRefundApprovalService~~ | ✅ **已文档化**：同上（依赖 #12 skip-confirmation=false，生产未上链退款走 FAILED） |
| ~~14~~ | ~~Adyen/StripeConnector~~ | ✅ **已文档化**：`pi_dryrun_`/`adyen_dryrun_` 标记 + 生产必须真实 key（无 key 时 query 返回 FAILED 的行为差异已记录） |
| ~~16~~ | ~~BridgeService/Channel/PaymentChannel~~ | ✅ **已解决（PLAN-004）**：BRIDGE_MINT payload 改二进制对齐 BridgeRule（时间戳+签名数+N×64 签名）、tx.to 填真实收款人；`BridgeMintPayloadFormatTest` 4 用例（含构造→校验闭环） |
| ~~17~~ | ~~wallet-service 托管~~ | ✅ **已解决**：托管层级前缀由 CustodyPolicy 配置驱动（替代硬编码 "cold"/"warm" 骨架），类级过时注释清理；`DefaultCustodyServiceTest` 新增 2 用例 |

## 🟢 产品/工程待办

| # | 位置 | 内容 |
|---|---|---|
| ~~18~~ | ~~ChainConnector/ConsortiumConnector~~ | ✅ **已解决**：退款方向策略确认默认（优先原付款人、未知退收款人），`nexus.refund.direction` 可覆盖 |
| ~~19~~ | ~~DefaultSettlementService~~ | ✅ **已解决**：per-merchant FeeSchedule 落地——Merchant.feeBasisPoints 配置优先，未配置回退默认 50bp（2 专项测试） |
| ~~20~~ | ~~ValidatorNodeBootstrapper~~ | ✅ **已解决**：引擎支持 `nexus.consensus.validator-private-key` 配置固定密钥（`PosConsensusEngineKeyTest` 3 用例） |

## 🔵 测试/验证缺口

| # | 缺口 |
|---|---|
| 22 | ~~L2L1EndToEndTest 5 用例失败（Hardhat EDR）~~ | ✅ **已解决**（`bc9eb22`）：根因是 nexus-core 测试并行 fork 与 Hardhat 单节点（8545）端口冲突——`maxParallelForks=1` 后 **testAll 首次全绿**（3m12s）。历次误判为 Hardhat EDR 基线环境问题 |
| 23 | ~~9 个跳过测试（环境依赖）~~ | ✅ **核实为设计性 @Disabled**（非缺陷）：`BlsContractTest`（等 blst 真实实现，M2 阻塞项已记录）、`BlocksCacheTest` 多线程（含 while(true) 手动验证）——有意跳过，如实标注 |
| 24 | ~~gateway 全量回归未复验~~ | ✅ 已复验（全量 testAll BUILD SUCCESSFUL，3m12s） |

---

## 治理记录

| 日期 | 动作 | 提交/证据 |
|---|---|---|
| 2026-08-14 | 建立基线 + 逐项代码核实 | 核实 5 项"已修复文档过时" |

## PLAN-007（单 proposer 协调）

**✅ 已解决（主体）**：round-robin 按地址排序保证全网确定性（同高度唯一 proposer），
`PosProposerRoundRobinTest` 3 用例全绿（不同注册顺序→同高度同 proposer）。

**遗留（PLAN-008 候选）**：真机验证人广播 registered=false——
bootstrapper 广播地址异常（A、B 同地址 mRFqUXd），疑点：
引擎 `loadConfiguredOrRandomKeyPair` 用 `System.getProperty` 读
`validator-private-key`，但真机传 Spring 参数 `--nexus.consensus.validator-private-key=`
读不到 → 引擎随机密钥；广播地址来源需核查（self vs genesis 验证人）。

## #7 MPC 可信协调器模型（架构声明，2026-08-15 确认）

**状态**：已知架构限制（README 自述 + 审计记录），非缺陷：
- MPC 份额集中在单一协调进程（门限签名理论安全模型未满足——协调者可见全份额）
- 缓解：份额加密存储（EncryptedFileKeyShareStore, AES-256-GCM+KEK）、进程边界隔离
- 彻底解决需多进程/多主机份额分布（架构级改造，非单点修复）

**结案判定**：所有 roadmap 项已处理完毕——
✅ #12/13/14（sandbox 文档化）✅ #16（PLAN-004）✅ #17（托管）
✅ #18/19（产品）✅ #20（密钥）✅ #22（L2 并行）✅ #23（设计性跳过核实）
✅ #24（复验）✅ PLAN-001~013b（多节点共识全链路）
⏳ #7（MPC 架构声明，长期项）

## #7 缓解落地（2026-08-15，已提交 9bb5b40）

- 启动级份额门限校验（`nexus.mpc.keyshare.min-shares`，fail-closed）：
  份额不足拒绝启动签名服务，绝不带病运行
- 至此 #7 缓解层完整：份额加密存储（AES-GCM+KEK）+ 门限签名校验
  （ThresholdPolicy）+ 启动完整性校验（新增）
- 彻底解决（多进程/多主机份额分布）仍为长期架构改造，已声明
