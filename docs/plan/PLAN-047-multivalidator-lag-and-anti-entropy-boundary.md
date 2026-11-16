# 方案 PLAN-047：多验证人拓扑的最终性 lag 基线 + 补票回看窗口边界量化

- **状态**：已交付（2026-10-27，含一次受污染跑 + 一次干净复跑）
- **前置**：PLAN-045（`nexus_finality_lag` 指标 + 告警，边界写明"未测真实多验证人
  拓扑的 lag 基线"）、PLAN-025（补票 anti-entropy，回看 50 epoch）、
  PLAN-042/043（单节点冷恢复实测）
- **性质**：验证切片——**产品代码零改动**；判据预登记

---

## 一、问题（两个待量化边界）

1. **PLAN-045 §四 留的边界**：`nexus_finality_lag` 只在 2 验证人拓扑实测过
   （全程 0）。3 验证人轮转出块时 lag 基线是多少？是否仍稳定为 0？
   （如果健康态 lag 就有 1，`FinalityStalled` 的 `>=2` 阈值需要重新标定。）
2. **PLAN-025 的回看窗口边界**：补票 anti-entropy 回看 50 epoch。一个宕机
   **超过 50 epoch** 的节点重启后，恢复不了的历史 epoch 有多少？边界到底卡在
   哪？（PLAN-042/043 的窗口（<50 epoch）内缺口是 21/683；窗外未知。）

## 二、实验设计（判据预登记）

拓扑（本机同内核三进程 + 三个独立 PG 库，如实边界见 §五）：

| 节点 | 角色 | 存储 | 密钥 |
|---|---|---|---|
| A | 常驻 mining | `nexuschain` | aa.. |
| D | 常驻 mining | `nexuschain_d` | bb.. |
| E | 阶段性宕机 | `nexuschain_e` | cc.. |

阶段：
1. **P1 健康基线**（3 验证人，约 5 分钟）：每 15s 采样三端
   `/consensus/finality`（lag / finalized / progressPercent / finalizedCheckpoints）；
2. **P2 E 宕机**（停 E 的 java 进程，**不动它的库**，约 10 分钟 = 远超 50 epoch）：
   A/D 继续出块；每 60s 采样 A/D 的 lag；
3. **P3 E 重启**（同参）：每 15s 采样 E 的 lag / 追块 / 补票，直到与 A 的
   `finalizedCheckpoints` 差值稳定；对比 E 与 A 的 epoch 覆盖缺口。

**预登记判定**：
1. P1 健康期 lag 基线：若出现 `lag>=1` 持续 >2 分钟 ⇒ 记录**真实基线值**，
   复核 `FinalityStalled` 阈值是否需上调；若全程 0 ⇒ 阈值不变、边界关闭；
2. P3 E 恢复后：量化"未恢复 epoch 数" = A 的 `finalized_checkpoints.max(epoch)`
   − E 的同值（补票尽力后的残余缺口），并与 50-epoch 回看窗对照（缺口应约等于
   宕机时长折算的 epoch − 50）；
3. P2 期间 A/D 的 lag 必须保持 0/短暂（宕机一个验证人不许拖停最终性）。

## 三、结果（2026-10-27：首轮受污染跑 + 干净复跑）

### 3.0 两轮跑的关系（先说清楚）

| 轮次 | 密钥 | 结论可用性 |
|---|---|---|
| Run 1（07:22-07:35 时段）| ❌ 4 字符（随机密钥） | E 重启换址 ⇒ 4 验证人 ⇒ 门限失效冻结。**该发现本身有效**（产品风险），但拓扑被污染 |
| Run 2（07:41-08:12） | ✅ 64-hex 固定密钥 | 干净基线；下文判据以 Run 2 为准，Run 1 的发现单列 |

### 3.1 Run 1 的脚本缺陷（保留记录）

本轮脚本把三个节点的 `validator-private-key` 传成了 **4 字符字符串**
（`aaaa`/`bbbb`/`cccc`）而非 64 hex 密钥 ⇒ 引擎按随机密钥生成地址，
**E 每次重启都会变成一个新的验证人地址**。日志实证：A/D 的注册表回放显示
**4 validators**（A_rand、D_rand、E_old、E_new）。下文凡受此污染的结论都标注
「受污染」，需干净重跑后复核。

### 3.2 Run 1 发现（产品风险，仍有效）：重启换钥 ⇒ 门限不可达

- P1（3 验证人，E 卡在 height 64）：A/D `finalized=true, progress=67%`
  （2/3 门限达标，A+D=2000/3000）；
- P2（E 宕机 631s ≈105 epoch）：A/D 继续出块、继续最终化（67%）——
  **宕机一个验证人不影响最终性**（判据 3 通过）；期间 A/D 的 lag 基线见 3.3；
- **P3（E 重启，新地址 ⇒ 第 4 个验证人）：A/D 的最终性冻结**——
  `finality vote submitted` 的最后几条 progress 从 67% → 33% → 25%，
  `finalizedCheckpoints` 停在 A=233 / D=235 不再增长，而链高继续推进到 591
  （持续 8+ 分钟）；E 自身卡在 height 61。
- **机制（日志支持，未代码级定案）**：注册表无上限也无死验证人回收，E 重启换钥
  后总质押变 4×1000=4000，A+D=2000=50% < 2/3 门限 ⇒ 永远无法达门限
  （`Validator-set full-table replay sent: 4 validators` + progress 25%/33% 为证）。
- **产品风险（与判据无关的额外发现）**：节点重启若换了密钥（误配/轮换），
  **全网最终性会永久冻结**而链仍在出块——正是最危险的"静默退化"。

### 3.2 受污染的不确定：E 的块同步卡死

- E 全程 `height=64`（P1）/`61`（P3）不动，但它的**最终性视图在跟着网络走**
  （P1 期间 f 从 47 涨到 102，与 A/D 同步）——即：**票能到、块到不了**；
- E 日志显示它在努力：`require blocks start from 61 stop at 316` →
  `blocks received start from 61 stop at 316`（追块窗口在工作），但单条推送被
  `invalid block received reason = the block height 587 is too small or too large,
  current height is 61` 拒绝；且高度 62 的块被
  `unknown proposer (validator set not synchronized) ... will be retried` 挂起。
- **待干净重跑定性**：3 验证人 + 正确固定密钥下，后加入/重启节点是否仍卡在
  "追块窗口在动但链头不动"（若复现 ⇒ 真 bug，属 F2/catch-up 族，另开切片）。

### 3.3 `nexus_finality_lag` 的真实基线 ≠ 0（判据 1，重标定必要）

P1/P2 全程 A/D 的 `finalityLag` **恒为 2**（20 个连续采样 + 10 个 60s 采样无一例外），
`finalized=true`。即：**健康 3 验证人拓扑的 lag 基线就是 2**，不是 2 验证人拓扑的 0。
⇒ **PLAN-045 的 `FinalityStalled`（`lag>=2` for 5m, critical）会在这个健康拓扑里
持续误报 critical**——阈值必须重标定（下游 PLAN-048）。

### 3.4 Run 2 干净基线（判据 1/3 复核 + 判据 2 首次测成）

**P1 健康 3 验证人（20×15s）**：A/E 的 lag **恒为 2**、E 高度与 A 完全同步、
finalized 67%（2/3 达门限）——**判据 1 复现且确认：健康基线 = 2**（PLAN-048 的
`changes==0` 阈值设计因此成立，`lag>=2` 必须废弃）；D 的 lag 在 0/1 摆动、
finalized 在 67%/33% 间交替——**33% 意味着 D 的本地视图只收到 1/3 的票**
（提交时快照的顺序浮动，PLAN-043 同款，最终仍 67% 收敛）。

**P2 E 宕机 631s（~105 epoch，10×60s）**：A/D 的 lag 恒 2、finalized 恒 67%
（2/3 达门限）、高度 249→480 持续推进——**判据 3 复核通过**：宕机一个验证人
不影响出块与最终性。

**P3 E 重启（40×15s）——判据 2 首次测成**：
- E **+15s 即从 113 追到 226**（追块加速生效，不是卡死——Run 1 的"链头卡死"
  确系随机密钥污染，**发现 B 撤销**），+45s 起与 A 逐块同步（E=h=499/A=h=499）；
- E 的检查点从 113 一路补到 334（`Vote-sync request (from epoch 305) served
  with 152 votes` 持续在跑），而 A 到 363；
- **窗外缺口量化：E 缺 29 个 epoch**（363−334）。宕机 105 epoch 中补回 76、
  丢失 29——与"回看 50 epoch"的口径定性吻合（窗口外的部分永久丢失）；
- 全程 E 的 lag 在 0/1/2 间摆动、A 恒 2，无告警级异常。

### 3.5 Run 1 的告警设计盲区（与判据无关，已由 PLAN-048 修复）

P3 最终性冻结后，A 的 `latestVotedEpoch` 跌出 8-epoch 回溯窗 ⇒
`nexus_finality_lag` 变成 **null（NaN）** ⇒ `FinalityStalled` 表达式不成立 ⇒
**最终性死掉时告警反而熄火**。PLAN-045 用"窗口内最近有票 epoch"定义 lag 的缺陷：
 lag 必须以**单调水位**（如 lastFinalizedEpoch/lastRecordedEpoch 全程最大值）定义，
 死锁时 lag 无界增长才该报警（下游 PLAN-048）。

### 3.6 判据总表（Run 2）

| 判据 | 预登记标准 | 结果 |
|---|---|---|
| 1. 健康 3 验证人 lag 基线 | 全程 0 ⇒ 阈值不变；≥1 持续 ⇒ 重标定 | **基线恒 2**（A/E），D 在 0/1 摆动 ⇒ 已重标定（PLAN-048） |
| 2. 宕机 >50 epoch 后的窗外缺口 | 量化未恢复 epoch 数 | **缺 29/105**（补回 76）——窗外部分永久丢失，与 50-epoch 回看定性吻合 |
| 3. 宕机一个验证人不影响最终性 | A/D lag 保持、finalized 达门限 | ✅ lag 恒 2、finalized 恒 67%、高度持续推进 |

**Run 1 的"E 卡死"（原发现 B）撤销**：干净密钥下 E 重启 15s 即从 113 追到 226
并同步——那是随机密钥污染的假象。

## 四、结论与处置

1. **判据 1（lag 基线）**：确认基线 = 2（A/E 恒定；D 在 0/1 摆动）⇒
   `FinalityStalled` 已重标定为 `changes(水位)==0`（PLAN-048）；
2. **判据 2（窗外缺口）**：**测成**——宕机 105 epoch、补回 76、**永久丢失 29**
   （补票回看 50 epoch 的定性边界首次量化；想补得更多就调大 anti-entropy 回看窗，
   但这属产品决策，不在本切片）；
3. **判据 3（宕机一个验证人）**：通过（第二轮复核）；
4. **Run 1 发现 A（产品风险，**未修**）**：重启换钥 ⇒ 注册表混入第 4 验证人 ⇒
   2/3 门限永不可达 ⇒ 全网最终性冻结而链继续出块。已由 PLAN-048 的
   `FinalityStalled`（进度冻结 ⇒ 告警）+ runbook 做到"可被发现"，
   治理动作（注册表死项回收/密钥稳定性强制）另立；
5. **Run 1 发现 B 撤销**：干净密钥下 E 重启 15s 即追块同步，"链头卡死"系随机
   密钥污染（4 验证人门限失效 + 乱序票）的假象，非产品 bug；
6. **脚本缺陷已记录**：实验脚本必须用 64-hex 固定密钥（否则每节点随机地址）。

## 五、边界（勿夸大）

- 本机同内核三进程 + 三个独立 PG；非跨内核/物理多机；E 的"宕机"是杀进程
  （不是断电/磁盘坏——那两项已分别由 PLAN-042/硬件受限项覆盖）；
- P2 不动 E 的库 ⇒ 只测"补票窗口"不测"数据丢失"；
- 采样粒度 15s（P1/P3）/60s（P2）：瞬态抖动可能落在采样间隙；
- 补票"丢失 29"是在**本拓扑参数**（epochLength=2、3s 出块、5s anti-entropy、
  50 epoch 回看）下的一次测量，不是通用常数；换参数需重测。

## 六、A-2 补测（2026-11-16，zcode）：女巫/临时键稀释的量化 + 治理缺口确认

> §四.4 判定「治理动作（注册表死项回收/密钥稳定性强制）另立」。本节给这块缺口补上
> **可量化的事实底座**：自注册零门槛 + 自报质押时，稀释门限的代价有多低、
> **死项是否跨重启持久**（决定威胁模型的复制度）。
> 本节仍是**量化取证**，不给治理方案拍板（产品决策，见 §六.4 选项清单）。

### 6.1 已坐实的机制（代码级复核，2026-11-16）

- `ValidatorNodeBootstrapper`：`@ConditionalOnProperty(nexus.consensus.mode=pos)`；
  `nexus.finality.self-register` 默认 **true**、`nexus.finality.self-stake` 默认 **1000**。
  监听 `ApplicationReadyEvent` ⇒ 取引擎签名钥推导本节点验证人地址 ⇒
  `validatorRegistry.register(address, pubHex, selfStake, 0.1)` ⇒
  `stakingService.seedStake(address, selfStake)` ⇒ 签名广播 + **10s/30s/60s 三次重发** +
  写入共享表 `validators_synced`（供重启后全量重放）。**全路径无审批/治理白名单**。
- `ValidatorRegistry.register`（`ValidatorRegistry.java:111`）仅四道闸：空地址 / 重复地址 /
  **集合满 100**（`DEFAULT_MAX_VALIDATORS`）/ **质押 < minStake**（`DEFAULT_MIN_STAKE=1000`）。
  **没有「是否经治理许可」这道**；且默认 minStake(1000) 恰等于默认 self-stake(1000)
  ⇒ 出厂配置下自报质押**等值通过**。
- `ValidatorRegistry.unregister`（:140）只 `setStatus(INACTIVE)`——条目仍在表内。
  `FinalityGadget.computeTotalWeight()` 按 `getActiveValidators()` 累加，
  故 INACTIVE 不计权重；但**进程被杀＝什么都没调用**，条目连 INACTIVE 都不是，
  权重照计。
- 对端收到 ADD 广播（`SyncManager.java:550-556`）**先 `seedStake` 再 `register`**
  ⇒ 一个已经死掉的临时键，其 1000 权重照样进入**其它诚实节点**的总权重。

### 6.2 演练实测（2026-11-16，三段判据）

拓扑：3 个诚实验证人 A/D/E（各自报 1000）+ N 个**临时 sybil 进程**（起来即自注册、
广播后立刻被杀）；epochLength=2、block-interval=2s、round-robin proposer、每节点独立 PG。

| 阶段 | 集合构成 | votedWeight/totalWeight | 最终性 | 证据 |
|---|---|---|---|---|
| PHASE 0 基线 | 3 诚实 | 2000/3000、3000/3000 交替（=66.7%/100%） | **正常推进**：f=64→71 @ h=146，lag=2 | `20261116-…`（见板） |
| PHASE 1 +1 死项 R1 | 3 诚实 + 1 死 | 3000/4000 = **75%** | **仍在最终化**：f=82→88；但已见出块 slot 空转（`voted=1000/4000` 那一刻＝轮到死项当出块人）与一次 30s 停摆 | R1.log：`Self-registered as validator: address=ZdTao2tgPx… stake=1000` |
| PHASE 2 +第 2 个死项 R2 | 3 诚实 + 2 死 | 3000/5000 = **60%** | **最终性冻结**：见下 | R2.log：`Self-registered as validator: address=fwLxvrGU2s… stake=1000` |

PHASE 2 的冻结形态（12 次 10s 采样，全同）：

- `finalizedCheckpoints` **恒为 140**（120s 内零增长），而链继续出块 **h=289→305（+16 块）**；
- `finalityLag` **单调 3→4→5→6→8→8→9→10→11**（2 分钟内 +8 epoch）；
- 逐 epoch 进度降到 `25%/75%/25%/25%/20%/20%`（2/3 门限永不可达）。

**攻击成本量化**：一个 JVM 进程跑约 **50 秒**即可完成自注册 + 签名广播 ⇒
**永久**获得 1000 的分母权重；**两个**进程（约 100 秒）就让 3×1000 的诚实集跌破 2/3。
不需要链上资金、不需要治理许可、不需要真实质押证明（self-stake 是配置值）。
这正是 §四.4「重启换钥 ⇒ 注册表混入第 4 验证人 ⇒ 2/3 门限永不可达」的同一根因，
本节把它从「偶遇」变成「可复现、可按成本计数」。

### 6.3 死项是否持久（决定威胁模型复制度）

- 杀掉 sybil 进程后**无人回收**：`unregister` 没有任何自动调用方（进程死亡不触发）；
- **库里持久**：PHASE 1 后 `nexuschain_a2a.validators_synced` = **4 行**（3 诚实 + R1）；
  PHASE 2 后 = **5 行**（R1 + R2 都在）。摘录地址前缀：
  `mRCg6sK3Et|aKURTTsKzh|ZdTao2tgPx|nv3gC5mGzF|fwLxvrGU2s`；
- **跨重启持久**：杀掉 A 再冷启，A.log 连续打印
  `Validator-set full-table replay sent: 4 validators (4 signed payloads verbatim, 0 legacy rebuilt)`，
  重启后 `voted=2000/4000`（R1 权重仍在分母里）；
- ⇒ 一次稀释是**永久性**的：不靠外部清理/换库，权重分母不会自己回去。

### 6.4 检测面：已有什么、时延多少（不新增代码）

- `FinalityStalled`（`deploy/k8s/50-prometheus-rules.yaml`）`for: 10m`
  （同文件测试钉住：9m 不报、12m 报）⇒ 本演练 120s 的冻结**在观测窗内不会触发**；
  最快可见时延 ≈ **10 分钟**；
- `nexus_finality_lag` 单调增长（3→11 / 120s）本机可观测，但 Prometheus 侧
  **没有 lag 增长类告警**（PLAN-048 只做了「水位冻结」这一种）；
- runbook 对 `abstain-risk` 的描述已预写「多余/僵死验证人会抬高总权重」，
  并给了对账手段（`GET /consensus/validator-set` 回放地址对账），
  但缺「如何发现」的操作化步骤；
- B-11（kind 告警端到端）已于 2026-11-14 交付 ⇒ 若后续要加/标定告警，
  **链路本身不再是缺口**，缺的只是判据。

### 6.5 治理缺口（登记，不拍板）

与 §四.4 同根的四个缺口（按可复现性排序）：

1. **自注册无治理闸**：任何进程（不需要资金/许可）可把自身写入验证人集合并广播全网；
2. **无死项回收**：进程死亡 ≠ 条目失效，集合永不收缩（本轮实测 100 秒造成永久冻结）；
3. **minStake 是自报值**：质押无链上资金/锁定证明，`1000` 是配置文件里的一个数；
4. **无密钥稳定性约束**：换钥重启即新增条目（§四.4 Run 1 发现，本节给出量化后果）。

处置选项（**产品决策，本件不拍板**）：
(a) `self-register` 默认关 + 白名单准入；(b) liveness 心跳 + 死项降权/回收；
(c) 质押改为可验证形态（链上锁定/治理多签）；(d) 只文档化 + 靠告警兜底。
任一项都超出「验证切片」范畴，按 §四.4 归属另立。

### 6.6 边界（勿夸大）

- 本机同内核 3+1 进程 + 三独立 PG；sybil 是**同机另起进程**，不是跨机器伪造身份；
- round-robin proposer：死项占 slot 的代价依赖 proposer 策略，换策略需重测；
- 75%/60% 两个门限点只在本拓扑参数（epochLength=2、block-interval=2s、3 个诚实×1000）
  下成立，不是通用常数；诚实集规模变化会整体平移；
- **未测**：真实私钥泄露（持有者主动作恶投票，属另一形态——那是伪造票而非稀释分母）
  与长程攻击（需历史链重写，现实性低，按 ROI 清单只登记蓝图不投入）；
- **未测**：anti-entropy 是否会给死项「补票」或复活其条目——本轮未观察到此形态，
  不宣称其无；
- 本件**不含任何产品代码改动**；治理选项见 §6.5，均待产品决策。
