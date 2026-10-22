# 方案 PLAN-025：独立存储拓扑下的最终性事实传播（Phase 6C 第七切片）

> 前置：PLAN-021 已让**共享存储**拓扑下的最终性视图收敛成立（各节点从同一个库里拉新票）。
> 本切片解决 roadmap #27 里剩下的那条：**独立存储部署**时，对端的票从来不在本节点库里，
> 广播错过的票**永久缺失** ⇒ 视图不收敛。

## 一、问题

最终性投票的跨节点传播是**广播**语义：

```
FinalityCoordinator 投票
  → FinalityVoteBroadcaster.broadcast(vote)
  → PeerServer.broadcast(Transactions{ VOTE(payload=0x5A + json) })
  → 各在线节点 SyncManager.onTransactions
  → FinalityVoteBroadcaster.onVoteReceived → FinalityGadget.submitVote
  → 落本节点自己的 finality_votes 表
```

广播只保证"当时在线"的节点收到。两种存储拓扑下的后果完全不同：

| 拓扑 | 掉线节点重启后 | 结果 |
|------|----------------|------|
| 共享存储（PLAN-021） | `FinalityGadget` 每 5 秒从**共享库**拉 `epoch >= from` 的新票 | 能补齐 ✅ |
| **独立存储**（本切片） | 本节点库里**只有自己投的票**，对端的票从未写入 | **永久缺失** ❌ |

具体表现：C 节点在 A/B 出块并最终化的那段时间离线，重新上线后它的最终性视图从
"当前 epoch 起"才有票，**历史 epoch 永远不会被标记为已最终化**，对外报告的水位与 A/B 不一致。

## 二、修复：补票请求（anti-entropy）

不引入新的响应类型，只加一个**请求**，响应复用既有投票通道：

```
节点 C（有缺口）
  → 广播 VoteSyncRequest{fromEpoch, maxVotes}     载荷 = 0x5B + {"from":N,"max":M}
节点 A/B（对端）
  → 从本地 finality_votes 取 epoch >= fromEpoch 的**带签名**票
  → 按 epoch 倒序截断到 maxVotes
  → 一次性回推 Transactions{ VOTE(0x5A ...) × N }
节点 C
  → 走原有 onVoteReceived → submitVote
  → 绑定校验（validateVoteBinding）+ 幂等 upsert + 本地补最终化
```

### 2.1 为什么响应复用 0x5A 而不是新增响应类型

- 接收侧完全复用既有校验链（公钥绑定、验证人注册表、聚合验签、幂等落库），**不新增信任面**；
- 老节点收到 0x5B 会因 `isVotePayload(payload)==false` 当非投票载荷丢弃 ⇒ **向后兼容**（只是不补票）；
- 回推的票本来就是**已签名**的，补票请求即使被伪造，也只能让对端重发已存在的签名票。

### 2.2 请求不签名（以及为什么可以接受）

补票请求不改变任何状态，只触发"重发已签名票"。因此：

- 伪造请求 ⇒ 最多造成带宽消耗（受 `max-votes` 上限约束），**不能伪造最终性**；
- 放大控制：单次最多回推 `max-votes`（默认 200）张；请求间隔默认 30 秒；
- 已知边界：没有按对端限速/去重（同一对端重复请求会被重复服务）——登记为后续项。

### 2.3 回看窗口

- 本地**有票**：`from = max(0, localMaxEpoch - margin)`，`margin` 默认 50 个 epoch
  ⇒ 修补"广播时恰好掉线/写库失败"的短缺口；
- 本地**无票**（冷启动）：`from = 0`，对端回推**最新** `max-votes` 张
  ⇒ 先恢复"当前 epoch 的最终性"，而不是从创世开始慢慢爬。

### 2.4 去重（必须一起修）

`recordVote` 的 SQL 是幂等 upsert（`JdbcUpsert`），但内存里的
`voteObjectCache`（JDBC）/ `voteObjects`（InMemory）原本是 `List.add`：
补票重复投递会让列表**无限增长**。本切片把它改成**按验证人去重替换**
（同一个 `(epoch, checkpoint, validator)` 只保留最新一条签名）。

### 2.5 装配

| 位置 | 改动 |
|------|------|
| `FinalityVoteSyncCodec`（新增） | 0x5B 魔数 + `{"from":N,"max":M}` 编解码，非法载荷返回 null |
| `FinalityVoteBroadcaster` | `requestVoteSync` / `scheduledVoteAntiEntropy`（默认 30s）/ `handleVoteSyncRequest` / `sendVotesOverP2P` |
| `SyncManager.onTransactions` | 0x5A 与 0x5B 都交给 `onVoteReceived`，内部按魔数分流 |
| `FinalityStateStore` | 新增 `loadLatestVoteObjects(from, limit)`（默认内存截断；JDBC 下沉为 `ORDER BY epoch DESC LIMIT ?`） |
| `JdbcFinalityStateStore` / `InMemoryFinalityStateStore` | `voteObjectCache` / `voteObjects` 按验证人去重替换 |
| `application.properties` | `nexus.finality.anti-entropy.{enabled,interval-ms,margin-epochs,max-votes}` |

## 三、测试

`nexus-core` 单测（11 例，全绿）：

- `FinalityVoteSyncCodecTest`（5 例）：往返、魔数不冲突、负数/超大 max 收敛、损坏载荷返回 null；
- `FinalityVoteBroadcasterAntiEntropyTest`（6 例）：
  - 请求带 0x5B 魔数与窗口参数；
  - 应答把本地已签名票全部回推；
  - `max=2` 时只回推 2 张；
  - **冷启动节点补票后本地补最终化**（两个各自独立的内存库 + mock P2P 回环）；
  - **重复补票不增长本地票列表**；
  - 定时器按 `margin` 回看（本地 max=101、margin=50 ⇒ from=51）。

## 四、验收（真实进程 + 独立 PostgreSQL 实例）

### 4.1 拓扑与判定口径

| 项 | 值 |
|----|----|
| 进程 | A（HTTP 19585 / P2P 9235）、B（HTTP 19586 / P2P 9236）两个独立 JVM |
| 存储 | 两个**独立 PostgreSQL 16.9 实例**（端口 55433 / 55434），各自独立数据目录 |
| 共识 | PoS，`min-validators-to-mine=2`、`block-interval=3s`、`epoch-length=2` |
| 补票参数 | `interval-ms=5000`、`margin-epochs=50`、`max-votes=200` |
| 判定 | 直接查**各节点自己的库**：`select count(*), min(epoch), max(epoch) from finality_checkpoints` |

> **同主机 ≠ 多机**：两个进程跑在同一台 Windows 主机上，本节验证的是**独立存储故障域**，
> 不是多机高可用，也不覆盖网络分区、断电、PG 主备切换。

为什么是两节点而不是三节点：本机三节点验收中，最后启动的 C 会因为**验证人集合未同步**
而卡在 height 1（`PendingBlocks: unknown proposer (validator set not synchronized)`），
链不前进 ⇒ 它根本不产生最终性事实，无法用于本切片的判定。这是该本机多节点工具链的既有局限，
与本切片无关；两节点足以覆盖"独立存储 + 对端票缺失"这一前提。

### 4.2 实验构造（精确复现本切片的问题前提）

1. A、B 各用独立库，预热 100 秒 —— 双方都最终化到 epoch 69，各自库里 69 个 checkpoint；
2. 停 B；
3. 在 **B 自己的库**里删掉"非 B 的票"（即 A 的 69 张票），并清空 `finality_checkpoints`
   —— 这等价于"对端的票从未写入本节点库"，正是 PLAN-025 §一 描述的独立存储初始状态；
   B 自己的 69 张票保留；
4. 重启 B，观察 75 秒。

对照两组：`nexus.finality.anti-entropy.enabled=true/false`。

### 4.3 结果

| 组 | 预热后 B | 清库后 B | 恢复后 B（checkpoint 区间 / 总数） | 其中历史区间 | A（始终在线） |
|----|----------|----------|-----------------------------------|--------------|----------------|
| **补票开** | 69 个（max 69） | 0 个，只剩自己的 69 张票 | **19..143 / 125 个** | **51 个（19..69）** | 1..143 / 143 个 |
| **补票关** | 68 个（max 68） | 0 个，只剩自己的 69 张票 | **70..145 / 76 个** | **0 个（≤68）** | 1..145 / 145 个 |

**补票开组的读数与设计完全自洽**：

- B 的 `max_epoch` 从 69 → **143**，与 A 的 143 **完全一致**（当前最终性水位收敛）；
- B 恢复了被删区间里的 **51/69** 个 checkpoint，缺口恰好是 **epoch 1..18**；
- 这正是 `margin-epochs=50` 的窗口边界：B 重启时本地 max=69 ⇒ `from = 69-50 = 19`，
  因此只回看 19..69。A 的票分布也印证了这一点（B 库里 A 的票 125 张，缺 1..18）。

**补票关组证明"缺失会永久保留"**：

- A 一次补票请求都没收到（`sync_served=0`）；
- B 重启后仍只有自己的 69 张票 + 重启后收到的新票，**epoch ≤ 68 的 checkpoint 数为 0**；
- B 的 checkpoint 只从 **70** 开始 —— 即"重启之后新产生的 epoch"，掉线/缺失期间的历史永久空缺；
- 两组 B 的 `max_epoch` 最终都能到 145 附近（因为新 epoch 本来就会正常最终化），
  所以**只看水位会误判为"已恢复"**；真正的判据是**历史区间是否被补回**（51 vs 0）。

> 结论口径：补票让"当前最终性水位"完全收敛，并且能补回窗口内的历史事实；
> **窗口外的远古 epoch（本实验的 1..18）不会补齐** —— 这是 §五 已声明的边界，不是实现缺陷。
> 把 `margin-epochs` 调大即可扩大回看范围（代价是更多补票流量）。

## 五、明确边界（勿夸大）

- **单主机**：本次验收是 2 个进程 + 2 个独立 PostgreSQL 16.9 实例，仍然是同一台 Windows 主机；
  验证的是**独立存储故障域**，不声称多机容灾。
- **本机三节点工具链限制**：第三个进程晚启动时会因验证人集合未同步而卡在 height 1
  （`unknown proposer (validator set not synchronized)`），无法产生最终性事实；
  本切片的两节点实验不依赖该路径，但这也意味着"三节点 + 晚加入者"的组合尚未在本机跑通。
- **水位会骗人**：补票关组最终 `max_epoch` 也能到 145（新 epoch 正常最终化），
  但历史区间 `epoch ≤ 68` 的 checkpoint 数为 0。判定收敛必须看**历史区间覆盖率**，不能只看水位。
- **补票窗口有上限**：对端只回推"最新 max-votes 张"。掉线时间过长（超出窗口 + margin）
  的**远古 epoch** 不会被补齐——这些 epoch 的最终性事实仍缺失（不影响"当前最终性"）。
- **没有按对端限速**：m 个对端同时请求会各自得到一份回推；当前规模（2~3 节点）可接受。
- **补票不替代状态同步**：区块同步仍走 `SyncManager`，本切片只补"最终性投票事实"。
- **仍未做**：真实网络分区、完整拜占庭矩阵、数据库整体不可用时的跨进程补票
  （进程崩溃仍会丢"尚未落库的票"，由 PLAN-018 的 outbox 兜底）。

## 六、后续顺序

1. 桥持久化（nexus-bridge 的 outbox/Saga 现状摸底与补齐）；
2. 真多机混沌（需要多台主机/独立网络故障注入）；
3. real-L1 接入（需要外部链授权）；
4. 性能/灾备/外部安全验证。
