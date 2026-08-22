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

## 注意事项

- Flyway migration 脚本一经发布不可修改；如需修正，新增更高版本号的脚本。
- 生产环境迁移前务必执行 V12 预检并备份数据。
- H2（开发 / sandbox）与 PostgreSQL 语法兼容性已在 V12 脚本中验证（UNIQUE 约束 NULL 语义一致）。