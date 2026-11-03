# 方案 PLAN-071：`finality_checkpoints` 脏行隔离

- **状态**：已交付（2026-11-03）
- **前置**：PLAN-067（32 字节哈希强校验，#407）、PLAN-069（`migrations/pg/` 首支脚本，
  #418）、`docs/db-schema-versioning.md`（迁移政策）
- **范围**：`migrations/pg/001` 扩展到第二张表（正向 + 回滚 + 契约门禁 + 端到端演练）
- **不包含**：给 `markFinalized` 加运行时防御、自动清洗、`finality_vote_outbox`、
  retention、真实集群、物理多机

---

## 一、这是 PLAN-069 明确留下的那一刀

PLAN-069 交接留言 §八 原文：

> `finality_checkpoints` 脏行的语义确认与清理；

PLAN-069 当时只**报告**该表脏行数（脚本 §4 的 `invalid_in_checkpoints`），
不搬运，理由是「该表脏行的来源与语义需另行确认」。本切片把确认做完了，
结论是**确实该清**。

## 二、取证（三步，都有实测）

### 2.1 写入侧没有闸门

`JdbcFinalityStateStore.markFinalized(epoch, byte[])` 直接 `hashStr()` 落库，
不校验长度。H2 探针实测（跑完已删除）：

```
3 字节 后: {epoch=1, hex=010203, len=6,  validLen64=false}
0 字节 后: {epoch=2, hex=,       len=0,  validLen64=false}
32 字节后: {epoch=3, hex=0000…,  len=64, validLen64=true}
```

### 2.2 生产路径确实能到达（这一步我第一次取证漏过）

`markFinalized` 的唯一生产调用点是 `FinalityGadget:891`，位于私有 `tryFinalize()`。
`tryFinalize` 有 5 个调用点，**4 个都过了 32 字节校验**：

| 调用点 | 是否已校验 |
|---|---|
| `submitVote`（L752 / L780 / L831） | ✅ `validateVoteBinding` 在 L742 先拦 |
| 收敛路径（L666） | ✅ 建键前已校验（PLAN-067） |
| **`restoreFromStore` → `sweepFinalization`（L954）** | **❌ 见下** |

`restoreFromStore()` 的「已终局分支」（L284-307）：

```java
stateStore.loadAllFinalized().keySet().forEach(finalizedCheckpoints::add);  // 无长度校验
stateStore.loadAllVotes().forEach((key, voters) -> {
    if (!finalizedCheckpoints.contains(key)) return;
    epochCheckpointVoters.put(key, restored);                     // L291 无条件
    if (hasValidCheckpointKey(key)) { indexCheckpointKey(...); }   // 只挡"建索引"
    epochCheckpointWeights.put(key, sum);                         // L306 无条件
});
sweepFinalization("startup-restore");                             // L369 立刻 sweep
```

`hasValidCheckpointKey` 只挡住了**索引**，没挡住**进集合**。
脏键因此进入 `epochCheckpointWeights`，被 `sweepFinalization` 遍历，
最终可能再次调用 `markFinalized`。

> **我第一次取证时的错误**：只检查了 `submitVote` / 收敛 / sweep 三条路径，
> 看 `sweepFinalization` 遍历的集合并未意识到其中一类键**从未经过校验**，
> 于是得出"生产路径到不了"的错误结论。补做 gadget 级实测后才纠正。
> 教训：不要把"多数路径安全"当成"所有路径安全"——要看**键是怎么进来的**。

### 2.3 后果：DB 与内存视图长期不一致（实测）

同一份库，接上真实 `FinalityGadget` 构造（内部走 restore + sweep），对照实验：

| 初始状态 | `gadget.isFinalized(9, cp)` | `finality_checkpoints` 表内容 |
|---|---|---|
| 32 字节已终局（干净） | `true` | 不变 |
| 3 字节已终局（脏） | **`false`** | **`{epoch=9, hex='010203', len=6}` 仍在** |

⇒ 库里明确写着「已终局」，内存视图却报「未终局」，**且脏行不会被任何路径清掉**
（每次重启都把同一行重新载入）。

## 三、边界（必须说清，否则会夸大）

**脏行不能由外部攻击者通过网络制造。** `submitVote` 与收敛路径都在建键前
`hasValidCheckpointHash` fail-closed（PLAN-067 已证）。本切片的脏行只能来自：

1. PLAN-067 之前的历史库（迁移前遗留）；
2. 手工 / 脚本直接写库。

⇒ 这是**历史污染 + 视图不一致**问题，**不是可达攻击面**，优先级 P2。

## 四、设计取舍

### 4.1 为什么给 checkpoints 单独一张隔离表

`finality_checkpoints` 的主键是 `(epoch, checkpoint_hash)`，**没有 `validator` 列**。
硬塞进 `finality_votes_quarantine` 就得给 `validator` 编一个哨兵值（空串/占位符）
—— **审计表一旦说谎，事后就无法可靠反查**。

所以每张源表配一张**同构**隔离表：

| 源表 | 隔离表 | 主键 |
|---|---|---|
| `finality_votes` | `finality_votes_quarantine` | `(epoch, checkpoint_hash, validator)` |
| `finality_checkpoints` | `finality_checkpoints_quarantine` | `(epoch, checkpoint_hash)` |

"搬回哪张表"由**表身份**决定，不靠判别列（判别列自己也会漂）。
审计列完全一致（`reason` / `quarantined_at` / `quarantined_by` / `migration_id`），
查"001 这次动了什么"用 `UNION ALL` 即可。

### 4.2 为什么修订 `001` 而不是新开 `002`

1. **`001` 是数据脚本，不受迁移棘轮约束**：已核验
   `scripts/check-migration-version-ratchet.py` 只认 `<模块>/db/migration/V<n>__*.sql`
   （实测识别 115 支 / 5 个目录，**不含** `migrations/pg/`），修订不会触发门禁；
2. **`001` 从未在任何真实环境执行过**（2026-11-03 才合入，运维尚未按 runbook 跑），
   修订不造成「已执行脚本被改」的漂移；
3. **语义上是同一件事**：`001` 的 §4 自检字段 `invalid_in_checkpoints` 早就把
   这个缺口写进脚本，本次只是把「只报告」补成「也隔离」。

> **约束（写进这里以免将来犯错）**：若 `001` 已在任何真实环境执行过，
> 后继任何变更**必须新开编号**（`002-...`），不得再修订 `001`。

### 4.3 脚本结构（结构不变，多了一组 b 段）

```
§0   建 votes 隔离表
§0b  建 checkpoints 隔离表          ← PLAN-071
§1   审计 votes（只读）
§1b  审计 checkpoints（只读）        ← PLAN-071
§2   搬运 votes 脏行
§2b  搬运 checkpoints 脏行          ← PLAN-071
§3   移除 votes 脏行（EXISTS 双保险）
§3b  移除 checkpoints 脏行（EXISTS）← PLAN-071
§4   自检（含 invalid_in_checkpoints，PLAN-071 起期望为 0）
```

两处判定都用**同一套口径**（与生产 `hasValidCheckpointHash` 逐字对应）：

```sql
length(checkpoint_hash) = 64 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$'
```

`migration_id` 区分来源：votes 用 `PLAN-069/001`，checkpoints 用 `PLAN-071/001`
—— 回滚据此分别收窄，不会把 checkpoints 的行搬回 votes（列都对不上）。

## 五、验证

### 5.1 端到端演练（WSL2 PostgreSQL 16.15，独立库）

造 4 类行：

| 表 | 合法 | 脏行 |
|---|---|---|
| `finality_votes` | 1 条（64 位小写 hex） | 3 条（3 字节 / 65 位 / 非 hex） |
| `finality_checkpoints` | 1 条 | 2 条（3 字节 / 63 位） |

| 阶段 | votes | votes 隔离 | cps | cps 隔离 |
|---|---|---|---|---|
| 演练前 | 4 | — | 3 | — |
| 正向迁移第 1 次 | **1** | 3 | **1** | 2 |
| 正向迁移第 2 次（幂等） | **1** | 3 | **1** | 2 |

- checkpoints 隔离行分类正确：`checkpoint_hash_len_6` / `checkpoint_hash_len_63`，
  `migration_id = PLAN-071/001`；
- 回滚后：votes 恢复 4、cps 恢复 3，两张隔离表**都保留**（审计痕迹）；
- 断言 `PASS cps_restored_3` / `votes_restored_4` / `cp_quarantine_retained_2` /
  `vote_quarantine_retained_3` / `cp_legal_untouched_1`。

脚本：`C:\Users\winge\AppData\Local\Temp\opencode\p071-rehearsal.sh`（仓库外）。

### 5.2 契约门禁（`FinalityDirtyRowMigrationContractTest` 6 → **8 例**）

新增两例：

| 用例 | 钉住什么 |
|---|---|
| `checkpointsAreQuarantinedToo` | 独立隔离表存在且主键同构；§2b/§3b **各自**都同时校验长度与字符集、不得退化成 `< 64`；先搬后删；DELETE 带 EXISTS 双保险；`ON CONFLICT (epoch, checkpoint_hash)` 幂等；带独立 `migration_id` |
| `rollbackRestoresEachTableBySource` | 回滚把 checkpoints 搬回 `finality_checkpoints`（**不得**写进 votes，该表有 validator 列会错位）；按 `migration_id` 收窄；不删/不 DROP 两隔离表 |

### 5.3 反向对照（4 组，全部精确命中）

| 组 | 变异 | 实测 |
|---|---|---|
| A | checkpoints 的 DELETE 判定 `= 64` → `< 64` | `checkpointsAreQuarantinedToo` 红（报错直指 `length(c.checkpoint_hash) < 64`） |
| B | checkpoints 的 DELETE 去掉 EXISTS 双保险 | 同上用例红（报错直指缺 EXISTS） |
| C | 回滚把 checkpoints 行搬回 votes 表 | `rollbackRestoresEachTableBySource` 红 |
| D | checkpoints 的 `migration_id` 塌缩成 `PLAN-069/001` | `checkpointsAreQuarantinedToo` 红（"必须带独立 migration_id"） |

还原后两文件 SHA-256 与变异前逐字一致：

```
fwd  c835bb9acf59893f4647e81f022f3171ab6d9943852ab8c1f4aa662c470ba630
rb   d02b5243848d7e43db222ecd8447a8bec5f1d9a5c712a9fefa8b1ed0be2eb54a
```

### 5.4 回归

- `org.nexus.consensus.finality.*` + `org.nexus.metrics.*`：全绿
- `nexus-core` 全量 `--rerun-tasks`：见交付留言
- SpotBugs / Code Hygiene：同 SAST 口径

## 六、边界（勿引用为更强结论）

1. **未在生产/预发库执行**：全部演练在本机 WSL2 PG 独立库；真实执行由运维按
   `docs/db-schema-versioning.md` §三.2 的升级窗口流程进行；
2. **不为 `markFinalized` 加运行时防御**：那会改变 `JdbcFinalityStateStore` 的
   存储层契约，且现有 5 处测试刻意用 3 字节哈希测「任意字节可存取」——
   是另一个需要单独决策的改动。本切片只清数据、不改行为；
3. **不做自动清洗**：脚本必须人造、人审、人执行；
4. ~~**不处理 `finality_vote_outbox`**~~ → **已由 PLAN-072 确认无需处理**（2026-11-03）：
   outbox 写入侧同样不校验长度（脏票可入队），但重放走 `submitVote` →
   `validateVoteBinding` 被**正常拒绝**（不抛异常），而 `tryFlush` 只在抛异常时才
   留着重试 ⇒ 脏票被移除、不沉淀、不变毒丸。详见
   `docs/plan/PLAN-072-outbox-dirty-vote-containment.md`；
5. **同内核单机**：非物理多机，不构成分布式/集群结论；
6. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 七、后续

- ~~`finality_vote_outbox` 的脏行传播性确认~~ → **已交付 PLAN-072**（2026-11-03）：
  结论是**不传播、无需清理**（重放时 `submitVote` 正常拒绝并移除，不沉淀、非毒丸），
  已固化为 `FinalityOutboxDirtyVoteContainmentTest`；
- `markFinalized` 是否加防御性校验——需与存储层「任意字节可存取」的测试契约
  一并决策；
- 把「迁移脚本已演练」接入 CI（当前是本地演练 + 契约门禁）；
- roadmap #27 其余项：epoch 窗口/规范检查点语义（阻塞于链接口）、
  真实集群告警演练、Prometheus 服务端成本、物理多机矩阵。
