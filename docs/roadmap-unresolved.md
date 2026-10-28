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
| 27 | **最终性故障注入剩余矩阵**（2026-10-21 新录） | 🟡 **首批已交付（PLAN-016 Phase 6B）**：重复/延迟/乱序投票、双签顺序反转、JDBC/最终性表短时不可用；修复写库失败污染内存、最终化提前对外可见、`pendingBlock` 60 秒超时。**第二切片已交付（PLAN-018 Phase 6C）**：持久化投票 outbox（`finality_vote_outbox` 表 + `FinalityOutboxManager`，跨进程重启存活）、完整 `Vote` 签名随票落库（重启后能恢复 Vote 对象并**自行补最终化**）、**接收侧独立重试**（远端票写库失败不再蒸发）。**第二切片已交付（PLAN-021 Phase 6C）**：最终性视图**跨节点收敛**（每 5 秒把库里的新票/新终局拉进本地视图，合并必须过与提交路径同一套绑定校验、去重、单调）⇒ 3 节点验收里 A/B/C 的对外权重视图从 3000/2000/1000 收敛为**一致 3000/3000**，掉线节点重启后完全追平。**第三切片已交付（PLAN-025 Phase 6C）**：**独立存储拓扑下的跨节点最终性事实传播** —— 新增 0x5B 补票请求（`from`/`max`）+ 对端回推**已签名**票 + 定时 anti-entropy（默认 30s、回看 50 epoch、单次上限 200 票）+ 按验证人去重；响应复用 0x5A 投票通道（绑定校验/聚合验签/幂等落库不变），老节点丢弃 0x5B（向后兼容）。**同主机 2 进程 + 2 个独立 PostgreSQL 16.9 实例实测**：把 B 库中"对端的票"删掉、保留 B 自己的票后重启，补票开 ⇒ B 恢复到 `19..143 / 125 个 checkpoint`、水位与 A 一致（历史区间恢复 **51** 个）；补票关 ⇒ 历史区间（≤68）恢复 **0 个**（只剩 `70..145 / 76 个` 新 epoch），证明独立存储下的历史缺口**不会自愈**。**仍未做**：真实网络分区、完整拜占庭矩阵、补票窗口外的远古 epoch（本实验 1..18）不补、数据库整体不可用时只剩内存队列兜底（进程崩溃仍丢票）、迁移前老数据没有签名。**"晚加入节点卡 height 1"条目已核销（PLAN-041，2026-10-26）**：空库晚加入节点实测 `unknown proposer` 5 次全部在 height 1 且可恢复（`block will be retried after validator-set sync`），T0+116s 拿到全表回放的验证人集后 0→136 一次补落库、与 A 同步至 385/385——不再卡死（机制：F2 挂起重试 + PLAN-030 全表回放；本机同内核边界，跨内核/物理多机未验）。PG 整体停止的 `AccountDB`/提案/状态接口问题由 #28 首批修复 |
| 29 | **区块写库失败被吞 + 接收侧把存储故障当坏块丢弃**（2026-10-22 新录，PLAN-019 派生） | 🟢 **①② 已收口（PLAN-039，2026-10-26）**：① `BlockWriteStats` 聚合写入失败/跳块的**尝试次数**（同一高度重试重复计数）与状态更新超时，`GET /consensus/block-writer` 纯内存读、库不可达仍可读；测试床注入 header 写入失败 2 分钟：`writeFailures` 0→1666、`lastFailureHeight` 155→204，撤除后停止增长并由确认轮次补写追平。② `GET /consensus/block-outbox` 增 `oldestPendingCreatedAt` / `maxAttempts`；**不自动清理**（滞留行是恢复数据/审计证据，revisit 条件见 PLAN-039 §2.3）。整库停机 4 分钟：`memoryPending` 0→100、`databasePending` 降级 -1，恢复 30s 内排空（B 34→147），随后追平。**③ 告警已对接（PLAN-040，2026-10-26）**：`BlockPersistenceMetrics` 把写失败/状态超时/outbox 积压/不可读接进 `/actuator/prometheus`（outbox 库读 30s 采样，抓取不查库），新增 4 条 PrometheusRule（`BlockWriteFailuresDetected` / `BlockWriterStateUpdateTimeouts` / `BlockPersistenceBacklog` / `BlockOutboxUnreadable`）+ runbook。**仍开放**：物理多机/真实机房断电与网络分区未验证（本轮为本机虚拟化边界三节点）；真实"永久滞留"行未复现（仅合成行验读取链路）。 |
| 30 | **节点级故障容忍与重启追平**（2026-10-22 新录，PLAN-020） | 🟡 **单主机 3 节点已交付（PLAN-020）**：杀掉 1/3 验证人 ⇒ 链继续出块、最终性不中断（2/3 阈值，epoch 1942 `voted=3000/3000` FINALIZED）；掉线节点重启后**完全追平并重新参与投票**，检查点全程缺口 0、ERROR 0。**顺带修掉一个真 bug**：`ValidatorSetPersistence.replay()` 在 `RowCallbackHandler` 里多调 `rs.next()` ⇒ **验证人集隔行丢失**（3 个只重放 2 个）⇒ 重启节点认不全 proposer ⇒ 报 "unknown proposer" ⇒ **永久卡在缺口高度无法采纳对端区块**（已修 + 回归测试 `ValidatorSetPersistenceReplayTest`，旧实现下 2/2 失败）。**边界修正（PLAN-027，2026-10-23）**：上述"杀 1/3 链继续"在**共享存储**拓扑成立；**独立存储 + 跨内核边界（Windows/WSL2/Docker）下不成立** —— C 下线触发轮转卡死（F3），B 落后被抑制成为提议人后无追块通道（F2），链停摆且 C 回来也不解；且星型拓扑下 C 的验证人集永远到不了 B（F1），C 提议的块在 B 处 `unknown proposer`。详见 `PLAN-027` §三。**磁盘损坏/数据丢失已实证（PLAN-042，2026-10-26）**：节点**全量持久化丢失**（PG 库 + LevelDB 缓存全毁）后不人工种子重启，20s 内自注册、T0+50s 经全表回放认回对端集合、T0+51s 追块 0→256 一次补齐、回到 proposer 轮转与投票、检查点经 50-epoch 补票重建 375/426 并与 A 收敛到 848/849（`unknown proposer` 全程 1 次）。**仍未做**：真正多机（物理多机 + 网络分区 + 断电 + **物理磁盘损坏**、PG 主备切换）、完整拜占庭矩阵、F2/F1 修复（F3 已修于 PLAN-028）与四修完成后的多机停机-恢复闭环重跑 |
| 28 | **PG 整体停止打穿 AccountDB、提案与状态接口**（2026-10-21 新录） | 🟡 **第一切片已交付（PLAN-017 Phase 6C）**：账户读取 fail-closed + 明确 `AccountStoreUnavailableException`；`/consensus/status`/P2P 握手改内存链头；proposer 安全暂停；`/consensus/storage-status` 降级观测；DBCP2 5 秒快速失败；异步采纳异常隔离。真实 PG 停止 43.9 秒验收：状态接口 HTTP 200、ERROR 0、恢复后 3549/3549、最终性检查点无缺口。**仍非 PG 高可用**：无主备切换、无持久化区块 outbox、未做多机网络分区/主机断电；PG 停止期间链停止出块 |
| 31 | **依赖库已知漏洞待升版（SCA 债务）**（2026-10-22 新录，PLAN-017 之后的 #177 附带发现） | 🟡 **JVM 侧已升版（PLAN-023，2026-10-22）**：netty 4.1 线 4.1.137→**4.1.139.Final**、4.2 线 4.2.17→**4.2.19.Final**（CVE-2026-89044，Transfer-Encoding 请求走私，CVSS 6.5，修复线 4.1.138+/4.2.18+）；okhttp `logging-interceptor` 4.9.0→**4.12.0**（CVE-2023-0833，来源 org.web3j:core:4.11.0）⇒ 基线条目已清零（config/dependency-check-baseline.json 的 history 留痕）。**仍在跟踪（2026-10-26 复查，master run 40240653579 报告）**："稳定报出 52 个漏洞（netty-all 4.2.17 + okhttp 4.9.0）"**已是旧口径**——PLAN-023 升版后清零：最新报告 250 个依赖**未抑制 CVE = 0**，唯一抑制项是 `CVE-2023-39017`（quartz，抑制依据=受影响制品 `quartz-jobs` 不在依赖图）。**NVD 缓存仍可能滞后**（PR 路径只读缓存库，`Last Checked=2026-10-19`），新增披露的发现依赖周 cron 刷新 + PLAN-022 基线比对，不依赖每次 PR 扫描。**注意**：#177 把 PR 路径改成「只读 NVD 缓存库」后，**扫描用的是缓存库**（`NVD API Last Checked=2026-10-19`），因此门禁**不会**自动发现新披露的漏洞 —— 升版之外还需要一个"定期刷新 + 对比"机制（周 cron 已在刷新缓存库，但没人比对差异）。**建议**：① 升 netty/okhttp 到已修复版本；② 给 OWASP job 增加"与上次基线比对、发现新增高危即阻断"的环节 —— **② 已交付（PLAN-022）**：`config/dependency-check-baseline.json`（已受理基线）+ `scripts/security/dependency-check-baseline-diff.py`，OWASP job 新增「基线比对」步骤，**只在周 cron / 手动刷新路径**执行（PR 路径仍用缓存库，避免每周全员被卡）；新增漏洞 ⇒ 判红 ⇒ 要么升版、要么显式更新基线 |
| 32 | **CI 门禁排队（runner 标签 / 平台侧）**（2026-10-22 新录，PLAN-024 派生） | 🟡 **已切换（PLAN-024，2026-10-22）**：29 处 `runs-on: ubuntu-26.04` → **`ubuntu-24.04`**（ci / security-scan / performance-test / k8s-sync-check / release）。**起因与归因（重要，已修正一次）**：2026-10-21~22 多个 PR 出现「run 已创建但全仓 in_progress=0、job 排队 15~60 分钟」。最初归因为「ubuntu-26.04 标签池为空」，但 2026-10-22 实测**推翻了这一归因**：① incident 前（15:0xZ）#182（**26.04**）排队 3.5 分钟、#183（24.04）4.4 分钟 —— **两标签无差异**；② 15:28Z 起 GitHub Actions 官方 incident（`degraded_performance`，至 16:41Z 仍在 investigating）期间，**26.04 与 24.04 的 job 全部排队 30+ 分钟且 in_progress=0** ⇒ 「排队」的主因在**平台侧**，不是标签供给，也不是本仓并发限制。**仍切换的理由**：24.04 是最成熟、供给最足的 LTS 镜像，属低风险对冲（构建产物不受影响 —— 镜像基础镜像由各 Dockerfile 决定）；**代价**：系统库安全更新比 26.04 晚一档（GitHub 每周重建镜像仍跟进补丁）。**待复核项已收口（PLAN-053，2026-10-28 API 取样）**：无 incident 时段 30 个样本中 22 个排队 **0 分钟**、其余 ≤2 分钟（1 个 17 分钟孤值，与 PLAN-024 记录的"平台侧瞬时态"形态一致），中位 0 好于 PLAN-024 记录的 incident 前样本（26.04=3.5min / 24.04=4.4min）⇒ **维持 24.04**、无回退理由；本结论只对"本仓当前时段"有效，未来再现 15 分钟级排队先查平台状态页 | PLAN-053 §三/§四 |
| 33 | **桥持久化：messaging 消息存储只有内存实现，messaging/liquidity 模块未装配进生产流**（2026-10-23 新录，PLAN-026） | 🟡 **消息存储已落库（PLAN-026）**：新增 `bridge_messages` 表（V5 迁移 + Hibernate ddl-auto 双轨，与 V0 同模式）+ `JdbcMessageStore`（语义与 InMemory 逐条对齐：save 按 messageId 主键去重、per 源链 max nonce 顺序水位、recordExecution 落执行哈希并置 EXECUTED）+ 装配开关 `nexus.bridge.messaging.store=memory\|jpa`（默认 memory 保持既有行为）；6 例测试含**重启语义**（同一库上新建 store 实例 ⇒ 去重/nonce 水位/执行追溯全保住）。**背景（摸底结论）**：桥的资金安全状态**已经落库**——幂等键（lock/mint/burn/unlock 四操作全用）、Saga（含崩溃恢复 `recoverIncompleteSagas` + 指数退避重试）、紧急暂停、保险基金流水、重放 nonce 均有 JPA 持久化。**仍未做**：① **装配已启动（PLAN-037 切片 1，2026-10-25）**：`MessagingRelayConfig` 提供 `nexus.bridge.messaging.relay-enabled` 开关（**默认关**），开启时创建 relayer/executor/worker/status Bean（store 沿用 memory/jpa）；**切片 2 已交付中继循环**（`MessageRelayWorker`：findPending 顺序消费 → 阈值验签 → 可选执行 → 记账；含 RELAYED 中间态语义修正）；**切片 3 已交付模拟链装配 + 观测端点**（`SimulatedChainAdapter` 内存态模拟链 + `GET /api/v1/bridge/messaging` 快照 + 适配器注册表键语义修正：按 chainId 归键）。**消息前段已交付演示口径（PLAN-046，2026-10-27）**：`ChainEventSource` 接口 + `SimulatedChainEventSource`（内存态演示源，测试床专用）+ `BridgeEventSourceWorker`（poll → format → 真实签名入库，逐事件容错）+ `eventSource` 观测字段；**仍未接真实外部链**（消息前段：真实链事件 → 落库属生产切片，需链凭证/网络出口/游标与重放保护）；真实链适配器无凭证时执行不可用（失败隔离保护）；② **日限额持久化已交付（PLAN-038，2026-10-26）**：深挖发现计数器本身亦为内存态（重启清零⇒限额可被重启绕过；多副本各持一份⇒可允出 N 倍）——`DailyUsageStore` 双档（memory 默认 / jpa：`bridge_daily_usage` 单行表 + REQUIRES_NEW 独立提交 + 行级锁 ⇒ **重启不清零 + 多副本全局限额**）+ 审计历史落库（`bridge_daily_limit_snapshot`，同日覆盖幂等）；`DefaultLiquidityManager` 零生产使用点 ⇒ **暂不持久化**（决策 + revisit 条件见 PLAN-038 §2.3）；③ `CircuitBreaker` **保持重启复位**（决策：设计定位为进程内瞬时故障门闩，跨重启冻结由 DB 级紧急暂停承担——见 PLAN-038 §2.3） |
| 34 | **非 distroless Linux 上节点启动永久卡死：`Fifo` 阻塞 main 线程**（2026-10-23 新录，PLAN-027 发现 1） | 🟢 **已修并实证（PLAN-031）**。原状：`org.nexus.ipc.Fifo` 是 `ApplicationRunner`，Linux 下 `new FileReader(fifo)` 打开 FIFO 读端会**阻塞等待写端**（`Fifo.java:135`），且发生在 `SpringApplication.callRunners()` 内、**先于 ApplicationReadyEvent** ⇒ `ValidatorNodeBootstrapper`（监听 ready）永不执行 ⇒ 不自注册、不广播验证人集、最终性协调器不装填、不出块（线程栈实证：main 卡 `Fifo.initFifo` → `callRunners`）。**影响面**：任何带 mkfifo 的 Linux（WSL/Ubuntu/完整发行版）；生产 core 镜像（distroless 无 mkfifo）走 `catch(IOException)` 优雅降级（`Fifo.java:104`）恰好幸免。修复：`run()` 改为启动单个守护线程（`nexus-fifo-ipc`）执行 init+读循环，main 立即返回——ApplicationReadyEvent 照常发布、IPC 功能照常；事件分发与 `writer` 仍同 worker 线程（无新增跨线程可见性面）；新增 `nexus.ipc.enabled`（默认 true；false 完全跳过——容器/CI 无 mkfifo 环境的显式逃生口）。测试 4 例（`FifoBootNotBlockedTest`：Linux 路径 run() 3s 内返回/守护线程存活/开关零线程/Windows 语义不变）。**已实证**：多机闭环重跑时 B 还原 `/usr/bin/mkfifo` 正常启动，`ValidatorNodeBootstrapper` 正常广播验证人集（`Validator-set broadcast sent: 186 bytes`），"永久卡死"不再复现（详见 `docs/plan/PLAN-027-multi-machine-findings.md` §六） |
| 35 | **共识活性三缺陷：验证人集不中继（F1）+ 出块节点无主动追块（F2）+ 提议人轮转无 view-change（F3）**（2026-10-23 新录，PLAN-027 发现 2/3/4） | 🟢 **已修并实证**：F3 已修（PLAN-028）；F2 已修（PLAN-029，rest 形态，口径修正）；F1 已修（PLAN-030，多机闭环关键）。**闭环重跑（`docs/plan/PLAN-027-multi-machine-findings.md` §六）**：T0 基线 19:50:23 A=649/B=643/C=650；T1 停 C 60s（19:51:53）A=680/B=681/C=—，A/B 继续出块（F3 不死锁）；T2 19:57:10 C 恢复；T3 ~20:10 A=932/B=927/C=932，C 补票追平。B `validators_synced` 3 行自然同步（无人工插行，F1 全表回放生效）；B `require blocks 534..542` grpc 追块落库正常；B `Validator-set broadcast sent` 正常（Fifo 启动不卡死）。**如实边界**：F1 中继未签名（沿用 PLAN-001 信任模型，伪造 add 风险与修复前同等）；rest 形态无 P2P 广播通道故 F1 不适用 rest 集群；虚拟化边界多机（三套内核），非物理多机 |
| 36 | **持久化区块 outbox 恢复的两个缺陷：单次只捞 64 条（尾部永不恢复）+ 存储不可用被当成"已恢复完"**（2026-10-23 新录，#194 回归 / #195 修复） | 🟢 **已修（#195，2026-10-23T20:12Z）**：`restoreFromOutbox()` 由"单次 `loadPending(64)` 后无条件置位"改为 ① 循环捞取直到取空；② 先取 `pendingCount()`，`<0`（读不到）⇒ **不置** `outboxRestored`、下一轮再试（`loadPending` 在库异常时吞异常返回空列表 —— 空 ≠ 捞完）；③ 批次无进展时停止本轮但同样不置位（避免对同一批无限重试）。回归 `PendingBlocksManagerOutboxRestoreCapTest`（#194 先证伪：`loadPending(64)` 对 70 行只返回 64、连调 3 次只捞 1 次；#195 补实现后 70 行跨批捞完、存储不可用连续两轮均不置位）。**遗留**：#29 登记的 ① 写库失败被吞成 `false`、② outbox 行无过期清理仍开放 |
| 38 | **grpc 大缺口追块不收敛：落后节点 ~24 块/分 < 出块 ~26 块/分**（2026-10-25 新录，PLAN-027 §六 边界的代码级定位） | 🟢 **切片 A 已修并量化（PLAN-033）**：`SyncManager.acceleratedCatchUp()`（5s 周期，差距>2×窗口且无在途/超时 20s ⇒ 提前拨 GetStatus → 顺序窗口流水线，在途≤1）+ 防打转（连续 5 轮零推进暂停 60s）。**实测 B 全量重置后：窗口请求 73 次/13.2 分钟（~1 次/11s）、追块 ~190 块/分（~8×）、净收敛 +164/分**；守卫零误触发。**边界**：测量依赖 B→A 外拨可达（A 广告地址 host.docker.internal:9235 在 WSL 不可达——独立拓扑环境问题，未修）；多 peer 并行（切片 B2）/区块体瘦身未做；切片 B 观测端点（`GET /consensus/catch-up`）已随附交付 | `PLAN-033` §切片A实现+实测；`SyncManagerCatchUpAccelerationTest` 5 例；`b-fg.log`（require blocks 73 行） |
| 37 | **rest 形态三个运行时缺陷：PeerServer 必填注入 ×2（NodeInfoController / Fifo）+ P2P 拉块解码拒绝服务端派生字段**（2026-10-25 新录，PLAN-029 §六 验证发现） | 🟢 **已修并实测**：① `NodeInfoController` 可选注入 + `/peers/status` null 降级；② `Fifo` 可选注入 + node info 字段 null 降级；③ `JSONEncodeDecoder` 新增 `decodeTolerant` 仅供 `decodeBlocks/decodeTransactions`（其余路径保持严格，`JSONEncodeDecoderP2PToleranceTest` 双向锁定）。**实测**（`p2p.mode=rest` + mining=true 的临时节点）：启动成功、`try to sync` 35 次、拉块 48 次、高度 0→900+ 单调追赶、`unknown proposer` 0 次。**边界**：~~rest 无验证人集同步通道~~ **已由 PLAN-032 关闭**（`GET /consensus/validator-set` 周期分发 + 同一处理路径；空库 rest 节点实测自动追块）；不断言最终追平时间 | 修复前 rest 节点无法启动/无法拉块（F2 rest 路径此前从未实机跑过）；详见 `PLAN-029` §六 |

---

## 治理记录

| 日期 | 动作 | 提交/证据 |
|---|---|---|
| 2026-08-14 | 建立基线 + 逐项代码核实 | 核实 5 项"已修复文档过时" |
| 2026-10-07 | 新增 #25 / #26 两条验证缺口；顺带勘误两处过期表述 | #25：`git log --all -- …/fullstack-drill-report-2026-09-14.md` 0 命中。#26：`FinalityVoteBroadcaster` 类注释原写"P2P 待接入、等 protoc 3.22.2 + 见 ADR-031-finality-p2p-integration.md"——投递两侧实际已接线，且该 ADR 文件名不存在（同编号实际文件是 `ADR-031-nexfinality-engineering-decisions.md`）；"等 protoc"这个前提也不成立——protoc 不在构建路径上，生成物 `NexusChainOuterClass.java` 已入库（`src/main/java/org/nexus/p2p/`），构建侧只有 `protobuf-java` 运行时依赖。两处均在 ADR / 源码注释就地更正 |
| 2026-10-21 | #26 关闭：最终性投票 P2P 往返测试 + 双节点强制重启验收 | `FinalityVoteP2PRoundTripTest`；`PLAN-015` §二/§三 |
| 2026-10-21 | #27 新录：最终性故障注入首批交付，剩余矩阵诚实登记 | `PLAN-016` §三/§四/§五；`FinalityGadgetFaultInjectionTest` / `JdbcFinalityStateOutageTest` |
| 2026-10-21 | #28 新录：PG/AccountDB 短时不可用第一切片交付 | `PLAN-017` §二/§三/§四；`AccountDBTest` / `ChainHeadProviderTest` / `ConsensusControllerStatusTest` / `PendingBlocksManagerStorageRetryTest` |
| 2026-10-23 | #27 第三切片交付（PLAN-025）+ #30 边界修正 + #34/#35 新录（PLAN-027 多机发现） | `PLAN-025` §四（独立存储补票实测）；`PLAN-027` §二/§三（跨边界补票实证 + Fifo 卡死 + 活性三缺陷） |
| 2026-10-23 | #35 F3 修复交付（PLAN-028）：提议人轮转 view-change（本地超时 round-stepping），F2/F1 仍开放 | `PLAN-028` §二/§三（设计 + 9 例测试）；#35 行内"已修/未修"分账 |
| 2026-10-23 | #35 F2/F1 修复交付（PLAN-029/030）：出块节点主动对账（rest 形态，口径修正）+ 验证人集中继与全表回放（grpc，多机闭环关键） | `PLAN-029` §四（rest 边界）；`PLAN-030` §二/§四（防环与伪造边界）；#35 行内更新 |
| 2026-10-23 | #34 修复交付（PLAN-031）：`Fifo`（ApplicationRunner）读循环移守护线程 `nexus-fifo-ipc`（main 不再阻塞，ApplicationReadyEvent 照常）+ `nexus.ipc.enabled` 总开关；4 例测试；**遗留**：多机闭环实证与"Linux 非 distroless 启动真回归"待 B 还原 mkfifo 后统一重跑 | `PLAN-031` §二/§三/§四；#34 行内更新 |
| 2026-10-23 | #34/#35 多机闭环重跑已通过（PLAN-027 §六） | `PLAN-027` §六（T0 基线 19:50:23 A=649/B=643/C=650；T1 停 C 60s 19:51:53 A=680/B=681/C=—；T3 追平 ~20:10 A=932/B=927/C=932；B `validators_synced` 3 行自然同步；B `require blocks 534..542` grpc 追块落库；B `Validator-set broadcast sent` 正常） |
| 2026-10-24 | #36 新录：持久化区块 outbox 恢复缺陷（一次捞不完 + 失败当成功）已由 #194（回归）/ #195（修复）关闭；本次只补台账（roadmap #36、CHANGELOG、`PLAN-017` §七 追记），**产品代码零改动** | #195：`PendingBlocksManager.restoreFromOutbox()` 循环捞取 + `pendingCount` fail-closed + 无进展不置位；`PendingBlocksManagerOutboxRestoreCapTest`（70 行跨批捞完 / 存储不可用两轮不置位） |
| 2026-10-24 | PLAN-001 签名化第二切片（接线）交付：消息 v2（nonce/signature，v1 逐字节不变）+ 发送侧签名 + 接收侧验签/重放护栏 + `validators_synced.nonce` 增量列 + `enforce` 开关（默认 false）。**如实边界**：默认兼容模式受理未签名消息 ⇒ 防伪造未真正生效；enforce 前置（全表回放签名语义）未满足 | `PLAN-001-signature-extension.md` §二/§三/§五；`ValidatorSetMessageVerifierTest` 7 例 + `ValidatorSetPersistenceNonceTest` 3 例 + `SyncManagerValidatorSetRelayTest` 新增 3 例；全模块 `BUILD SUCCESSFUL` |
| 2026-10-24 | PLAN-001 签名化**多机回归**通过：三节点（虚拟化边界）广播 311B 签名载荷；A 库 nonce 对 B/C 全 >0（验签落库）、无签名回放路径共存；0 bad-sig 误拒；停 C 60s 链路未断、C 恢复 60s 追平（A=1743/C=1745）。**边界**：B 落后扩大=既有 grpc 追块速率边界；enforce 未启用 | `PLAN-001-signature-extension.md` §七；`nodeA.log`/`b-fg.log`/`mm-app-c logs`；三库 nonce 查询 |
| 2026-10-25 | PLAN-001 签名化**切片 4（回放签名语义）**交付：`validators_synced.signed_payload` 增量列 + 回放逐字节复用原始签名载荷（COALESCE 不清空）；`enforce` 唯一前置关闭，默认仍 false（待全量升级灰度） | `PLAN-001-signature-extension.md` §八；`ValidatorSetPersistenceSignedPayloadTest` 3 例 + `SyncManagerValidatorSetRelayTest` 新增 2 例 + `PortableUpsertPostgresTest` 真实 PG 断言；全模块 `BUILD SUCCESSFUL` |
| 2026-10-25 | PLAN-001 切片 4 **enforce=true 三节点实测通过**：全量重置三库后显式 enforce——回放全部 `3 signed payloads verbatim / 0 rebuilt`（A=66/B=36/C=32 次）；`DROP_UNSIGNED`=0、bad-sig=0（A/B/C）；广播 4×311B/节点；三库 3/3 行 nonce>0+载荷在库；停 C 60s 链路未断（800→810）、C 60s 追平（827=827）。**边界**：B 落后=grpc 追块速率边界；enforce 默认值仍 false（运维灰度） | `PLAN-001-signature-extension.md` §八 实测块；A/B/C 日志；三库 `validators_synced` 查询 |
| 2026-10-25 | PLAN-032 交付：**rest 形态验证人集分发**（`GET /consensus/validator-set` 全表回放载荷 + `SyncClient.syncValidatorSet` 30s 周期拉取 + 拦截器白名单修复）——与 grpc 回放同源构建、复用同一验签/重放/注册路径。**实测零人工预置**：空库 rest 节点自动 `3 payload(s) applied` ×12、三验证人 registered=true、`unknown proposer`=0、高度 0→332+ 单调追块 | `PLAN-032` §四；`SyncManagerValidatorSetRelayTest` +2 例；全模块 `BUILD SUCCESSFUL` |
| 2026-10-25 | #38 新录并切片 A 修复：**grpc 大缺口追块加速**（PLAN-033）——5s 周期提前对账 + 顺序窗口流水线（在途≤1）+ 防打转；实测追块 ~24→~190 块/分、净收敛转正 | `PLAN-033` §六实测表；`SyncManagerCatchUpAccelerationTest`；全模块回归绿 |

| 2026-10-25 | PLAN-033 切片 B（追块观测端点）交付：`GET /consensus/catch-up` 只读快照（gap/在途窗口年龄/暂停窗），数据源 `SyncManager.catchUpSnapshot()`，不扩展 rest 协议对象；白名单同步 | `SyncManagerCatchUpSnapshotTest` 3 例；PLAN-033 §切片B |
| 2026-10-26 | **#33 余项收口（PLAN-038）**：日限额持久化交付（`DailyUsageStore` 双档——jpa 档重启不清零 + 多副本全局限额；审计历史落库）；`DefaultLiquidityManager` 暂不持久化（零使用点，附 revisit）；`CircuitBreaker` 保持重启复位（决策） | PLAN-038 §2.3；`JdbcDailyUsageStoreTest`（含重启/多副本语义）等 14 例；模块全量 620 例绿 |
| 2026-10-26 | **#29 ③ 告警对接（PLAN-040）**：`BlockPersistenceMetrics` 暴露写失败/状态超时/outbox 积压与可读性指标（outbox 30s 采样，抓取不查库）；新增 4 条 PrometheusRule + runbook（11→15 条）；**仍开放**：物理多机/真实断网断电、真实永久滞留行 | PLAN-040 §二/§三；`BlockPersistenceMetricsTest` 6 例；`BlockWriteStats` getter |
| 2026-10-26 | **#27 "晚加入节点卡 height 1"条目核销（PLAN-041，验证切片，产品代码零改动）**：预登记三判据全过——空库晚加入节点 `unknown proposer` 5 次全在 height 1 且可恢复（`will be retried after validator-set sync`），T0+116s 经全表回放拿到 A 的集合，0→136 一次补落库并与 A 同步至 385/385；根因链=现有机制组合（挂起重试+全表回放+outbox 循环捞回）。前两次运行失败为本机动态端口池瞬态抢占（环境问题，已如实记录） | PLAN-041 §三；`F:\Agent\opencode\workspace\pl041-latejoin.log`；`mm\pl041-{A,D}.log` |
| 2026-10-26 | **#30 磁盘损坏/数据丢失已实证（PLAN-042，验证切片，产品代码零改动）**：D 全量持久化丢失（DROP 库 + 删 LevelDB 缓存）后同参重启——20s 自注册、T0+50s 全表回放认回对端集合、T0+51s 追块 0→256 一次补齐、回到 proposer 轮转与投票、检查点经 50-epoch 补票重建 375/426、终态 A=848/D=849；`unknown proposer` 全程 1 次。其④登记项（A 50% vs D 100%）由 **PLAN-043** 10 分钟窗口测量判为提交时快照的顺序浮动（两侧均能到 100%，checkpoint 全一致） | PLAN-042 §三/§四；`pl042-storageloss.log`；`mm\pl042-{A,D}.log` |
| 2026-10-26 | **PLAN-043 长窗口最终性收敛（验证切片，零产品改动）**：10 分钟窗口高度逐采样一致、每 epoch checkpoint 同串、A/D 提交时快照 51/595 次 100% 且出现角色互换 ⇒ 暂态（H1）；DB 终态 A 1366 票 / D 1345 票、max epoch 均 683。**未测得项已由 PLAN-044 承接**：对端票到达后本地视图翻 true 的观测点不存在（仅有提交时快照）——PLAN-044 新增 `GET /consensus/finality`（只读内存）+ `finalityLag`，并用 5s 粒度采样复现 50%↔100% 浮动、确认冷恢复后两端 `finalityLag=0`、检查点数同步前进（393/392）。**遗留（2026-10-27 复查未复现，根因未定）**：D 重启后前 ~35s 的几次 `h=0/f=64` 采样与首票时间矛盾且与"空库"不符（复查同场景为 `f=0`），已排除旧进程残留（端口断言关闭），可能解释（当时 DROP 是否成功/响应来源）无法用留存数据证实证伪；该几次数据始终不采纳为证据 | PLAN-043 §三/§四；`pl043-longwindow.log`；PLAN-044 §四（复查记录） |
| 2026-10-26 | **最终性可见性端点（PLAN-044，产品变更）**：`GET /consensus/finality` 只读快照（currentEpoch/latestVotedEpoch/finalityLag/finalizedCheckpoints/progress）+ `FinalityGadget.finalizedCheckpointCount()`；白名单同步；8 例单测。**首轮实测 360/360 no-progress 暴露设计缺陷**（progress 绑当前高度的 epoch，而链高多数时刻不在检查点上）⇒ 改为"回溯 8 epoch 报最近有票 epoch"；新增 `finalityLag`（最终性落后几个 epoch） | PLAN-044 §二/§四；`FinalitySnapshotTest` 6 例 + `ConsensusControllerFinalityTest` 2 例 |
| 2026-10-26 | **#31 SCA 旧口径核销（取证，零产品改动）**：master run 40240653579 的 OWASP 报告 250 依赖**未抑制 CVE=0**，唯一抑制 `CVE-2023-39017`（quartz-jobs 不在依赖图）；"稳定 52 个漏洞"为 PLAN-023 升版前口径 | roadmap #31 行内更新 |
| 2026-10-27 | **最终性停滞指标与告警（PLAN-045，产品变更）**：`FinalityMetrics` 暴露 `nexus_finality_*`（enabled/current_epoch/latest_voted_epoch/lag/finalized/progress_percent/finalized_checkpoints，未装配 NaN）+ 2 条 PrometheusRule（`FinalityStalled` critical `lag>=2` for 5m；`FinalityBelowQuorum` warning `percent<100` for 10m），告警总数 15→17；关闭"链在出块但最终性跟不上"的告警盲区 | PLAN-045 §二/§三；`FinalityMetricsTest` 5 例；runbook +2 |
| 2026-10-27 | **#33 切片 4 演示口径（PLAN-046，产品变更）**：`ChainEventSource` 接口 + `SimulatedChainEventSource`（内存态演示源）+ `BridgeEventSourceWorker`（poll→format→真实签名入库，逐事件容错）+ `GET /api/v1/bridge/messaging` 的 eventSource 活值字段，消息前段不再空转。**仍未做**：真实外部链事件源（链凭证/网络出口/游标/重放保护）、多签真实验证人集合 | PLAN-046 §二/§三；25 例（新增 11）；模块全量回归绿 |
| 2026-10-27 | **PLAN-047 三验证人实测 + 复跑补全（验证切片，零产品改动）**：判据 1 确认（健康 3 验证人 lag 基线恒 2 ⇒ PLAN-048 已重标定）；判据 2 测成（E 宕机 105 epoch 后重启，+15s 追块同步、补回 76、**永久丢失 29**——补票 50-epoch 回看边界首次量化）；判据 3 通过（宕机一个验证人 A/D 持续最终化 67%）。Run 1 的"链头卡死"系脚本随机密钥污染，**发现 B 撤销**；**Run 1 产品风险保留（未修）**：重启换密钥 ⇒ 注册表混入第 4 验证人 ⇒ 2/3 门限不可达 ⇒ 最终性冻结而链继续出块（PLAN-048 已做到可被发现，治理另立） | PLAN-047 §三/§四；`pl047-multivalidator.log`（Run 1/2） |
| 2026-10-27 | **最终性告警重标定（PLAN-048，产品变更）**：`finalitySnapshot` 改**粘滞水位**（有记录才前进、无记录保持）⇒ 死锁时 lag 无界增长 + progress 冻结（原来 NaN 熄火）；`FinalityStalled` 改为 `changes(latest_voted_epoch[15m])==0` for 10m（与拓扑无关）；runbook 重写。**仍未做**：僵死验证人回收（PLAN-047 风险，只做到"可被发现"——观测面已由 PLAN-049 补齐）、`changes==0` 的测试床复测 | PLAN-048 §二/§三；16 例全绿；规则 8 groups/17 alerts |
| 2026-10-27 | **验证人注册表可观测性（PLAN-049，只观测不剔除）**：`GET /consensus/catch-up` 追加 `validators` 摘要（total/active/inactive/capacity + 排序地址列表），零共识逻辑改动。**自动剔除的三项阻碍已登记**：① 僵死判据需 SLA 输入（误删临时分区健康验证人伤 liveness）② 验证人集消息签名化（PLAN-001 enforce 默认 false，签名化前自动 remove=安全倒退）③ 移除权限的治理决议。**runbook 纪律**：候选僵死项（换钥重启留下新旧地址）与 `/consensus/validator-set` 对账后走治理路径，不手工改 `validators_synced` | PLAN-049 §二/§四/§五；`SyncManagerCatchUpSnapshotTest` 5 例 |
| 2026-10-27 | **补票回看窗 margin 决策测量（PLAN-048b，零产品改动）**：margin=200 复跑同场景 ⇒ 窗外缺口 **29→0**（105 个宕机 epoch 全补回、终态 272/272/272），代价=单次响应打满 `max-votes=200` 自动拆多轮（~2 分钟搬完）。**决策建议**：默认维持 50；SLA 需覆盖 ~20 分钟级宕机可调 200；恢复总时长（非 margin 值）才是真下限。**revisit**：更长宕机仍丢 ⇒ 需游标式分页续传；max-votes 成瓶颈 ⇒ 评估上调 | PLAN-048b §三/§四；`pl048-multivalidator.log` |
| 2026-10-27 | **`FinalityStalled` 告警演练（PLAN-050，零产品改动）**：判据 B 通过（杀到单验证人 ⇒ 水位冻结/链高照涨/lag 无界 ⇒ 告警必触发）；判据 A **转为真阳性**——健康集群里 D 的投票在 epoch 60 后静默停止（卡在 `unknown proposer at height 125`，链高照涨、无 ERROR/WARN、仍服务补票）⇒ "活着但弃权"节点被照出。**根因缩小**：注册表有该验证人、round-robin 也选得出，但 `isProposerKnown` 的 pubKeyHash 匹配认不出（疑匹配口径/装入时序；校验在 PendingBlocksManager 侧即挂起）。**派生 PLAN-051 定位修复**；告警表达式不改 | PLAN-050 §三/§四；`pl050-alert-rehearsal.log` |
| 2026-10-27 | **弃权风险可观测性（PLAN-051 第一步，产品变更）**：`PendingBlocksManager` 未知提案人挂起改为**按高度累计**（不同高度独立计数），达 5 次升级 ERROR + `abstain-risk` 标记（指向 `/consensus/finality` 与 `/consensus/catch-up` 的对账入口）；**defer 语义不变**。3 例新测试 + 模块全量回归绿。**仍未修根因**：`isProposerKnown` 的 pubKeyHash 匹配口径疑点待测试床复现数据（纪律：先看清再动手） | PLAN-051 §二/§三；`PendingBlocksManagerAbstainObservabilityTest` 3 例 |
| 2026-10-27 | **PLAN-051 复现跑（零产品改动）**：同脚本复现**未复现**弃权形态——P1 三节点水位完全同步（59→149 每 30s+6）、D 仅 1 条 unknown-proposer（height 1 冷启动）；`abstain-risk` 观测面**按设计工作**（height=1 窗口实测 deferCount 5/6/7 升级 ERROR，3 分钟收敛）；P2 死锁判据 B 依旧通过（水位冻结/链高照涨/lag 96）。**重新定性**：弃权触发条件应为"追块高峰 + validator-set 装载时序"偶发窗口（PLAN-050 中 D 处于 124→164 追块高峰），**根因未定性、不做猜测性修复**；三路信号（FinalityStalled + abstain-risk + validators 摘要）已闭环，"活着但弃权"不可静默 | PLAN-051 §三/§四；`pl051-multivalidator.log`（Run 2） |
| 2026-10-28 | **桥 relay/event-source 调度从未执行——已修（PLAN-055，产品变更）**：ShedLock 4.x 拒绝 primitive 返回类型的 `@SchedulerLock` 方法，AOP 在调用前抛 `LockingNotSupportedException` ⇒ `BridgeEventSourceWorker`/`MessageRelayWorker` 自 PLAN-026/037/046 装配以来在 `relay-enabled=true` 下从未运行（单测用 `new` 绕开 AOP 故全绿）。修复=两处 `runOnce()` int→Integer；顺带补 bootstrap jar PG 驱动。复验：真起 bridge 应用（event-source + execute 全开）计数增长、逐条 executed+txHash。**同类盲区第 2 例（第 1 例=PLAN-038 的 Bean 重复）** ⇒ 装配开关类代码必须真起应用验证。 | PLAN-055 §三/§四；20 例 messaging 单测不回归 |
| 2026-10-27 | **弃权风险指标化（PLAN-052，产品变更）**：`AbstainMetrics` 暴露 `nexus_finality_{defer_total,defer_max_height,defer_max_count,abstain_risk}`（只读 PLAN-051 的 defer 计数、抓取零 DB）+ 告警第 18 条 `BlockAdoptionAbstainRisk`（warning，`abstain_risk==1` for 5m；单节点弃权≠全网死锁，后者归 FinalityStalled）。**口径**：计数只增不减 ⇒ 1 的含义是"本进程启动以来见过弃权风险"，非"当前正在弃权"；"当前"语义属后续切片。4 例新测试；规则 9 groups/18 alerts | PLAN-052 §二/§三；`AbstainRiskMetricsTest` 4 例 |

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
