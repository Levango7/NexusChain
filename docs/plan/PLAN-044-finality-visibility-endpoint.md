# 方案 PLAN-044：最终性视图可见性端点（承接 PLAN-043 未测得项 + 运维可见性）

- **状态**：已交付（2026-10-26）；单测 8 例 + 两轮实测（首轮暴露设计缺陷并修正，
  见 §四）
- **前置**：PLAN-043（10 分钟窗口证明 50%/100% 是提交时快照的顺序浮动，但**日志里
  没有"对端票到达后本地视图何时翻 true"的观测点**）、PLAN-018/021/025（最终性
  持久化/收敛/补票）
- **范围**：新增只读端点 `GET /consensus/finality`（内存链头 + gadget 内存视图，
  不查库）+ `FinalityCoordinator.finalitySnapshot(long)` + `FinalityGadget
  .finalizedCheckpointCount()`；白名单同步；单测 8 例
- **不包含**：改最终性共识算法、改投票/聚合/补票逻辑、指标化（另立）

---

## 一、问题

1. PLAN-043 的收尾登记项：**最终性的公开可观测性缺失**——`/consensus/status`
   只有高度/哈希，最终性进度只存在于进程内 gadget；对"某 epoch 是否已 2/3 最终化"
   运维只能翻日志（且日志只有**提交时快照**，见 PLAN-043 §四）；
2. 长窗口收敛测量无法脚本化：每次都要 grep 日志文件，不能像 `/consensus/block-writer`
   那样 `curl` 一次拿到结论；
3. 排障语境：链看着在出块，但最终性跟不上（权重不够/某验证人掉线）时，
   现有端点没有一个能直接回答"最终性视图停在哪"。

## 二、方案（additive-only，零共识逻辑改动）

### 2.1 `FinalityGadget.finalizedCheckpointCount()`

返回内存 `finalizedCheckpoints` 集合大小——**进程内已最终化检查点数**（与持久化
`finality_checkpoints` 表的区别：这里是内存视图，冷恢复节点需补票重建，
PLAN-042/025 已实证该重建路径）。

### 2.2 `FinalityCoordinator.finalitySnapshot(long height)`

纯内存读（不查库）。字段（§4.1 的实测修正后）：

| 字段 | 含义 |
|---|---|
| `height` / `epochLength` / `currentEpoch` | 调用方给的高度、epoch 长度、该高度的 epoch |
| `checkpoint` | 该高度是否恰为 epoch 边界 |
| `finalizedCheckpoints` | 内存已最终化检查点总数 |
| `latestVotedEpoch` | 从 `currentEpoch` 向下回溯 8 个 epoch 内、最近一个有本地视图记录的 epoch（`null`=无） |
| `finalityLag` | `currentEpoch - latestVotedEpoch`：最终性落后出块几个 epoch（`null`=无记录） |
| `progress` | `latestVotedEpoch` 的 `finalized/progressPercent/votedWeight/totalWeight/checkpointHash`；无记录为 `null`（不冒充 0%） |

### 2.3 `GET /consensus/finality`（ConsensusController）

与 `/consensus/status` 同口径：只读 `chainHeadProvider.currentHeader()`；协调器
未装配（非 pos 模式）返回 `enabled=false`。`LOCAL_ONLY_PUBLIC_PATHS` 同步
（否则 local-only 拦截会 401，见 #233 教训）。

**口径（必须写进文档与响应注释）**：`progressPercent` 是**进程内聚合视图的提交时
快照**，随对端票到达而增长（2 验证人拓扑刚投完自己通常是 50%）；看收敛请抽样
多次或看 `finalizedCheckpoints` 是否随高度增长——PLAN-043 §四 的判断口径不变。

## 三、测试（8 例）

- `FinalitySnapshotTest` 6 例：无任何投票时 `latestVotedEpoch/progress` 为 null
  （不冒充 0%）；非检查点高度仍上报元数据；只投自己 → `false/50%`；收齐两票 →
  `true/100%`；**链高不在检查点时仍报最近有票 epoch + `finalityLag`**（本轮实测
  修正点）；`finalizedCheckpoints` 随 epoch 增长；快照**不触碰持久化 store**。
- `ConsensusControllerFinalityTest` 2 例：装配时编码快照且不碰链对象；
  未装配时 `enabled=false` 但仍带高度。

## 四、测量（2026-10-26；第一轮失败 → 修正 → 第二轮复测）

### 4.1 第一轮：遥测不可用（失败实录）

初版把 `progress` 绑定到「当前高度的 epoch」。A+D 场景每 5s 采样 6 分钟：
**360/360 次全部 `no-progress`**。根因：链高在绝大多数时刻**不是** epoch 边界
（epochLength=2 时每次采样只有约一半概率落在检查点上，且投票只发生在节点看到
检查点高度的**那一刻**），而初版在非检查点直接返回 `progress=null`。

### 4.2 修正

`finalitySnapshot` 改为「**当前 epoch 向下回溯 8 个 epoch，报最近一个有本地视图
记录的 epoch**」，新增 `currentEpoch` / `latestVotedEpoch` / `finalityLag`
（最终性落后出块几个 epoch——最终性是否跟上的一眼指标）。

### 4.3 第二轮复测（A 常驻 mining；D 全量存储丢失后同参重启；每 5s 采样）

- **遥测可用性修正生效**：除 D 自身启动窗外，每次采样都拿到
  `finalized/progressPercent/finalizedCheckpoints`（不再有 `no-progress`）；
- **A 的 50%↔100% 浮动用 5s 粒度直接可见**：t+5s A=`False/50%`，随后全部
  `True/100%`——与 PLAN-043 "提交时快照随对端票到达顺序浮动"结论同构，
  现在可用 `curl` 复现而不必 grep 日志；
- **D 冷恢复后的最终性重建**：D 自己从 **epoch=1/height=2** 重新投票
  （日志：`Finality vote submitted: epoch=1, height=2 …` @07:54:47），
  补票回填在跑（`Vote-sync request (from epoch 15) served with 50 votes`），
  t+45s 起 `finalizedCheckpoints` 与 A 只差 1（A=75 / D=74），并保持
  "始终差 1 个检查点"同步前进到 t+360s（A=393 / D=392）——**视图在持续收敛且
  同步推进**；`finalityLag` 两端全程为 0；
- **一处未采纳为证据的异常（如实登记）**：D 重启后前 ~35s（07:54:10–33）的几次
  采样显示 `h=0` 且 `finalizedCheckpoints=64`（与 A 同值），而 D 的第一票在
  07:54:47 才发生——两者矛盾，说明那几次响应不来自完成初始化的 D2
  （Tomcat 在 `Started Start` 之前可能已开始服务）。宿主机重启后测试床 PG
  （55433）已停，未能复现验证；**这几次数据不采纳**，留作后续复查项。

## 五、边界（勿夸大）

- 端点是**观测面**，不是新的对外最终性 API：`progressPercent` 仍是提交时快照，
  与"已对外承诺最终性"的语义不同（后者由 `FinalityGadget.isFinalized` /
  `finality_checkpoints` 表表达）；
- 内存计数重启清零、冷恢复需补票重建（PLAN-042 实测缺口 21/683 epoch）；
- 未做指标化/告警（最终性停滞告警可另立，引用本端点同一数据源）。
