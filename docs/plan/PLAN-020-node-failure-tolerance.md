# 方案 PLAN-020：3 节点节点级故障容忍（Phase 6C 第四切片）

> 状态：实现 + 验收完成（证据见 §四）
> 前置：[PLAN-017](PLAN-017-account-store-degradation.md)、[PLAN-018](PLAN-018-finality-persistence.md)、
> [PLAN-019](PLAN-019-block-outbox.md)
> 关联缺口：`docs/roadmap-unresolved.md` #27（多副本最终性缓存一致性）、
> `docs/agent-board/20260722-0830-nex-dc-cached-nvd-resolution.md`

---

## 一、目标与边界

既定顺序里的下一项是「3 节点多主机混沌测试」。**本切片只能做到单主机 3 节点** ——
本环境只有一台 Windows 主机、一个容器 PostgreSQL，**没有多机、没有独立存储、
没有网络分区设备**。因此本切片的定位必须说清楚：

| 性质 | 本切片 | 真正多机混沌 |
|------|--------|-------------|
| 节点数 | 3 | 3+ |
| 节点间网络 | 同一台主机的 localhost | 跨主机/跨网段 |
| 存储 | **共享同一个 PG**（与链、账户、最终性表同一个库） | 每节点独立存储 |
| 可注入的故障 | 杀进程（节点级） | 杀进程 + 网络分区 + 断电 + 磁盘损坏 + PG 主备切换 |
| 能证明的 | **节点级故障容忍**（1/3 验证人掉线时的出块与最终性） | 完整的拜占庭故障矩阵 |

⇒ **不要把本切片的结果说成"多机高可用已验证"**。它验证的是：
「3 个验证人里掉 1 个，链继续出块、最终性不中断、掉线节点回来能追平并重新参与投票」。

## 二、节点级故障的性质：2/3 阈值

`FinalityGadget` 的最终化判据是 `votedWeight ≥ 2/3 × totalWeight`。
3 个验证人（等额质押）时：**任意 1 个掉线，剩下 2 个的权重正好达到 2/3** ⇒
最终性应当继续；**掉 2 个则必然停摆**（1/3 < 2/3），这是协议本身的性质，不是缺陷。

## 三、这次验收挖出的真 bug（已修）

### 3.1 现象

3 节点验收第一轮：**杀掉 C → A/B 正常**；**C 重启后追平卡住** ——
C 的高度停在缺口高度（7766），每 30 秒重试一次，日志反复出现：

```
PendingBlocks: unknown proposer (validator set not synchronized) at height 7766;
block will be retried after validator-set sync (height=7766)
```

即：重启后的节点**认不全 proposer**，于是 `ConsensusRule` 的
`PosConsensusEngine.isProposerKnown(block)` 判否 → 区块被拒 → 该节点**永远无法采纳对端区块**。

### 3.2 根因

`ValidatorSetPersistence.replay()`（启动时从共享表 `validators_synced` 恢复验证人集）：

```java
jdbc.query("SELECT address, public_key, stake_amount FROM validators_synced", rs -> {
    while (rs.next()) {            // ← 元凶
        ...register...
    }
});
```

`RowCallbackHandler` 的语义是「**每行调用一次**」，回调内再调 `rs.next()` 会把游标
**多推一行 ⇒ 隔行丢失**。实测：库里 3 个验证人，重启节点只重放了 2 个
（漏掉的那位恰好是某些区块的 proposer）⇒ 症状如上。

这与 `JdbcFinalityStateStore.reload()` 里**早已修过**的同类坑同源
（那里留有注释：「回调内不能再调用 `rs.next()`（旧实现这样写会跳过隔行）」）——
说明这个反模式在本仓**不止一处**，需要按类排查。

### 3.3 修法

- `replay()` 去掉内层 `rs.next()`，直接用回调的当前行（与 `JdbcFinalityStateStore.reload()` 一致）。
- 新增回归测试 `ValidatorSetPersistenceReplayTest`：
  5 个验证人 ⇒ 必须**全部**重放（锁死"隔行丢失"）；
  重复重放不得重复注册。
  **已验证该测试在旧实现下 2/2 失败、修复后通过**（不是"写完就绿"的假测试）。

## 四、验收证据

环境：同主机 3 进程 + 容器 PostgreSQL 16（**共享一个库**，见 §一）。
脚本：`C:\Users\winge\AppData\Local\Temp\opencode\p6c-3node-chaos.ps1`（临时目录，不入库），
原始输出：`p6c-3node-console2.log`。

### 4.1 预热（3 验证人在线）

- 高度 A=7773 B=7773 C=7774；三者都提交了 epoch 1942 的票。
- 最终性：三个节点各自视图不同（见 §五），但**都是 FINALIZED**。

### 4.2 杀掉 C（1/3 验证人掉线，45 秒）

| 观测 | 结果 |
|------|------|
| A/B 是否继续出块 | **是**：7773 → 7776 |
| A 的最终性（epoch 1942） | `FINALIZED`，`voted_weight=3000 / total_weight=3000`（100%） |
| B 的最终性（epoch 1942） | `FINALIZED`，`voted_weight=2000 / total_weight=2000`（100%） |
| 区块 outbox | `memoryPending=0, databasePending=0`（无待补写积压） |

⇒ **1/3 验证人掉线时，链继续出块、最终性不中断**（2/3 阈值按设计生效）。

### 4.3 C 重启（90 秒）

| 观测 | 结果 |
|------|------|
| 高度 | A=7785 B=7785 **C=7784**（1 个高度内追平，仍在同步中） |
| C 是否重新参与投票 | **是**：日志出现 `Finality vote submitted: epoch=1945` 与 `epoch=1946` |
| C 的最终性查询 | `FINALIZED`（epoch 1944） |
| 全局检查点 | `finality_checkpoints` epoch 1870..1946，**缺口 0**；每 epoch 投票数 2..3 |
| 日志 | `ERROR` A=0 B=0 C=0；**无 reorg / fork 事件** |

⇒ 修复后，**掉线节点回来能追平并重新参与投票**，最终性全程无缺口。

## 五、明确边界（勿夸大）

1. **不是多机**：三节点同主机、共享同一个 PG ⇒ 无法证明跨主机网络分区、
   独立存储故障、主机断电、磁盘损坏。
2. **多副本最终性视图不一致（既有缺口，#27）**：同一 epoch，A 看到
   `voted=3000/3000`，B 看到 `2000/2000`，C 看到 `1000/1000` ——
   因为 `JdbcFinalityStateStore` 的读路径是**启动快照**，节点之间不互相失效
   （A 记的票不会立刻出现在 B/C 的内存视图里）。最终性**事实**在库里是一致的
   （检查点无缺口），但**各节点对外报告的权重进度不同**。这是已登记的缺口，未修。
3. 掉 2 个验证人时链必然停摆（2/3 阈值），本切片未测（协议性质，非缺陷）。
4. 本切片没有注入：网络分区、PG 主备切换、磁盘损坏、时钟偏移、拜占庭消息伪造。
5. 共享 PG ⇒ 任何**库级**故障对三个节点是**对称**的（要么全好要么全坏）；
   节点级故障（杀进程）才是不对称的，也才是本切片覆盖的。

## 六、后续顺序

1. **多机混沌**（真多机 + 独立存储 + 网络分区 + 断电）——需要真实环境；
2. **real-L1 / 桥资金链路持久化**；
3. 性能、灾备与外部安全验证；
4. 顺带按类排查 `RowCallbackHandler` + 内层 `rs.next()` 这一反模式在别处的残留
   （本次在 `validators_synced` 重放与 `finality_votes` 重载两处各命中一次）。
