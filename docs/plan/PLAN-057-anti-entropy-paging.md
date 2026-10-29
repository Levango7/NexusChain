# 方案 PLAN-057：补票从"单轮上限"到"游标分页"（roadmap #27 根治项）

- **状态**：已交付（2026-10-29）；**8 轮测试床共抓出 11 处缺陷，11 处均已修**。
  第八轮实测（第 10 处修复后）**首次计分追平历史缺口**（E 厂机 602 s，缺 246 票；
  标记链 1→353 连续、零跳过；+75 s E=604 vs A=606，终态 1025/1026 差 **1 票**），
  但那 1 票暠出第 11 处：**响应页按 LIMIT 硬截会把边界 epoch 的票切在页外**，
  而游标按 `servedTo+1` 跳过 ⇒ 那票永远丢失。已修（响应端按 epoch 完整交付）。
  **第 11 处修后未复测** ⇒ 仍不宣称闭环。第三轮我曾误判为"调度停摆"，该结论已更正
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

## 五、验证（测试床，2026-10-28 四轮三节点实测）

拓扑：A/D/E 三个 Windows JVM（**同内核虚拟化，非物理多机**）+ 三个独立 PG 库；
`interval-ms=5000`、`max-votes=200`、`margin-epochs=200`、`paging-enabled=true`；
E 阶段性杀进程（不动库）。脚本与日志均在**仓库外**，不入仓：
`mm-pl057-paging.ps1` / `mm-pl057-verify.ps1`、`pl057-paging.log` / `pl057-verify.log`。

### 5.1 四轮的宕机规模（都 > 上限 200，正是分页要解决的量级）

| | 第 1 轮 | 第 2 轮（复测） |
|---|---|---|
| E 宕机时长 | 631 s | 601 s |
| 宕机前 votesE | 276 | 151 |
| 宕机后 votesA | 531 | 455 |
| **缺口** | **255 票** | **304 票** |

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

### 5.4 第 2 轮：判据修好了，但**首轮起点又错了**

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

### 5.5 第三轮复测：我此前的结论是**错的**——没有"调度停摆"，是两个代码缺陷

第三轮之后我曾写下"首轮之后整条 anti-entropy 调度静默，怀疑 broadcast 阻塞
饿死 `@Scheduled` 单线程调度器"。**该结论错误，现予更正。**

已排除该猜测：`ScheduleConfig` 的调度池大小是 **20**，不是单线程；
且 `Broadcast` 只是同步遍历 peer 调 `dialWithTTL`，没有共享锁能饿死整个池。

第四轮加了**心跳观测**（分页开启时每轮 tick 打一行状态）后，真相很清楚：

```
maxEpoch=69  cursor=-1  askedFrom=-1  lastResponded=-1  votesSinceRound=0   graceElapsed=false peerExhausted=false
maxEpoch=118 cursor=0   askedFrom=0   lastResponded=118 votesSinceRound=114 graceElapsed=false peerExhausted=false
maxEpoch=118 cursor=119 askedFrom=119 lastResponded=118 votesSinceRound=0   graceElapsed=false peerExhausted=true
maxEpoch=119 cursor=119 askedFrom=119 lastResponded=119 votesSinceRound=2   graceElapsed=false peerExhausted=false
```

全程 **128 个 tick、每 ~5s 一次** ⇒ 调度一直在跑，从未停摆。
对端服务日志也印证：`19:04:27 from epoch0 cursor=true served with 196 votes (servedMaxEpoch=70)`。

**缺陷 1（游标被现场票拖走）**：`lastRespondedEpoch` 同时被
「补票响应」和「正常广播」两条路径写入。上表第 2 行：补票批次只到
`servedMaxEpoch=70`，但 `lastResponded=118`——多出来的 48 个 epoch 全是
**正在生产的广播票**。于是第 3 行游标直接跳到 119，**整个 71..118 的中段缺口
被一次性跳过**，之后再也不会问。这正是 PLAN-057 想补的那部分。

**缺陷 2（终止分支不可达）**：`roundIssuedAtNanos` 每轮都刷新，而每轮间隔
就是 `interval-ms` ⇒ "距上次发请求的时间"恒等于 interval。
测试床用了 `paging-grace-ms=6000 > interval-ms=5000` ⇒
**128 个 tick 里 `graceElapsed=true 出现 0 次**、`peerExhausted=true 出现 25 次`。
⇒ 终止分支是死代码，分页永远不退出、也永不回退 margin 修补。

注：代码缺省 `paging-grace-ms=5000 < interval-ms=30000`，缺陷 2 不会触发；
是本测试床把 grace 设得比 interval 大才暴露。**但缺陷 1 与配置无关，任何配置下都会发生。**

### 5.6 四轮小结（不外推）

| 轮次 | 首轮请求 | 首轮实收 | 之后 | 追平 |
|---|---|---|---|---|
| 1（判据缺陷） | `from 335` | 0 票 | margin 涓流持续 | 583 s |
| 2（起点缺陷） | `from 197` | +129 票 | 全停（**误判**） | 未追平 |
| 3（起点已修） | `from 0` | **+109 票** | 全停（**误判**） | 未追平 |
| 4（加心跳） | `from 0` | +114 票 | 游标跳前沿，中段被跳过 | 未追平 |
| 5（修 6+7 后） | `from 171` | +200 票（171..237 实际服务段） | 游标跳到 372、原地打转 | 未追平（769 s 差 246 票） |
| 6（加 0x5C 后） | `from 0`（4s 后被毒标记拽到 201） | 首包 200 票 | 沿头部小步爬 201→333 | 未追平（670 s 差 358 票） |
| 7（修 6+7+8+9 后） | `from 0`（3 tick 后被老启发式拽到 184） | 首包 200 票（1..67） | 沿头部小步爬 187→353 | 未追平（917 s 差 123 票） |
| 8（修 10 后） | `from 1` | 首包 200 票（1..67） | 标记链连续爬 1→353 | **基本追平（75 s 差 1 票）** |

**能说的**：起点修好了（首轮从 0 起、一次 196/200 票服务）；
心跳观测证明了调度从未停摆，并定位到两个真实缺陷；
第五轮进一步证明第 7 处修复有效（终止分支从死代码变为可达）。
**不能说的**：分页能连续多轮搩完缺口——前 7 轮都没有；第八轮终于基本追平（差 1 票），但那 1 票暠出第 11 处，修后尚未复测。
**我曾错误地声称过"调度停摆"**，那是把"我没有看到日志"当成了"没有发生"。

第 6/7 两处修复各配一例回归测试并通过反向对照。第五轮测试床随后验证：
**第 7 处确实修好**；**第 6 处不够**——窗口过滤只挡得住窗口外的涓流。

### 5.7 第五轮：第 7 处修复确认，第 6 处暴露第 8 处

配置故意用 `paging-grace-ms=6000 > interval-ms=5000`（正是第四轮暴露
第 7 处的那套），三节点、E 宕机 602 s（缺口 ~246 票）。

**第 7 处（终止不可达）——确认修好**：

```
00:55:46  cursor=372 askedFrom=372 lastResponded=371 graceElapsed=false peerExhausted=true
00:55:51  cursor=372 askedFrom=372 lastResponded=371 graceElapsed=true  peerExhausted=true
00:55:51  Anti-entropy paging complete: no vote at or after epoch 372 after 6000ms grace
```

第四轮 128 个 tick 里 `graceElapsed=true` 出现 **0 次**，本轮正常出现；
`paging complete` 打点 36 次。计时从"游标上次前进"起算是正确的。

**第 6 处（窗口过滤）——不够，暴露第 8 处**：

```
# 对端 A/D 日志（两边一致）
Vote-sync request (from epoch171, cursor=true) served with 200 votes (servedMaxEpoch=237)
# 请求方 E 的心跳
maxEpoch=371 cursor=171 askedFrom=171 lastResponded=371
maxEpoch=371 cursor=372 askedFrom=372 lastResponded=371 peerExhausted=true
```

E 请求 `from=171`，A 按升序服务 **171..237**；但 E 的游标跳到了 **372**。
原因：第 6 处的窗口上界 = `roundAskedFrom + max-votes` = `171 + 200` = **371**，
而链头正好是 371 ⇒ **链头的正常广播票落在窗口内**、被当成响应票 ⇒
`238..370` 一次都没被请求过。

**根因不是窗口算错了，而是"用收到的最大 epoch 猜响应范围"这个启发式本身**：
请求方**无法从收到的票**判断对端实际服务了哪一段——`servedMaxEpoch=237`
只存在于对端日志里。任何"按最大 epoch 推进"的变体（含"连续覆盖"）都会
在同一形态下失败：链头票与响应票混在同一个通道里。

**放大器**：终止分支把 `nextSyncCursorEpoch` 重置为 `-1` ⇒ 下一轮重新按
`localMax - margin` 算地板 = 171 ⇒ 再问一遍同一个起点、再跳到 372、再终止。
本轮 `paging-start=37` / `paging-complete=36`，全在原地打转，一票没多拿。

结果：`+769s votesE=1079 votesA=1325`（差 246）⇒ **NOT-CAUGHT-UP**。

#### 第 8 处修法：0x5C 响应标记（向后兼容）

响应端在回推票之后，额外广播一条 **0x5C** 标记，携带
`requestFrom / servedFrom / servedTo / count`：

- 请求方据此**精确**推进 `cursor = servedTo + 1`，不再看"收到的最大 epoch"；
- `count=0`（空页）也发标记 ⇒ 请求方**立即**终止，不必再等一个宽限期；
- `requestFrom` 用于识别"这是不是我这轮的回答"（迟到响应/别家请求会被忽略）；
- **向后兼容**：老节点不认识 0x5C，按 `isVotePayload=false` 丢弃，只是继续用
  宽限期兜底；新节点收到老节点的响应（无标记）时退回第 6 处的启发式路径。

配套两点（不改协议）：

1. 终止后**保留续点**（`nextSyncCursorEpoch = roundAskedFrom`），不再回地板；
   并加 `paging-cooldown-ms`（缺省 60000）冷却，避免在"已无货"处每 interval 空转；
2. `onVoteReceived` 增加 0x5C 守卫——`FinalityVoteCodec.decode` **不校验魔数**，
   未被路由的标记会被解成一张"垃圾票"落库；
3. 老启发式回退加**两道闸门**：`!pagingMarkerCapable`（对端一旦证明会发标记，
   此后不再退回启发式）**且** `graceElapsed`（首轮标记可能还在路上，此时
   `lastRespondedEpoch` 可能只反映链头广播票）。没有这两道闸门时，
   "标记在途、链头票先到"仍会把游标拖到链头——第 13 例专测此形态。

### 5.8 第六轮：0x5C 标记被 margin 响应污染（第 9 处）

修复 6/7/8 后跑第六轮（同参数：grace=6000 > interval=5000，E 宕机 601 s）。

E 的标记处理日志（27 条）暴露一切：

```
02:11:08  starting cursor crawl from epoch 0 (local max epoch=199, margin=200)
02:11:12  marker: served 49..200 (200 votes) for request from 0 -> next cursor 201
```

E 从 0 起爬升；A 按升序回 1..50（对端日志 `servedRange=1..50`）。但 **4 秒后**
E 的游标跳到 **201**——这个标记携带的实际范围是 **49..200**，是"最新 N 条"
的形态，只有 margin（`cursor=false`）响应才长这样。

**污染路径**：标记的实现为**所有**补票请求回带，而请求方只按 `requestFrom`
匹配，无法区分这条标记来自游标响应还是 margin 响应。D/A 节点在
"头部跟爬 ⇒ 游标处无新数据 ⇒ 宽限期终止 ⇒ 进入 `paging-cooldown`"循环里
发 **margin 请求** `from=0`（冷却地板 = maxEpoch - margin < 0 ⇒ 0）⇒
响应 latest-200（49..200）附带标记 ⇒ 广播全网 ⇒ 恰在同一轮 from=0 的 E
命中 ⇒ 游标被拽到 201 ⇒ **51..200 中段永久不再请求**。

随后 E 沿头部 201→333 小步跟爬 650 s（每轮只前进 1~4 epoch，因为响应只带
头部切片），中段的 87..196（宕机窗口）只是**顺路**被别家 margin 响应填了一部分：
最终 `votesE=799 vs votesA=1157`（差 358），`NOT-CAUGHT-UP`。

**修法（两端都要有）**：

1. **响应端**：仅 `cursor=true` 的请求回带 0x5C 标记；margin 响应不带。
2. **请求方**：标记的 `servedTo` 不得超出本轮**实收**的最大 epoch
   （`lastRespondedEpoch` 已含第 6 处的窗口过滤）。每个连接的广播是
   "先页后标记"的 FIFO，诚实响应必有 `servedTo <= 实收`；超出者一律丢弃
   （可能是伪造、可能来自旧 margin 响应、可能是跨连接乱序）。

回归：第 14 例（margin 响应不得携带标记）+ 第 15 例（伪造标记不得把游标拽出
实收范围）。反向对照：响应端放开 → 仅第 14 例红；请求方去掉一致性校验 →
仅第 15 例红。

### 5.9 第七轮：老启发式在首个无标记宽限 tick 抢跑（第 10 处）

修完 6/7/8/9 后第七轮复测（同参数：grace=6000 > interval=5000，E 宕机 611 s）。

**好的部分**（四条修复均兑现）：

- 无污染瞬跳：`marker` 字段随标记正确开关；**没有**任何"游标瞬跳到链头"的形态；
- 标记驱动的 `next cursor` 链正常；
- `count=0` 的**立即**终止出现（`paging-complete`，本轮 8 次）；
- resume 点保留：终止后 `resuming cursor crawl from epoch N`。

**坏的部分**：游标在最开始的几个 tick 被老启发式拽走。E 的 tick 日志：

```
04:34:46.161 starting cursor crawl from epoch 0 (local max epoch=67)
04:34:51.400 maxEpoch=183 cursor=0  askedFrom=0   votesSinceRound=5
04:34:56.410 maxEpoch=183 cursor=0  askedFrom=0   votesSinceRound=0  graceElapsed=true
04:35:01.430 maxEpoch=184 cursor=184 askedFrom=0  ← 老启发式 fired
04:35:06.596 marker: peer has nothing at or after epoch 187 (requestFrom=187)
```

第一轮 from=0 的响应页（200 票，1..67、A/D 双发）在 E 重启后的
**block-sync 高峰里**没能在宽限期内送达（E 同时在追赶 232 个区块）。
`markerlessGraceTicks=0`、graceElapsed 一成立，老启发式立刻按
`lastRespondedEpoch+1=184` 推进⇒ **68..135 这 68 个 epoch 从此没有被请求过**。

**库存差异画像**（本轮终态查询）：

- `68..119`（52 epoch）E 拿 2/3 票 = 实时广播丢了自己那一票，补票侧 2.99 票/epoch 无损；
- `120..353` 双边 3/3（margin + 实时都正常工作）；
- E 全程零 ERROR、无绑定拒绝，**它只是在分页循环外没被问到 68..135**。

**修法（第 10 处）**：老启发式必须攒够 3 个"无标记且宽限已过"的 tick 才启用。
回归测试第 16 例（前两个 tick 游标必须是 0；第三个 tick 才允许推进），
反向对照（阈值改为 1）时第 16 例立刻红：`expected: <0> but was: <4>`。

**方法论追加**："现场有没有别的流量在涨我这个计数器"的姊妹问句——
**进程刚重启的那几秒，信号里混着多少不可控延迟？**
（block-sync 高峰、连接重连、负载冷启动；同一套 5 s tick 在那段时间里不代表稳态。）


### 5.10 第八轮：首次计分追平（差 1 票），但暠出第 11 处

第 10 处修复后的第八轮（同参数：grace=6000 > interval=5000，E 厂机 602 s，历史缺口 246 票）。

**标记链完整连续，零跳过：**

```
09:28:06 starting cursor crawl from epoch 1 (local max epoch=201, margin=200)
09:28:16 marker: served 1..67   (200 votes) for request from 1   -> next cursor 68
09:28:21 marker: served 68..134  (200 votes) for request from 68  -> next cursor 135
09:28:26 marker: served 135..203 (200 votes) for request from 135 -> next cursor 204
09:28:31 marker: served 204..205 (4 votes)   for request from 204 -> next cursor 206
09:30:37 marker: peer has nothing at or after epoch 231 (requestFrom=231)
...
```

+75 s 时 E=604 vs A=606；+765 s 时 1025 vs 1026。

**但终态差 1 票，暠出第 11 处：**

| 指标 | 数据 |
|---|---|
| A 库总票 | 1026 |
| E 库总票 | 1025（缺 **epoch 134 / validator `nv3gC5..`** 一票） |
| 分桶 | 0..119 与 140..341 **0 差异**；120..139 E=59/A=60 |

根因链：A 的响应页 `served 68..134 (200 votes)` —— 67 epoch × 3 = 201 票，
`ORDER BY epoch ASC LIMIT 200` 把第 201 票（epoch 134）**截断在页外**；标记只报
`servedTo=134`（页内最大 epoch），游标按 +1 跳到 135 ⇒ epoch 134 永远不再被问。
**“页内最大 epoch” ≠ “已完整交付”**。

**修法（第 11 处）：**

1. 响应端（`loadVoteObjectsAscending`，接口默认 + JDBC 两处）：多取 1 行做哨兵，**哨兵行所在 epoch 整体带上** ⇒ 页永不切开一个 epoch（代价最多超 1 个 epoch 的票，生产 ~3）；
2. 请求端：`noteEpochForCursor` 窗口上界 +1 给哨兵余量（否则响应标记会被一致性护航误丢——本次实现过程真实发生过）。

**回归**：第 17 例造 `epoch1×3 + epoch2×1`、`limit=2`，新实现第 1 页必须把 epoch1 全 3 张 + epoch2 一并带上。反向对照（退化为纯 LIMIT）：4 例红。

**已知剪离点**：同内核虚拟化下都能丢，真网更会；修后未复测。

## 六、边界

- **不改**接收端落库/绑定校验/聚合验签：分页只改「请求从哪里开始」与「响应按什么顺序给」；
- 混部（新<->旧节点）自动退化为「单轮上限、多跑一轮空转后退出」，**不保证**追上 >200 的缺口；
- 终止判据依赖宽限期内是否见到游标处新数据 ⇒ **宽限期必须大于 P2P 往返**，
  否则假终止（缺省 5000ms，生产 interval 30s 时绰绰有余；测试床 5s interval 是临界值）；
- 首轮起点取 margin 地板 ⇒ **margin 越大首轮搬得越多**（与 margin 模式同源，
  这是有意的：宁多搬幂等票，不可漏中段）；
- §5.2 / §5.4 的数字都是**各自修正前**的实测，不能互相印证、也不能外推到修正后；
- 真正的端到端「永不丢票」仍需多机拓扑验证（硬件受限项不变）。


## 七、后续

- ~~修缺陷 1（游标拖走）~~ **已修**：**不需要改协议**——用「本轮请求窗口」过滤，
  只有落在 `[roundAskedFrom, roundAskedFrom + max-votes]` 内的 epoch 才可推进游标。
  依据：响应端按升序服务，`from` 之后前 N 张票的最后一张 epoch 上界是
  `from + N`（≥1 票/epoch 时 N 张票至多跨 N 个 epoch）⇒ 窗口外的票不可能是
  本轮响应，只能是贴着链头的广播涓流。回归测试第 10 例，反向对照成立；
- ~~修缺陷 2（终止不可达）~~ **已修**：宽限期改为从「游标上次前进」起算，
  同一游标上的重发只是重传、不重置计时。回归测试第 11 例
  （grace=200ms > tick 间隔 40ms），反向对照成立；
- ~~第五轮测试床~~ **已跑**（2026-10-28）：第 7 处修复确认生效；第 6 处
  窗口过滤不足 ⇒ 发现第 8 处；
- ~~修缺陷 8（响应范围不可知）~~ **已修**：**0x5C 响应标记**（见 §5.7），
  向后兼容；回归测试第 12 例（复现第五轮形态：对端回 171..237、链头 371
  有广播票，游标必须落在 371 而不是 372）与第 13 例（标记在途、链头票先到
  时不得触发老启发式），反向对照均成立；
- ~~第六轮测试床~~ **已跑**（2026-10-28）：0x5C 标记被 margin 响应污染
  ⇒ 发现第 9 处；
- ~~修缺陷 9（标记污染）~~ **已修**：响应端仅游标请求回标记 + 请求方
  校验 `servedTo <= 本轮实收`（见 §5.8）；第 14/15 例回归，反向对照均成立；
- ~~第七轮测试床~~ **已跑**（2026-10-29）：老启发式在首个无标记宽限 tick 抢跑
  ⇒ 发现第 10 处；
- ~~修缺陷 10（抢跑）~~ **已修**：老启发式必须攒够 3 个无标记宽限 tick
  （见 §5.7）；回归测试第 16 例，反向对照成立；
- ~~第八轮测试床~~ **已跑**（2026-10-29）：首次计分追平 246 票缺口（终态差 1 票），但暠出第 11 处；
- ~~修缺陷 11~~ **已修**：响应端按 epoch 完整交付（见§5.9），第 17 例回归 + 反向对照；下一步：第九轮，验证 0 遗漏。
- 补一例「宽限期不足 ⇒ 假终止」的负向测试（当前只有正向）；
- 与 PLAN-048b 的对照测量合流：同一脚本、只切 `paging-enabled`。

## 八、方法论沉淀（本方案最值得留下的）

八轮测试床 + 17 例单测，共抓出 11 处缺陷，其中 **9 处单测都没抓到**。
共性是：**单测的环境比生产简单**—— PLAN-055 是"绕开 AOP"、本次是
"没有广播涓流 + 投递同步 + 进程态不含重启后的历史 + 调度器不阻塞"。

可复用的问句（写带计数/游标/状态的判据前先问）：
1. 现场有没有**别的流量**也在增加我这个计数器？（第 4 处：广播涓流）
2. 我这个量在**进程重启后**是什么值？（第 5 处：`lastRespondedEpoch` 停在宕机前）
3. 数据是**异步**到的吗？我下一 tick 就判，会不会读到"还没到货"？（宽限期）
4. 我的起点是**从库里算的**还是**从进程态读的**？（第 5 处：后者引入 197）
5. 我的判据**边界值**会不会与现场某个**真实值**重合？（第 8 处：窗口上界
   `from+N` 正好等于链头 ⇒ 过滤形同虚设。边界值重合时，"上界"不再是
   区分条件——这时要靠**显式信号**，不能靠启发式）
6. 这个信号还有**别的模式**也会发吗？（第 9 处：请求和响应都只有
   "起点"一个维度，margin 与游标共享同一通道 ⇒ 必须让响应端按请求模式
   分流，并让接收方校验信号内容与实际收到的一致性）
7. 进程**刚重启**的那几秒，我的信号里混着多少不可控延迟？（第 10 处：重启后首轮响应页在 block-sync 高峰里丢包，老启发式第 3 个 tick 就按 `lastResponded+1` 拉走游标——稳态测得的超时/回退参数不能直接套到冷启动窗口）

5. 我这个循环**会不会被别的东西挡住**？（第 6 处征兆：整条调度静默，
   连 margin 都不发——单测里每个循环都独自跑，永远碰不到这种互相饿死）

第 6 条是本方案最大的未解项：**单测绿 + 逻辑对，但循环在真实集群里跑不完第二轮。**

