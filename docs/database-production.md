# 数据库生产化指南（生产族：MySQL 8 / OceanBase MySQL 模式）

> **版本**：v2.69.0+（2026-10-12 修正）
> **适用服务**：nexus-gateway、nexus-wallet-service（有 Flyway 的服务）
> **数据库**：**MySQL 8.0（同族可选 OceanBase 社区版 MySQL 模式）**
> **连接池**：HikariCP（Spring Boot 默认）
> **迁移工具**：Flyway（Spring Boot BOM 管理）
>
> ## ⚠️ 2026-10-12 重要修正（安装阻断级）
>
> 本文档原先描述的是 **PostgreSQL 16** 生产路径，与仓库现实矛盾，已修正为 MySQL 家族：
>
> 1. **原 prod profile 根本起不来**：gateway / wallet-service 的 `application-prod.yml`
>    指向 `classpath:db/migration-pg`，而该目录**冻结在 V17 / V3**（维护中的
>    `db/migration` 已到 V96 / V4），叠加 `ddl-auto=validate` ⇒ 启动即
>    `Schema-validation: missing table/column`。
> 2. **与已决方向相反**：ADR-033（2026-10-03 Accepted）定的是
>    **OceanBase 社区版（MySQL 模式）**，且全仓唯一被维护的迁移集就是 MySQL 方言的
>    `db/migration`；bridge / signing-service 在 prod 下本来就走 MySQL。
> 3. **现在的口径**：prod 数据源 = MySQL 8（外部实例或自带 `mysql:8.0` 容器），
>    Flyway 走 `db/migration`（**唯一**维护集），`db/migration-pg` 保留但不再被任何
>    profile 引用（见该目录 `DEPRECATED.md`）。
>
> 下文凡涉及 PostgreSQL 的章节（安装、调优、语法转换）均已改写或标注为历史；
> 若发现残留的 PG 说法，以本节为准。

## 第1章 部署架构

### 1.1 整体架构

```
┌─────────────────┐     ┌─────────────────┐    ┌──────────────────┐
│  nexus-gateway  │     │nexus-wallet-svc │    │ nx-signing-svc   │
│  (HikariCP 20)  │     │  (HikariCP 20)  │    │  (桥/钱包同族)    │
└────────┬────────┘     └────────┬────────┘    └────────┬─────────┘
         │ jdbc:mysql://mysql:3306/nexus_gateway  │ /nexus_wallet │ /nexus_signing
         ▼                         ▼                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                MySQL 8.0（单实例；客户可用 OceanBase）              │
│  ┌───────────────┐ ┌──────────────┐ ┌───────────┐ ┌───────────┐  │
│  │ nexus_gateway │ │ nexus_wallet │ │nexus_sig..│ │nexus_bridge│ │
│  │  V1..V96      │ │  V1..V4      │ │  V1       │ │  V1..V4   │  │
│  └───────────────┘ └──────────────┘ └───────────┘ └───────────┘  │
│  另：seata 库（TC 事务日志，表由 Seata 自建）                       │
│  max_connections=151, innodb_buffer_pool_size=25% 物理内存         │
└──────────────────────────────────────────────────────────────────┘
```

**core（链节点）例外，仍用 PostgreSQL**：core 没有 JPA/Flyway，但有**PG 方言的 JDBC 链存储**
（`RDBMSBlockChainImpl` + `nexus-core/src/main/resources/ddl.sql`：`bytea` 类型、双引号标识符），
故全栈业务库切 MySQL 时 core 保留 PG。core 迁 MySQL 需重写 `ddl.sql` 并核实 JDBC 读写路径
（独立批次）。**因此客户安装需要准备两个实例**：MySQL 8（业务五库）+ PostgreSQL 16（`nexus_core`）。

### 1.2 库划分

| 库名 | 归属服务 | Flyway 迁移版本 | 说明 |
|------|----------|----------------|------|
| `nexus_gateway` | nexus-gateway | V1..V96 | 商户、订单、退款、订阅、编排支付、风控、清结算、对账差错、出款账户、审计事件（全新库 97 张表，含 `flyway_schema_history`） |
| `nexus_wallet` | nexus-wallet-service | V1..V4 | 托管余额、地址白名单、提现审批/审批人、审计事件（全新库 7 张表） |
| `nexus_signing` | nexus-signing-service | V1 | 签名服务（TCC undo_log 等） |
| `nexus_bridge` | nexus-bridge | V1..V4 | 跨链桥交易 |
| `seata` | Seata TC | （Seata 自建） | `global_table`/`branch_table`/`lock_table`/`distributed_lock` |

建库建账号脚本：`deploy/config/init-mysql-dbs.sh`（compose 首次启动自动执行；
K8s 侧见 `deploy/k8s/30-infrastructure.yml` 的 `mysql-init` ConfigMap —— **两处内容必须一致**）。

### 1.3 Profile 与数据源映射

| Profile | 数据源 | Flyway locations | 用途 |
|---------|--------|------------------|------|
| `dev` | H2 内存（MODE=MySQL） | `classpath:db/migration` | 本地开发、单元测试 |
| `prod` | **MySQL 8**（`NEX_DB_URL` 覆盖） | `classpath:db/migration` | 生产部署（**与 dev 同一套迁移**） |
| 默认（无 profile，如 bridge/signing/core） | MySQL 8.x | `classpath:db/migration` | 容器/K8s 环境变量注入场景 |

> 三行指向**同一套**迁移脚本是本轮对齐的直接收益：dev/CI 跑过的语句就是生产跑的语句，
> 不再存在"PG 版迁移一年没人跑过"的漂移面。

## 第2章 MySQL 8 安装与配置

### 2.1 Docker 部署（推荐）

```bash
docker run -d \
  --name nexus-mysql \
  -e MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD} \
  -e MYSQL_USER=nexus -e MYSQL_PASSWORD=${MYSQL_PASSWORD} \
  -p 3306:3306 \
  -v mysqldata:/var/lib/mysql \
  -v ./deploy/config/init-mysql-dbs.sh:/docker-entrypoint-initdb.d/init-mysql-dbs.sh:ro \
  mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --default-time-zone=+00:00
```

初始化脚本只在**数据目录为空**（首次启动）时执行；已初始化的实例要补建库/账号，
可手工执行同一份脚本（`sh deploy/config/init-mysql-dbs.sh`，需先导出 `MYSQL_*` 变量）。

### 2.2 初始化脚本做了什么

`deploy/config/init-mysql-dbs.sh`：

```sql
-- 五个库（四个业务库 + seata），全部 utf8mb4/utf8mb4_unicode_ci
CREATE DATABASE IF NOT EXISTS nexus_gateway ...;  -- 另有 wallet/signing/bridge/seata
-- 每库一个专用账号（最小权限，只授权本库）+ 通用账号 nexus（seata 与运维用，五库授权）
-- 监控导出器所需最小全局权限：PROCESS, REPLICATION CLIENT（仅授予 nexus）
```

**账号口径**：`nexus_gateway` 只能访问 `nexus_gateway` 库（`SHOW GRANTS` 可验证）；
生产请按需为每个账号设置 `CONNECTION LIMIT` 或在实例层限流（MySQL 8 无 per-user
`CONNECTION LIMIT`，可用 `max_user_connections` 资源限制实现）。

### 2.3 MySQL 关键参数

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| `max_connections` | 151（默认） | gateway(20) + wallet(20) + signing/bridge + seata + 运维余量 |
| `innodb_buffer_pool_size` | 物理内存 50–70% | InnoDB 缓冲池（最重要的一项） |
| `innodb_flush_log_at_trx_commit` | 1 | 金融场景必须为 1（每次提交刷盘，崩溃不丢已提交事务） |
| `sync_binlog` | 1 | 同上，配合 binlog 归档/主从 |
| `character_set_server` / `collation_server` | utf8mb4 / utf8mb4_unicode_ci | 中文与审计明细必须 |
| `default_time_zone` | `+00:00` | 与各服务 `serverTimezone=UTC` 严格一致（不一致会导致时间字段偏移） |
| `long_query_time` | 1 | 慢查询日志阈值（秒） |
| `innodb_print_all_deadlocks` | 1 | 死锁全量落错误日志（资金类表并发冲突排查） |

> 迁移实测（2026-10-12）：全新 MySQL 8.0 上 gateway 83 个迁移脚本 20 秒跑完到 v96、
> wallet 4 个脚本 <1 秒；唯一告警是 `Integer display width is deprecated`
> （`TINYINT(1)` 等写法，MySQL 8 仍接受）。

### 2.4 docker-compose.prod.yml 片段（现行）

见仓库 `docker-compose.prod.yml` 的 `mysql` 服务（含 init 脚本挂载、utf8mb4/UTC 参数、
`mysqladmin ping` 健康检查）与四个应用服务的 `NEX_DB_URL` 注入。

## 第3章 HikariCP 连接池配置

### 3.1 参数说明

表：HikariCP 连接池参数说明表

| 参数 | 生产值 | 说明 | 调优建议 |
|------|--------|------|----------|
| `maximum-pool-size` | 20 | 池中最大连接数 | 根据 `(峰值QPS × 平均查询耗时ms) / 1000` 估算，留 20% 余量 |
| `minimum-idle` | 5 | 最小空闲连接数 | 与峰值差距大时可设较低，避免空闲连接占用 PG max_connections |
| `connection-timeout` | 30000ms | 获取连接最大等待时间 | 超时抛 `SQLTransientConnectionException`，建议 ≥ PG `statement_timeout` |
| `idle-timeout` | 600000ms | 空闲连接存活时长 | 仅当池中连接 > `minimum-idle` 时生效，应 < PG `tcp_keepalives_idle` |
| `max-lifetime` | 1800000ms | 连接最大生命周期 | 必须 < PG `idle_in_transaction_session_timeout`，建议 30min |
| `leak-detection-threshold` | 60000ms | 连接泄漏检测阈值 | 借出后超时未归还则记 WARNING 日志，0 关闭，生产建议 60s |
| `pool-name` | nexus-*-hikari | 连接池名称 | 用于日志和监控指标识别 |

### 3.2 连接数容量规划

PostgreSQL `max_connections=100` 下的连接分配：

表：连接数容量规划表

| 服务 | HikariCP 上限 | 峰值占用 | 余量 |
|------|--------------|---------|------|
| nexus-gateway | 20 | ~15 | 5 |
| nexus-wallet-service | 20 | ~10 | 10 |
| 监控 / 备份 / DBA | 10 | ~5 | 5 |
| **合计** | **50** | **30** | **50** |

余量连接用于：MySQL 维护会话（OPTIMIZE/ALTER、备份）、mysqld-exporter、DBA 排障。

### 3.3 连接池监控指标

通过 Micrometer + Prometheus 暴露的 HikariCP 指标：

表：HikariCP 监控指标说明表

| 指标 | 类型 | 告警阈值 | 说明 |
|------|------|---------|------|
| `hikaricp.connections.active` | Gauge | > 18 (90%) | 活跃连接数，接近 max 需扩容 |
| `hikaricp.connections.idle` | Gauge | - | 空闲连接数 |
| `hikaricp.connections.pending` | Gauge | > 5 | 等待获取连接的线程数，>0 说明池不够用 |
| `hikaricp.connections.creation` | Counter | - | 连接创建次数，频繁创建说明 max-lifetime 过短 |
| `hikaricp.connections.timeout` | Counter | > 0/min | 获取连接超时次数，>0 说明池耗尽 |
| `hikaricp.connections.leak` | Counter | > 0 | 连接泄漏次数，>0 需排查未关闭的 Connection |
| `hikaricp.connections.usage` | Timer | p99 > 5s | 连接持有时长，过长说明有慢查询或事务未提交 |

Prometheus 告警规则示例：

配置：prometheus-alerts.yaml

```yaml
groups:
  - name: hikaricp-alerts
    rules:
      - alert: HikariCPConnectionPoolExhausted
        expr: hikaricp_connections_pending > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "HikariCP 连接池耗尽（{{ $labels.pool }}）"
          description: "等待获取连接的线程数 > 0 持续 1 分钟"

      - alert: HikariCPConnectionLeak
        expr: increase(hikaricp_connections_leak[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "HikariCP 检测到连接泄漏（{{ $labels.pool }}）"
          description: "过去 5 分钟内有连接泄漏，需排查未关闭的 Connection"

      - alert: HikariCPActiveConnectionsHigh
        expr: hikaricp_connections_active / 20 > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "HikariCP 活跃连接数过高（{{ $labels.pool }}）"
          description: "活跃连接数超过池上限 90% 持续 5 分钟"
```

## 第4章 Flyway 迁移脚本管理

### 4.1 目录结构

```
nexus-gateway/src/main/resources/db/
├── migration/                        # MySQL 方言 —— **唯一维护集**（dev/CI/prod 全用这一套）
│   ├── V1__init_schema.sql
│   ├── ...
│   ├── V93__orchestrated_payments_addresses.sql
│   ├── V94__reconciliation_discrepancy_channel_type.sql
│   ├── V95__merchant_payout_accounts.sql
│   └── V96__audit_events.sql
└── migration-pg/                     # ⚠️ 已废弃（DEPRECATED.md）：冻结在 V17，无任何 profile 引用
```

> **2026-10-12**：`migration-pg` 曾是 prod profile 的迁移集，但长期无人维护（冻结在 V17/V3）
> 且与 ADR-033 的 MySQL 方向相反 ⇒ 已从 prod profile 摘除，保留目录仅供历史查阅。
> **不要再向 `migration-pg` 添加迁移**；新迁移一律进 `db/migration`。

### 4.2 （历史）MySQL → PostgreSQL 语法转换对照

> **本节已作废**（2026-10-12 数据库族回退为 MySQL）。保留仅作历史参考：
> 它记录的是当年"迁到 PG"时的写法，现在**没有任何迁移走这条路**。

| MySQL 语法 | PostgreSQL 语法 | 说明 |
|-----------|----------------|------|
| `BIGINT AUTO_INCREMENT` | `BIGSERIAL` | 自增主键，PostgreSQL 用序列实现 |
| `LONGBLOB` | `BYTEA` | 二进制大对象，PostgreSQL 用 BYTEA |
| `BLOB` | `BYTEA` | 同上 |
| `DATETIME(6)` | `TIMESTAMP(6)` | PostgreSQL 无 DATETIME 关键字 |
| `UNIQUE KEY name (cols)` | `CONSTRAINT name UNIQUE (cols)` | 约束命名语法 |
| `INDEX name (cols)`（内联） | `CREATE INDEX name ON table (cols)` | PostgreSQL 不支持内联 INDEX |
| `ENGINE=InnoDB` | （删除） | PostgreSQL 无存储引擎概念 |
| `DEFAULT CHARSET=utf8mb4` | （删除） | 字符集在 initdb --encoding=UTF8 设置 |
| `COMMENT '...'`（内联） | `COMMENT ON COLUMN tbl.col IS '...'` | 或直接用 SQL 行注释 |

### 4.3 迁移脚本编写规范

1. **版本号**：`V<n>__<description>.sql`，`<n>` 递增整数，`<description>` 用下划线分隔的英文
2. **幂等性**：`CREATE TABLE/INDEX` 加 `IF NOT EXISTS`，`ALTER TABLE ADD COLUMN` 加 `IF NOT EXISTS`
3. **可逆性**：每个 V<n> 应有对应的 U<n> 回滚脚本（生产环境禁用 Flyway clean）
4. **大小**：单脚本 < 500 行，大变更拆分为多个版本
5. **测试**：新脚本需在**真实 MySQL 8**（或 H2 MODE=MySQL 单测 + 一次真库预演）验证后再合入；
   本仓 2026-10-12 的安装预演配方（docker 起 mysql:8.0 + flyway CLI 直跑某服务的
   `db/migration` 目录）可直接复用

### 4.4 Flyway 命令

命令示例：Flyway 常用命令

```bash
# 查看迁移状态
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} \
       -locations=filesystem:<模块>/src/main/resources/db/migration info

# 执行迁移
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} \
       -locations=filesystem:<模块>/src/main/resources/db/migration migrate

# 验证迁移（CI/CD 门禁）
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} \
       -locations=classpath:db/migration-pg validate

# 修复 checksum 不匹配（仅开发环境，生产禁用）
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} repair
```

## 第5章 备份策略

### 5.1 备份方案

表：备份方案对照表

| 备份类型 | 频率 | 保留时长 | 工具 | 存储位置 |
|---------|------|---------|------|---------|
| 全量备份 | 每日 02:00 | 30 天 | `mysqldump --single-transaction` | S3 (nexus-backups/mysql/) |
| WAL 归档 | 实时 | 7 天 | `archive_command` | S3 (nexus-backups/pg/wal/) |
| 跨月备份 | 每月 1 日 | 12 个月 | 全量备份的副本 | S3 (nexus-backups/pg/monthly/) |

### 5.2 备份脚本

见 `deploy/scripts/backup-mysql.sh`，功能：
- `mysqldump --single-transaction --routines --triggers` + gzip（InnoDB 一致性快照，不锁表）
- 上传 S3（AWS CLI，启用服务器端加密 SSE-KMS）
- 保留 30 天，超期自动删除（S3 Lifecycle Policy 或脚本清理）
- 备份完成写入 `nexus-backup-metrics` 文件供监控

### 5.3 恢复脚本

见 `deploy/scripts/restore-mysql.sh`，功能：
- 从 S3 下载指定日期的备份文件
- 默认 `DROP DATABASE` 后重建再导入（保证「恢复=回到备份点」）；`--suffix` 可恢复到新库人工切换
- 支持指定库（`nexus_gateway` / `nexus_wallet` / `all`）
- 恢复后自动运行 `flyway validate` 校验 schema 一致性

## 第6章 恢复演练

### 6.1 恢复演练步骤

1. **准备环境**：在隔离的测试 PostgreSQL 实例上演练，禁止在生产实例操作

命令示例：启动测试 PostgreSQL 实例

```bash
docker run -d --name pg-restore-test \
  -e POSTGRES_PASSWORD=test \
  -p 5433:5432 \
  postgres:16-alpine
```

2. **执行恢复**：

命令示例：从 S3 恢复 2026-08-08 的备份

```bash
./deploy/scripts/restore-mysql.sh \
  --date 2026-08-08 \
  --database nexus_gateway \
  --target-host localhost:5433 \
  --target-user postgres \
  --target-password test
```

3. **验证数据完整性**：

SQL：验证数据完整性

```sql
-- 1. 检查关键表行数
SELECT 'merchants' AS tbl, COUNT(*) FROM merchants
UNION ALL SELECT 'payment_orders', COUNT(*) FROM payment_orders
UNION ALL SELECT 'refunds', COUNT(*) FROM refunds
UNION ALL SELECT 'subscriptions', COUNT(*) FROM subscriptions;

-- 2. 检查 Flyway 迁移历史
SELECT version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

-- 3. 检查外键完整性（孤儿记录）
SELECT 'orphan_api_keys' AS issue, COUNT(*) FROM merchant_api_keys mak
LEFT JOIN merchants m ON mak.merchant_id = m.id
WHERE m.id IS NULL;
```

4. **应用层验证**：启动 gateway / wallet-service 连接恢复后的库，跑冒烟测试

5. **记录演练结果**：演练时间、耗时、发现问题、改进措施，归档至 `docs/drill-records/`

### 6.2 RTO / RPO 目标

表：恢复时间目标对照表

| 指标 | 目标 | 说明 |
|------|------|------|
| RPO（恢复点目标） | < 5 分钟 | WAL 归档实时上传，最多丢失 5 分钟数据 |
| RTO（恢复时间目标） | < 30 分钟 | 下载备份 + mysql 导入 + 应用重启 |

## 第7章 连接池监控

### 7.1 Grafana Dashboard

推荐 Grafana Dashboard ID：`15462`（HikariCP 官方社区 Dashboard），导入后修改数据源为 Prometheus。

关键 Panel：
1. 连接池使用率（active / maximum-pool-size）
2. 连接获取耗时 P99（hikaricp.connections.acquisition）
3. 连接持有时长 P99（hikaricp.connections.usage）
4. 等待线程数（hikaricp.connections.pending）
5. 泄漏连接数（hikaricp.connections.leak）

### 7.2 PostgreSQL 监控

使用 `postgres_exporter` 暴露 PG 指标到 Prometheus：

配置：postgres-exporter.yaml

```yaml
postgres_exporter:
  image: quay.io/prometheuscommunity/postgres-exporter:v0.15.0
  environment:
    - DATA_SOURCE_NAME=postgresql://postgres:${POSTGRES_SUPERUSER_PASSWORD}@postgres:5432/postgres?sslmode=disable
  expose: ['9187']
```

关键 PG 指标：
- `pg_stat_activity_count`：活跃会话数
- `pg_stat_database_blks_hit` / `pg_stat_database_blks_read`：缓存命中率
- `pg_stat_user_tables_seq_scan`：顺序扫描次数（高频说明缺索引）
- `pg_locks_count`：锁等待数
- `pg_stat_replication_lag`：复制延迟（主从架构）

## 第8章 故障排查

### 8.1 常见问题

表：常见故障排查对照表

| 现象 | 可能原因 | 排查步骤 |
|------|---------|---------|
| 启动报 `Connection refused` | PG 未启动 / 网络不通 | `pg_isready -h postgres -p 5432` |
| 启动报 `authentication failed` | 用户名/密码错误 | 检查环境变量 `NEX_DB_USERNAME` / `NEX_DB_PASSWORD` |
| Flyway 报 `checksum mismatch` | 迁移脚本被修改 | `flyway repair` 更新 checksum（生产需审批） |
| Flyway 报 `Validate failed` | 迁移版本不连续 | 检查 `flyway_schema_history` 表 |
| HikariCP 报 `Connection is not available` | 连接池耗尽 | 检查 `hikaricp.connections.active` 是否达 max |
| 查询报 `out of shared memory` | PG `max_connections` 过低 | 调整 PG `max_connections` 或降低应用池大小 |
| 查询报 `idle in transaction session timeout` | 事务未提交 | 排查 `@Transactional` 方法是否有长耗时操作 |

### 8.2 紧急回滚（PostgreSQL → H2）

如生产环境 PostgreSQL 出现严重问题需紧急回滚到 H2：

1. 切换 `SPRING_PROFILES_ACTIVE=dev`（使用 H2 + 原 `db/migration`）
2. （历史）从 PostgreSQL 导出数据：`pg_dump --data-only --column-inserts`
3. 转换为 H2 兼容 INSERT 语句（注意 BOOLEAN / TIMESTAMP 格式）
4. 通过 H2 Console 导入数据

> **警告**：H2 为内存数据库，回滚仅作为临时应急，数据持久化需尽快恢复 PostgreSQL。

## 附录 A：变更记录

| 日期 | 版本 | 变更 | 负责人 |
|------|------|------|--------|
| 2026-08-09 | v2.0.0 | P2-T8 初始版本：H2 → PostgreSQL 16 生产化 | 后端工程师 |