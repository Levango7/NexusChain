# 方案 PLAN-069：最终性历史脏行清理

- **状态**：已交付（2026-11-03）
- **前置**：PLAN-067（32 字节检查点哈希强校验，#407）、PLAN-068（抓取端可见性，
  提供真实 PG 实证手段，#413）、`docs/db-schema-versioning.md`（迁移政策）
- **范围**：`migrations/pg/` 首支脚本（脏行隔离 + 可回滚 + 审计列）、
  「脚本口径 == 生产口径」的契约门禁、Gradle 测试输入登记、文档与 CHANGELOG
- **不包含**：在生产/预发库执行、自动清洗、`finality_checkpoints` 清理（**已由
  PLAN-071 补足**）、
  通用数据保留策略（retention）、物理多机、真实集群

---

## 一、问题（为什么必须做）

PLAN-067 的交付文档在「边界（勿引用为更强结论）」里留下一条明确的欠账：

> **不清洗历史短哈希行**：恢复时丢弃、不建索引、不占新键配额，但数据仍在库里，
> 清理需要单独迁移脚本与审计窗口。

PLAN-068 的交接留言又重复登记了一次。把它具体化为可验证的事实：

### 1.1 生产校验已生效，数据层从未被清理

`FinalityGadget.hasValidCheckpointHash()` 要求检查点哈希必须是
`Block.HASH_SIZE = 32` 字节，`admission` / `convergence` / `restore` 三路
fail-closed。但**库里的历史行原样躺着**。本机实测（PLAN-068 期间预置）：

```
 epoch | checkpoint_hash | hexlen |      validator
-------+-----------------+--------+-----------------------
     1 | 010203          |      6 | p068-probe-validator
```

`hexlen=6` ⇒ 3 字节；协议要求 64 个 hex 字符（32 字节）。这条行至今仍在库里。

### 1.2 这些行的实际代价

PLAN-067 已把它们的影响压到"无害但不干净"：

- 收敛路径每 5 秒重扫一次，靠 `(key, validator, reason)` 去重才没让指标虚增；
- 恢复路径丢弃、不建索引（`hasValidCheckpointKey` 守卫）、不占 `K=4` 配额。

但**表仍在增长、磁盘与备份仍在承担**，且 `finality_votes` 没有任何清理机制。
"不参与逻辑"不等于"应该永远留在生产表里"。

### 1.3 迁移机制只有文字，没有第一支脚本

`docs/db-schema-versioning.md` §三.2 规定：破坏性变更（含**数据重写**）必须

1. 新建 `migrations/pg/NNN-desc.sql`（NNN 三位递增）；
2. 递增对应 store 的 `SCHEMA_VERSION`；
3. 更新该文档 §一 表格；
4. 由**运维在升级窗口手动执行**；
5. CHANGELOG 记录。

而 `migrations/pg/` **在仓库里根本不存在**（`Test-Path migrations` = False）。
这条政策自 2026-10-19 落地以来从未被执行过——本切片是第一支脚本。

## 二、设计与取舍

### 2.1 隔离而不是删除（关键决策）

直接 `DELETE` 是**不可回滚**的。本切片把待清理的行**原样搬运**到新表
`finality_votes_quarantine` 之后，才从 `finality_votes` 移除：

| 维度 | 直接 DELETE | 隔离（本方案） |
|---|---|---|
| 生产逻辑是否还看到脏行 | 否 | 否（等效） |
| 能否原样恢复 | **不能** | **能**（一条 INSERT ... SELECT） |
| 审计可查 | 否 | 是（`reason` / `quarantined_at` / `quarantined_by` / `migration_id`） |
| 是否需要先备份整个库 | 是 | 否 |

隔离表是 **additive-only**（`CREATE TABLE IF NOT EXISTS`），符合 §三.1；
但它承载的是**数据搬运**，属 §三.2 的破坏性变更 ⇒ 仍必须走脚本 + 运维手工执行，
**不**在应用启动时自动跑。

### 2.2 判定口径必须与生产逐字一致

这是本切片最容易做错、也最危险的地方。口径不一致是**双向**破坏：

- 脚本**更宽**（如只查 `length < 64`）⇒ 漏掉 65 位与非 hex 的行，
  声称"清完了"而库里仍有脏行；
- 脚本**更严**（如只查 `= 3`）⇒ 漏掉其他畸形行，同样不干净；
- 脚本判定与生产**不同源** ⇒ 把生产认为合法的行搬走 ⇒ 真实投票记录消失。

生产侧的两道事实：

1. `FinalityGadget.hasValidCheckpointHash(h)` ⇒ `h != null && h.length == Block.HASH_SIZE`；
2. `JdbcFinalityStateStore.hashStr()` 写入用 `HexFormat.of().formatHex(bytes)`
   ⇒ 32 字节恰好是 **64 个小写 hex 字符**；读回用 `hexToBytes()`，
   非 hex 会解成 `null` ⇒ 后续校验必然失败。

⇒ 一条库行合法 ⟺ **长度为 64 且全为 hex 字符**。SQL 侧：

```sql
length(checkpoint_hash) = 64 AND checkpoint_hash ~ '^[0-9a-fA-F]{64}$'
```

大小写都接受：`HexFormat.parseHex` 不区分大小写，故 Java 侧也接受大写。

### 2.3 脚本结构（先审计、再搬运、后删除、最后自检）

```
§0  建隔离表（幂等，含四列审计）
§1  审计（只读）——按行列出 checkpoint_hash、hex_len、分类
§2  搬运脏行 → 隔离表（ON CONFLICT DO NOTHING，幂等）
§3  从 finality_votes 移除（带 EXISTS 隔离表双保险）
§4  自检（只读）——remaining_invalid 必须为 0
```

§3 的 `EXISTS` 不是冗余：若 §2 因任何原因少搬了一行，§3 就不会删它
——**宁可留下脏行，也不静默丢数据**。

### 2.4 回滚是真的回滚

`001-...rollback.sql` 把隔离行按 `migration_id = 'PLAN-069/001'` 搬回
`finality_votes`，且**不删除隔离表**（审计痕迹必须留）。

搬回是**安全**的：这些行会被生产侧重新判定为脏行（`hasValidCheckpointHash`
仍拒绝），只是恢复到"脏行留在生产表里"的原始状态。要彻底抹掉审计痕迹是
另一个需要显式决策的动作，不放在回滚路径里。

## 三、验证

### 3.1 端到端演练（本机 WSL2 PostgreSQL 16.15，独立库）

在独立库 `nexus_p069_rehearsal` 上（不碰 `nexus_p068`）：

| 阶段 | 结果 |
|---|---|
| 造 2 条合法 + 4 条脏行（3 字节 / 63 位 / 65 位 / 64 位非 hex） | 6 行 |
| 正向迁移第 1 次 | 剩 2 行，隔离 4 行 |
| 正向迁移第 2 次（幂等） | **仍**剩 2 行、隔离 4 行，不报错 |
| 隔离表内容 | 4 行各自正确归类：`checkpoint_hash_len_6` / `_len_63` / `_len_65` / `_not_hex` |
| 回滚 | 6 行全部恢复，隔离表仍保留 4 行 |
| 断言 | `PASS votes_restored_6`、`PASS legal_rows_untouched_2`、`PASS quarantine_retained_4` |

脚本：`C:\Users\winge\AppData\Local\Temp\opencode\p069-rehearsal.sh`（仓库外）。

### 3.2 契约门禁（`FinalityDirtyRowMigrationContractTest`，6 例）

| 用例 | 钉住什么 |
|---|---|
| `javaAndSqlPredicatesAgreeOnAllSamples` | 8 个样本（合法小写/大写、3 字节、63、65、非 hex、空、混合）上 Java 口径与 SQL 口径结论**逐个相同** |
| `everyPredicateChecksBothLengthAndCharset` | **逐语句**校验 §2 INSERT / §3 DELETE / §4 自检都同时查长度与字符集，且不得退化成 `< 64` |
| `hashSizeAndSqlLiteralStayInSync` | `Block.HASH_SIZE * 2 == 64`（改了常量就必须同步改脚本） |
| `forwardScriptQuarantinesBeforeDeleting` | INSERT 必须在 DELETE **之前**；DELETE 必须带按三列主键匹配的 EXISTS 双保险 |
| `rollbackRestoresRowsAndKeepsAuditTrail` | 回滚搬回行、只按 `migration_id` 收窄、**不得** `DROP TABLE` 或清空隔离表 |
| `quarantineTableCarriesAuditColumns` | 四列审计列齐备，`migration_id` NOT NULL |

### 3.3 反向对照（5 组，全部精确变红）

| 组 | 变异 | 结果 |
|---|---|---|
| A | §3 DELETE 判定 `= 64` → `< 64` | `everyPredicateChecksBothLengthAndCharset` 红 |
| B | §3 DELETE 去掉 hex 字符集检查 | 同上红 |
| C | §3 DELETE 去掉 EXISTS 双保险 | `forwardScriptQuarantinesBeforeDeleting` 红 |
| D | 回滚脚本加 `DROP TABLE` | `rollbackRestoresRowsAndKeepsAuditTrail` 红 |
| E | 调换 §2/§3 顺序（先删后隔离） | `forwardScriptQuarantinesBeforeDeleting` 红 |

还原后两文件 SHA-256 与变异前逐字一致：

```
forward   cee3ee4a176536277b61d02605b1210d7395067f68215cb0b4de4c1ddcb17ff6
rollback  994994c888260ba4bfec0f8be755f2fefae26272cd99389ea3b8f6cad4570b28
```

#### 反向对照抓到的两个**真实缺陷**（写在这里，因为它们改变了实现）

1. **断言被别处文本蒙混过关**：最初把"长度 = 64 + hex 字符集"写在对**整份 SQL**
   的 `contains()` 上。§1 审计段、§2 搬运段、§4 自检段都有同样的文本，
   所以只改坏 §3 DELETE 时断言仍然为真——**门禁形同虚设**。
   修法：改为 `statement(...)` 截取单条语句后逐语句断言（变异 A/B 现在精确命中）。
2. **DELETE 的 EXISTS 检查也是整份 SQL 级**：`sql.substring(deleteAt).contains("finality_votes_quarantine")`
   仍能在前文命中。修法：截到分号，并额外要求三列主键都参与匹配
   （只按部分列会误删同 epoch 其他验证人的行）。

### 3.4 Gradle 测试输入（第三个真实缺陷）

`FinalityDirtyRowMigrationContractTest` 读的是仓库根的 `migrations/pg/*.sql`，
它**不在 test 任务的默认输入里**。实测：改坏 SQL 后跑测试，Gradle 判定
`UP-TO-DATE` 直接跳过，输出 `BUILD SUCCESSFUL in 3s`——本地"绿"，而门禁**从未执行**。

这正是 `build.gradle` 里 PLAN-062 注释描述的同一型缺陷（规则文件不在输入里）。
修法照同一路径：

```groovy
inputs.dir("${rootDir}/migrations/pg")
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("pgMigrations")
```

验证：改 SQL → 不再 UP-TO-DATE → 变异被抓（红）；还原 → 绿。

## 四、边界（勿引用为更强结论）

1. **未在生产/预发库执行**：所有演练都在本机 WSL2 PostgreSQL 16.15 的独立库上；
   真实执行由运维按 §三.2 的升级窗口流程进行，本切片只交付脚本与门禁；
2. **不做自动清洗**：`docs/db-schema-versioning.md` §三.3 明确"应用启动只做版本校验，
   不做自动升级"，本切片不破坏这条。脚本必须人造、人审、人执行；
3. **同内核单机**：WSL2 里的 PG 与 Windows 侧的 Java 同属一台物理机，
   **不是物理多机**，不构成分布式/集群结论；
4. **~~不处理 `finality_checkpoints`~~ → 已由 PLAN-071 处理**：本切片当时只
   **报告**该表脏行数（`invalid_in_checkpoints`）不搬运，理由是「来源与语义需确认」。
   PLAN-071 确认结果：该表脏行经 `FinalityGadget.restoreFromStore` 的「已终局分支」
   （该分支不做长度校验）进入 `epochCheckpointWeights`，造成「库里写着已终局、
   `isFinalized()` 却返回 false」的长期视图不一致；现已由同一脚本 `001`
   搬进 `finality_checkpoints_quarantine`。详见
   `docs/plan/PLAN-071-checkpoint-quarantine.md`；
5. **不做通用 retention**：只处理"格式非法"的行，不处理"合法但过旧"的行；
6. **回滚不抹审计**：隔离表在回滚后仍然保留，这是有意的；
7. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 五、执行手册（给运维）

```bash
# 1) 预览：只看 §1 审计段，确认真实环境中要动多少行、分别是什么分类
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 \
     -f migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.sql

# 2) 备份（即使有隔离表，正式环境仍应先备份 finality_votes）

# 3) 执行并确认 §4 输出 remaining_invalid = 0

# 4) 需要回滚时
psql -h <host> -U <user> -d <db> -v ON_ERROR_STOP=1 \
     -f migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.rollback.sql
```

## 六、后续

- ~~`finality_checkpoints` 脏行的语义确认与清理~~ → **已交付 PLAN-071**（2026-11-03）：
  语义已确认（restoreFromStore 已终局分支无条件入 weights ⇒ DB/内存视图不一致），
  清理已并入同一支脚本 `001`（独立隔离表 `finality_checkpoints_quarantine`）；
- 通用 retention（合法但过旧的票）——需产品定义保留窗口；
- 把"迁移脚本已演练"接入 CI（当前是本地演练 + 契约门禁，未在 CI 里起 PG 跑脚本）；
- roadmap #27 的其余项：epoch 窗口/规范检查点语义（阻塞于链接口）、
  真实集群告警演练、吞吐/基数基准、物理多机矩阵。
