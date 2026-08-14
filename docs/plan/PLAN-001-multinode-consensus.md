# 方案 #1：NexusChain 多节点共识拓扑

- **状态**：Approved（2026-08-14 审核通过）
- **日期**：2026-08-14
- **前置**：#20 固定验证人密钥（已解决）、P2P 握手与投票传输（已实证）
- **目标**：3 节点真实拓扑达成「共享单链出块 → 跨节点投票汇聚 → 共识最终化」

## 审核决策（2026-08-14）

| 决策点 | 结论 |
|---|---|
| 验证人同步方案 | **A. P2P 广播**（复用 TRANSACTIONS 链路，幂等注册） |
| 出块策略 | **配置启用**：新增 `nexus.consensus.proposer-strategy=round-robin`，默认随机 |
| 拓扑规模 | **2 节点 A/B 起步**（先最小闭环，再扩 3） |
| 广播持久化 | **落库重放**：验证人集合写入共享表，重启加载 |

---

## 一、现状事实核查（已代码确认）

| 能力 | 现状 | 证据 |
|---|---|---|
| P2P 双向握手 | ✅ 已实证 | `enable-discovery=true` 后 A.peers 含 B |
| 投票广播/接收 | ✅ 已实证 | `sendOverP2P` + `SyncManager.onTransactions` |
| 固定验证人密钥 | ✅ 已解决 | `validator-private-key` 配置（#20） |
| 确定性 proposer | ✅ 已有 | `PosProposer.selectProposerByRoundRobin`（height % size） |
| 他节点区块写入 | ✅ 已有 | `SyncManager.onProposal → receiveBlocks` |
| 共享链状态 | ✅ 天然支持 | 多节点连同一 Postgres（区块/账户共享） |
| **验证人集合一致性** | ❌ 缺失 | 各节点 ValidatorRegistry 独立，互不认识 |

**核心缺口收敛为 2 个**：
1. 验证人集合跨节点同步（B 不认识 A 的验证人 → 拒绝 A 投票）
2. 出块协调启用（默认随机 proposer 会多节点同时出块冲突；需切确定性轮询）

---

## 二、目标拓扑

```
3 节点，共享同一 Postgres（nexuschain），各自独立 LevelDB 缓存 + 固定密钥

┌─────────────┐   P2P   ┌─────────────┐   P2P   ┌─────────────┐
│  node-A     │◄───────►│  node-B     │◄───────►│  node-C     │
│  19585/9235 │         │  19586/9236 │         │  19587/9237 │
│  privKey-A  │         │  privKey-B  │         │  privKey-C  │
└──────┬──────┘         └──────┬──────┘         └──────┬──────┘
       └──────────────────────┼───────────────────────┘
                              ▼
              ┌───────────────────────────┐
              │   Postgres: nexuschain    │  ← 共享链状态（区块/账户）
              └───────────────────────────┘
```

**关键原则**：链状态单源（共享 PG）；共识参与（验证人集合）全网一致；出块轮换确定；最终性投票 P2P 传播后各节点本地汇聚。

---

## 三、方案设计（按实施顺序）

### 步骤 1：验证人集合跨节点同步（核心缺口 #1）

**问题**：B 的 `ValidatorRegistry` 只认自己的验证人，A 的投票到达 B 后被 `submitVote` 拒绝（非 ACTIVE/未知）。

**方案 A（推荐）：验证人广播消息（ValidatorSetMessage）**
```
新 P2P 消息类型：复用 TRANSACTIONS 通道，新增 transaction_type 标记
  payload = FinalityVoteCodec 风格 JSON:
    {"type":"validator-set","action":"add|remove","address":...,"pubkey":...,"stake":...}

流程：
1. ValidatorNodeBootstrapper 自举注册本节点后，向全网广播自己的验证人信息
2. 各节点 SyncManager 收到 validator-set 消息 → ValidatorRegistry.register()
   （幂等：已存在跳过）
3. 结合 #20 固定密钥：每个节点用配置密钥 → 地址/公钥稳定 → 广播一次全网可认
```

**方案 B（备选）：启动时通过共享 PG 对齐**
```
利用共享 Postgres：验证人集合存入共享表，各节点启动时加载。
缺点：ValidatorRegistry 是内存 @Component，改造为 DB 加载侵入大；
     且治理动态变更（ValidatorRpcController）需同步写表。不推荐。
```

**决策建议**：方案 A（P2P 广播），与投票消息同构，复用已验证的传输链路。

### 步骤 2：出块协调——确定性轮询（核心缺口 #2）

**问题**：`PosConsensusEngine.propose()` 用 `proposer.selectProposer(height)`（权益加权**随机**）——3 节点都可能被随机选中 → 同时出块 → 同高度多块冲突。

**方案**：切换/复用 `selectProposerByRoundRobin(height)`（`height % 活跃验证人数`）：
```
- 每个节点用相同活跃验证人集合（步骤 1 保证一致）→ height % N 结果一致
- 仅当被选中者是本节点时才出块（现有 fail-closed 已保证）
- 需确认 PosConsensusEngine 当前注入的是随机版还是轮询版（核查代码）
```

**补充**：出块间隔（blockInterval）需对齐，避免竞态；验证人集合变化时
round-robin 索引按最新集合计算（与指纹失效机制兼容）。

### 步骤 3：区块传播与最终化汇聚（复用现有）

```
出块节点：propose → 本地采纳（writeBlock）→ P2P 广播 Proposal
其他节点：SyncManager.onProposal → receiveBlocks → 写链（已有）
最终性：各节点 FinalityCoordinator 对检查点投票 → P2P 广播 → 他节点汇聚
        → 各节点 FinalityGadget 独立判定 2/3（已有）
```

---

## 四、实施清单（待审核通过后执行）

| # | 任务 | 文件 | 预估 |
|---|---|---|---|
| 1 | ValidatorSet 广播消息编解码 + 路由 | `net/FinalityVoteCodec` 扩展 或新 `ValidatorSetCodec` | 1 天 |
| 2 | SyncManager 处理 validator-set → ValidatorRegistry.register | `SyncManager.onTransactions` 扩展 | 0.5 天 |
| 3 | Bootstrapper 自举后广播验证人信息 | `ValidatorNodeBootstrapper` | 0.5 天 |
| 4 | 出块切确定性轮询（核查+切换） | `PosConsensusEngine`/`PosProposer` | 0.5 天 |
| 5 | 3 节点脚本 + 端到端验证 | `scripts/` + 真机 | 1 天 |

**预估总工期**：3.5-4 天（含验证）

## 五、风险与验证标准

| 风险 | 缓解 |
|---|---|
| 验证人广播顺序/时序（A 广播时 B 未启动） | 幂等注册 + 定时重广播（bootstrapper 启动后 N 秒再发一次） |
| 共享 PG 的区块写入竞争 | 确定性 proposer 保证单节点出块；其他节点仅写确认 |
| 轮询切换影响单节点测试 | 保持随机为默认，轮询通过配置 `nexus.consensus.proposer-strategy=round-robin` 启用 |

**验证标准（达成即闭环）**：
```
1. 3 节点启动后 peers 全互联（A/B/C 互相可见）
2. 各节点注册表含全部 3 验证人（验证人广播生效）
3. 任一高度仅有 1 个 proposer 出块（确定性轮询生效，无同高冲突）
4. A 出块 → B/C 收到并写链（区块传播生效）
5. A 投票 → B/C 汇聚（voted_weight 反映多节点投票）
6. 检查点达 2/3 → 各节点 FINALIZED 一致
```

---

## 六、待审核决策点

1. **验证人同步方案**：A（P2P 广播）vs B（共享 PG 对齐）——建议 A
2. **出块策略**：默认保持随机，轮询按配置启用？还是直接默认轮询？
3. **拓扑规模**：验证用 3 节点（A/B/C）还是 2 节点起步（A/B）？
4. **共享 PG 表隔离**：验证人广播消息是否需要持久化（重启后重放）？

请审核并给出决策，通过后按第四节清单执行。
