# NexusChain 数据库迁移指南

本指南记录 NexusChain 各版本的数据库迁移步骤、关键 migration 脚本说明及回滚方法。
迁移工具采用 Flyway，脚本位于 `nexus-gateway/src/main/resources/db/migration/`。
数据库：PostgreSQL（生产）/ H2（开发 / sandbox）。

## 当前版本 v2.74.0

> 版本依据：`build.gradle` 中 `version = '2.74.0'`、git tag `v2.74.0`、`CHANGELOG.md` 最新**已发布**条目 `[2.74.0] - 2026-10-14`，三者一致。

**迁移基线**（master @ v2.74.0）：V1–V66、V71、V81–V98、V103–V108，共 91 支脚本。其中 V67–V70、V72–V80 为历史上从未分配的保留号段；V99–V102 为号段空洞，永久保留不填（见[迁移版本号分配规则](#迁移版本号分配规则)与[迁移脚本清单](#迁移脚本清单)）。

**下一可用迁移版本号：V109+**（master 现最高号为 V108，见[迁移脚本清单](#迁移脚本清单)与[号段沿革说明](#号段沿革说明)）。

### 通用升级步骤

1. **拉取目标版本代码**：`git checkout v2.74.0`（或合并对应分支）。
2. **编译验证**：`./gradlew assemble -x test`，确认 BUILD SUCCESSFUL。
3. **启动服务**：`./gradlew :nexus-gateway:bootRun`，Flyway 会在启动时自动检测并执行未应用的增量 migration。
4. **验证迁移结果**：查询 Flyway 历史表，确认新增版本均已执行且 `success` 为真：
   ```sql
   SELECT installed_rank, version, description, success
   FROM flyway_schema_history
   ORDER BY installed_rank;
   ```
5. **回归测试**：执行 `./gradlew :nexus-gateway:test`，确认核心用例通过。

> 生产环境迁移前务必完成数据备份；含数据修正语义的迁移（如 V12 的唯一约束）须先执行预检，参见下文对应历史章节。

## 迁移版本号分配规则

> **规则出处**：agent-board 留言 [`20261017-1900-nex-migration-number-allocation-rule.md`](agent-board/20261017-1900-nex-migration-number-allocation-rule.md)（作者 nex，随 PR #130 合入 master 生效）。本节为该留言确立规则的完整落地说明。

### 规则内容

1. **新增迁移版本号 = `max(master 上所有 V<n>) + 1`**。迁移版本号是**单调递增的时钟**，不是编号池——只从顶部往下取，永远不填洞。
2. **任何空洞号段一律不填**。当前已知空洞/保留号段：
   - **V99–V102**：号段空洞，**永久保留不填**（成因见下节）；
   - **V67–V70、V72–V80**：历史上从未分配过脚本的保留号段，同样不填。
3. **取号前必须核对在途 PR 的占用情况**：当前 master 最高号为 **V108**，无在途 PR 占用更高号段，故**下一可用版本号为 V109+**。
4. **PR 正文必须给出迁移版本号取值依据**：写明提交时 master 的最高号（附脚本文件名佐证），以及是否避开了在途 PR 已占用的号段。

### V99–V102 空洞成因

- PR #106 最初携带 V99–V102 四支迁移；同期 #110（KEK 轮换）为给 #106 让号，把自己的建表迁移从 V99 改号为 V103，**让号方与占号方顺序反转**，造成"低号后到"。
- 在 V103 已应用的存量库上，若 #106 以 V99–V102 合入，启动即失败（Flyway `outOfOrder` 未配置、默认 false，低于已应用版本的新迁移会直接判红）。
- 同一模式的事故发生了两次（#110 让号反转；#52-B 因 V99–V102 是否可用取决于 #106 结果而被阻塞），根因均为"取当前最低空闲号（FIFO 填洞）"的分配策略——两个分配者并发且其中一方让号/延期时，"低号后到"必然发生。
- 处置：#106 将四支迁移**顺延为 V104–V107**（只改文件名中的版本号，SQL 内容不动）；顺延后 V99–V102 形成的空洞**永久保留不填**。

### 正确与错误示例

| 判断 | 示例 | 说明 |
|------|------|------|
| ✅ 正确 | master 最高为 V108 时，新迁移取 **V109** | `max(master) + 1` |
| ✅ 正确 | 当前 master 最高 V108、一新 PR 已占用 V109，另一新迁移取 **V110** | 避开在途占用号段 |
| ❌ 错误 | 新迁移取 **V99–V102**（填洞） | 空洞永久不填；低号后到会使已应用 V103 的存量库启动失败 |
| ❌ 错误 | master 最高 V108 时新迁移直接取 **V110**（跳号） | 制造新空洞，违反单调递增取号 |
| ❌ 错误 | 新迁移取 **V108**（撞已落 master 的号） | 与既有迁移同号冲突，Flyway 报 "Found more than one migration with version" |

### PR 正文取值依据模板

新建迁移的 PR 正文必须包含类似如下表述：

> 取值依据：提交时 master 最高迁移号为 V108（`V108__auto_withdraw_payout_claim.sql`）；无在途 PR 占用更高号段，故本迁移取 V109。

## 迁移脚本清单

截至 v2.74.0，清单与 `nexus-gateway/src/main/resources/db/migration/` 实际文件一一对应（共 91 支）。

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
| V13 | `V13__merchant_keypairs.sql` | merchant_keypairs 表：VaultKeyManager 商户密钥对落库（修复重启丢密钥） |
| V14 | `V14__additional_performance_indexes.sql` | 高频查询路径补充复合索引（性能优化） |
| V15 | `V15__routing_rule_tables.sql` | 路由规则持久化表 routing_rules（v2.37.0，RoutingEngine write-through 存储） |
| V16 | `V16__order_notify_url.sql` | payment_orders 增加 notify_url 列（支付结果异步通知地址持久化） |
| V17 | `V17__orchestration_metrics_columns.sql` | orchestrated_payments 增加延迟/成本可观测列 |
| V18 | `V18__settlement_persistence.sql` | 清结算账务核心持久化三表（ledger_entry / clearing_order / settlement_record） |
| V19 | `V19__add_finality_status_column.sql` | payment_orders 增加 finality_status 列（统一交易/支付最终性模型） |
| V20 | `V20__add_qr_code_token_column.sql` | payment_orders 增加 qr_code_token 列（扫码支付安全令牌） |
| V21 | `V21__add_split_tables.sql` | 分账/分润体系：split_rules + split_orders 两表 |
| V22 | `V22__add_merchant_settlement_config.sql` | 商户结算周期配置表（T0/T1/T2/T3/WEEKLY/MONTHLY/CUSTOM） |
| V23 | `V23__add_merchant_limit_configs.sql` | 商户限额配置表 |
| V24 | `V24__add_device_fingerprints.sql` | 设备指纹表 |
| V25 | `V25__add_risk_events.sql` | 风控事件表 |
| V26 | `V26__add_connector_configs.sql` | 渠道连接器配置表 |
| V27 | `V27__add_reconciliation_file_records.sql` | 对账文件记录表 |
| V28 | `V28__add_alert_rules.sql` | 告警规则表 |
| V29 | `V29__add_alert_events.sql` | 告警事件表 |
| V30 | `V30__add_sla_targets.sql` | SLA 目标定义表 |
| V31 | `V31__add_sla_measurements.sql` | SLA 测量记录表 |
| V32 | `V32__add_api_keys.sql` | API Key 生命周期管理表（密钥 SHA-256 哈希存储） |
| V33 | `V33__add_webhook_subscriptions.sql` | Webhook 订阅管理表 |
| V34 | `V34__add_api_version_policies.sql` | API 版本生命周期策略表（ACTIVE/DEPRECATED/SUNSET/RETIRED） |
| V35 | `V35__add_data_export_requests.sql` | 数据导出请求表 |
| V36 | `V36__add_reconciliation_discrepancies.sql` | 对账差错记录表 |
| V37 | `V37__add_merchant_applications.sql` | 商户入驻申请表 |
| V38 | `V38__add_risk_rule_configs_and_action_records.sql` | 风控规则配置表 + 处置动作记录（DB 驱动、免重启调整风控策略） |
| V39 | `V39__add_dead_letter_records.sql` | 死信记录表（Webhook 可靠性增强，DB 持久化 DLQ） |
| V40 | `V40__add_split_limit_strategy_extension.sql` | 阶梯分账、延迟分账、渠道限额、限额调整记录 |
| V41 | `V41__add_alert_suppression_and_escalation.sql` | 告警聚合 + 告警抑制 + 告警升级 |
| V42 | `V42__add_suspense_accounts.sql` | 挂账资金记录表（差错资金自动挂账 + 人工处置） |
| V43 | `V43__add_settlement_confirmation_records.sql` | 链上结算确认记录表（确认追踪、超时重试、告警） |
| V44 | `V44__add_merchant_accounts.sql` | 商户虚拟账户表（BALANCE/FROZEN/RESERVE 三类） |
| V45 | `V45__add_account_transactions.sql` | 账户流水表（不可篡改审计流水） |
| V46 | `V46__add_transaction_logs.sql` | 分布式事务日志表（TCC/SAGA 故障恢复） |
| V47 | `V47__add_void_reversal_tables.sql` | 撤销/冲正表（当日撤销 VOID + 隔日冲正 REVERSAL） |
| V48 | `V48__add_escrow_preauth_tables.sql` | 担保交易 / 预授权表 |
| V49 | `V49__add_order_status_extension.sql` | PaymentOrder 状态扩展（新增 VOIDED/REVERSED 枚举，无 DDL 仅注释） |
| V50 | `V50__add_transfer_rules.sql` | 资金调拨规则表（余额阈值/定时/手动触发） |
| V51 | `V51__add_collection_strategies.sql` | 资金归集策略表 |
| V52 | `V52__add_reserve_configs.sql` | 备付金配置表（阈值、自动补充、监控参数） |
| V53 | `V53__add_reconciliation_adjustments.sql` | 对账差异调整记录表（自动 + 人工审批全流程） |
| V54 | `V54__add_balance_alert_configs.sql` | 余额预警配置表（WARNING/CRITICAL/EMERGENCY 多级阈值） |
| V55 | `V55__add_auto_withdraw_rules.sql` | 自动提现规则表（DAILY/WEEKLY/MONTHLY） |
| V56 | `V56__add_risk_account_link_records.sql` | 风控-账户联动记录表（联合索引保证幂等） |
| V57 | `V57__add_large_transaction_interceptions.sql` | 大额交易拦截表（人工审核/超时升级） |
| V58 | `V58__add_fund_reports.sql` | 资金报表表（日/周/月报，CSV/JSON） |
| V59 | `V59__add_clearing_settlement_records.sql` | 清算结算记录表（批次入账与结算划拨全过程） |
| V60 | `V60__extend_account_operation_type.sql` | 账户流水操作类型注释扩展（新增 8 种操作类型） |
| V61 | `V61__extend_merchant_accounts_add_alert_flag.sql` | 商户账户表新增 alert_flag 预警级别列 |
| V62 | `V62__add_transaction_audit_records.sql` | 交易对账审计记录表（每日正向 + 反向审计） |
| V63 | `V63__payment_security_encryption.sql` | 交易加密增强：密钥元数据表 + 加密策略表 + payment_orders 扩展 |
| V64 | `V64__payment_security_replay_protection.sql` | 防重放攻击增强：配置表（热更新）+ 拦截统计表 |
| V65 | `V65__payment_security_3ds.sql` | 3D Secure 2.0：参数配置表 + 认证记录表 |
| V66 | `V66__payment_security_password.sql` | 支付密码验证：密码表 + 密码历史 + 安全策略 + 二次验证记录 |
| V67–V70 | —（无脚本） | 保留号段：历史上从未分配，不填（见号段分配规则） |
| V71 | `V71__wechat_platform_certificates.sql` | 微信平台证书缓存表（自动轮换与过期检测） |
| V72–V80 | —（无脚本） | 保留号段：历史上从未分配，不填（见号段分配规则） |
| V81 | `V81__reconciliation_rule_configs.sql` | 对账规则配置表（全局/渠道/商户多层级） |
| V82 | `V82__compensation_records.sql` | 补偿记录表（自动补偿执行过程与结果） |
| V83 | `V83__reconciliation_report_records.sql` | T+1 对账报表记录表（元数据与汇总统计） |
| V84 | `V84__routing_strategy_configs.sql` | 多目标路由策略配置表 + routing_rules 扩展 |
| V85 | `V85__channel_health_history.sql` | 渠道健康度历史记录表 + connector_configs 扩展 |
| V86 | `V86__fallback_route_configs.sql` | 商户/渠道级降级路由配置表 |
| V87 | `V87__compensation_routing_records.sql` | 跨渠道补偿路由记录表 |
| V88 | `V88__routing_experiments.sql` | 路由实验（A/B）配置表 |
| V89 | `V89__routing_decision_records.sql` | 路由决策审计记录表 |
| V90 | `V90__merchant_routing_profiles.sql` | 商户路由策略配置表 + merchants 扩展 |
| V91 | `V91__routing_experiment_stats.sql` | 路由实验组统计持久化表（write-through 累加，重启续算） |
| V92 | `V92__risk_limit_accruals.sql` | 商户限额滚动累加表（自然日/自然月计数器口径） |
| V93 | `V93__orchestrated_payments_addresses.sql` | orchestrated_payments 补付款/收款地址列（P0 修复：连接器构造链上交易必需） |
| V94 | `V94__reconciliation_discrepancy_channel_type.sql` | 对账差错补渠道归属字段（P0 修复：长款按渠道原路退回） |
| V95 | `V95__merchant_payout_accounts.sql` | 商户出款去向（结算账户）绑定表（P0 修复：自动提现真实出款） |
| V96 | `V96__audit_events.sql` | 审计事件落库（P0 修复：资金链路操作可查询） |
| V97 | `V97__sanction_list_imports.sql` | 制裁名单导入记录（生效名单版本台账） |
| V98 | `V98__subscription_charge_claim.sql` | 订阅周期扣款幂等认领表（并发重复扣款防线） |
| V99–V102 | —（无脚本） | **号段空洞，永久保留不填**（成因见号段分配规则） |
| V103 | `V103__kek_rotation_progress.sql` | KEK 轮换迁移进度表（跨副本/重启断点续传；立项号 V99，落地让号为 V103） |
| V104 | `V104__clearing_settlement_records_add_version.sql` | clearing_settlement_records 补乐观锁 version 列（L3 预演缺陷 #26；原 V99 顺延） |
| V105 | `V105__currency_wallets.sql` | currency_wallets 建表（L3 预演缺陷 #31：实体缺建表脚本；原 V100 顺延） |
| V106 | `V106__exchange_rates.sql` | exchange_rates 建表（L3 预演缺陷 #31 续；原 V101 顺延） |
| V107 | `V107__entity_schema_drift_closure.sql` | 实体 ↔ schema 漂移收口（L3 复验缺陷 #38；原 V102 顺延） |
| V108 | `V108__auto_withdraw_payout_claim.sql` | 自动提现「出款认领」协议列（批次⑤ P0 加固：扣账/出款调用/规则推进拆分事务） |

### 号段沿革说明

V104–V107 原为 V99–V102（PR #106 立项号），随 PR #106（分支 `fix/l3-rehearsal-round2-20261014`）**纯重命名**顺延——只改文件名中的版本号，SQL 内容不动——已于 2026-10-17 合并入 master（`2dc3f7f0`）。V108 随后落地（批次⑤ P0 加固）。

master 现最高号为 **V108**；按[迁移版本号分配规则](#迁移版本号分配规则)，下一可用版本号为 **V109+**。

## v2.27.0 升级指引（历史）

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
- **新增迁移前必读[迁移版本号分配规则](#迁移版本号分配规则)**：取号 = `max(master) + 1`（当前 master 最高 V108，故从 **V109** 起），避开在途 PR 占用号段，任何空洞（V99–V102 及 V67–V70、V72–V80 保留号段）一律不填；PR 正文必须给出取值依据。
- 生产环境迁移前务必执行 V12 预检并备份数据。
- H2（开发 / sandbox）与 PostgreSQL 语法兼容性已在 V12 脚本中验证（UNIQUE 约束 NULL 语义一致）。
