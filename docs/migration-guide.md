# NexusChain 数据库迁移指南

本指南记录 NexusChain 各版本的数据库迁移步骤、关键 migration 脚本说明及回滚方法。
迁移工具采用 Flyway，脚本位于 `nexus-gateway/src/main/resources/db/migration/`。
数据库：PostgreSQL（生产）/ H2（开发 / sandbox）。

## 当前版本 v2.27.0

### 迁移前置条件

- 已升级至 v2.26.0 并完成其全部 migration（V1–V11）。
- 数据库连接配置正确（`spring.datasource.*`）。
- Flyway 已启用（默认开启，`spring.flyway.enabled=true`）。
- 建议在迁移前对 `payment_orders` 表执行数据备份，确认无重复 `chain_tx_hash` 值（见下文 V12 预检）。

### 迁移步骤

1. **拉取 v2.27.0 代码**：`git checkout v2.27.0`（或合并对应分支）。
2. **编译验证**：`./gradlew assemble -x test`，确认 BUILD SUCCESSFUL。
3. **启动服务**：`./gradlew :nexus-gateway:bootRun`，Flyway 会在启动时自动检测并执行新增的 V12 migration。
4. **验证迁移结果**：
   - 查询 Flyway 历史表确认 V12 已执行：
     ```sql
     SELECT installed_rank, version, description, success
     FROM flyway_schema_history
     WHERE version = '12';
     ```
   - 确认约束存在（PostgreSQL）：
     ```sql
     SELECT conname FROM pg_constraint
     WHERE conname = 'uk_payment_orders_chain_tx_hash';
     ```
5. **回归测试**：执行 `./gradlew :nexus-gateway:test`，确认 PaymentFlowIntegrationTest 等用例通过。

### V12 migration 说明（chain_tx_hash 唯一约束）

**脚本**：`V12__chain_tx_hash_unique.sql`

**背景**：第三轮安全审计 P0-5 发现支付确认交易绑定存在唯一性漏洞。原实现未对 `payment_orders.chain_tx_hash` 施加唯一约束，攻击者可复用合法 txHash 确认多笔订单，或并发 `confirmPayment` 调用因竞态导致同一 txHash 双重绑定。

**变更内容**：

```sql
ALTER TABLE payment_orders
    ADD CONSTRAINT uk_payment_orders_chain_tx_hash UNIQUE (chain_tx_hash);
```

**关键语义**：

- `chain_tx_hash` 允许 NULL（尚未确认的订单为 NULL）。
- PostgreSQL / MySQL / H2 的 UNIQUE 约束均允许多个 NULL 值，未确认订单不受影响。
- 应用层在 `PaymentServiceImpl.confirmPayment` 中通过 `findByChainTxHash` 预检提供更友好的错误信息，DB 约束作为最终防线。

**迁移前预检（重要）**：

若历史数据中存在同一 `chain_tx_hash` 被多笔订单绑定的情况，添加唯一约束会失败。迁移前执行：

```sql
SELECT chain_tx_hash, COUNT(*) AS cnt
FROM payment_orders
WHERE chain_tx_hash IS NOT NULL
GROUP BY chain_tx_hash
HAVING COUNT(*) > 1;
```

- 若返回空集：可直接迁移，V12 会成功执行。
- 若返回非空：需先人工核对并清理重复绑定（保留正确订单，将其余订单的 `chain_tx_hash` 置 NULL 或标记为异常），再执行迁移。

### 回滚步骤

Flyway 不支持自动回滚，需手动执行以下步骤。

1. **停止服务**：确保无新请求写入 `payment_orders`。
2. **回滚 V12（删除唯一约束）**：
   ```sql
   ALTER TABLE payment_orders
       DROP CONSTRAINT IF EXISTS uk_payment_orders_chain_tx_hash;
   ```
3. **修正 Flyway 历史表**（标记 V12 为已回滚）：
   ```sql
   DELETE FROM flyway_schema_history WHERE version = '12';
   ```
4. **回退代码版本**：`git checkout v2.26.0`。
5. **启动服务**：确认服务以 v2.26.0 正常运行。

> **风险提示**：回滚 V12 会恢复 chain_tx_hash 唯一性漏洞（P0-5）。仅在紧急情况下执行，并尽快重新迁移至 v2.27.0 或更高版本。

## 迁移脚本清单

| 版本 | 脚本 | 说明 |
|------|------|------|
| V1 | `V1__init_schema.sql` | 初始 schema |
| V2 | `V2__performance_indexes.sql` | 性能索引 |
| V3 | `V3__orchestration_tables.sql` | 编排表 |
| V4 | `V4__request_id_unique.sql` | request_id 唯一约束 |
| V5 | `V5__risk_settlement_tables.sql` | 风控 / 清结算表 |
| V6 | `V6__refund_approval_tables.sql` | 退款审批表 |
| V7 | `V7__add_undo_log.sql` | undo_log（分布式事务回滚） |
| V8 | `V8__webhook_delivery_tables.sql` | Webhook 投递表 |
| V9 | `V9__subscription_engine_tables.sql` | 订阅引擎表 |
| V10 | `V10__tenant_tables.sql` | 多租户表 |
| V11 | `V11__shedlock.sql` | ShedLock 分布式锁表 |
| V12 | `V12__chain_tx_hash_unique.sql` | chain_tx_hash 唯一约束（v2.27.0 安全加固） |
| V18 | `V18__settlement_persistence.sql` | 清结算账务核心持久化三表（ledger_entry / clearing_order / settlement_record） |

## V18 迁移说明（清结算账务核心持久化，v2.41.x）

### 背景与目标

清结算账务核心此前为纯内存实现——`Ledger`（复式记账）、清算订单 PENDING staging、链上/银行对账记录（`InMemoryChainRecordSource` / `InMemoryBankRecordSource`）在进程重启后全部丢失，生产不可靠。V18 将其持久化为「JdbcTemplate（账本）+ JPA（清算订单/对账记录）」的混合方案：

- **重启不丢**：账本分录、PENDING 订单、对账记录全部落库。
- **跨实例可见**：结算调度器（ShedLock 单实例）drain 的 PENDING 批次来自数据库而非进程内存。
- **幂等防重**：账本以 `(reference, account)` 唯一键防止同一订单重复记账；对账记录以 `(reference, source)` 唯一键复刻内存去重语义。

### 变更内容

**脚本**：`V18__settlement_persistence.sql`（三表 DDL 一次建齐，MySQL / PostgreSQL / H2 MODE=MySQL 兼容）

| 表 | 管理方式 | 说明 |
|----|---------|------|
| `ledger_entry` | JdbcTemplate 手管（无 JPA 实体） | 复式记账分录：借/贷双行原子写入，`UNIQUE(reference, account)` 幂等；余额 = `SUM(CREDIT ? amount : -amount)` |
| `clearing_order` | JPA `@Entity`（`ClearingOrder`） | `order_id` 业务主键；PENDING 落库 → drain 删除 → settle 同键回写 SETTLED+`settlement_tx_hash` |
| `settlement_record` | JPA `@Entity`（`SettlementRecord`） | 新增 `source`（CHAIN/BANK）区分两类对账记录，`UNIQUE(reference, source)` |

**应用层配套改造**（双模式设计，既有纯单测零破坏）：

| 类 | 改造 |
|----|------|
| `org.nexus.settlement.clearing.Ledger` | 双构造器：`@Autowired(required=false)` 注入 JdbcTemplate/事务管理器 → DB 模式（TransactionTemplate 包借/贷双写）；无参构造 → 原内存语义 |
| `org.nexus.settlement.clearing.ClearingOrder` | 叠加 `@Entity/@Table`，保留 `@JsonProperty` 序列化与隐式无参构造 |
| `org.nexus.settlement.reconciliation.SettlementRecord` | 叠加 `@Entity`，新增 `id`/`source` 字段，保留既有构造器 |
| `ClearingOrderRepository` / `SettlementRecordRepository` | 新增 Spring Data JpaRepository（findByStatus / findBySource / findByReferenceAndSource / deleteBySource） |
| `org.nexus.gateway.settlement.SettlementEventCollector` | 双构造器：DB 模式 PENDING 落库、drain 查询+删除；无 repository 走内存 staging |
| `InMemoryChainRecordSource` / `InMemoryBankRecordSource` | 同 Bean 可选注入 repository（不新增 Bean，避免接口注入歧义），source=CHAIN/BANK |
| `org.nexus.settlement.clearing.DefaultClearingEngine` | `@Autowired(required=false)` 注入仓储，settle 终态（SETTLED/FAILED + settlementTxHash）回写 |
| `org.nexus.gateway.config.SettlementPersistenceConfig` | 新增：`@EntityScan`+`@EnableJpaRepositories` 显式扩至 settlement 两个包（composite build 库实体经 gateway classpath 装配） |

**Spring Boot 4.0 模块化适配**（本次迁移中发现并解决的兼容问题）：

| 组件 | Boot 3.x 包路径 | Boot 4.0 新路径 |
|------|----------------|----------------|
| `@JdbcTest` | `o.s.boot.test.autoconfigure.jdbc` | `o.s.boot.jdbc.test.autoconfigure` |
| `@DataJpaTest` | `o.s.boot.test.autoconfigure.orm.jpa` | `o.s.boot.data.jpa.test.autoconfigure`（且不再支持 `classes` 属性） |
| `TestEntityManager` | `o.s.boot.test.autoconfigure.orm.jpa` | `o.s.boot.jpa.test.autoconfigure` |
| `@EntityScan` | `o.s.boot.autoconfigure.domain` | `o.s.boot.persistence.autoconfigure` |
| 测试依赖 | 随 starter-test 提供 | 需显式 `spring-boot-jdbc-test` / `spring-boot-jpa-test` / `spring-boot-data-jpa-test` |

### 迁移前置条件

- 已完成 V1–V17 全部 migration。
- `spring.jpa.hibernate.ddl-auto=validate`（生产默认）：V18 三表 DDL 与实体映射逐列对齐，由 `SettlementPersistenceSchemaValidateIT`（H2 + 生产 V18 脚本 + validate）作为 CI 防线拦截漂移。
- dev/test 环境无需额外操作：test profile 的 `create-drop` 自建表；dev 的 H2 `MODE=MySQL` + `update` 兼容 V18 脚本。

### 验证步骤

1. **启动验证**：Flyway 自动执行 V18 后查三表存在：
   ```sql
   SELECT version, success FROM flyway_schema_history WHERE version = '18';
   ```
2. **回归测试**：
   ```bash
   ./gradlew --no-daemon --no-parallel --max-workers=1 :nexus-settlement:test :nexus-gateway:test
   ```
   两模块全绿（settlement 159 用例含 12 个新增 H2 集成测试；gateway 全量含 SchemaValidateIT/WiringIT 两个防线）。
3. **运行时行为验证**：支付成功 → SettlementEventCollector 落 PENDING（查 `clearing_order` where status='PENDING'）→ 结算调度器 drain → settle 回写 SETTLED+`settlement_tx_hash`，同时 `ledger_entry` 出现借/贷双行、`settlement_record` 出现 source=CHAIN 回填记录。

### 已知注意事项

- **`@EnableJpaRepositories` 显式声明的退避效应**：`SettlementPersistenceConfig` 一旦显式声明仓储扫描包，Spring Data 自动配置整体退避——必须覆盖 gateway 全部仓储包（现以 `org.nexus.gateway` 全包扫描）。新增顶层包下的仓储时无需改动；若未来 gateway 出现 `org.nexus.*` 之外的仓储包，需同步扩展该 config。
- **`SettlementScheduler.drainStaging` 的取出语义**：DB 模式为「查询 PENDING + 删除」，与内存模式「快照 + 清空」语义一致；结算失败的订单不重入 staging（与内存实现行为一致），由对账报告暴露差错。
- **`ledger_entry` 无 JPA 实体**：其结构正确性由 `LedgerJdbcTest`（H2 真实落库）与 V18 脚本共同保证，`validate` 不覆盖此表。

### 回滚步骤

Flyway 不支持自动回滚，手动执行：

1. 停止 gateway 实例（确保无新 PENDING 写入）。
2. 若需保留账务数据供审计，先导出三表备份。
3. 删除三表并修正历史：
   ```sql
   DROP TABLE IF EXISTS ledger_entry;
   DROP TABLE IF EXISTS clearing_order;
   DROP TABLE IF EXISTS settlement_record;
   DELETE FROM flyway_schema_history WHERE version = '18';
   ```
4. 回退代码版本，应用自动回退到内存模式（无 JdbcTemplate/Repository 注入时双模式设计走内存路径）。

## 注意事项

- Flyway migration 脚本一经发布不可修改；如需修正，新增更高版本号的脚本。
- 生产环境迁移前务必执行 V12 预检并备份数据。
- H2（开发 / sandbox）与 PostgreSQL 语法兼容性已在 V12 脚本中验证（UNIQUE 约束 NULL 语义一致）。