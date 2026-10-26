# 方案 PLAN-047：多验证人拓扑的最终性 lag 基线 + 补票回看窗口边界量化

- **状态**：开发中（2026-10-27）
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

## 三、结果（2026-10-27 首轮；含一次脚本缺陷的如实记录）

### 3.0 脚本缺陷（先说，因为它污染了部分结论）

本轮脚本把三个节点的 `validator-private-key` 传成了 **4 字符字符串**
（`aaaa`/`bbbb`/`cccc`）而非 64 hex 密钥 ⇒ 引擎按随机密钥生成地址，
**E 每次重启都会变成一个新的验证人地址**。日志实证：A/D 的注册表回放显示
**4 validators**（A_rand、D_rand、E_old、E_new）。下文凡受此污染的结论都标注
「受污染」，需干净重跑后复核。

### 3.1 受污染但仍有产品价值的发现：重启换钥 ⇒ 门 Pernentes 死锁

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

### 3.4 告警设计的致命盲区（与判据无关的额外发现）

P3 最终性冻结后，A 的 `latestVotedEpoch` 跌出 8-epoch 回溯窗 ⇒
`nexus_finality_lag` 变成 **null（NaN）** ⇒ `FinalityStalled` 表达式不成立 ⇒
**最终性死掉时告警反而熄火**。PLAN-045 用"窗口内最近有票 epoch"定义 lag 的缺陷：
 lag 必须以**单调水位**（如 lastFinalizedEpoch/lastRecordedEpoch 全程最大值）定义，
 死锁时 lag 无界增长才该报警（下游 PLAN-048）。

### 3.5 补票回看窗口边界（判据 2）——本跑未测成

P3 中 E 未恢复块同步（3.2），其检查点始终停在 102，无法量化"窗外缺口"；
E 的补票请求 `from epoch 188 served with 99 votes` 在持续（补票链路活着）。
⇒ 判据 2 留待 E 的卡死问题定性后重测。

## 四、结论与处置

1. **判据 1（lag 基线）**：不通过——健康 3 验证人拓扑 lag 基线 = 2（恒定为 2，
   非瞬态）⇒ `FinalityStalled` 阈值重标定（PLAN-048，含 NaN 盲区修复）；
2. **判据 2（窗外缺口）**：未测成（E 卡死挡住了）——留待 3.2 定性后重测；
3. **判据 3（宕机一个验证人）**：通过——A/D 在 E 宕机 631s 期间持续最终化；
4. **额外发现 A（产品风险）**：重启换钥 ⇒ 第 4 验证人 ⇒ 门限 2/3 永不可达 ⇒
   全网最终性冻结而链继续出块。登记为风险项 + 后续治理（注册表死项回收/
   密钥稳定性强制），不在本切片改代码；
5. **额外发现 B（待定性 bug）**：3 验证人下 E 的链头卡死（追块窗口动、链头不动、
   height-62 块被 unknown-proposer 挂起）——需干净重跑（固定密钥）确认是否复现；
6. **脚本缺陷已记录**（3.0）：重跑脚本必须用 64 hex 固定密钥。

## 五、边界（勿夸大）

- 本机同内核三进程 + 三个独立 PG；非跨内核/物理多机；E 的"宕机"是杀进程
  （不是断电/磁盘坏——那两项已分别由 PLAN-042/硬件受限项覆盖）；
- P2 不动 E 的库 ⇒ 只测"补票窗口"不测"数据丢失"；
- 采样粒度 15s（P1/P3）/60s（P2）：瞬态抖动可能落在采样间隙。
