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

---

## 六、rest 形态验证记录（2026-10-25）

> 目的：把 F2 修复（mining 节点不再跳过 `getStatus`）放到真实 rest 形态里跑。
> 结论先行：**F2 逻辑被实机执行并工作**；但顺手发现 rest 形态有**三个既有运行时
> 缺陷**（与 F2 逻辑无关）——修掉之前 rest 节点根本起不来，意味着本切片的
> rest 路径在此前**从未被实机跑过**。

### 6.1 发现并修复的三个缺陷（均为既有问题）

| # | 缺陷 | 症状 | 修复 |
|---|------|------|------|
| 1 | `NodeInfoController` 对 `PeerServer` 为**必填注入**（`@Autowired`） | rest（无 PeerServer bean）**上下文启动失败** | 可选注入 + `/peers/status` null 降级（空对端） |
| 2 | `Fifo` 同样必填注入 `PeerServer` | 修掉 #1 后的下一个启动阻断点 | 可选注入 + `getNodeInfo()` 的 P2P 字段 null 降级 |
| 3 | `JSONEncodeDecoder.decodeBlocks/decodeTransactions` 用**严格 mapper**（`FAIL_ON_UNKNOWN_PROPERTIES=true`） | 服务端区块 JSON 自带 getter 派生字段（`channelTransaction/stableCoinTransaction/paymentExtensionType/typeName/bridgeTransaction`）⇒ **拉块解码全失败**，`SyncClient.getBlocks` 永久拉不到块 | 新增 `decodeTolerant` 仅供 P2P 两方法；其余 `decode` 保持严格（单测双向锁定） |

### 6.2 实测（第 4 个临时 rest 节点）

- 配置：`p2p.mode=rest`、`enable-mining=true`、peers = A/C 的 HTTP 端点
  （`miner.validators` 文件的 host:port）、独立库；验证人集经测试床**预置**
  `validators_synced` 三行装载（理由见边界）。
- 证据（单次运行约 35 分钟，日志 `rest-node.log`）：
  - 启动成功；`try to sync from nexus seeds` **35 次** —— mining 开启仍执行
    `getStatus`，即 **F2 修复点本身**；
  - `receive blocks startListening from X stop at Y` **48 次**；
  - 高度 **0 → 900+** 持续单调追赶（约 +50/分钟；期间链路头从 ~1100 推进到
    2600+，gap 2161 → 1737 收敛）；
  - `unknown proposer` **0 次**（预置验证人集后）。
- 单测：`JSONEncodeDecoderP2PToleranceTest`（3 例：P2P 容忍 ×2 + 非 P2P 仍严格）
  ；`NodeInfoControllerRestModeTest`（null 降级不 NPE）。

### 6.3 边界（不夸大）

- **不断言"最终追平时间"**：本次只观测到"持续、单调、正确的追赶"；
  净收敛速率有限（每条 peer 状态周期上限 50 块），完全追平需长跑，未等到。
- **rest 形态的验证人集同步通道**（F1 的中继/回放挂 grpc P2P 广播；rest 无该通道
  ——与 §五 第 3 条同口径）：本次验证时属未做、以测试床预置共享表绕过；
  **已由 PLAN-032 关闭**（`GET /consensus/validator-set` 周期分发 +
  同一处理路径；空库 rest 节点实测自动追块，见 `PLAN-032` §四）。
- grpc 部署（多机实验拓扑）不受本次修复影响（`SyncClient` 仅在 rest 装配）。
