# 方案 PLAN-029：出块节点主动对账追块（修复 PLAN-027 发现 3 / F2）

> 承接 PLAN-027 §五后续顺序第 2 项（首项 F3 已交付 PLAN-028）。
> **重要口径修正（本切片发现）**：F2 的代码层事实无误，但其"多机停摆根因"的
> 归属不精确——`SyncClient` 仅在 `p2p.mode=rest` 时装配（`@ConditionalOnProperty`），
> 而多机实验三节点均为 `p2p.mode=grpc`（PeerServer 路径）；实验 B 的日志范式
> `require blocks 162..164`（`SyncManager.onStatus`）证明 **grpc 路径本来就
> 有主动追块**，B 追不上是 F1（验证人集不中继 → `unknown proposer`）。
> 因此：F2 是 **REST 部署形态的真实缺陷**（修复照交付），多机停摆闭环
> 主要靠 F1（PLAN-030）+ 已修 F3（PLAN-028）。

## 一、问题

`SyncClient.getStatus()`（30s 周期，REST 模式远程高度对账入口）开头：

```java
if (enableMining) { return; }   // 出块节点永不主动补块
```

出块节点只靠被动接收 `/consensus/blocks` 广播——错过（重启窗口/网络抖动/
上游落库失败）即永久缺失。与 `sendStatus()`（仅 mining 节点广播状态）恰成
"miner 只播不拉 / 非 miner 只拉不播"的对偶，两半都各自缺一条腿。

## 二、修复

- 删除 `getStatus()` 的 mining 门禁：mining/非 mining 行为统一。
  拉取路径本身已有护栏（对端不高于本地则跳过），mining 节点加入无额外负载。
- 决策抽为独立方法 `buildCatchUpQuery(localHeaderHeight, remote)`：
  - 对端不高/无效 → `null`；对端更高 → 窗口 `[localHeight, remote]`
    （`ConsensusController.MAX_BLOCKS_IN_TRANSIT_PER_PEER=50` 裁剪，从起点
    clip——与既有非 mining 行为逐字一致）。
- `chainHeadProvider.currentHeader()` 取本地链头（PLAN-017 语义），
  判定与请求全部走既有 `/consensus/{status,blocks}` REST 通道。

## 三、测试（nexus-core，全部通过）

`SyncClientMiningSyncTest` 4 例：

| 用例 | 验证点 |
|------|--------|
| `miningNodeStillIssuesStatusPoll` | **F2 核心**：`enableMining=true` 下 `getStatus()` 仍发起对端状态请求 |
| `catchesBehindPeerWithClippedWindow` | 对端 +70 → 窗口 `100..149`（超出 50 裁剪） |
| `catchesBehindPeerWithinWindow` | 小窗口（1..30）端到端 |
| `noCatchUpWhenPeerNotAheadOrInvalid` | 对端不高/更低/null → 不追 |

## 四、明确边界（勿夸大）

- **REST 形态专属**：grpc 部署（多机实验拓扑）不装配 `SyncClient`，
  其追块走 `SyncManager.onStatus`（已有）；本切片不声称 grpc 路径受益。
- **F1 未修时仍可能"追到手落不进库"**（C 提议的块 unknown proposer）——
  F2 与 F1 是互不替代的互补修复，本切片不声称闭环。
- **`sendStatus()` 门禁保留**（非 mining 节点不广播状态）：最小 diff；
  非 mining 广播状态是否有价值留待后续切片评估。
- **重叠拉取去重**：mining 节点可能同时收到广播块与拉取块——
  `receiveBlocks` 校验落链自会去重（该行为已在既有路径验证）。

## 五、后续顺序

1. **PLAN-030（F1）**：验证人集中继 + 全表回放（grpc 路径，多机闭环关键）；
2. 四项修完后（F3 done / F2 done / F1 / Fifo）重跑多机实验：`停 C 60s → 恢复 → 补票追平`；
3. （可选）rest 部署形态的对称修复（`ConsensusController` 服务端无 P2P 广播通道，
   F1 的中继/回放均挂 P2P `server.broadcast`——rest 集群形态没有 P2P 通道本身，
   此口径与 F1 文档同步）。
