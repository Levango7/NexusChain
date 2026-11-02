# 方案 PLAN-067：最终性投票载荷与检查点键上界

- **状态**：已交付（2026-11-02）
- **前置**：PLAN-065（准入验签前移，#389）、PLAN-066（拒绝计数与告警，#400）、
  PLAN-016/018/021/025/057（故障注入 / 签名落库 / 跨节点收敛 / 补票 / 分页）、
  PLAN-064（promtool 规则演练门禁）
- **范围**：32 字节检查点哈希强校验、codec/broadcaster 载荷上限、收敛路径
  “先校验后建键”、收敛游标只由有效合并推进、提交路径“写库成功后才建内存 voter set”、
  单验证人单 epoch 新键上限 K=4、epoch→keys 索引、三个新拒绝原因与告警扩展、
  回归测试与文档
- **不包含**：epoch 窗口/规范检查点语义校验（需要链接口）、清洗已污染的历史 DB 行、
  真实网络分区、物理多机、真实 L1、桥资金链路、wallet/settlement lane、
  性能/吞吐基准、Grafana 看板与通知链路

---

## 一、问题（为什么必须做）

PLAN-065 把单票 Ed25519 验签前移到计票/落库之前，PLAN-066 把四条拒绝路径变成
可聚合的计数与告警。但“拒绝判定本身”仍假设输入是**有界的正常票**：

| 缺口 | 修复前行为 | 后果 |
|---|---|---|
| 检查点哈希长度 | 只要求非 null/非空 | 3 字节哈希可建键；4KB 哈希在 `epochKey()` 里 hex 成 8KB 字符串，`signingPayload()` 按 `8+len` 分配 |
| P2P 载荷大小 | codec 直接解析，broadcaster 无入站闸 | P2P 允许近 4MB 单帧，解析前就为攻击帧分配大 String/字节数组 |
| 单验证人单 epoch 造键数 | 无上限 | 一个验证人可造无限检查点键，内存与双签扫描成本随键数增长 |
| 收敛路径建键顺序 | 先 `computeIfAbsent` 再校验 | 坏行在内存留下不可回收的空 voter set / 检查点键 |
| 收敛游标推进 | 所有键都推进 `maxEpoch` | 一条 epoch=1000 的脏行把游标推到 1000，之后写入的 epoch 1..999 合法票永远不再被 `loadVoteObjectsSince(from)` 看到（liveness 缺陷） |
| 提交路径建键顺序 | 先建 voter set 再写库 | 写库因 `VARCHAR(128)` 溢出抛异常时留下空键；重试可能被误判为重复票 |
| 双签检测扫描 | 遍历全表所有检查点键 | 每票 O(总键数)，键越多越慢 |

这些缺口与 PLAN-065/066 是**同一条攻击面的不同维度**：PLAN-065 防“伪造签名
占位”，PLAN-066 让“拒绝可见”，本切片防“畸形/超长输入把判定路径本身打垮”。

## 二、设计与取舍

### 2.1 检查点哈希必须是 32 字节

`Block.HASH_SIZE = 32` 是协议事实。`FinalityGadget` 新增：

```java
private static boolean hasValidCheckpointHash(byte[] checkpointHash) {
    return checkpointHash != null && checkpointHash.length == Block.HASH_SIZE;
}
```

- **先校验、后建键**：`checkVoteBinding()` 在注册表/验签之前挡掉短/超长/null
  哈希，返回 `CHECKPOINT_HASH_INVALID`；收敛路径复用同一判定，不再先
  `computeIfAbsent`。
- **查询路径也护栏**：`isFinalized()` / `record()` 对畸形哈希直接返回
  “未最终化/零权重”，不让只读接口成为 `epochKey()` 的放大入口。
- **恢复路径独立闸**：`restoreFromStore()` 对库里的坏哈希行单独丢弃并计入
  `RESTORE` phase——它与 admission 的 `checkVoteBinding` 是两道独立防线，
  反向对照里分别验证（见 §4.3）。

### 2.2 P2P 载荷上限 2048 字节

`FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES = 2048`。正常票字段上限合计约 600
字节（epoch ≤ 20、checkpoint hex 64、validator ≤ 128、sig hex 128、pub hex 64，
加 JSON 键名/引号/逗号约 100），取 2048 留 3 倍余量。

- **codec decode**：`raw.length > MAX_ENCODED_VOTE_BYTES` 直接返回 null，不进入
  JSON 解析；
- **broadcaster 入站统一闸**：`onVoteReceived()` 在区分投票/补票请求/响应标记
  **之前**先做尺寸检查，超限计 `PAYLOAD_TOO_LARGE@admission`，不误记成
  `MALFORMED_VOTE`；
- **为什么两道**：codec 是最后一道解析闸，broadcaster 是更早的入口闸；只保留
  codec 会让补票请求/响应标记等非投票载荷绕过尺寸检查。

### 2.3 收敛游标只由“有效事实”推进

原实现：

```java
Set<String> voters = epochCheckpointVoters.computeIfAbsent(key, ...);
maxEpoch = Math.max(maxEpoch, epoch);   // 任何键都推进
```

改为：

- 只有**本轮真正合并了有效票**的键、**已有有效投票者**的键、**已终局**的键
  才推进 `convergeFromEpoch`；
- 从未合并过任何有效票的键（`voters == null`，典型是历史脏行）必须靠本轮
  合并成功才推进；
- 合并前先走 `checkVoteBinding()`，坏行不建键、不推进游标。

**为什么“已有有效投票者的键”可以推进**：它之前已经合并过有效票，说明该键
是真实存在过的检查点，不是本轮才出现的脏行；它的 epoch 可以安全地作为游标
下界。这样既不会让脏行跳过低 epoch 合法票，也不会因为反复重扫同一批有效键
而让游标停滞。

### 2.4 收敛拒绝计数去重

收敛每 5 秒重扫一次库。若同一条坏行每轮都往 counter 加 1，“库里有一条历史
脏行”会被放大成持续增长的攻击曲线。新增：

```java
private final Set<String> convergenceRejectionsCounted = ConcurrentHashMap.newKeySet();
private static final int MAX_CONVERGENCE_REJECTION_KEYS = 4_096;
```

按 `(checkpointKey, validator, reason)` 去重；集合有界，达到上限后不再为**新**
坏行计数（判定/日志不受影响）。admission 路径的每条网络消息仍然逐条计数——
它才是“正在被攻击”的信号。

### 2.5 提交路径：写库成功后才建内存 voter set

顺序改为：

1. `stateStore.recordVote(...)`（含签名与公钥）；
2. 写库成功后才 `epochCheckpointVoters.computeIfAbsent(...)`；
3. 再 `voters.add(...)` / 索引 / 权重 / 双签扫描。

写库失败时内存状态保持原状，重试不会被误判为重复票；也不会留下不可回收的
空键。

### 2.6 单验证人单 epoch 新键上限 K=4

`MAX_CHECKPOINTS_PER_VALIDATOR_PER_EPOCH = 4`，只约束**该验证人新引入的键**：

- 对已存在键投票**永不拦截**——否则拜占庭验证人先造满 4 个假键，就能把其他
  验证人挡在真检查点之外（把防放大机制变成 liveness 攻击工具）；
- 其他验证人仍可为同一个新键建键；
- 恢复路径只为 32 字节哈希的键建索引，历史短哈希行不占配额、不参与双签扫描
  （本切片**不清洗**这些行，只让它们不进入新索引）。

K=4 是防内存/扫描放大的**工程上界**，不是共识规则；2/3 阈值语义未变。

### 2.7 epoch→keys 索引

新增 `Map<Long, Set<String>> epochCheckpointKeys`。提交路径的双签检测从“遍历
全表所有检查点键”改为“只看同一 epoch 的键”，把每票的扫描复杂度从 O(总键数)
降到 O(同 epoch 键数)。

### 2.8 三个新拒绝原因与告警扩展

`FinalityRejectionCounter.Reason` 新增：

```text
CHECKPOINT_HASH_INVALID     // 检查点哈希不是 32 字节
CHECKPOINT_LIMIT_EXCEEDED   // 单验证人单 epoch 新键超过 4 个
PAYLOAD_TOO_LARGE           // P2P 最终性载荷超过 2048 字节
```

`FinalityVoteRejected` 告警的 reason 正则扩展为：

```text
signature_invalid|pubkey_mismatch|checkpoint_hash_invalid|checkpoint_limit_exceeded|payload_too_large
```

summary 从“出现伪造票”改为“出现异常拒绝”——后三类不是伪造签名，但同样
“合法节点之间正常不会发生”，属于 liveness 攻击前兆。

### 2.9 刻意不做

- **epoch 窗口/规范检查点语义校验**：需要链接口提供“哪个块是规范检查点”，
  本切片没有链接口；登记为残余风险，不在最终性层猜。
- **清洗已污染的历史 DB 行**：历史短哈希行是既有事实，清洗需要迁移脚本与
  审计窗口；本切片只保证它们不占新键配额、不参与双签扫描、恢复时被丢弃。
- **真实网络分区/物理多机实验**：见 §五。

## 三、代码改动

| 文件 | 改动 |
|---|---|
| `consensus/finality/FinalityGadget.java` | 32 字节哈希校验（admission/convergence/restore/查询）、`epochCheckpointKeys` 索引、K=4 新键上限、收敛先校验后建键、游标只由有效事实推进、收敛拒绝去重、写库后才建 voter set、双签扫描按 epoch 索引、restore 只索引合法键 |
| `consensus/finality/FinalityRejectionCounter.java` | `Reason` 新增 3 个闭合值 |
| `consensus/finality/net/FinalityVoteCodec.java` | `MAX_ENCODED_VOTE_BYTES = 2048`；decode 超限返回 null |
| `consensus/finality/net/FinalityVoteBroadcaster.java` | 入站统一尺寸闸；`PAYLOAD_TOO_LARGE` / `MALFORMED_VOTE` 计数；可选 `setRejectionCounter` |
| `consensus/finality/FinalityCoordinator.java` | 区块哈希非 32 字节时 fail-closed 跳过投票，不再退化成空哈希投票 |
| `metrics/FinalityRejectionMetrics.java` | 适配器 Javadoc 的 6 → 9 种原因 |
| `deploy/k8s/50-prometheus-rules.yaml` | reason 列表/正则/summary/description 扩展 |
| `deploy/k8s/tests/prometheus-rules.test.yaml` | 更新 2 条既有断言；新增 `checkpoint_limit_exceeded` / `payload_too_large` 两条演练用例 |

**没有改共识阈值语义**：2/3 最终化条件、聚合验签、投票权重计算均未改；新增的
是输入边界与内存/扫描上界。

## 四、验证

### 4.1 Java 侧

| 测试 | 覆盖点 | 结果 |
|---|---|---|
| `FinalityCheckpointBoundsTest`（新，6 例） | 短/超长/null 哈希 admission 拒绝且不建键；收敛坏哈希不推进游标且不重复计数；restore 坏哈希丢弃并计数；K=4 第 5 个新键被拒、其他验证人可建键、对已存在键投票放行 | 6/6 绿 |
| `FinalityVotePayloadBoundsTest`（新，4 例） | codec 超限拒绝、正常票通过、broadcaster 超限计数、malformed 计数 | 4/4 绿 |
| `FinalityCoordinatorTest`（改，+1 例） | 非 32 字节区块哈希 fail-closed 跳过投票、不进入补写队列 | 绿 |
| `consensus.finality.*` 全包 | 无回归 | **153 用例 / 0 失败 / 0 错误 / 0 跳过** |
| `org.nexus.metrics.*` 全包 | 指标适配器与契约测试无回归 | **27 用例 / 0 失败 / 0 错误 / 0 跳过** |
| `MultiNodeConsensusConvergenceTest` / `ConsensusChaosTest` / `FinalityVoteP2PRoundTripTest`（改） | 三个包外用例的检查点从 4 字节改为 32 字节；否则新校验会正确拒绝旧测试票（这是**测试适配**，不是放宽生产校验） | 绿 |

`nexus-core` 全量与 SpotBugs 门禁结果见 §4.4。

### 4.2 promtool 规则演练（PLAN-064 门禁同步）

环境：WSL2 Ubuntu-24.04 内的 promtool **v3.15.1**（与 CI 固定的同一版本），
规则文件由 `scripts/extract-prometheus-rules.py` 提取；应用与 DB 均不参与，
**只是 CLI 工具，不构成多节点/分布式声明**。

```text
extracted 9 groups / 20 rules (20 alerts)
Checking rules.yaml
  SUCCESS: 20 rules found
  SUCCESS                        ← promtool test rules 全部用例通过（含新增 2 个）
```

新增用例：

- `finality-vote-rejected-checkpoint-limit-exceeded`：`reason="checkpoint_limit_exceeded"` 增长 ⇒ 告警触发；
- `finality-vote-rejected-payload-too-large`：`reason="payload_too_large"` 增长 ⇒ 告警触发；
- 既有 `signature_invalid` / `pubkey_mismatch` 两条断言同步更新为新的 summary/description。

### 4.3 反向对照（实跑）

对每组缺口做定点变异、跑对应测试、记录红/绿，再从备份还原并核对 SHA-256。
日志留在仓库外（`C:\Users\winge\AppData\Local\Temp\opencode\p067-reverse\`）。

| 组 | 变异 | 精确变红的用例 | 还原后 |
|---|---|---|---|
| A | 移除 `checkVoteBinding` 的 32 字节哈希闸 | `admissionRejectsNullCheckpointHashWithoutNpe`、`admissionRejectsOversizedCheckpointHashBeforeCreatingAnyKey`、`admissionRejectsShortCheckpointHashBeforeCreatingAnyKey`、`convergenceBadHashDoesNotAdvanceCursorPastLowerValidVotes`（4 红） | 绿 |
| B | 恢复“任何键都推进游标”的旧行为 | `convergenceBadHashDoesNotAdvanceCursorPastLowerValidVotes`（1 红） | 绿 |
| C | 让 `validatorEpochKeyLimitReached` 恒返回 false | `fifthNewKeyIsRejectedButOtherValidatorsCanStillCreateIt`（1 红） | 绿 |
| D | 同时移除 codec 与 broadcaster 的尺寸闸 | `codecRejectsEncodedPayloadAboveLimit`、`broadcasterDropsOversizedPayloadAndCountsIt`（2 红） | 绿 |
| E | 移除 restore 路径自己的哈希闸 | `startupRestoreDropsBadHashAndReplaysTheRejectionCount`（1 红） | 绿 |

**A 组为什么没有让 restore 用例变红**：restore 路径有独立的哈希闸（§2.1），
所以只改 `checkVoteBinding` 不会影响它；E 组单独移除 restore 闸后该用例精确
变红——这正好证明两道防线各自有效，而不是“一个测试靠另一条路径蒙对”。

还原后 `FinalityCheckpointBoundsTest` + `FinalityVotePayloadBoundsTest`
**10/10 绿**；变异前后的 `FinalityGadget.java` / `FinalityVoteCodec.java` /
`FinalityVoteBroadcaster.java` SHA-256 逐字一致。

### 4.4 最终门禁

| 门禁 | 命令 | 结果 |
|---|---|---|
| `nexus-core` 全量测试 | `:nexus-core:nexus-core:test --rerun-tasks --offline` | **218 个测试类 / 1839 用例 / 0 失败 / 0 错误 / 19 跳过**（19 跳过为仓库既有，PLAN-066 全量同为 19） |
| SpotBugs | `:nexus-core:nexus-core:spotbugsMain`（在线，findsecbugs-plugin 1.13.0） | **BUILD SUCCESSFUL**；`build/reports/spotbugs/main.xml` **0 个 `BugInstance`** |
| 规则演练 | promtool v3.15.1 `check rules` + `test rules` | **20 rules / 全部用例通过**（见 §4.2） |

**全量首轮为什么红过**：首轮 1839 用例有 **9 个失败**，全部集中在
`MultiNodeConsensusConvergenceTest`（5）、`ConsensusChaosTest`（3）、
`FinalityVoteP2PRoundTripTest`（1）——它们的 `checkpoint(epoch)` 仍返回 4 字节
`{epoch,1,2,3}`，被新的 32 字节校验正确拒绝。把这三处测试夹具改成 32 字节后
第二轮 **0 失败**。这是**测试适配**，不是放宽生产校验：反向对照 A 组证明
生产路径的哈希闸一旦移除，对应用例立即变红（§4.3）。

## 五、诚实边界（勿夸大）

1. **同内核 JVM 测试**：没有真实网络分区、没有物理多机、没有跨内核对比。
   promtool 跑在 WSL2 的 Linux 内核上，但那是**同一个物理机的另一个内核里的
   一个 CLI 工具**，不构成任何分布式/多机结论。
2. **没有做 epoch 窗口/规范检查点语义校验**：最终性层现在只保证“哈希长度
   合法、键数有界”，不保证“这个检查点属于当前 epoch 窗口”或“它是规范链上
   的块”。跨 epoch 重放、旧检查点投票仍可能在协议层发生；需要链接口后再补。
3. **不清洗历史脏行**：库里的短哈希行仍存在；本切片只在恢复时丢弃未终局的坏票、
   不建索引、不占新键配额。已终局的历史短哈希行仍保留在内存计数/投票者展示中，
   但 `isFinalized(epoch, shortHash)` 会按 32 字节闸 fail-closed 返回 false——
   这是**查询边界**，不是数据删除；清理需要单独的迁移脚本与审计窗口。
4. **K=4 不是共识规则**：它只防内存/扫描放大，不改变 2/3 阈值；单个验证人
   在 4 个键内仍可制造歧义，双签检测照旧工作。
5. **2048 字节是保守工程值**：未做真实 P2P 抓包统计与吞吐基准；若未来票字段
   扩展（如聚合签名），需要重新评估上限并同步 codec/broadcaster/告警文案。
6. **告警未在真实集群触发**：promtool 只证明 PromQL 可解析、标签集与阈值在
   合成序列上按预期工作；没有起真实 Prometheus/Alertmanager，没有通知链路验收。
7. **未触碰真实 L1 / 桥资金链路 / wallet / settlement lane**；本切片属
   consensus/finality 域。

## 六、后续

- **epoch 窗口/规范检查点语义**：需要链接口提供“规范检查点”事实；在此之前
  不得声称最终性层已防跨 epoch 重放。
- **历史脏行清理**：单独迁移切片，先盘点短哈希行数量与来源，再决定删除/重写；
  必须可回滚、有审计日志。
- **抓取端补验**：确认三个新 reason 在 `/actuator/prometheus` 的标签值可见
  （WSL2 PG 单机边界，明确标注非物理多机）。
- **真实集群告警演练**：用合成流量触发 `FinalityVoteRejected`，验证
  Alertmanager 路由与通知。
- **物理多机/网络分区矩阵**：本切片不覆盖，仍属 #27 的剩余项。
