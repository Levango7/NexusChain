# 方案 PLAN-030：验证人集中继与全表回放（修复 PLAN-027 发现 2 / F1）

> 承接 PLAN-027 §五后续顺序第 3 项（F3 已交付 PLAN-028、F2 已交付 PLAN-029）。
> F1 是多机停摆闭环的**关键一环**：grpc 拓扑下 B 收到了 C 的块与追块请求，
> 却因注册表缺 C 而全部 `unknown proposer` 丢弃——排在前面的 F3/F2 修复后
> B 若仍不认识 C，链照样停。多机实证材料（B 的 C 缺行靠人工插行绕行 + 重启
> 后无法自愈）即本缺陷的注脚。

## 一、问题

validator-set 广播（魔数 0x56，TRANSACTIONS 通道旁路交易）由
`ValidatorNodeBootstrapper` 在自注册时发出（10s/30s/60s 三次重发），
**只发直连对端**（`peerServer.broadcast` = 单跳）：

```text
C ──add(C)──▶ A          （A 收到并注册 C）
A ──nothing──▶ B         （B 永远不知道 C）
```

星型拓扑（C↔B 直连被防火墙挡）下：
- C 提议的块在 B 处 `unknown proposer (validator set not synchronized)` 无法落库；
- C 无论上线下线，B 的 ordered set 都只有 [B, A]——F3 的 view-change 到不了 C；
- B 重启后**无人重放历史** add ⇒ 缺 row 永久化（实验中人工插行的原因）。

## 二、修复（SyncManager，两手）

### (a) 中继（单条转发）

`handleValidatorSet` 成功处理 add/remove 后调用 `relayValidatorSet(payload)`：
- 载荷按 **SHA-256 内容指纹**去重（LRU 容量 512）——同一载荷每节点最多
  转发一次，回声两跳内收敛（A 转发 → C 收到自己 → C 转发 → A/B 指纹命中
  停止），无广播风暴；
- malformed/缺 pubkey/注册表未装配等异常路径**不转发**（防垃圾传播）；
- 转发封装与 Bootstrapper 同构（`TransactionType.VOTE` 旁路交易 +
  `server.broadcast`）。

### (b) 全表回放（重启自愈）

`onGetStatus`（对端每 30s 咨询本节点状态）末尾调用
`replayValidatorSetTable()`：把 `validatorRegistry.getAllValidators()` 全量
包装为单条 TRANSACTIONS 广播（每验证人一条 add 载荷）：
- 新/重启节点靠它补齐错过的历史 add（B 下次拉 A 时立刻知道 C）；
- 3 节点规模每周期 3 条 add，损耗微小；>64 人截断（`MAX_VALIDATORS_PER_REPLAY`）
  并 warn —— 防超大集合撑爆单条消息；
- `server == null`（非 grpc 装配）与注册表为空直接跳过。

**接收端不变**：`SyncManager.onTransactions` 的魔数分流与幂等注册
（`seedStake` → `register` → `upsert` 落库）原样复用，重复 add 无害。

## 三、测试（nexus-core，与 F2 切片一并全部通过）

`SyncManagerValidatorSetRelayTest` 6 例：

| 用例 | 验证点 |
|------|--------|
| `relaysFirstSeenAddToPeers` | 首见 add → 广播 1 次、载荷逐字节一致 |
| `relayedPayloadNotForwardedAgain` | 同载荷二次 → 不再转发（防环） |
| `removeAlsoRelayedOnce` | remove 同样转发一次、本地置 INACTIVE |
| `malformedPayloadNotRelayed` | 非 validator-set 载荷不转发 |
| `fullTableReplayOnGetStatus` | onGetStatus → 状态应答 + 全表 2 人单消息回放（逐条 decode 校验） |
| `noServerNoReplay` | 无 PeerServer（非 grpc/单机）不回放 |

## 四、明确边界（勿夸大）

- **两跳收敛是工程取舍**：网络分区恢复/拓扑变化后某个载荷可能从未
  经过"必经中转"——保证不追逐极端拓扑（全联通弱关系下的 add 总会
  经过 ≥1 个共同邻居，实验拓扑满足；全被阻断时也无害，只是缺 row）。
- **全表回放无签名认证**：沿用 PLAN-001 验证人集合消息的既有信任模型
  （未签名、明文 JSON）——伪造 add 与修复前同等跳出，不在本切片扩界。
  （数十量级节点后需要引入 add 载荷自证 ≥ 质押门槛 + 广播频次限额。）
- **rest 部署形态不适用**：F1 的两处挂点都在 grpc 的 `SyncManager`
  （`server.broadcast`）；`p2p.mode=rest` 的集群无 P2P 广播通道，
  中继/回放两者皆不可达——如实登记为 REST 形态的未来工作。
- **DOS 小面**：恶意 peer 每 30s 无限次 GET_STATUS 会放大回放次数
  （每次 = 全表规模 add）。当前单条消息 ≈3 条 add、成本微小；
  攻击面准入在"p2p 端口可达的 peer"之后（既有前提）。

## 五、后续顺序

1. 四项齐活（F3 done / F2 done / **本切片 done** / Fifo）后重跑多机实验：
   `停 C（app+PG）60s → 恢复 → 补票追平`完整闭环；
2. 更新 `PLAN-027` §五、roadmap #34/#35/#30 的闭环实证数据；
3. （可选）验证人集消息签名化（defer，见本节 DOS/伪造边界）。
