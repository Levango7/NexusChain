# 方案 PLAN-028：共识活性 —— 提议人轮转 view-change（修复 PLAN-027 发现 4 / F3）

> 承接 PLAN-027 §五后续顺序（该切片按优先级 F3 > F2 > F1 > Fifo 排序，本切片为
> 首个修复切片）。此前登记的缺陷：round-robin 按**纯高度**映射提议人，
> 指定槽位的提议人死亡或被落后抑制时，该高度永远无人出块，全网停摆且不能自愈
> （多机实验日志范式：A 每秒重复 `Selected proposer aKURTT... at height 165` 无限等待）。

## 一、问题

`PosProposer.selectRoundRobinProposer(height)`（PLAN-007）按
`index = height % size` 在**地址排序后的有序验证人集合**中固定选取：

```text
slot(163) = A，slot(164) = C，slot(165) = B   （ordered = [B, A, C]，size=3）
```

缺陷两条：
- **死亡槽位无人接管**：C 下线后轮到 C 的 slot（164）无人出块；
- **被抑制的槽位无人接管**：B 落后被 `isBehindPeerChain` 锁死出块
  （PLAN-002 抑制机制工作正常），轮到 B 的 slot（165）同样无人出块。

没有 round/超时/换人概念与对应实现——`height` 完全确定提案者，两个独立缺陷
互为放大：一轮卡死即封死「后续轮到任何人」的可能 ⇒ 停摆不可自愈。

## 二、修复设计：本地计时的 view-change（Tendermint 式 round-stepping）

- 提案者函数分子的确定性扩展：`index = (height + round) % size`。
  对同一 `(height, round)`，全网各节点独立算出**同一**提案者（仍按地址序），
  保持 PLAN-007 的确定性协调不破。
- round 的推进为**各节点本地计时**（不加网络消息）：
  `PosConsensusEngine.advanceRoundIfSlotTimedOut(height)`：
  - 高度变化（本地 best 推进，含收到对端块）→ 重置 `round=0`、周期起点=now；
  - 同一高度空闲超过 `nexus.consensus.slot-timeout-seconds`（默认 **5s**；
    对齐抖动余量 > 传播延迟量级，`0` 显式禁用维持 PLAN-007 原语义）→
    `round + 1`、周期基准按**固定周期**推进（绝对基准推进而非重置为 now，
    单次调用最多推进一轮——检测延迟不累积、不一次吞掉多个周期，
    长时间无块由每秒调度器持续追赶）。
  - `propose()` 主流程：计算 height 后 `int round = advanceRoundIfSlotTimedOut(height)`，
    再 `selectProposerWithStrategy(height, round)`（random 策略**不受影响**）。
- 死亡节点恢复后：客户端按 `(height + round) % size` 轮转逐个尝试槽位，
  轮转到其自身 slot 时接管出块，链恢复活性。
- 同高度双块兜底（过渡窗口不同节点 round 观测可能错位 ⇒ 双块）：
  由**已存在**的机制承接——`validate()` 的提案者校验保证块本身合规，
  分叉由 PLAN-003 reorg（采纳更长的分叉链）收敛。不新增网络消息；风险敞口
  为双块分叉的一过性（同高度两块，最终一条链胜出），寿命以秒计。

**配置**：`nexus.consensus.slot-timeout-seconds`（默认 5；0 = 禁用轮换）。
默认值使该修复对**单节点部署无感**（自己就是唯一槽位提案者，
本地即块 → 高度推进不断重置 round），对多节点按需调节。

## 三、测试（nexus-core，全部通过）

新增/扩展（gradle `:nexus-core:nexus-core:test` 全模块 BUILD SUCCESSFUL）：

| 用例 | 验证点 |
|------|--------|
| `PosConsensusEngineViewChangeTest.disabledTimeoutNeverAdvances` | timeout=0 禁用：恒 round=0（PLAN-007 原语义保留） |
| `.newHeightResetsView` | 高度推进 → round 重置（新视图周期） |
| `.withinTimeoutKeepsRound` | 槽位空闲 3s < timeout=5s：不再推进（避免抖动换人） |
| `.beyondTimeoutAdvancesOneRoundPerPeriod` | 超 6s → round=1；固定周期 5s 推进（绝对基准：拨 now-6s 后空闲 1s 不再递增） |
| `.advancingThroughProposerSequenceRestoresLiveness` | 连续 6 周期拨 2s → round 1..6 稳定递增（活性恢复路径） |
| `.differentHeightsAdvanceIndependently` | 停摆后高度漂移/回滚场景的重置 || `PosProposerRoundRobinTest.roundZeroMatchesHeightOnlySemantics` | `round=0` 与纯高度语义**完全一致**（含负高度） |
| `.roundAdvancesToNextProposerInFixedOrder` | slot(5)=v2 卡住 → round=1 → v0、round=2 → v1（地址序顺延） |
| `.roundWrapsAroundConsistently` | round ≥ size 取模回绕一致性；跨节点（不同注册顺序）对同一 `(h, round)` 同一提案者 |

## 四、明确边界（勿夸大）

- **一致性是真保证，活性靠时钟**：`(height, round) → 提案者` 全网一致（确定性）；
  但 round 的推进是**各节点本地超时**，过渡窗口各节点 round 观测可以错位
  （两节点本地周期起点相差最多 1s 调度间隔 + 传播延迟）⇒ 理论上可同高度各出双块。
  双块的兜底机制均已存在（fail-closed 验签、PLAN-003 reorg 采长链），
  但"双块分叉收敛"本身没有新的绝对性保证，实践寿命一过性。
- **无拜占庭防御**：恶意节点可广播伪造高 round 块（被 fail-closed 验签拒绝），
  也可乘机在错位窗口竞争出块——与修复前相同的拜占庭敞口，不在本切片扩界。
- **无提案者健康度自动降质/罚没**：超时轮转是"绕开"，不是"追究"；罚没逻辑不变。
- **多机实证未完成**：本切片为单元测试 + 全模块回归；"停 C 60s → 恢复 → 补票追平"
  的多机闭环待 F2/F1/Fifo 都修复后统一重跑（PLAN-027 §五顺序 4）。
- **仅 round-robin 策略受益**：random 策略（默认单节点语义）无 round 概念，未改。

## 五、后续顺序

1. **F2**（发现 3）：出块节点主动追块通道——`SyncClient.getStatus()` 去掉
   `if (enableMining) return;` 或改为"落后时允许"；
2. **F1**（发现 2）：验证人集广播中继（星型拓扑 B↔C 缺 C）；
3. **发现 1**：Fifo Linux 启动卡死（读循环移守护线程 / `nexus.ipc.enabled` 开关）；
4. 四项修完后重跑多机实验：`停 C（app+PG）60s → 恢复 → 补票追平` 完整闭环，
   更新 `PLAN-027` §五、roadmap #34/#35/#30 状态。
