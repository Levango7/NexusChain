# 方案 PLAN-079：outbox 真实 PG 复核 + legacy `origin` 大小写静默删除

- **状态**：已交付（2026-11-05）
- **前置**：PLAN-072（H2 上确认 outbox 脏行不传播）、PLAN-073/074（outbox 可观测性与本地链路）
- **范围**：真实 PG 复核 PLAN-072 结论；修复 legacy/手工 `origin` 大小写导致
  `removePendingVote` / `markPendingVoteFailure` 静默 0 行的问题
- **不包含**：重试策略/attempts 上限/自动清理、`submitVote` 拒绝语义、
  生产库数据操作、物理多机

---

## 一、问题怎么被发现的

本次真实 PG 取证时，我第一次 seed 把 `finality_vote_outbox.origin` 写成大写
`'LOCAL'`（应用落库短码是小写 `local`），结果观察到：

- 补写路径把该行读出、`submitVote` 正常拒绝（哈希长度闸），日志打印
  "row persisted after restart"；
- 但行**没有被删掉**，每 ~5s 被重新处理一次（日志持续增长，144 行）——
  人工毒丸形态；
- 根因：`FinalityVoteOrigin.code()` 是 `local`/`received`；读取用
  `fromCode`（大小写不敏感），而 `removePendingVote` 的 WHERE 是
  `origin = ?` 精确等值 ⇒ 大写行为 0 行、**且不检查 affected rows**。

**重要边界**：应用自己写入的行永远是小写短码 ⇒ PLAN-072 的「脏行不传播」结论
不受影响；该缺口只在 legacy/手工/异常大小写数据上触发。这是"syzkaller 式"的
意外收获，不是夸大现有缺陷。

## 二、修复

`JdbcFinalityStateStore`：

- `removePendingVote` / `markPendingVoteFailure` 的 origin 匹配改为
  `LOWER(origin) = LOWER(?)`（PG/H2 一致）；
- 两条语句检查 affected rows，0 行时 `log.warn`（epoch/validator/origin），
  不再静默。

刻意不做：不改 `enqueuePendingVote` 的 UPSERT 匹配（PK 含 origin，同一逻辑键
两种大小写会成为两行，属另一议题；本切片只保证"存在即可清理"）。

## 三、验证

### 3.1 真实 PG 复核（同内核单机）

| 场景 | 结果 |
|---|---|
| 正确小写 `origin='local'` 的 3 字节哈希脏行 | 启动后**处理一次**（日志 3 行，无重复）、`submitVote` 拒绝、行被移除；`outbox=0 / dirty=0 / vote_rows=0 / dirty_votes=0` |
| 错误大写 `origin='LOCAL'`（修复前） | 行删不掉、每 ~5s 重试（**人工毒丸**，即本切片要消除的形态） |
| 修复后的大写 `origin='LOCAL'` 行 | 启动后**处理一次**（日志 3 行，无重复）后**被移除**；`outbox=0 / votes=0`（修复有效性在真实 PG 上复验） |

### 3.2 单测与反向对照

- `JdbcFinalityStateStoreTest` 新增 2 例：
  - `legacyUppercaseOriginRowIsRemovedCaseInsensitively`；
  - `legacyUppercaseOriginRowGetsFailureAttemptsIncremented`；
- 反向对照 A：删除查询改回 `origin = ?` ⇒ 第一例精确红
  （`expected: <0> but was: <1>`），同批次无其他失败；
- 反向对照 B：失败更新改回 `origin = ?` ⇒ 第二例精确红
  （`expected: <1> but was: <0>`）；
- 还原后 11/11 绿。

## 四、边界（勿夸大）

1. **同内核单机**：WSL2 PostgreSQL 16.15 + 单进程 core；非物理多机；
2. 触发源是 legacy/手工行；**不改变** PLAN-072 对应用写入行的结论；
3. `LOWER(origin)` 不再命中原 PK 索引；outbox 表常态极小（应用侧队列每 origin
   ≤256，库行短暂滞留），可接受；如未来行量增长需评估；
4. 未触碰 L1 / 桥资金 / wallet / settlement 契约；未做生产库数据操作。
