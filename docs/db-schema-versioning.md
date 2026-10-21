# DB Schema 版本化政策（批次⑥ 第 7 项 / PLAN-014 Phase 0）

- **状态**：已实施（2026-10-19）
- **背景**：`docs/audit/2026-10-19-nexus-core-narrative-reality-routes.md` §7 —— PG 侧 7 张表
  全部 `CREATE TABLE IF NOT EXISTS`、无版本记录；LevelDB 无 meta 版本键。旧代码可静默打开新库
  （反之亦然），破坏性 schema 变更无护栏。
- **实现**：`org.nexus.db.SchemaMeta.ensure(jdbc, store, version)` + LevelDB `__meta/schema_version` 键。

## 一、覆盖范围（store ↔ 表/组件 ↔ 当前版本）

| store | 组件 | 表 / 键 | 当前版本 |
|---|---|---|---|
| `payment_state` | `JdbcPaymentStateStore` | `payment_channels` / `stablecoin_positions` / `bridge_transactions` / `bridge_replay_keys` | 1 |
| `finality_state` | `JdbcFinalityStateStore` | `finality_votes`（PLAN-018 起含 `signature`/`public_key` 两列）/ `finality_checkpoints` / `finality_vote_outbox`（PLAN-018 新增） | 1 |
| `validator_set` | `ValidatorSetPersistence` | `validators_synced` | 1 |
| `l2_fraud_state` | `JdbcFraudProofStateStore` | `l2_fraud_batches` / `l2_fraud_bonds` / `l2_fraud_conflicts` | 1 |
| （LevelDB） | `org.nexus.db.Leveldb` | meta 键 `__meta/schema_version` | 1 |

登记表（PG/H2 共享）：

```sql
CREATE TABLE IF NOT EXISTS nexus_schema_meta (
  store      VARCHAR(64) PRIMARY KEY,
  version    INT        NOT NULL,
  updated_at BIGINT     NOT NULL   -- epoch millis
);
```

## 二、语义（fail-fast）

| 情形 | 行为 |
|---|---|
| 登记表 / store 记录缺失 | 以当前代码版本登记（**首版采纳既有库**；不经数据变更） |
| 版本相等 | 通过 |
| 数据库版本 **低于** 代码（`DATABASE IS OLDER`） | **拒绝启动**，提示先执行迁移脚本（本文件 §四） |
| 数据库版本 **高于** 代码（`DATABASE IS NEWER`） | **拒绝启动**（防旧代码开新库）；部署匹配版本 |
| LevelDB 版本键不可解析（损坏） | **拒绝启动**，报 `unreadable (corrupt meta key)` |

> 版本不匹配即异常（`SchemaMeta.MismatchException`）——**调用方不得吞没**。
> `ValidatorSetPersistence` 对连库失败的容忍语义保持不变，但版本不匹配会显式重抛。

## 三、迁移政策

1. **additive-only（默认）**：只加表 / 加列 / 加索引；不改语义、不删列。此类变更可直接
   发布，`CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 即幂等。
2. **破坏性变更**（改类型 / 删列 / 改键 / 数据重写）**必须**：
   - 新建迁移脚本 `migrations/pg/NNN-desc.sql`（NNN 三位递增）；
   - 递增对应 store 的 `SCHEMA_VERSION` 常量；
   - 更新本文件 §一 表格；
   - 迁移脚本**由运维在升级窗口手动执行**（应用启动不做自动 DDL 迁移）；
   - CHANGELOG 记录。
3. 应用启动只做**版本校验**，不做"自动升级"——避免多实例启动竞态下 DDL 交错执行。

> **PLAN-018 的 additive-only 变更说明**：`finality_votes` 增列 `signature`/`public_key`、
> 新增 `finality_vote_outbox` 表，均属 §三.1 的 additive-only ⇒ **版本号保持 1**、
> 不需要 `migrations/pg/` 脚本，幂等 DDL 在 `JdbcFinalityStateStore.init()` 里执行。
> 注意这与 §三.3「启动只做版本校验」并不矛盾：仓库既有做法本就是启动时执行
> `CREATE TABLE IF NOT EXISTS`（见 `ValidatorSetPersistence`），additive-only 的幂等 DDL
> 沿用同一路径；**破坏性变更仍然必须走脚本 + 升版本**。
> 幂等性由 `JdbcFinalityStateStoreTest.initIsIdempotentOnLegacySchema` 锁定
> （老库没有 `signature` 列时，`init()` 必须把它补齐，否则新代码写签名直接
> `BadSqlGrammarException`）。

## 四、运行手册（版本不匹配时）

1. 看到启动异常 `Schema version mismatch for store=...`：
   - `DATABASE IS OLDER` → 按 §三.2 顺序执行 `migrations/pg/` 中对应脚本，再重启应用；
   - `DATABASE IS NEWER` → 应用版本过旧，回滚/升级应用到匹配版本。
2. LevelDB 侧：`__meta/schema_version` 低于代码 → 破例变更需人工评估键空间兼容性；
   不要手工篡改 meta 键（除显式迁移流程）。
3. 查当前登记：`SELECT * FROM nexus_schema_meta;`

## 五、验收与测试

- `SchemaMetaTest`（H2）：缺失→写入 / 相等→通过 / 旧库→拒绝（带迁移指引）/ 新库→拒绝 / 多 store 隔离。
- `LeveldbSchemaVersionTest`：新库写键 / 同版本重启通过 / 旧值拒绝 / 新值拒绝 / 损坏值拒绝。
- `JdbcFraudProofStateStoreTest`（H2）：`l2_fraud_state` 版本登记 / 批次 JSON 往返 / upsert 幂等 /
  bond 状态更新 / 冲突嵌套证明往返 / 删除（Phase 4）。
