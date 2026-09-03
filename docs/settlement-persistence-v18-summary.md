# 清结算账务核心持久化落地 — 变更摘要

> **迁移编号**：V18（`V18__settlement_persistence.sql`）
> **日期**：2026-09-03
> **影响模块**：nexus-settlement（composite build 库）、nexus-gateway（组合根）
> **验证结果**：`:nexus-settlement:test`（159 用例）+ `:nexus-gateway:test` 全量 BUILD SUCCESSFUL
> **回滚策略**：drop 三表 + 清 Flyway 历史 + 回退代码 → 双模式设计自动降级内存运行

---

## 一、迁移目标

解决清结算账务核心**纯内存实现的三大生产风险**：

| 风险 | 迁移前 | 迁移后 |
|------|--------|--------|
| 重启丢数据 | Ledger 复式分录 / PENDING 订单 / 对账记录全部丢失 | 全部落库持久化 |
| 跨实例不可见 | 结算调度器只能看本进程 staging | 数据库共享，ShedLock 单实例 drain |
| 无幂等防线 | 重复事件可重复记账 | DB 唯一键兜底，借/贷原子回滚 |

## 二、数据库变更

**脚本**：`nexus-gateway/src/main/resources/db/migration/V18__settlement_persistence.sql`（三表一次建齐，MySQL / PostgreSQL / H2 MODE=MySQL 兼容）

| 表 | 管理方式 | 关键设计 |
|----|---------|---------|
| `ledger_entry` | JdbcTemplate 手管（无 JPA 实体） | `UNIQUE(reference, account)` 幂等防重记账；借/贷双行同一 `TransactionTemplate` 事务原子提交；余额 = `SUM(CREDIT ? amount : -amount)` |
| `clearing_order` | JPA `@Entity`（ClearingOrder） | `order_id` 业务主键：PENDING 落库 → drain 删除 → settle 同键回写 SETTLED + `settlement_tx_hash` |
| `settlement_record` | JPA `@Entity`（SettlementRecord） | 新增 `source`（CHAIN/BANK）双源共表；`UNIQUE(reference, source)` 复刻内存按 reference 去重语义 |

## 三、代码变更

### nexus-settlement（6 个文件）

| 类 | 变更类型 | 说明 |
|----|---------|------|
| `clearing.Ledger` | 修改 | 双构造器：`@Autowired(required=false)` 注入 JdbcTemplate + 事务管理器 → DB 模式；无参构造保留原内存语义（**既有单测零改动**） |
| `clearing.ClearingOrder` | 修改 | 叠加 `@Entity/@Table` 与逐列映射；保留 `@JsonProperty` 序列化与隐式无参构造 |
| `clearing.ClearingOrderRepository` | 新增 | JpaRepository：`findByStatus`（drain 取批） |
| `reconciliation.SettlementRecord` | 修改 | 叠加 `@Entity`，新增 `id`（IDENTITY）/`source` 字段；保留无参/四参构造器 |
| `reconciliation.SettlementRecordRepository` | 新增 | JpaRepository：`findBySource` / `findByReferenceAndSource` / `deleteBySource` |
| `clearing.DefaultClearingEngine` | 修改 | `@Autowired(required=false)` 注入仓储；settle 终态（SETTLED/FAILED + txHash）回写落库，失败仅 WARN 不阻断 |

### nexus-gateway（2 个文件 + 依赖）

| 类 | 变更类型 | 说明 |
|----|---------|------|
| `settlement.SettlementEventCollector` | 修改 | 双构造器：DB 模式 `save(PENDING)` + drain 查询删除；无仓储走内存 staging |
| `config.SettlementPersistenceConfig` | 新增 | `@EntityScan` + `@EnableJpaRepositories` 显式扩至 settlement 两包（composite build 库实体经同 JVM classpath 装配） |
| `build.gradle`（两个模块） | 修改 | 补 `spring-boot-jdbc-test` / `spring-boot-jpa-test` / `spring-boot-data-jpa-test`（Boot 4.0 模块化拆分） |

### nexus-settlement test 基座（1 个）

| 类 | 变更类型 | 说明 |
|----|---------|------|
| `SettlementTestConfiguration` | 新增 | test 源码集 `@SpringBootConfiguration` + `@EnableAutoConfiguration` 切片锚点（库模块无启动类） |

## 四、测试变更（新增 13 用例 + 2 防线）

| 测试 | 类型 | 数量 | 防什么 |
|------|------|------|--------|
| `LedgerJdbcTest` | @JdbcTest + H2 | 5 | 借/贷原子性、balanceOf SQL 聚合、entriesOf 顺序、`(reference, account)` 幂等回滚 |
| `ClearingOrderRepositoryTest` | @DataJpaTest | 3 | PENDING 落库、findByStatus、同键 upsert 终态回填、全字段往返（JPQL 投影绕一级缓存） |
| `SettlementRecordRepositoryTest` | @DataJpaTest | 4 | source 隔离、幂等查询、唯一约束拒绝重复、按源清空 |
| `SettlementPersistenceSchemaValidateIT` | 防线 | — | **V18 DDL ↔ 实体逐列对齐**（H2 执行生产 V18 + `ddl-auto=validate`，先于生产启动暴露漂移） |
| `SettlementWiringIT` | 防线 | — | composite 实体/仓储经生产 `SettlementPersistenceConfig` 装配（create-drop 建全表） |

既有 8 个 `new` 内存实现的纯单测**零改动全绿**（双模式内存回退路径）。

## 五、踩坑实录（Spring Boot 4.0 模块化迁移）

| # | 问题 | 现象 | 解决 |
|---|------|------|------|
| 1 | 测试注解包路径全迁移 | `@JdbcTest`/`@DataJpaTest`/`TestEntityManager`/`@EntityScan` 编译失败 | 逐个解 jar 确认新路径：`jdbc.test.autoconfigure` / `data.jpa.test.autoconfigure` / `jpa.test.autoconfigure` / `persistence.autoconfigure` |
| 2 | 库模块无切片锚点 | `Unable to find a @SpringBootConfiguration` | test 源码集补 `SettlementTestConfiguration` |
| 3 | `@DataJpaTest` 无 Flyway + 移除 `classes` 属性 | `missing table` / 编译失败 | `@ContextConfiguration` + `spring.sql.init.schema-locations` 指向生产 V18（同源且先于 validate 执行） |
| 4 | **显式 `@EnableJpaRepositories` 令 Spring Data 自动配置退避** | gateway 15+ 既有仓储（7 个包）不再被扫，全上下文集成测试大面积失败 | 扫描范围从 `org.nexus.gateway.repository` 扩为整个 `org.nexus.gateway` |
| 5 | `@Sql` 时序晚于 EntityManagerFactory | validate 先于建表执行 | 弃用 `@Sql`，改 DataSource 初始化期执行的 `spring.sql.init` |

## 六、验证步骤（复现）

```bash
# 1. 全量回归
./gradlew --no-daemon --no-parallel --max-workers=1 :nexus-settlement:test :nexus-gateway:test

# 2. 防线单跑
./gradlew :nexus-gateway:test --tests "org.nexus.gateway.persistence.*"
```

运行时行为链验证：支付成功 → `clearing_order` 出现 PENDING → 调度器 drain → settle 回写 SETTLED+txHash → `ledger_entry` 借/贷双行 → `settlement_record` 出现 source=CHAIN 记录 → 对账全匹配。

## 七、已知注意事项

1. **`@EnableJpaRepositories` 退避效应**：`SettlementPersistenceConfig` 显式声明后，Spring Data 自动配置不再默认扫描——现以 `org.nexus.gateway` 全包兜底；未来 gateway 若出现 `org.nexus.*` 之外的仓储包，需同步扩展该 config。
2. **drainStaging 取出语义**：DB 模式「查询 PENDING + 删除」与内存「快照 + 清空」一致；结算失败订单不重入 staging（与原行为一致），由对账报告暴露差错。
3. **`ledger_entry` 不受 validate 覆盖**（无 JPA 实体）：由 `LedgerJdbcTest` 真实落库 + V18 脚本双保证。
4. **测试 profile 依赖差异**：test 用 `create-drop` 自建表、禁 Flyway（V1..V17 的 MySQL 方言在 H2 兼容性受限），V18↔实体对齐由 SchemaValidateIT 单独守。

## 八、相关文档

- 迁移操作手册：`docs/migration-guide.md`（V12 章节后新增 V18 章节，含前置条件/验证 SQL/回滚步骤）
- 设计决策记录：gateway `SettlementPersistenceConfig` / settlement `Ledger` 类 Javadoc
