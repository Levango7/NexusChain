# 方案 PLAN-050：`FinalityStalled` 告警的测试床演练（PLAN-048 登记项）

- **状态**：已交付（2026-10-27）；演练暴露**一个真实的误报缺陷**（判据 A 不通过），
  已定位到根因并完成表达式重设计
- **前置**：PLAN-048（告警重标定：`changes(nexus_finality_latest_voted_epoch[15m])==0`
  for 10m，登记"未做测试床复测"）、PLAN-044（指标与端点）、PLAN-047（死锁形态
  实证：注册表混入第 4 验证人 ⇒ 最终性冻结而链继续出块）
- **范围**：**零产品代码改动**——用测试床实证两条判据：健康不误报、死锁必触发
- **不包含**：Alertmanager 路由/通知链路（部署侧）、多机拓扑

---

## 一、问题（为什么必须演练）

`FinalityStalled` 是本仓**唯一**一条"最终性死亡"专用告警，且 PLAN-047 实测过
对应故障形态（出块正常 + 最终性冻结 8 分钟+）。它当前的表达式
`changes(nexus_finality_latest_voted_epoch[15m]) == 0` for 10m 有两个**未经
测试床验证**的假设：

1. **健康稳态不误报**：3 验证人中 A/E 的 lag 基线恒 2（PLAN-047 实测），
   但那是 lag；`changes(水位)` 看的是**水位是否推进**——稳态下每 ~6s 推进一次，
   15min 内应有 ~150 个样本点变化。**假设需要实证**：若某节点水位因快照口径
   （粘滞 + 8-epoch 回溯窗）出现"长时间不动但实际健康"，就会误报 critical；
2. **死锁必触发**：水位冻结 ⇒ `changes==0` 对 15m ⇒ 10min 后 critical。
   **假设需要实证**：若冻结发生时水位仍在动（例如补票/重放在推水位），告警哑火。

## 二、实验设计（预登记判据）

拓扑（同 PLAN-047/048b）：A/D/E 三进程 + 三独立 PG；指标实时读
`GET /consensus/finality`（等价 `/actuator/prometheus` 的 gauge 值——
注意：本实验**不起 Prometheus**，用"水位值序列"直接模拟 `changes()` 语义：
统计窗口内水位取不同值的次数）。

**阶段**：
- **P1 健康基线（~8 分钟）**：每 30s 采样 A/D/E 的
  `latestVotedEpoch`；统计每个节点"8 分钟窗口内水位变化次数"
  （等价 `changes(水位[15m])` 的 1/4 缩比，epoch 推进速率同比例）。
  - 判据 A：每节点变化次数 **> 20 次**（即 15min 全窗 > 60 次）⇒ 健康不误报；
- **P2 注入死锁（~20 分钟）**：杀掉 D 和 E（只剩 A 一个活验证人 ⇒
  1/3 权重 < 2/3 门限 ⇒ 最终性冻结、链仍出块——PLAN-047 形态的最简复现）；
  每 60s 采样 A 的水位。
  - 判据 B：A 的水位在 P2 期间**冻结不变**（且链高继续增长）⇒
    `changes==0` 成立 ⇒ 告警必触发；同时 A 的 `progress` 应冻结在最后已知值
    （PLAN-048 粘滞语义）。

**收尾**：恢复 D/E 不需要（实验即终止）；记录台账。

## 三、结果（2026-10-27）

### 3.1 判据 B（死锁必触发）：✅ 通过

P2 杀掉 D/E（只剩 A ⇒ 1/3 权重 < 2/3 门限）后：

- A 的水位**从 149 冻结**（1201s 采样窗口内一次未动）；
- 链高继续推进 309→489（**出块与最终性彻底分离**，正是最危险形态）；
- A 的 `progress` 冻结在 `False/33%`（PLAN-048 粘滞语义正确工作）；
- `finalityLag` 单调增长 6→96，`finalizedCheckpoints` 冻结 148。

⇒ `changes(水位[15m])==0` 在死锁下**必然成立**，告警逻辑无误。

### 3.2 判据 A（健康不误报）：❌ **不通过——发现真实缺陷**

P1 健康 8 分钟窗口：A/E 水位每 30s +6（投影 15min 约 30 次变化，**安全**），
但 **D 的水位 8 分钟内只动了 1 次**（59→62 后冻结在 62），投影 15min 仅 2 次
⇒ `changes==0` 在 D 上**成立 ⇒ 会对一个"活着"的节点报 critical**。

**根因（日志实证）**：D 从 15:27:54（epoch 60）起**完全停止投票**——
`Finality vote submitted` 再无新行，只有持续服务别人的 `Vote-sync request`；
同时 D 的链高在涨（t+480s 时全网 h=300）。即：**D 不再是"最终性死锁"，
而是"最终性弃权"（自身投票链路断了，但仍在出块/追块/服务补票）**——
水位冻结恰好是它的真实症状，从告警语义看**不是误报**。

但这暴露了一个真正的**产品问题**：一个"活着但不再投票"的验证人节点能在
健康集群里长期存在而**没有任何显性症状**（D 的高度一直跟着涨、日志只有
INFO 级 `unknown proposer at height 125; will be retried`）。
`FinalityStalled` 恰好把它照了出来——从"告警是否有用"角度这是**真阳性**；
从"节点自评健康"角度这是**一个必须修的缺陷**：投票链路为何在 epoch 60 后停止。

**待定性（PLAN-051，已有强证据）**：D 的投票停在 epoch 60（height 120）后，
**卡点是一条 unknown-proposer 消息**：
- 15:28:41 起 D 反复 `PendingBlocks: unknown proposer (validator set not
  synchronized) at height 125; block will be retried after validator-set sync`
  （全程仅 height 1 ×2 + height 125 ×12，**只有这一条卡住**）；
- height 125 的 proposal 在 round-robin 里的 proposer 是 D 的注册表里
  **INACTIVE/未登记的地址**（区块收到却认不出提案人）；
- `onBlock` 的投票前置是 `NewBlockEvent`（采纳后触发）⇒ **125 采纳不了 ⇒
  D 从此不再产票**，而链高照涨（日志 `blocks received ... stop at 164`）。
- 代码路径**已缩小**：D 的注册表三个验证人都在（15:22-15:23 三条
  `Validator registered`），D 自己也 `Selected proposer nv3gC5...` 选它
  （round-robin 里**有**它）——但 `isProposerKnown`（按 coinbase pubKeyHash
  查注册表）却认不出。怀疑面：`findValidatorByPubKeyHash` 的 pubKey 匹配
  （注册表存的是公钥 hex，coinbase.to 是 pubKeyHash 截断）与
  registry 装入时序；D 日志 15:28 后无 `Validate failed` WARN（校验在
  PendingBlocksManager 侧就挂了，未到引擎）。
- **下一切片（PLAN-051）**：在该测试床上复现"D 停止投票 + height 125 卡住"，
  在 `ConsensusRule/PendingBlocksManager` 加定点观测（拒绝原因结构化），
  定位 proposer 视图不一致根因后修复。**本切片不改产品代码。**

## 四、结论与处置

1. **判据 B 通过**：死锁⇒水位冻结⇒`changes==0`⇒告警必触发，链路成立；
2. **判据 A 不通过但转为真阳性**：D 的水位冻结不是告警误报，而是
   **"活着但不再投票"的弃权节点**被照出来——告警设计正确，产品有缺陷；
3. **派生工作项（PLAN-051）**：定位 D 在 epoch 60 后停止投票的根因
   （测试床复现 + 代码路径分析），这才是本轮演练最有价值的产出；
4. **告警表达式不变**（`changes(水位[15m])==0` for 10m）；runbook 增补
   "弃权节点"形态的排查入口（投票链路 vs 门限 vs 追块）。

## 五、边界（勿夸大）

- 本实验用"水位值序列统计"模拟 Prometheus `changes()`，**未起真实
  Prometheus/Alertmanager**——`for: 10m` 的 pending→firing 状态机由规则语义
  保证，不在本实验范围；
- 死锁注入方式是"杀到只剩 1 验证人"（权重门限不可达），与 PLAN-047 Run 1 的
  "注册表混入第 4 验证人"是**两种不同成因**的同形态故障——本实验验证的是
  **告警对形态的敏感度**，不是穷尽故障模式；
- 单机同内核三进程；`changes` 依赖 30s 抓取间隔的假设已在 §一讨论，
  本实验按 30s 采样对齐。
