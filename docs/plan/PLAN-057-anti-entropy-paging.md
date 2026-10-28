# 方案 PLAN-057：补票从"单轮上限"到"游标分页"（roadmap #27 根治项）

- **状态**：已交付（2026-10-28）；三轮测试床抓出 5 处缺陷、已修 4 处，
  **第 6 处（anti-entropy 调度整体停摆）未定位** ⇒ 端到端效果未证明，不宣称闭环
- **前置**：PLAN-025（anti-entropy 0x5B 补票）、PLAN-048b（margin=200 实测窗外缺口
  29→0，但确认当时是"多轮自然逼近"）、PLAN-047/042（宕机 105 epoch 丢 29 的量化基线）
- **范围**：补票协议加 `cursor` 分页（响应端按请求升序服务 + 请求端游标推进），
  让"补回多少"只取决于**轮次与间隔**，不再受 `max-votes` 单轮上限约束
- **不包含**：改投票格式、改接收端落库/绑定校验/聚合验签、改 MANIFEST 信任模型

---

## 一、问题（PLAN-048b 复看后的重新定性）

PLAN-048b 用 `margin-epochs=200` 把窗外缺口从 29 压到 0，结论写的是
"当前是多轮自然逼近，不是分页"。**这次实现期复看代码，把定性改掉了**：

`JdbcFinalityStateStore.loadLatestVoteObjects` 的 SQL 是

```sql
SELECT ... FROM finality_votes WHERE epoch >= ? AND signature IS NOT NULL
ORDER BY epoch DESC LIMIT ?
```

——**永远返回最新的 N 条**。于是"从 `from` 起请求"这个语义只在前 N 条覆盖整个
`[from, 最新]` 区间时才成立。缺口 ≤ N 时确实能补上（这就是 048b 窗外缺口归零的
真实原因：最新 200 条覆盖了 105 epoch 的缺口）；缺口 > N 时，**中段结构上补不回来**——
请求端每轮拿回的还是同一批最新票，"from 每轮只前进约 1 个 epoch"不是收敛慢，
是**补不上**。

所以 PLAN-057 不是"性能优化"，是**能力缺口**：缺口 > 200 票时旧协议无解。

## 二、方案

### 2.1 协议（0x5B 载荷体，向后兼容）

```json
{"from":<epoch>,"max":<上限>,"cursor":<bool>}
```

- `cursor=true`：请求方声明**分页爬升**；响应端必须**升序**（老→新）服务；
- `cursor=false`：缺省，完全维持 PLAN-025 行为（降序、取最新 N 条）。

**向后兼容**（已测）：老节点不认识 `cursor` 键，`FinalityVoteSyncCodec.decode`
解出 `false` ⇒ 走原路径。新节点对老节点发 `cursor=true` 时，老节点仍降序回最新
N 条 ⇒ 请求端游标被推到"最新+1"，下一轮请求空转一次后按终止条件退出分页
⇒ **渐进降级，不会死循环、不会漏补**（见 §2.3）。

### 2.2 响应端：升序服务

新增 `FinalityStateStore.loadVoteObjectsAscending(fromEpoch, limit)`：

- JDBC 覆盖为 `ORDER BY epoch ASC LIMIT ?`（排序下沉 SQL，避免全表读入）；
- 默认实现按 epoch 升序在内存截断。

`handleVoteSyncRequest` 按 `req.cursor()` 分流：
`cursor=true` ⇒ 升序；否则沿用 `loadLatestVoteObjects`。

### 2.3 请求端：游标推进与终止

状态（`FinalityVoteBroadcaster`）：

- `nextSyncCursorEpoch`：下一轮起点，-1 = 未启用/已完成；
- `lastRespondedEpoch`：本轮已见最大 epoch（`onVoteReceived` 记录，**单调不回退**）；
- `votesSinceRound`：本轮请求发出后收到的票数（含正常广播来的）；
- `pagingRoundIssued`：是否已发过分页请求。

每轮：

```
推进游标 = max(游标, lastRespondedEpoch + 1)     // 本地投票继续时不会被拉回
请求 from = 游标（首轮 0）
votesSinceRound 清零后计时
```

终止判据（**关键**）：`pagingRoundIssued && votesSinceRound < maxVotes`
⇒ 上一轮响应不足上限 ⇒ 响应端已无货（响应条数 = min(可服务数, 上限)）。

> **为什么不用"游标越过本地 maxEpoch"**：第一版就是这么写的，被测试抓出来——
> 追赶期本地 `maxEpoch` 天然落后于对端，这个条件会在**第一轮后就触发**，
> 分页等于没开。改用"响应条数不足上限"后，判据只依赖对端的供给量。
> 正常广播来的票会让计数偏大 ⇒ 最多多跑一轮空转后终止，不会漏补。

## 三、实现期被抓出的修正（同一内核，逐条记）

1. **游标推进缺失**：我只写了"记录 lastRespondedEpoch"，没写"写回 nextSyncCursorEpoch"。
   `cursorAdvancesMonotonicallyAcrossRounds` 第 2 轮 `expected 5 but was 0` 直接命中。
2. **终止条件用错量**：如上，`cursor > maxEpoch` 过早判完成。
3. **测试自身的错误假设**：我手工用 `max=4` 请求、却拿调度器的 `maxVotes=200`
   比计数，报了假失败。改为真实规模（250 票 / 默认上限 200）后，这一例同时变成
   **对旧行为的直接反证**：第 2 轮必须从 201 起，旧实现会再次请求并拿回同一批
   最新 200 张，1..200 的中段永远补不上。

**第 4 处由测试床抓出**（8 例单测都没抓到），见 §5.3：终止判据数的是"收到票数"
而不是"游标处有没有新数据" ⇒ 在线集群的广播涓流下，分页第一轮后就自认为完成。
这是本方案最重要的教训：**单测全绿 ≠ 生产可用**，与 PLAN-055 同型盲区。

**第 5 处也由测试床抓出**（修正第 4 处后复测才发现），见 §5.4：首轮起点取
进程内 `lastRespondedEpoch + 1`，而该值在宕机节点重启后停在**宕机前最后一条
本地票** ⇒ 首轮只问到边界 1~2 票就判无货，中段完全没搬。
起点必须从**库里算**（margin 地板），不能从**进程态读**。

⇒ 5 处里 2 处单测抓到、3 处测试床抓到；可复用的问句见 §八。

## 四、测试

`FinalityVoteSyncPagingTest`（**9 例**，全绿）：`nexus-core` 模块全量回归绿。
第 8、9 例是测试床回归（§5.3 / §5.4），此前 7 例全绿但**没抓住真缺陷**。

1. `cursorRequestCarriesCursorFlag` — `cursor=true` 载荷带 `"cursor":true`；
2. `legacyRequestOmitsCursor` — 两参入口隐式 `cursor=false`；
3. `responderServesAscendingForCursorRequests` — 分页请求下响应端**升序**
   （5 张票 max=3 ⇒ 回 1,2,3；降序实现会回 5,4,3）；
4. `cursorAdvancesMonotonicallyAcrossRounds` — 250 票、上限 200：
   两轮 0 → 201 → 251 补完全部 250 张；
5. `shortResponseCompletesPaging` — 3 张可服务、上限 4 ⇒ 只回 3；
6. `decodeWithoutCursorDefaultsToFalse` — 无 cursor 键 ⇒ `false`（含兼容构造器）；
7. `schedulerFallsBackToMarginWhenPagingDisabled` — 关开关时走旧 margin 路径
   （本地 max=101、margin=50 ⇒ from=51）；
8. `pagingSurvivesBroadcastTrickleAndAsyncDelivery` — **测试床回归（第 4 处）**：
   只收到远小于上限的票 + 宽限期过后，不得判终止，须从已见最大+1 继续；
9. `firstPagingRoundStartsFromMarginFloorNotLastResponded` — **测试床回归（第 5 处）**：
   进程态 `lastRespondedEpoch` 很高时，首轮起点仍须是 margin 地板。

## 六、边界

- **不改**接收端落库/绑定校验/聚合验签：分页只改「请求从哪里开始」与「响应按什么顺序给」；
- 混部（新<->旧节点）自动退化为「单轮上限、多跑一轮空转后退出」，**不保证**追上 >200 的缺口；
- 终止判据依赖宽限期内是否见到游标处新数据 ⇒ **宽限期必须大于 P2P 往返**，
  否则假终止（缺省 5000ms，生产 interval 30s 时绰绰有余；测试床 5s interval 是临界值）；
- 首轮起点取 margin 地板 ⇒ **margin 越大首轮搬得越多**（与 margin 模式同源，
  这是有意的：宁多搬幂等票，不可漏中段）；
- §5.2 / §5.4 的数字都是**各自修正前**的实测，不能互相印证、也不能外推到修正后；
- 真正的端到端「永不丢票」仍需多机拓扑验证（硬件受限项不变）。


## 五、验证（测试床，2026-10-28 三轮三节点实测）

拓扑：A/D/E 三个 Windows JVM（**同内核虚拟化，非物理多机**）+ 三个独立 PG 库；
`interval-ms=5000`、`max-votes=200`、`margin-epochs=200`、`paging-enabled=true`；
E 阶段性杀进程（不动库）。脚本与日志均在**仓库外**，不入仓：
`mm-pl057-paging.ps1` / `mm-pl057-verify.ps1`、`pl057-paging.log` / `pl057-verify.log`。

### 5.1 三轮的宕机规模（都 > 上限 200，正是分页要解决的量级）

| | 第 1 轮 | 第 2 轮 | 第 3 轮 |
|---|---|---|---|
| E 宕机时长 | 631 s | 601 s | 601 s |
| 宕机前 votesE | 276 | 151 | 142 |
| 宕机后 votesA | 531 | 455 | 425 |
| **缺口** | **255 票** | **304 票** | **283 票** |

### 5.2 第 1 轮：判据缺陷 ⇒ 分页等于没开

| 项 | 实测 |
|---|---|
| 票数追平耗时 | **583 s** |
| 全程 `cursor=true` 请求 | **1 次** |
| 其余请求 | 全部 `cursor=false`（margin 模式） |

根因见 §5.3。**这是单测抓不到的那一类。**

### 5.3 第 1 轮根因：终止判据问错了问题

原判据「本轮收到的票数 < `max-votes`」。在线集群里正常广播是**涓流**
（实测约 3 票 / 5s），永远到不了 200 ⇒ 该条件几乎恒真 ⇒ 分页在**第一轮后就
自认为完成**，随后每 5s 一次「开始爬升 → 立刻判完成」空转：

```
10:26:15 Anti-entropy paging: starting cursor crawl from epoch 335 (local max epoch=334)
10:26:20 Anti-entropy paging complete: previous round returned 0 votes (< max 200)
```

**为什么 8 例单测此前全绿**：测试只投递补票响应、没有广播涓流，且投递同步
⇒ 计数够、响应也已到达 ⇒ 判据漏洞不暴露。与 PLAN-055「单测用 `new` 绕开
Spring AOP 所以全绿」**同型盲区**：单测的环境比生产简单。

**修正**：判据改为「本轮请求起点 C，宽限期后仍没见过 epoch >= C 的票
⇒ 对端在 C 处无货」；新增 `paging-grace-ms`（缺省 5000ms）容纳 P2P 异步投递；
新增回归测试第 8 例。

### 5.4 第 2 轮（复测）：判据修好了，但**首轮起点又错了**

判据修正后确实见效——`+26s` 一次性补进 **129 票**（第 1 轮全程只零星进 3 票/轮）。
但后端证据显示仍然只搬了一轮：

- E2 日志只有 1 条 `Anti-entropy paging: starting cursor crawl from epoch 197`；
- 全程 `cursor=true` 仅 A 侧 2 条 / D 侧 1 条，且响应对端「只服务 1~2 票」；
- 无 `paging complete`，之后彻底没有 paging 请求。

**根因（第 5 处）**：首轮起点取自进程内 `lastRespondedEpoch + 1`。
E 宕机前最后一条本地票是 epoch 196 ⇒ 重启后 `lastRespondedEpoch=196`
⇒ 首轮 `cursor=197`，只问到边界 1~2 票就判「无货」退出，**304 票的中段完全没搬**。

**修正**：首轮起点改为与 margin 模式同源的地板
`max(0, localMaxVoteEpoch - margin-epochs)`（该地板 <= 任何真实缺口起点，
不漏中段）；续轮才推进 `lastRespondedEpoch + 1`。
新增回归测试第 9 例 `firstPagingRoundStartsFromMarginFloorNotLastResponded`。

### 5.5 第三轮复测：起点修好了，但**整个 anti-entropy 调度随后停摆**

同参数再跑一轮（E 宕机 601s，缺口 `votesA 425 - votesE 142 = 283 票`）：

| 观察 | 实测 |
|---|---|
| 首轮起点 | **`from epoch 0`**（margin 地板，第 5 处已修） |
| 首轮服务量 | A 回 `served with 200 votes (servedMaxEpoch=67)` |
| E 首轮实收 | `+17s votesE 142 → 251`，即 **+109 票**（第 2 轮同期只 +4 票/轮） |
| 之后 | 只有涓流（~4 票 / 9s），**10 分钟内未追平** |
| `cursor=true` 请求总数 | **1 次** |
| `cursor=false`（margin）请求总数 | **0 次** |

**起点修正确实生效**（首轮从 0 起、一次拿到 200 票服务、净收 109 票）。

**但发现一个更严重的新现象**：首轮之后，E 上**连 margin 请求都再也没有发过**
（A/D 侧 `cursor=false` 计数为 0）。也就是说这不是"分页提前退出"，
而是 **`scheduledVoteAntiEntropy` 整个调度在首轮后彻底停摆**——
而第 2 轮（旧判据）同样只跑一轮分页，之后 margin 请求仍在持续。

对照之下，怀疑对象从"分页逻辑"转向"调度链路 / P2P"：
`@Scheduled(fixedDelay)` 从上一轮**完成**起算，若 `peerServer.broadcast`
在首轮阻塞（gRPC CANCELLED 类问题该轮日志里确实出现过），单线程调度器
就此饿死 ⇒ 分页与 margin 全停。**此推断未证实**，需下一轮带
`logging.level.org.nexus.consensus.finality=DEBUG` 复跑定位。

### 5.6 三轮小结（不外推）

| 轮次 | 首轮请求 | 首轮实收 | 之后 | 追平 |
|---|---|---|---|---|
| 1（判据缺陷） | `from 335` | 0 票 | margin 涓流持续 | 583 s |
| 2（起点缺陷） | `from 197` | +129 票 | 全停 | 未追平 |
| 3（起点已修） | `from 0` | **+109 票** | 全停 | 未追平 |

**能说的**：起点修正把"首轮问错位置"修好了，首轮搬运量从涓流变成一次 200 票服务。
**不能说的**：分页能否连续多轮搬完 283 票缺口——**三轮都没能证明**，
因为后两轮都出现了调度停摆，而停摆本身还没定位。

## 七、后续

- **定位调度停摆**（§5.5，优先级最高）：开 DEBUG 复跑，确认是
  `peerServer.broadcast` 阻塞饿死 `@Scheduled(fixedDelay)` 单线程调度器，
  还是别的原因。**在此之前分页的端到端效果无从谈起**；
- 补一例「宽限期不足 ⇒ 假终止」的负向测试（当前只有正向）；
- 与 PLAN-048b 的对照测量合流：同一脚本、只切 `paging-enabled`。

## 八、方法论沉淀（本方案最值得留下的）

两轮测试床 + 9 例单测，抓出 5 处缺陷，其中 **4 处单测都没抓到**。
共性是：**单测的环境比生产简单**—— PLAN-055 是"绕开 AOP"、
本次是"没有广播涓流 + 投递同步 + 进程态不含重启后的历史"。

可复用的问句（下次写带计数/游标/状态的判据时先问）：
1. 现场有没有**别的流量**也在增加我这个计数器？（本次：广播涓流）
2. 我这个量在**进程重启后**是什么值？（本次：`lastRespondedEpoch` 停在宕机前）
3. 数据是**异步**到的吗？我下一 tick 就判，会不会读到"还没到货"？
4. 我的起点是**从库里算的**还是**从进程态读的**？（本次：后者引入了 197）

