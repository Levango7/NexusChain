# #27 历史切片归档（最终性故障注入剩余矩阵）

- **来源**：`docs/roadmap-unresolved.md` #27「最终性故障注入剩余矩阵」（2026-10-21 新录）
- **拆分前位置**：`master` @ `91329fda`；原 #27 行第 3 列（内容格）共 **10331 字符**
- **归档时间**：2026-11-05（E5 拆分动作，零事实改动）
- **归档原则**：本文件是**原文逐字归档**。相对原文只新增了按 PLAN 分段的小标题；数字、PLAN 编号、边界措辞、SHA-256、用例数一律未改、未概括、未删除。切片之间重复的事实按原文照旧保留（去重只发生在 `roadmap-unresolved.md` 的当前缺口清单里）。
- **当前仍开放的缺口**：见 `roadmap-unresolved.md` 的「#27 当前缺口清单」。

---


## PLAN-016 首批（Phase 6B）

🟡 **首批已交付（PLAN-016 Phase 6B）**：重复/延迟/乱序投票、双签顺序反转、JDBC/最终性表短时不可用；修复写库失败污染内存、最终化提前对外可见、`pendingBlock` 60 秒超时。

## PLAN-018 第二切片（Phase 6C）

**第二切片已交付（PLAN-018 Phase 6C）**：持久化投票 outbox（`finality_vote_outbox` 表 + `FinalityOutboxManager`，跨进程重启存活）、完整 `Vote` 签名随票落库（重启后能恢复 Vote 对象并**自行补最终化**）、**接收侧独立重试**（远端票写库失败不再蒸发）。

## PLAN-021 第二切片（Phase 6C；原文编号与 PLAN-018 重复）

**第二切片已交付（PLAN-021 Phase 6C）**：最终性视图**跨节点收敛**（每 5 秒把库里的新票/新终局拉进本地视图，合并必须过与提交路径同一套绑定校验、去重、单调）⇒ 3 节点验收里 A/B/C 的对外权重视图从 3000/2000/1000 收敛为**一致 3000/3000**，掉线节点重启后完全追平。

## PLAN-025 第三切片（Phase 6C）

**第三切片已交付（PLAN-025 Phase 6C）**：**独立存储拓扑下的跨节点最终性事实传播** —— 新增 0x5B 补票请求（`from`/`max`）+ 对端回推**已签名**票 + 定时 anti-entropy（默认 30s、回看 50 epoch、单次上限 200 票）+ 按验证人去重；响应复用 0x5A 投票通道（绑定校验/聚合验签/幂等落库不变），老节点丢弃 0x5B（向后兼容）。**同主机 2 进程 + 2 个独立 PostgreSQL 16.9 实例实测**：把 B 库中"对端的票"删掉、保留 B 自己的票后重启，补票开 ⇒ B 恢复到 `19..143 / 125 个 checkpoint`、水位与 A 一致（历史区间恢复 **51** 个）；补票关 ⇒ 历史区间（≤68）恢复 **0 个**（只剩 `70..145 / 76 个` 新 epoch），证明独立存储下的历史缺口**不会自愈**。**仍未做**：真实网络分区、完整拜占庭矩阵、补票窗口外的远古 epoch（本实验 1..18）不补、数据库整体不可用时只剩内存队列兜底（进程崩溃仍丢票）、迁移前老数据没有签名。

## PLAN-041 附记：晚加入节点卡 height 1 核销

**"晚加入节点卡 height 1"条目已核销（PLAN-041，2026-10-26）**：空库晚加入节点实测 `unknown proposer` 5 次全部在 height 1 且可恢复（`block will be retried after validator-set sync`），T0+116s 拿到全表回放的验证人集后 0→136 一次补落库、与 A 同步至 385/385——不再卡死（机制：F2 挂起重试 + PLAN-030 全表回放；本机同内核边界，跨内核/物理多机未验）。PG 整体停止的 `AccountDB`/提案/状态接口问题由 #28 首批修复。

## PLAN-065 第四切片（2026-11-01）

**第四切片已交付（PLAN-065，2026-11-01）**：拜占庭输入矩阵之「公钥正确、签名伪造」——准入校验原先只比对公钥，伪造票可占住验证人位、把合法票去重掉，使检查点永久无法最终化（重启后依旧）；现网络提交与跨节点收敛在计票/落库前做单票 Ed25519 验签，启动恢复只信任签名有效的未终局票（已终局保留历史展示口径）。`FinalityVoteForgeryTest` 4 例 + 老库恢复用例 1 例；反向对照 5 红、修复后 finality 全包 **135/135 绿**（同内核 JVM 测试，非物理多机）。**本切片未覆盖**：真实网络分区、完整拜占庭矩阵其余输入（女巫/长程/私钥泄露）、物理多机。

## PLAN-066 第五切片（2026-11-02）

**第五切片已交付（PLAN-066，2026-11-02）**：PLAN-065 放过之后四条拒绝路径只有 WARN 日志、无法回答"是否正在被伪造票攻击"——新增 `FinalityRejectionCounter` 端口（consensus 侧定义、metrics 侧适配，避免包循环）+ `nexus_finality_vote_rejected_total{reason,phase}` 与 `nexus_finality_finalize_rejected_total{reason}` 两个计数器 + `FinalityVoteRejected` / `FinalityFinalizeRejected` 两条告警（promtool v3.15.1 实跑 20 rules 全过，新增 6 个演练用例含"良性 reason 不触发"）。**刻意不做验证人地址标签**：攻击者用随机地址洪泛即可让标签基数无界增长、被观测对象被观测本身打垮，按人定位仍走日志。反向对照：抹掉三处转发调用 ⇒ 6 用例精确红、恢复后 7/7 绿；metrics+finality 全包 169/169 绿。**边界**：同内核 JVM 单测 + WSL2 里的 promtool CLI（非物理多机）、未在真实集群触发、未做抓取端补验与吞吐基准。

## PLAN-067 第六切片（2026-11-02）

**第六切片已交付（PLAN-067，2026-11-02）**：PLAN-065/066 之后，拒绝判定仍假设输入有界——新增 32 字节检查点哈希强校验（admission/convergence/restore/查询四路，先校验后建键）、codec/broadcaster 载荷上限 2048 字节、收敛游标只由"真正合并有效票/已有有效投票者/已终局"的键推进（脏高 epoch 行不再跳过低 epoch 合法票）、提交路径写库成功后才建内存 voter set、单验证人单 epoch 新键上限 K=4（仅限新键；对已存在键投票永不拦截，避免假键挡住真检查点）、epoch→keys 索引把双签扫描从全表降到同 epoch。新增 3 个拒绝原因（checkpoint_hash_invalid / checkpoint_limit_exceeded / payload_too_large）并扩展 `FinalityVoteRejected` 告警与 2 条 promtool 演练。测试：`FinalityCheckpointBoundsTest` 6 例 + `FinalityVotePayloadBoundsTest` 4 例 + coordinator fail-closed 1 例；finality 全包 153/153 绿、metrics 27/27 绿。反向对照 5 组（哈希 4 红、游标 1 红、K 上限 1 红、载荷 2 红、restore 独立闸 1 红），还原后 10/10 绿且三文件 SHA-256 与变异前一致。**边界**：同内核 JVM 单测（非物理多机）；未做 epoch 窗口/规范检查点语义校验（需链接口）；不清洗历史短哈希行（只丢弃/不建索引/不占配额）；未在真实集群触发告警。

## PLAN-068 第七切片（2026-11-02）

**第七切片已交付（PLAN-068，2026-11-02）**：PLAN-066/067 之后仍有一条从未被验证的链路——两版文档都登记了「未做 /actuator/prometheus 抓取端补验」，全仓 src/test 里搜 actuator/prometheus 只有 2 处注释、零条抓取断言。本切片先用真实进程 + 真实 PG 实证：WSL2 PostgreSQL 16.15 库 + Windows 侧单进程 nexus-core（localonly 取默认 true、19585 端口），不带 token 实测 /actuator/prometheus **200**、同进程 /cheackTxlist 与 /nodeInfo 仍 **401** ⇒ 抓取可达且鉴权在工作。**结论是不改白名单**：Boot 4.0.8 的 actuator 由独立 WebMvcEndpointHandlerMapping 提供、构造时不调 setInterceptors，WebMvcConfigurer 注册的 HandlerInterceptor 够不到 /actuator/**；把 /actuator/prometheus 塞进 LOCAL_ONLY_PUBLIC_PATHS 是无效修复且会固化错误心智模型；/actuator/health 也不加（Helm/k8s/compose/prod 探针全部打 /height）。标签可见性用真实 PG 实证：插入 3 字节哈希脏行、等一个 5s 收敛周期后，抓取文本出现 nexus_finality_vote_rejected_total{phase="convergence",reason="checkpoint_hash_invalid"} 1.0（插入前该序列连 # HELP 都不存在）。门禁：FinalityMetricsScrapeGateTest 2 例（RANDOM_PORT 真实内嵌 Tomcat + JDK HttpClient 发不带 token 的 GET，断言 200 + 三个新 reason 各 =1 + # TYPE counter，外加 401 对照与生产配置断言）+ FinalityRejectionLabelContractTest 4 例（规则 reason=~ 的每个取值必须与枚举逐字一致，且 PLAN-067 三个新 reason 必须被某条告警消费——FinalityRulesContractTest 只校验指标名、不看标签值）。反向对照 4 组精确变红（抹掉 counter.increment 1 红、生产配置去掉 prometheus 2 红、放行对照端点 401 断言红、规则里拼错 reason 3 红），还原后 4 文件 SHA-256 与变异前逐字一致；另有**一条重要的「仍绿」反向结果**：把 excludePathPatterns 去掉让拦截器名义覆盖 /** 后抓取仍 200，独立再证实 HandlerInterceptor 不参与 actuator 的独立 HandlerMapping（想给 actuator 加鉴权必须用 EndpointFilter / Spring Security / 独立 management.server.port）。nexus-core 全量 **220 类 / 1845 用例 / 0 失败 / 0 错误 / 19 跳过**，Code Hygiene 49 = 基线。**边界**：同内核单机（WSL2 PG + Windows 侧 core，**非物理多机**）；未起真实 Prometheus/Alertmanager、未在真实集群触发告警；actuator 目前**没有鉴权**（本切片只证「抓取可达、标签可见」，不是「暴露面已加固」）；未做吞吐/基数基准；合成拒绝不代表真实攻击。

## PLAN-070 第八切片（2026-11-03）

**第八切片已交付（PLAN-070，2026-11-03）**：PLAN-068 的遗留项写作「吞吐/**基数**基准（**高基数**下 phase × reason 的抓取开销）」——认领前查代码发现**这个前提不成立**：Reason 是 9 项闭合枚举、Phase 3 项、FinalizeReason 1 项，FinalityRejectionMetrics 的 map 键是 long（两个 int ordinal 打包）与枚举 name()，都是 ConcurrentHashMap ⇒ 键集合由编译器保证有界，序列数上限 = 9×3+1 = **28，与流量/验证人数/地址数无关**（PLAN-066 刻意不加 validator 地址标签正是为此）。若照原提法做会写一个测不出问题的压测。**实测**（Micrometer 1.16.7 + prometheus 1.4.3）：27 序列全注册后单次 scrape **0.0749 ms** / 文本 **2096 字节**，1→27 序列只涨约 1.6 倍（固定开销占主导）；并发下平均 0.0433 ms、最差 1.5466 ms、序列行数 27 无丢失。**同时纠正了我自己的预查错误**：以为「counter 未 increment 就不注册」，实测 register() 一旦执行即使 count=0 也会出现在抓取文本（值 0.0）；但生产用 computeIfAbsent 懒注册，故「从未发生的 reason」确实不出现，且 increase(<不存在序列>) 返回空向量 ⇒ 不误报、首现即生效、无延迟窗口。门禁 FinalityRejectionScrapeBudgetTest 4 例钉住序列上限、**标签键严格等于 {reason,phase}/{reason}**、体积预算、懒注册与稀疏序列语义（全部断言结构性事实、不断言绝对耗时以免 flaky）。**反向对照抓到门禁自身一个真实缺陷**：最初只数「序列行数」，加 validator 标签后每个 (reason,phase) 仍只有一行 ⇒ 条数不变、体积只涨几十字节 ⇒ 预算断言完全不红（当时是另一条用例字面量拼接意外变红，属「红得不精确」）；改为断言标签键集合后变异 A 精确命中 `实际 [phase, reason, validator]`。反向对照 2 组：加 validator 标签 → 标签键断言红；改预注册全量 → 稀疏序列两例红（预期 0 实际 27）；还原后 SHA-256 与变异前逐字一致。**边界**：不是压力测试/容量规划（不测 Prometheus 服务端存储/查询成本）；同内核单机非物理多机；未起真实 Prometheus/Alertmanager；不改生产注册方式。

## PLAN-071 第九切片（2026-11-03）

**第九切片已交付（PLAN-071，2026-11-03）**：PLAN-069 留下的那一刀「finality_checkpoints 脏行的语义确认与清理」。取证三步：① markFinalized(epoch, byte[]) **无长度闸门**（H2 探针实测可写 3 字节与 0 字节行）；② 生产路径**确实能到达**——restoreFromStore() 的「已终局分支」把键无条件写进 epochCheckpointWeights（hasValidCheckpointKey 只挡「建索引」、没挡「进集合」），随后 sweepFinalization("startup-restore") 会遍历它；③ 后果实测——3 字节已终局行的 isFinalized() 返回 **false** 而库里仍写着该行，形成长期 DB/内存视图不一致，且每次重启都重新载入、永不清除。**先纠自己的错**：第一次取证只看了 submitVote/收敛/sweep 遍历的集合，没意识到其中一类键从未经过校验，得出过「生产路径到不了」的错误结论，补做 gadget 级实测后才纠正——教训是不要把「多数路径安全」当成「所有路径安全」，要看键是怎么进来的。设计：checkpoints 单独一张同构隔离表 finality_checkpoints_quarantine（该表主键无 validator 列，硬塞进 votes 隔离表就得编哨兵值，审计表说谎就无法可靠反查）；修订已合入的 001 而不新开 002（有依据：migrations/pg/ 不在迁移棘轮门禁作用域内，已核验该门禁只认 <模块>/db/migration/V<n>__*.sql；且 001 从未在任何真实环境执行过）——并把「一旦 001 在真实环境执行过就必须新开编号」写进 docs/db-schema-versioning.md。端到端演练（4 类行：votes 1 合法+3 脏、cps 1 合法+2 脏）：迁移后 votes 4→1、cps 3→1，隔离 3/2 分类正确，重复执行幂等，回滚后两表都恢复且隔离表保留审计。门禁 FinalityDirtyRowMigrationContractTest 6→8 例；反向对照 4 组全部精确命中（判定 =64→<64、去掉 EXISTS、回滚搬错表、migration_id 塌缩），还原后 SHA-256 与变异前逐字一致。**边界**：脏行**不能由外部攻击者制造**（submitVote/收敛均 fail-closed），只能来自迁移前历史库或手工写库 ⇒ 历史污染+视图不一致，非可达攻击面；未在生产库执行；不为 markFinalized 加运行时防御（会改变存储层契约，且现有 5 处测试刻意用 3 字节哈希测「任意字节可存取」）；不处理 finality_vote_outbox。

## PLAN-072 第十切片（2026-11-03）

**第十切片已交付（PLAN-072，2026-11-03）**：PLAN-071 留下的最后一条「finality_vote_outbox 脏行传播性确认」。**结论是不传播、无需清理**——所以交付的是证据与门禁，不是清理脚本。取证：H2 + 真实 JdbcFinalityStateStore + 真实 FinalityGadget + 真实 FinalityOutboxManager，用一条**签名完全有效、仅哈希长度非法（3 字节）**的票（确保测的是长度闸而非签名校验）。① 脏票**能**进 outbox（enqueue 只挡 null，不校验长度；实测入队后 pending=1、表 1 行）；② 但重放时 flush → tryFlush → submitVote，而 submitVote 先走 validateVoteBinding（含 32 字节校验）把脏票**正常拒绝**（返回 finalized=false，**不抛异常**），tryFlush 只在 catch 到异常时才留着重试 ⇒ 实测连续 3 轮 flush 后内存队列与 outbox 表**都归零**，finality_votes/finality_checkpoints 均 0 行，isFinalized=false，voter 集合空 ⇒ **不沉淀、不变毒丸、不污染**。三张表行为对比（本切片核心结论）：votes 与 checkpoints 写入侧都不校验、脏行**会沉淀**（故 PLAN-069/071 需隔离）；outbox 是「写库意图」暂存，**它的出口 submitVote 本身就 fail-closed** ⇒ 脏票只是「一次失败的尝试」不是「沉淀的事实」。门禁 FinalityOutboxDirtyVoteContainmentTest 2 例（含「脏票可入队」**前提断言**，防止将来给 enqueue 加校验后用例假绿；以及连续 3 轮 flush 不残留）。反向对照 2 组全部精确命中：A 让 submitVote 抛异常（模拟硬失败）⇒ 两例都红（expected 0 but was 1，即毒丸行为）；B 给 enqueue 加长度校验 ⇒ 前提断言红；还原后两文件 SHA-256 与变异前逐字一致。**一个必须说清的隐含依赖**：本结论前提是「submitVote 对非法哈希正常拒绝而非抛异常」；若将来改成抛异常，脏票会变成毒丸（每 5s 重试一条永远失败的票、永远留在 outbox），门禁会红但修复需新代码（如按 attempts 上限丢弃并计数）。**边界**：这是「不需要做」的结论、**不是「已修复」**；取证在 H2 上完成（与 PG 同一 store 驱动）；同内核单机非物理多机；不涉及生产库数据操作。

## PLAN-073 第十一切片（2026-11-04）

**第十一切片已交付（PLAN-073，2026-11-04）**：PLAN-072 确认 outbox 脏行不传播后，暴露了「outbox 运行状态在 Prometheus 上完全不可见」的盲区，且端点自身有两个语义缺陷——积压数用 `loadPendingVotes(64).size()` 最多只报 64（真实 500 行会低报）、DB 读失败被 catch 成 0（与「已排空」无法区分）、`totalFailed` 把「队列满永久丢票」和「重试失败」混在一起。修法：`FinalityStateStore.countPendingVotes()`（JDBC `SELECT count(*)`、内存 `size()`）+ `FinalityOutboxStatus.databaseReadable`（`isDegraded()` 纳入不可读）；新增 `FinalityOutboxMetrics`（`MeterBinder` + `@Component`，30s 采样、抓取零 DB）8 个指标；队列满只加 `dropped`；4 条告警（Dropping / Backlog / MemoryBacklog / Unreadable，规则 20→24）+ promtool 4 组演练 + 指标名/标签契约门禁（`EXPECTED_METER_COUNT` 14→22，`pending` 标签键严格 `{origin}`、取值 local/received）。反向对照 3 组精确命中：`count(*)`→`LIMIT 64`（100≠64）、读不到→报 0（NaN≠0）、丢票→`totalFailed`（dropped 1≠0），还原后三文件 SHA-256 一致。全量 nexus-core **1869 用例 / 0 失败 / 0 错误 / 23 跳过**；SpotBugs 通过、Code Hygiene 49 = 基线。**边界**：同内核单机 + promtool（非物理多机）；未起真实 Prometheus/Alertmanager、未在真实集群触发；采样 30s 滞后；不改重试策略/attempts 上限/自动清理。

## PLAN-074 第十二切片（2026-11-04）

**第十二切片已交付（PLAN-074，2026-11-04）**：用户指示「本地环境能做多少做多少」⇒ 拆除 PLAN-073 时「需 Prometheus/Alertmanager」的前提——从 GitHub releases 下载 Prometheus 3.15.1（sha256 `6a726885…`）+ Alertmanager 0.34.2（`17ee14ac…`），与 WSL2 PostgreSQL 16.15、WSL 内 JDK 单进程 nexus-core 组成**本机三进程**验证链路（同内核单机，非物理多机）。四条事实全部实测：① 真实 PG 插 100 行 ⇒ `database_pending 100.0`（旧实现低报 64），Prometheus 侧 `value=1e+02`；② `service postgresql stop` ⇒ `database_pending NaN` + `database_readable 0.0` ⇒ PLAN-073 修的「读不到冒充 0 积压」在真实连接失败路径上成立；③ 抓取文本 9 条序列的 `# TYPE`/`_total`/`origin` 形状与契约一致；④ `FinalityOutboxBacklog`（for 10m）与 `FinalityOutboxUnreadable`（for 5m）在真实 Prometheus **firing**、真实 Alertmanager **active**、webhook 落盘，且 PG 停机后 Backlog 自动转非 firing、Unreadable 亮起（语义切换符合预期）。CI 侧新增 `FinalityRulesCounterUsageTest`：counter 必须包 `increase()`/`rate()` 引用（裸比较在 counter 归零后永不触发），违规时报出「哪条告警 + expr 原文」；实现返工一次（自扫规则文件会把 description/注释/组级说明块算成引用 ⇒ 改为复用 `PrometheusRulesFixture.groupExprLines`）。反向对照：`increase(...dropped_total[10m])`→裸比较 ⇒ 精确报出 `FinalityOutboxDropping` 且无连带假阳性；还原后绿、规则文件 SHA-256 `29F80F9E…845F1A74` 一致。**边界**：自下载社区二进制、非真实机房、不宣称告警已在真实集群触发；scrape 15s+采样 30s 与生产可能有差异；9090-9091 被 Windows docker 容器占用故监控栈用 9097/9098/9099/9094；Alertmanager 默认 gossip 端口与 webhook 冲突改 9099；实验脚本与日志均留在仓库外。**仍未做**：真实网络分区、完整拜占庭矩阵其余输入（女巫/长程/私钥泄露）、物理多机、真实集群告警演练、Prometheus 服务端成本基准。
---

## E5 补录（owner 回填，2026-11-05；非原文内容）

> 本节由 owner（nex lane）在 E5 拆分后按 master 现状补录；**不改上文任何逐字内容**。

### PLAN-069（`finality_votes` 历史脏行隔离）

- 原被 PLAN-071/072 正文引用，但 #27 序列缺对应分段（E5 漂移通知第 3 条）；
- 交付：`finality_votes` 迁移前历史脏行（短哈希/畸形）的「先隔离后删除、
  可回滚」脚本与演练；master 上交付留言 #420 `c5835fa5`；实现细节见
  `docs/plan/PLAN-069-finality-dirty-row-cleanup.md`；
- 与 PLAN-071（checkpoints 隔离）、PLAN-072（outbox 脏行不传播确认）合计三张
  最终性表收口。

### 编号勘误（E5 漂移通知第 1 条）

- 上文 `PLAN-018 第二切片` 与 `PLAN-021 第二切片` 的「第二」编号重复（原文如此）；
- 按实际交付序列，PLAN-072 是本矩阵第 13 段切片，不是「第十二」；
- 本勘误只作说明，不回写上文。
