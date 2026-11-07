# 方案 PLAN-082：`FinalityVoteSyncPagingTest` 去时序化（并修正一处假回归）

- **状态**：交付中
- **前置**：PLAN-057（分页协议与测试床）、PLAN-081（同型时序问题）
- **范围**：**只改测试**；7 处 `Thread.sleep` 逐例判定
- **不包含**：生产侧时钟注入、真实集群/物理多机、告警阈值

---

## 一、为什么做

`FinalityVoteSyncPagingTest` 是 finality 分页协议的主回归集，含 **7 处
`Thread.sleep`**。PLAN-081 的教训是：**不能看见 sleep 就当缺陷**——必须先判定
它是「等待下界」（安全，慢机器只会更早满足）还是「等待异步事件」（可能假红/假绿）。

本切片逐个判定，并用反向对照证明改后仍能红在原来的缺陷上。

## 二、逐例判定

| # | 用例 | sleep 作用 | 判定 | 处置 |
|---|---|---|---|---|
| 1 | `pagingSurvivesBroadcastTrickleAndAsyncDelivery` | 等 grace=60ms 过去 | 非必需：第二 tick 的继续由 0x5C 标记（`servedTo=3 ⇒ cursor=4`）驱动；`graceElapsed` 只影响终止分支 | 删 sleep；改名 `shortPageDoesNotStopPaging`；修正 javadoc |
| 2 | `graceLongerThanTickGapStillTerminates` | 每 tick 40ms，等 grace=200ms 累计 | **原测试是假回归**（见 §三） | 扣住后续标记，让终止只依赖 grace 累计；**保留 sleep**（墙钟契约） |
| 3 | `heuristicFallbackStillServesLegacyPeerWithPageSizedBatch` | 等 grace=80ms 过去 3 次 | grace 不是被测对象；被测对象是「3 个无标记 tick + 页级量闸门」 | `grace=0`，删 3 处 sleep；tick 数语义不变 |
| 4 | `noPageReceivedMustNeverDragTheCursor` | 等 grace=80ms 过去 | 名不副实：A 只扣标记，30 票的页仍投递 ⇒ 实际测「页级量 < maxVotes/2 不得拉游标」 | `grace=0`，删 sleep；改名 `smallPageWithoutMarkerMustNotDragCursor`；修正 javadoc |
| 5 | `noResponseYetMustNotBeReadAsPeerExhausted` | 等 grace=80ms 过去 | grace 不是被测对象；被测对象是 3 个无标记 tick 的终止闸门 | `grace=0`，删 2 处 sleep；tick 数语义不变 |

**结论**：7 处中 **6 处可删**；`graceLongerThanTickGapStillTerminates` 的 1 处
**必须保留真实时间**——它测的就是「墙钟宽限期跨 tick 累计」，用 `grace=0` 或伪造
字段会让旧实现也绿，属于降级断言。

## 三、关键发现：`graceLongerThanTickGapStillTerminates` 原本是假回归

### 3.1 原测试的实际时序（master `85f14357`）

对端 A 只有 epoch 1..3，且**会正常回 0x5C 标记**：

1. tick 1：B 从 0 请求；A 回 3 票 + 标记 `count=3` ⇒ B 的 `nextSyncCursorEpoch=4`；
2. tick 2（sleep 40ms）：B 从 4 请求；A 无货 ⇒ 回**空页 + `count=0` 标记**
   ⇒ B 的 `pagingPeerExhausted=true`；
3. tick 3：`markerSaysExhausted=true` ⇒ **立即终止**（约 80ms），
   与 `grace=200ms` **无关**；
4. tick 4：冷却期发 margin 请求 ⇒ `sawMarginRequest=true` ⇒ 测试绿。

因此旧实现（`roundIssuedAtNanos` 每 tick 无条件重置）**同样会绿**——终止由标记
驱动，根本没走到「宽限期累计」分支。

### 3.2 证据（先证伪，再修）

- **变异**：`boolean cursorAdvanced = !pagingRoundIssued || cursor != roundAskedFrom;`
  → `boolean cursorAdvanced = true;`（模拟旧实现每 tick 重置）；
- **未修改测试**跑 `graceLongerThanTickGapStillTerminates`：**绿**（见 §五证据表）；
  ⇒ 该测试对「计时不得重置」没有回归力。

### 3.3 修法

- **第一页的标记照常送达**（让游标从 0 推进到 4）；
- **后续标记全部扣住**（模拟标记丢失/老对端）；
- 于是终止只能由
  `graceElapsed && peerExhaustedAtCursor && markerlessGraceTicks >= 3`
  触发，才真正压住「计时不得每 tick 重置」；
- 保留 `Thread.sleep(40)`：它是**等待下界**——机器越慢，修正实现越早满足
  200ms（终止更早），不会假红。旧实现只有在**单次 tick 间隔也 ≥ 200ms**
  （5 倍于 sleep 下界）时才可能偶发假绿；正常 CI 的 tick 间隔在 40~100ms 量级。

## 四、改动清单

1. 删 6 处 `Thread.sleep`（用例 1/3/4/5），移除不再需要的 `throws Exception`；
2. 用例 2 保留 1 处 sleep，并加「为什么必须真实时间」的注释；
3. 用例 3/4/5 的 `grace` 设为 0，注释说明「隔离的是 tick 数/量闸门，不是宽限期」；
4. 两处改名 + javadoc 修正：
   - `pagingSurvivesBroadcastTrickleAndAsyncDelivery` → `shortPageDoesNotStopPaging`
     （实际是同步中继、无涓流；异步/涓流由 PLAN-057 测试床取证）；
   - `noPageReceivedMustNeverDragTheCursor` → `smallPageWithoutMarkerMustNotDragCursor`
     （页实际到达，只是量不足；「一页都没到」由用例 5 覆盖）；
5. 同步更新 `docs/plan/PLAN-057-anti-entropy-paging.md` 中的测试名引用（保留原名括注）。

**零生产代码改动**。

## 五、验证（实测，2026-11-07）

环境：Windows + JDK 17.0.20.8；每条命令都带
`cleanTest --no-build-cache --no-daemon`（防 build cache 假绿）。

### 5.1 基线与修改后

| 阶段 | 结果 | 测试耗时 |
|---|---|---|
| 基线（master `85f14357`） | 20/20 绿 | 13.99s |
| 修改后 | 20/20 绿 | 9.06s |
| 压测 3 轮 | 待填 | 待填 |

### 5.2 假回归证明（先证伪）

| 变异 | 测试 | 结果 |
|---|---|---|
| `cursorAdvanced = true`（旧实现每 tick 重置计时） | **未修改**的 `graceLongerThanTickGapStillTerminates` | **绿**（BUILD SUCCESSFUL，209s）⇒ 原测试对该缺陷无回归力 |

### 5.3 反向对照（修改后必须红）

| # | 变异 | 目标用例 | 实际红在哪条断言 |
|---|---|---|---|
| 1 | 终止条件加旧判据 `votesSinceRound < maxVotes` | `shortPageDoesNotStopPaging` | `assertEquals(4L, secondFrom)`：`expected <4> but was <0>`（tick 2 即判完成并回退 margin） |
| 2 | `cursorAdvanced = true` | `graceLongerThanTickGapStillTerminates` | `assertTrue(sawMarginRequest)`：`expected <true> but was <false>`（永不终止） |
| 3 | `MARKERLESS_GRACE_TICKS_NEEDED 3 → 1` | `heuristicFallbackStillServesLegacyPeerWithPageSizedBatch` | `assertEquals(0L, from)`：`expected <0> but was <151>`（tick 2 即推进） |
| 4 | 页级量闸门 `>= maxVotes/2` → `>= 0` | `smallPageWithoutMarkerMustNotDragCursor` | 第 4 轮 `assertEquals(0L, lastCursorFrom(...))`：`expected <0> but was <31>` |
| 5 | `MARKERLESS_GRACE_TICKS_NEEDED 3 → 1` | `noResponseYetMustNotBeReadAsPeerExhausted` | `assertTrue(second.cursor())`：第二条变 `cursor=false, from=0`（提前终止） |

每组变异后 `git checkout -- FinalityVoteBroadcaster.java` 还原；最终生产代码
`git diff` 为 0 行。5 组全部红在**预期断言**上，不是"红了就行"。

## 六、边界

1. **只改测试**：未改 `FinalityVoteBroadcaster` 的任何生产逻辑；
2. 未加 `Gradle retry`、未降级断言、未用 `Thread.sleep` 掩盖新的等待；
3. 未触碰 L1 / 桥资金 / wallet / settlement lane；
4. 不宣称「消除全部 flake」：本切片只处理列明的 7 处；
5. 用例 2 仍依赖真实时间，但方向安全（sleep 是下界）；已在注释与本文写明。
