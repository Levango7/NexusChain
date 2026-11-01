# 方案 PLAN-065：最终性投票签名伪造（公钥正确）绕过准入

- **状态**：已交付（2026-11-01）
- **前置**：PLAN-016（最终性故障注入首批切片）、PLAN-018（投票签名随票落库 +
  重启补最终化）、PLAN-021（跨节点收敛与提交路径共用绑定校验）、
  PLAN-025（独立存储拓扑下的补票）
- **范围**：投票准入验签（网络提交 + 跨节点收敛）、启动恢复过滤、回归测试与文档
- **不包含**：真实网络分区、物理多机、真实 L1、桥资金链路、完整拜占庭矩阵的
  其余输入（女巫/长程/私钥泄露等）、BLS 聚合替换、性能基准

---

## 一、问题（为什么必须做）

`FinalityGadget.validateVoteBinding()` 原先只做三件事：

1. 验证人地址在注册表中；
2. 验证人状态为 `ACTIVE`；
3. **票内携带的公钥 == 注册公钥**。

签名验签被推迟到 `tryFinalize()` 的聚合验签。问题在于：**公钥是公开信息**。
攻击者不需要任何私钥，就能构造一张“公钥正确、签名伪造”的票：

```text
Vote(epoch, checkpoint, "v1", 垃圾签名, v1 的注册公钥)
```

这张票会：

1. 通过 `validateVoteBinding()`；
2. 被写入 `finality_votes`（含伪造签名）；
3. 给 `v1` 计入质押权重，并把 `v1` 放进该检查点的 `voters` 集合；
4. 随后真正的 `v1` 投出合法票时，`voters.contains("v1")` 为真 ⇒ 被当作
   “重复票”幂等拒绝，**合法票对象永远进不了 `epochCheckpointVotes`**；
5. 达到 2/3 阈值后，`tryFinalize()` 用包含伪造票的集合做聚合验签 ⇒ 永远失败；
6. `finalizationRejected` 置位后，重复票不会再次触发聚合验签；
7. 伪造票已经落库，**重启后依旧占位**，检查点永久无法最终化。

这不是“最终化晚一点”的性能问题，而是**可被任意网络参与者触发的永久性
liveness 攻击**：只要抢先投一张带真实公钥的垃圾签名票，就能把一个检查点
钉死在“权重够了但验签不了”。攻击者甚至不需要是验证人。

## 二、根因

### 2.1 准入校验与签名校验分离

PLAN-021 把绑定校验抽成 `validateVoteBinding()`，供 `submitVote()` 与
`convergeFinalityView()` 共用，但当时只校验到“公钥匹配”。设计上把签名验证
留给最终化时的 `SignatureAggregator`，理由是“聚合验签是性能基石”。

这个分层忽略了一个顺序问题：**计票/落库发生在聚合验签之前**。一旦伪造票
先进入 `voters`，合法票就会被去重掉，聚合验签即使正确也永远没有合法票可验。

### 2.2 三条会把票加入计票集合的路径

| 路径 | 修复前 | 风险 |
|------|--------|------|
| `submitVote()`（网络/本地提交） | 只校验公钥绑定 | 伪造票可落库、计权、占位 |
| `convergeFinalityView()`（跨节点收敛） | 复用同一绑定校验 | 对端库里的伪造行会被拉进本地视图 |
| `restoreFromStore()`（启动恢复） | 无条件信任 `loadAllVotes()` / `loadAllVoteObjects()` | 修复前已落库的伪造行在重启后继续占位 |

只修 `submitVote()` 不够：收敛路径会把对端库里的坏行重新拉进来，启动恢复
会把本机库里的坏行重新拉进来。

## 三、修复

### 3.1 新增 `hasValidVoteSignature(Vote)`

只依赖票自身携带的公钥、签名、检查点与 `signingPayload()`，**不查注册表**。
这样启动恢复时验证人集尚未装载也不会误丢合法票；网络/收敛入口则先做注册表
绑定，再调用本方法。任何格式异常一律 fail-closed。

### 3.2 `validateVoteBinding()` 增加单票 Ed25519 验签

在公钥匹配之后、计票/落库之前调用 `hasValidVoteSignature()`。失败直接返回，
调用方不会写 `voters`、不会写库、不会计权重。`submitVote()` 与
`convergeFinalityView()` 自动同时生效。

最终化时的聚合验签**保留**，作为纵深防御；本次没有移除或放宽它。

### 3.3 启动恢复只信任签名有效的未终局票

`restoreFromStore()` 现在分两种口径：

- **已终局检查点**：保留原“全部投票者”展示口径（含迁移前无签名行）。
  最终化是不可逆历史事实，不能因为老数据没有签名就把它从展示里抹掉。
- **未终局检查点**：只从 `loadAllVoteObjects()` 恢复**签名有效**的票；
  无签名或验签失败的行走 `WARN` 日志后丢弃。投票者集合、权重、Vote 对象
  三者从同一批有效票重建，避免“权重恢复了但票对象没有”的半状态。

这与 `JdbcFinalityStateStore` 的既有行为一致：JDBC 的
`loadAllVoteObjects()` 本来就只读 `signature IS NOT NULL` 的行。

### 3.4 兼容性与迁移口径

- 生产 JDBC 库中，PLAN-018 之后的票都带签名；PLAN-018 之前的未终局行会被
  本修复丢弃。这是有意的 **liveness 折衷**：无法验签的历史行不可信，保留它们
  等于保留伪造票的占位能力。丢弃后，合法票重新到达或 anti-entropy 补票即可
  重新累计；已终局检查点不受影响。
- 网络协议不变：`FinalityVoteCodec` 仍然要求 `pub` 字段；老格式（无公钥）
  继续 fail-closed。

## 四、验证

### 4.1 新增/改写的测试

| 测试 | 覆盖点 |
|------|--------|
| `FinalityVoteForgeryTest`（新，4 例） | 伪造票不得计权/落库；伪造票不得占位阻塞最终化；收敛路径必须验签；重启恢复必须丢弃历史坏签名行且合法票仍能最终化 |
| `FinalityGadgetFaultInjectionTest.restartFromAddressOnlyStoreDropsUnsignedVotesAndReaccumulates`（改写，1 例） | 老库（只存地址、不存签名）重启后不得恢复不可信权重；合法票重新到达后仍能累计并最终化 |

> 该故障注入用例原为 `restartAfterVoteButBeforeFinalizationNeedsNewSignatureToFinish`，
> 测试替身只实现旧 3 参 `recordVote`（不落签名）。PLAN-018 后生产路径已改为
> 签名随票落库；PLAN-065 又把“无签名行不可信”前移到恢复路径，因此该用例
> 按“老库迁移”语义改写，不再保留旧断言。

### 4.2 反向对照（实跑）

把 `FinalityGadget.java` 临时还原成修复前版本（`git checkout --`），实跑：

```text
FinalityGadgetFaultInjectionTest > restartFromAddressOnlyStoreDropsUnsignedVotesAndReaccumulates FAILED
FinalityVoteForgeryTest > restartDropsBadSignatureRowsSoCheckpointCanStillFinalize FAILED
FinalityVoteForgeryTest > convergenceSkipsVoteWithValidPublicKeyButBadSignature FAILED
FinalityVoteForgeryTest > forgedVoteCannotBlockFinalizationByPoisoningVoterSet FAILED
FinalityVoteForgeryTest > forgedSignatureWithRegisteredPublicKeyIsRejectedBeforePersist FAILED
10 tests completed, 5 failed
```

恢复修复版本后，同一批 **10 用例 0 失败**。反向对照证明新用例确实由本修复
驱动变红，而不是“恒红/恒绿”的装饰测试。

### 4.3 回归

- finality 全包（`org.nexus.consensus.finality.*` + `persistence.*` + `net.*`）
  **135 用例，0 失败，0 跳过**（`--rerun-tasks` 强制实跑，非构建缓存）；
- `nexus-core` 全量：**1816 用例，0 失败，0 错误，19 跳过**（`--offline` 实跑）；
- 修复落地后、用例改写前，该包为 135 用例 / 1 失败（即上述老库恢复用例因
  新恢复口径变红）；按新语义改写后归零。

原始日志（仓库外，不入库）：

- `C:\Users\winge\AppData\Local\Temp\opencode\forgery-before4.log`（修复前 3 红）
- `C:\Users\winge\AppData\Local\Temp\opencode\forgery-restore-before.log`（恢复路径红）
- `C:\Users\winge\AppData\Local\Temp\opencode\reverse-control.log`（整体还原 5 红）
- `C:\Users\winge\AppData\Local\Temp\opencode\reverse-control-restored.log`（还原后 10 绿）
- `C:\Users\winge\AppData\Local\Temp\opencode\finality-regression5.log`（135 绿）

## 五、诚实边界（勿夸大）

1. **单主机 JVM 测试**：本切片全部证据来自同进程/同内核单元测试与内存存储；
   没有真实网络分区、没有物理多机、没有跨内核对比。不得据此宣称“拜占庭容错
   已在多机环境验证”。
2. **只覆盖“公钥正确、签名伪造”这一类输入**：私钥泄露后的真实签名伪造、
   女巫攻击、长程攻击、验证人协同作恶、消息重放洪泛等仍属完整拜占庭矩阵的
   未覆盖项。
3. **每票一次 Ed25519 验签**：未做吞吐/延迟基准；最终化时的聚合验签仍保留，
   总成本高于修复前。对当前验证人数量级（个位数到几十）可接受，但未用生产
   负载验证。
4. **未终局的无签名历史行会被丢弃**：这是有意的安全优先折衷，代价是升级后
   这些检查点需要新票/补票才能最终化；已终局历史不受影响。
5. **未改真实 L1 / 桥资金链路**：本切片只触及最终性投票准入与恢复。
6. **未新增拒绝计数指标**：验签失败目前只打 `WARN` 日志；拒绝量观测是后续项，
   不在本切片冒充已完成。**（2026-11-02 回填：已由
   [PLAN-066](PLAN-066-finality-vote-rejection-metrics.md) 兑现——四条拒绝路径
   都有了 `nexus_finality_vote_rejected_total{reason, phase}` 计数与两条告警；
   但 PLAN-066 同样**没有**做验证人地址维度的标签，见其 §2.3 的基数风险论证。）**

## 六、后续

- 完整拜占庭矩阵剩余项：真实网络分区、验证人协同双签、私钥泄露、长程攻击、
  消息重放/洪泛；
- 验签失败计数指标（按验证人/原因分桶）与告警，避免只靠日志 —— **（2026-11-02
  交付：PLAN-066；其中“按验证人分桶”经论证后**刻意不做**，理由见
  [PLAN-066](PLAN-066-finality-vote-rejection-metrics.md) §2.3 —— 攻击者用随机
  地址即可打垮标签基数。）**
- BLS 聚合替换后，确认“单票验签 + 聚合验签”的分层仍然成立；
- 若未来引入验证人集热变更，补“变更窗口内旧票/新票”的准入矩阵。

## 七、相关文档回填

- PLAN-016 §五 #1/#7 的“重试队列不持久化 / 完整 Vote 签名未持久化”已由
  [PLAN-018](PLAN-018-finality-persistence.md) 关闭：`finality_vote_outbox` +
  `FinalityOutboxManager` 提供持久化重试队列，签名随票落库后重启无需新票即可
  补最终化（`FinalityRestartFinalizationTest`）。
- PLAN-021 §2.2 的“绑定校验 = 注册表 + ACTIVE + 公钥一致”在本切片后扩展为
  “注册表 + ACTIVE + 公钥一致 + **单票签名有效**”；收敛路径与提交路径继续
  共用同一入口。
- 本切片 §六 的第一条遗留项已由 [PLAN-066](PLAN-066-finality-vote-rejection-metrics.md)
  交付（2026-11-02），其 §七 反向引用了本条。
