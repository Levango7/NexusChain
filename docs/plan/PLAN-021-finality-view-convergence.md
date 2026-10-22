# 方案 PLAN-021：最终性视图的跨节点收敛（Phase 6C 第五切片）

> 状态：实现完成，验收见 §四
> 前置：[PLAN-018](PLAN-018-finality-persistence.md)、[PLAN-019](PLAN-019-block-outbox.md)、
> [PLAN-020](PLAN-020-node-failure-tolerance.md)
> 关联缺口：`docs/roadmap-unresolved.md` #27（多副本最终性缓存一致性）

---

## 一、问题

3 节点验收（PLAN-020）暴露了一个**跨节点视图不一致**的问题：同一 epoch，三个节点对外
报告的权重进度完全不同。

| 节点 | `/rpc/v1/finality/epoch/{epoch}` 返回 |
|------|--------------------------------------|
| A | `voted_weight=3000 / total_weight=3000`（100%） |
| B | `voted_weight=2000 / total_weight=2000`（100%） |
| C | `voted_weight=1000 / total_weight=1000`（100%） |

**根因**：`FinalityGadget` 的内存视图（`epochCheckpointVoters` / `epochCheckpointVotes` /
`finalizedCheckpoints`）**只在启动恢复时从库里装载一次**
（`JdbcFinalityStateStore.reload()`），之后**再也不会更新**：

- 启动之后对端投的票，本节点**永远看不到** ⇒ ①对外报告的权重进度是错的；
  ②即便对端已把该检查点标记终局，本节点也**永远不会本地补最终化**
  （`tryFinalize` 的聚合验签只覆盖它自己收到的那几张票）。

代码里其实早就有这句注释：「多副本部署下缺乏跨实例失效机制 —— 实例 A 终局某检查点后，
实例 B 的内存快照不会更新」，只是当时把它登记为"已知局限，本次未修"。

## 二、修复：把"库里的新事实"周期性地拉进本地视图

### 2.1 存储层：按 epoch 增量拉取

`FinalityStateStore` 新增两个 default 方法（未实现的实现退化为"不支持收敛"）：

- `loadVoteObjectsSince(long fromEpoch)` → `epoch|checkpointHex` → 完整 Vote 列表（**带签名**）；
- `loadFinalizedKeysSince(long fromEpoch)` → 已终局的 `epoch|checkpointHex` 集合。

`JdbcFinalityStateStore` 用 `WHERE epoch >= ?` 实现（**只拉最近若干个 epoch**，
不做全表扫描）；`InMemoryFinalityStateStore` 同语义实现。

### 2.2 gadget 侧：带绑定校验的合并

`FinalityGadget` 新增 `@Scheduled(fixedDelay = 5s)` 的收敛任务
（`convergeFinalityView()`），把库里的新票/新终局并入内存视图，并重试这些检查点的最终化。

**安全边界（四条，缺一不可）**：

| 边界 | 做法 |
|------|------|
| 绑定校验 | 合并进来的票必须通过**与提交路径同一套**校验（`validateVoteBinding()`：注册表存在 + ACTIVE + 公钥与注册公钥一致）。否则等于"库里的任意行都能给自己加权重" |
| 单调性 | 只增不减（与"缓存不设 TTL"的理由一致：最终性事实不可逆） |
| 去重 | 靠投票者集合；同一验证人不会被计两次权重 |
| 有界 | 拉取窗口只覆盖"最近若干个 epoch"；收敛起点在启动恢复时置为"已恢复的最大 epoch"（避免第一次就全表扫描） |

> 实现要点：绑定校验从 `submitVote` 里**抽成 `validateVoteBinding()`** 由两条路径共用，
> 避免"提交路径严、收敛路径松"这种典型的安全漂移。

## 三、测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `FinalityViewConvergenceTest`（新，4 例） | 对端落库的票被拉进本地视图 → 权重口径对齐（1000→3000）→ **本节点能本地补最终化**；重复收敛**不重复计权**；**公钥不匹配的票被拒绝**（伪造票不得计权）；对端已终局的事实被本节点视图采纳 |

## 四、验收

环境：同主机 3 进程 + 容器 PostgreSQL 16（**共享一个库**，明确非多机）。
脚本：`C:\Users\winge\AppData\Local\Temp\opencode\p6c-3node-chaos.ps1`（临时目录，不入库），
原始输出：`p6c-3node-console4.log`。

### 4.1 收敛前（PLAN-020 同期实测）

| 节点 | 同一 epoch 的对外视图 |
|------|----------------------|
| A | `voted_weight=3000 / total_weight=3000` |
| B | `voted_weight=2000 / total_weight=2000` |
| C | `voted_weight=1000 / total_weight=1000` |

### 4.2 收敛后（本次）

| 时点 | A | B | C |
|------|---|---|---|
| 预热（3 节点在线） | `3000/3000` | `3000/3000` | `3000/3000` |
| 杀掉 C（1/3 掉线） | `3000/3000` | `3000/3000` | （按设计不可达） |
| C 重启后 | `3000/3000` | `3000/3000` | `3000/3000` |

⇒ **三个节点的最终性视图完全一致**（收敛前是 3000/2000/1000）。

其它观测：

- C 重启后**完全追平**：A=7827 B=7827 **C=7827**（收敛前 C 落后并卡在缺口高度）；
- `finality_checkpoints` epoch 1870..1956，**缺口 0**；每 epoch 投票数 2..3；
- `ERROR` A=0 B=0 C=0；**无 reorg**；outbox 无积压。

### 4.3 收敛过程中挖出的第二个 bug（已修）

第一次跑收敛版时，C 仍报 `1000/1000`，查证后发现**不是收敛逻辑的问题**，而是
**DB 重放路径没把质押额灌进 StakingService**：

- `StakingService.getStake()` 只读**本地账本**（不回落注册表）；
- `ValidatorSetPersistence.replay()` 只把验证人注册进 `ValidatorRegistry`，
  质押额留在库里没进账本 ⇒ 节点"知道有这些验证人，却算不出他们的权重"
  ⇒ 总权重偏小 ⇒ **永远达不到 2/3 阈值**；
- 广播路径（`SyncManager` 收验证人集合）是有 `seedStake` 的，**只有 DB 重放这条路漏了**。

修法：`replay()` 里同步 `stakingService.seedStake(addr, stake)`
（`seedStake` 只填空账本、不覆盖已有质押 ✔，与广播路径同一做法），
并补回归测试 `ValidatorSetPersistenceReplayTest.replayAlsoSeedsStakeSoTotalWeightIsCorrect`。

## 五、明确边界（勿夸大）

1. **收敛的是"视图"，不是"信任"**：合并进来的票仍要过绑定校验与聚合验签；
   收敛只是让本节点**看得见**对端已落库的事实。
2. **收敛有延迟**（≤5 秒 + 拉取耗时），不是强一致。
3. 收敛**只覆盖最终性层**；链状态（账户/余额）**没有**同类机制 —— 链的视图本来就是
   各自本地的，靠 P2P 同步收敛，语义不同（链是最终一致的账本，最终性是"不可逆结算"）。
4. 拉取窗口内的**迟到票**（同一 epoch 在窗口之外才落库）可能漏掉；当前 epoch 始终在窗口内
   （收敛起点取"当前最大 epoch"，含），风险有限。
5. 未覆盖：多副本共享一个 PG 之外的部署形态（每节点独立库 ⇒ 收敛**失效**，
   因为对端的票不在本节点的库里）——**这是本方案的硬边界**：
   收敛只在"共享存储"拓扑下成立；独立存储部署仍需跨节点事实传播（订阅/反查）机制。

## 六、后续顺序

1. **独立存储部署下的跨节点事实传播**（订阅终局事件 / 反查对端）——本方案的适用边界之外；
2. **真多机混沌**（需真实环境）；
3. **real-L1 / 桥资金链路持久化**；
4. 性能、灾备与外部安全验证。
