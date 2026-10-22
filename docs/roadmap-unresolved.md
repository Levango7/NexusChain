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
| 6 | ZK 证明非真实 Groth16 + MOCK 占位 | ✅ **已解决（方案 C，2026-10 复核）**：真实 Groth16 由 `zk-groth16-service`（Rust arkworks，BN254 配对）提供；Java 远程对接 fail-closed、mock 默认拒绝；本地 Schnorr 仅为未配置远程时的降级路径并如实标注 |
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

| # | 缺口 | 状态与证据 |
|---|---|---|
| 22 | ~~L2L1EndToEndTest 5 用例失败（Hardhat EDR）~~ | ✅ **已解决**（`bc9eb22`）：根因是 nexus-core 测试并行 fork 与 Hardhat 单节点（8545）端口冲突——`maxParallelForks=1` 后 **testAll 首次全绿**（3m12s）。历次误判为 Hardhat EDR 基线环境问题 |
| 23 | ~~9 个跳过测试（环境依赖）~~ | ✅ **核实为设计性 @Disabled**（非缺陷）：`BlsContractTest`（等 blst 真实实现，M2 阻塞项已记录）、`BlocksCacheTest` 多线程（含 while(true) 手动验证）——有意跳过，如实标注 |
| 24 | ~~gateway 全量回归未复验~~ | ✅ 已复验（全量 testAll BUILD SUCCESSFUL，3m12s） |
| 25 | **ADR `security-ops-batch-design-2026-09-14.md` §1.5「Phase B：kind 演练重跑」未闭环**（2026-10-07 新录） | ⬜ 未做。该节把"gateway→signing 真实 Feign 互调"定为 Boot4 批的**最终验收标尺**（此前只验到编译+启动层），并声明产出 `docs/deploy/fullstack-drill-report-2026-09-14.md`。取证：该路径 `git log --all` **0 命中**（从未写入，非被删）；仓内唯一演练报告是 09-12 那次的六个发现，不含这 5 项；五项关键词在 `CHANGELOG.md` 零命中。**不是文档过时，是验证没落**；且**当前不可重跑**——该演练的资产（`Dockerfile.java-drill`、`fullstack-drill-{infra,gateway,services}.yaml`、`nacos-init-v3.sh`，清单见 `fullstack-drill-report-2026-09-12.md:100-106`）在**仓外的** `F:\Nexus\build\`，本机现已不存在（`git ls-files \| grep -i drill` 只剩 `deploy/kind/` 两份与 09-12 报告）。先入库工具链，"重跑演练"才是任何人可执行的动作 |
| 26 | ~~最终性投票的跨节点 P2P 投递零测试覆盖~~（2026-10-07 新录） | ✅ **已解决（2026-10-21，PLAN-015 Phase 6A）**：新增 `FinalityVoteP2PRoundTripTest`，真实触达 `FinalityVoteBroadcaster.sendOverP2P()` → `PeerServer.broadcast(Transactions/VOTE)`，再把捕获载荷构造成 `Message(TRANSACTIONS)` 交给 `SyncManager.onMessage()`，经 `onTransactions()` 的 `FinalityVoteP2PCodec.isVotePayload` 分流后进入对端 `FinalityGadget` 完成最终化。原缺口描述（两参构造器 peerServer=null、无测试触达两侧）保留作历史记录 |
| 27 | **最终性故障注入剩余矩阵**（2026-10-21 新录） | 🟡 **首批已交付（PLAN-016 Phase 6B）**：重复/延迟/乱序投票、双签顺序反转、JDBC/最终性表短时不可用；修复写库失败污染内存、最终化提前对外可见、`pendingBlock` 60 秒超时。**第二切片已交付（PLAN-018 Phase 6C）**：持久化投票 outbox（`finality_vote_outbox` 表 + `FinalityOutboxManager`，跨进程重启存活）、完整 `Vote` 签名随票落库（重启后能恢复 Vote 对象并**自行补最终化**）、**接收侧独立重试**（远端票写库失败不再蒸发）。**第二切片已交付（PLAN-021 Phase 6C）**：最终性视图**跨节点收敛**（每 5 秒把库里的新票/新终局拉进本地视图，合并必须过与提交路径同一套绑定校验、去重、单调）⇒ 3 节点验收里 A/B/C 的对外权重视图从 3000/2000/1000 收敛为**一致 3000/3000**，掉线节点重启后完全追平。**仍未做**：真实网络分区、完整拜占庭矩阵、**独立存储部署下的跨节点事实传播**（收敛只在共享存储拓扑下成立）、数据库整体不可用时只剩内存队列兜底（进程崩溃仍丢票）、迁移前老数据没有签名。PG 整体停止的 `AccountDB`/提案/状态接口问题由 #28 首批修复 |
| 29 | **区块写库失败被吞 + 接收侧把存储故障当坏块丢弃**（2026-10-22 新录，PLAN-019 派生） | 🟡 **接收侧已修（PLAN-019 Phase 6C）**：存储不可用时不再丢区块，改交 `PendingBlocksManager` 走重试 + 持久化 outbox（`pending_block_outbox` 表）。**仍未做**：① 「写库失败」被 `RDBMSBlockChainImpl.writeBlock` 吞成 `false`，`StateDB` 跳过该块（DB 里该高度区间短暂缺块，补写靠后续确认轮次），运维看不到失败聚合；② outbox 行无过期清理（永远写不进去的区块会滞留）；③ 未在多副本/多机下验证 |
| 30 | **节点级故障容忍与重启追平**（2026-10-22 新录，PLAN-020） | 🟡 **单主机 3 节点已交付（PLAN-020）**：杀掉 1/3 验证人 ⇒ 链继续出块、最终性不中断（2/3 阈值，epoch 1942 `voted=3000/3000` FINALIZED）；掉线节点重启后**完全追平并重新参与投票**，检查点全程缺口 0、ERROR 0。**顺带修掉一个真 bug**：`ValidatorSetPersistence.replay()` 在 `RowCallbackHandler` 里多调 `rs.next()` ⇒ **验证人集隔行丢失**（3 个只重放 2 个）⇒ 重启节点认不全 proposer ⇒ 报 "unknown proposer" ⇒ **永久卡在缺口高度无法采纳对端区块**（已修 + 回归测试 `ValidatorSetPersistenceReplayTest`，旧实现下 2/2 失败）。**仍未做**：真正多机（独立存储 + 网络分区 + 断电 + 磁盘损坏 + PG 主备切换）、完整拜占庭矩阵 |
| 28 | **PG 整体停止打穿 AccountDB、提案与状态接口**（2026-10-21 新录） | 🟡 **第一切片已交付（PLAN-017 Phase 6C）**：账户读取 fail-closed + 明确 `AccountStoreUnavailableException`；`/consensus/status`/P2P 握手改内存链头；proposer 安全暂停；`/consensus/storage-status` 降级观测；DBCP2 5 秒快速失败；异步采纳异常隔离。真实 PG 停止 43.9 秒验收：状态接口 HTTP 200、ERROR 0、恢复后 3549/3549、最终性检查点无缺口。**仍非 PG 高可用**：无主备切换、无持久化区块 outbox、未做多机网络分区/主机断电；PG 停止期间链停止出块 |
| 31 | **依赖库已知漏洞待升版（SCA 债务）**（2026-10-22 新录，PLAN-017 之后的 #177 附带发现） | 🟡 **JVM 侧已升版（PLAN-023，2026-10-22）**：netty 4.1 线 4.1.137→**4.1.139.Final**、4.2 线 4.2.17→**4.2.19.Final**（CVE-2026-89044，Transfer-Encoding 请求走私，CVSS 6.5，修复线 4.1.138+/4.2.18+）；okhttp `logging-interceptor` 4.9.0→**4.12.0**（CVE-2023-0833，来源 org.web3j:core:4.11.0）⇒ 基线条目已清零（config/dependency-check-baseline.json 的 history 留痕）。**仍未做**：OWASP Dependency-Check 每次扫描稳定报出 **52 个漏洞**，主要是 `netty-all 4.2.17.Final` 系的 `CVE-2026-89044`（约 48 个 jar 命中）与 `okhttp logging-interceptor 4.9.0` 的 `CVE-2023-0833`；因未达门禁线（`failBuildOnCVSS = 9.0`）而判绿。**注意**：#177 把 PR 路径改成「只读 NVD 缓存库」后，**扫描用的是缓存库**（`NVD API Last Checked=2026-10-19`），因此门禁**不会**自动发现新披露的漏洞 —— 升版之外还需要一个"定期刷新 + 对比"机制（周 cron 已在刷新缓存库，但没人比对差异）。**建议**：① 升 netty/okhttp 到已修复版本；② 给 OWASP job 增加"与上次基线比对、发现新增高危即阻断"的环节 —— **② 已交付（PLAN-022）**：`config/dependency-check-baseline.json`（已受理基线）+ `scripts/security/dependency-check-baseline-diff.py`，OWASP job 新增「基线比对」步骤，**只在周 cron / 手动刷新路径**执行（PR 路径仍用缓存库，避免每周全员被卡）；新增漏洞 ⇒ 判红 ⇒ 要么升版、要么显式更新基线 |
| 32 | **CI 门禁排队（runner 标签 / 平台侧）**（2026-10-22 新录，PLAN-024 派生） | 🟡 **已切换（PLAN-024，2026-10-22）**：29 处 `runs-on: ubuntu-26.04` → **`ubuntu-24.04`**（ci / security-scan / performance-test / k8s-sync-check / release）。**起因与归因（重要，已修正一次）**：2026-10-21~22 多个 PR 出现「run 已创建但全仓 in_progress=0、job 排队 15~60 分钟」。最初归因为「ubuntu-26.04 标签池为空」，但 2026-10-22 实测**推翻了这一归因**：① incident 前（15:0xZ）#182（**26.04**）排队 3.5 分钟、#183（24.04）4.4 分钟 —— **两标签无差异**；② 15:28Z 起 GitHub Actions 官方 incident（`degraded_performance`，至 16:41Z 仍在 investigating）期间，**26.04 与 24.04 的 job 全部排队 30+ 分钟且 in_progress=0** ⇒ 「排队」的主因在**平台侧**，不是标签供给，也不是本仓并发限制。**仍切换的理由**：24.04 是最成熟、供给最足的 LTS 镜像，属低风险对冲（构建产物不受影响 —— 镜像基础镜像由各 Dockerfile 决定）；**代价**：系统库安全更新比 26.04 晚一档（GitHub 每周重建镜像仍跟进补丁）。**待复核**：在无 incident 时段做 26.04 vs 24.04 对照测量；若确认无差异，维持或回退成本都为零 |

---

## 治理记录

| 日期 | 动作 | 提交/证据 |
|---|---|---|
| 2026-08-14 | 建立基线 + 逐项代码核实 | 核实 5 项"已修复文档过时" |
| 2026-10-07 | 新增 #25 / #26 两条验证缺口；顺带勘误两处过期表述 | #25：`git log --all -- …/fullstack-drill-report-2026-09-14.md` 0 命中。#26：`FinalityVoteBroadcaster` 类注释原写"P2P 待接入、等 protoc 3.22.2 + 见 ADR-031-finality-p2p-integration.md"——投递两侧实际已接线，且该 ADR 文件名不存在（同编号实际文件是 `ADR-031-nexfinality-engineering-decisions.md`）；"等 protoc"这个前提也不成立——protoc 不在构建路径上，生成物 `NexusChainOuterClass.java` 已入库（`src/main/java/org/nexus/p2p/`），构建侧只有 `protobuf-java` 运行时依赖。两处均在 ADR / 源码注释就地更正 |
| 2026-10-21 | #26 关闭：最终性投票 P2P 往返测试 + 双节点强制重启验收 | `FinalityVoteP2PRoundTripTest`；`PLAN-015` §二/§三 |
| 2026-10-21 | #27 新录：最终性故障注入首批交付，剩余矩阵诚实登记 | `PLAN-016` §三/§四/§五；`FinalityGadgetFaultInjectionTest` / `JdbcFinalityStateOutageTest` |
| 2026-10-21 | #28 新录：PG/AccountDB 短时不可用第一切片交付 | `PLAN-017` §二/§三/§四；`AccountDBTest` / `ChainHeadProviderTest` / `ConsensusControllerStatusTest` / `PendingBlocksManagerStorageRetryTest` |

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

## README:104 自述 P0 补核（2026-08-15）

README 自述"软件升级配置写入为占位（未接入 Nacos）"——**已解决**（GOV-P2-03 修复）：
NACOS 模式改 Nacos Open API 发布配置（Java HttpClient 免客户端依赖，fail-safe）。
其余 README P0 项（事件源认证/审计持久化/哈希）此前已核实为已修复（文档过时）。

## 占位核实终局（2026-08-15，收尾）

**BRIDGE_LOCK 缺口修复**：注释承诺"须包含目标链和收款人"但仅校验非空——
补 JSON 解析校验 targetChain/recipient（BridgeLockValidationTest 4 用例）。

**占位核实（全部设计语义，非缺陷）**：
- BatchTransferService emptySig 占位 → 后续真实 Ed25519 签名（privateKey.sign）
- ChannelSettlementService/PaymentChannelService from/to 占位 → 校验器不要求非空
  （payload 承载数据，与 #16 同类已确认）
- 其余占位（BRIDGE_MINT from/BridgeService 等）此前 PLAN-004/#16 已确认设计语义

## v2.0.0 TODO 治理记录（2026-08-15）

**已解决**：
- BlacklistRule 配置加载激活（此前无数据源空转）✅
- STR 可疑交易报告 JSONL 持久化（重启恢复）✅
- Webhook dead-letter Redis 化 ✅

**待办（已核实，非紧急）**：
- [ ] SubscriptionServiceImpl 订阅授权链上交易（authTxHash 现为占位 UUID，无下游消费无资金风险；真实提交待签名服务）
- [ ] DidService DID 链上解析（现进程内注册表；依赖链上 DID 合约）
- [ ] FundSweep 冷钱包转移多签审批（现直接转移；待审批流接入）
