# 数据库生产化指南（P2-T8：H2 → PostgreSQL 16）

> **版本**：v2.0.0 Phase 2 生产就绪  
> **任务**：P2-T8 数据库生产化  
> **适用服务**：nexus-gateway、nexus-wallet-service  
> **数据库**：PostgreSQL 16  
> **连接池**：HikariCP（Spring Boot 默认）  
> **迁移工具**：Flyway 9.22.3（Spring Boot 3.2.5 BOM 管理）

## 第1章 部署架构

### 1.1 整体架构

图：数据库部署架构图

```
┌─────────────────┐     ┌─────────────────┐
│  nexus-gateway  │     │nexus-wallet-svc │
│  (HikariCP 20)  │     │  (HikariCP 20)  │
└────────┬────────┘     └────────┬────────┘
         │ jdbc:postgresql         │ jdbc:postgresql
         │ :5432/nexus_gateway     │ :5432/nexus_wallet
         ▼                         ▼
┌─────────────────────────────────────────────┐
│          PostgreSQL 16 (单实例)              │
│  ┌───────────────┐  ┌──────────────────┐   │
│  │ nexus_gateway │  │   nexus_wallet   │   │
│  │   (库)        │  │      (库)        │   │
│  │  V1-V7 迁移   │  │   V1-V3 迁移     │   │
│  └───────────────┘  └──────────────────┘   │
│  max_connections=100, shared_buffers=256MB │
└─────────────────────────────────────────────┘
```

### 1.2 库划分

表：数据库划分对照表

| 库名 | 归属服务 | Flyway 迁移版本 | 业务表数量 | 说明 |
|------|----------|----------------|-----------|------|
| `nexus_gateway` | nexus-gateway | V1-V7 | 8 张业务表 + undo_log | 商户、订单、退款、订阅、编排、风控、结算、退款审批 |
| `nexus_wallet` | nexus-wallet-service | V1-V3 | 4 张业务表 + undo_log | 托管余额、地址白名单、提现审批、审批人 |

两库独立部署，避免单库故障扩散，便于按服务维度做备份和扩容。

### 1.3 Profile 与数据源映射

表：Spring Profile 与数据源映射对照表

| Profile | 数据源 | Flyway locations | 用途 |
|---------|--------|------------------|------|
| `dev` | H2 内存（MODE=MySQL） | `classpath:db/migration` | 本地开发、单元测试 |
| `prod` | PostgreSQL 16 | `classpath:db/migration-pg` | 生产部署 |
| 默认（无 profile） | MySQL 8.x | `classpath:db/migration` | K8s 环境变量注入场景 |

## 第2章 PostgreSQL 16 安装与配置

### 2.1 Docker 部署（推荐）

命令示例：Docker 部署 PostgreSQL 16

```bash
docker run -d \
  --name nexus-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=${POSTGRES_SUPERUSER_PASSWORD} \
  -e POSTGRES_DB=postgres \
  -p 5432:5432 \
  -v pgdata:/var/lib/postgresql/data \
  -v ./deploy/scripts/init-postgres.sql:/docker-entrypoint-initdb.d/init.sql:ro \
  postgres:16-alpine
```

### 2.2 初始化脚本

`deploy/scripts/init-postgres.sql` 创建应用库和账号（生产环境账号最小权限原则）：

SQL：初始化数据库和账号

```sql
-- 创建应用库（nexus_gateway / nexus_wallet）
CREATE DATABASE nexus_gateway ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C';
CREATE DATABASE nexus_wallet  ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C';

-- 创建应用账号（禁止使用 superuser 连接应用库）
CREATE USER nexus_gateway WITH PASSWORD '${NEXUS_GATEWAY_DB_PASSWORD}';
CREATE USER nexus_wallet  WITH PASSWORD '${NEXUS_WALLET_DB_PASSWORD}';

-- 授权（仅授予对应库的所有权限，跨库无权限）
GRANT ALL PRIVILEGES ON DATABASE nexus_gateway TO nexus_gateway;
GRANT ALL PRIVILEGES ON DATABASE nexus_wallet  TO nexus_wallet;

-- 连接数限制（防应用连接泄漏拖垮整个 PG 实例）
ALTER USER nexus_gateway CONNECTION LIMIT 30;
ALTER USER nexus_wallet  CONNECTION LIMIT 30;
```

### 2.3 PostgreSQL 关键参数调优

表：PostgreSQL 关键参数说明表

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| `max_connections` | 100 | gateway(20) + wallet(20) + 监控/备份(10) + 余量(50) |
| `shared_buffers` | 256MB | 总内存的 25%，PG 共享内存缓冲池 |
| `effective_cache_size` | 768MB | 总内存的 75%，告知查询规划器操作系统缓存大小 |
| `work_mem` | 4MB | 单查询排序/哈希内存，避免溢出到磁盘 |
| `maintenance_work_mem` | 64MB | VACUUM / CREATE INDEX 内存 |
| `wal_buffers` | 16MB | WAL 日志缓冲区 |
| `checkpoint_completion_target` | 0.9 | 检查点平滑分布 |
| `random_page_cost` | 1.1 | SSD 存储随机读成本（默认 4.0 针对机械盘） |
| `idle_in_transaction_session_timeout` | 60000 | 空闲事务超时 60s（ms），防止长事务阻塞 VACUUM |
| `statement_timeout` | 30000 | 单语句超时 30s（ms），防止慢查询拖垮连接池 |
| `log_min_duration_statement` | 1000 | 慢查询日志阈值 1s（ms） |

### 2.4 docker-compose.prod.yml 更新

将 `postgres:15-alpine` 升级为 `postgres:16-alpine`：

配置：docker-compose.prod.yml（PostgreSQL 16 片段）

```yaml
postgres:
  image: postgres:16-alpine
  environment:
    - POSTGRES_USER=postgres
    - POSTGRES_PASSWORD=${POSTGRES_SUPERUSER_PASSWORD}
    - POSTGRES_DB=postgres
  volumes:
    - pgdata:/var/lib/postgresql/data
    - ./deploy/scripts/init-postgres.sql:/docker-entrypoint-initdb.d/init.sql:ro
  expose: ['5432']
  healthcheck:
    test: ['CMD-SHELL', 'pg_isready -U postgres']
    interval: 10s
    retries: 5
  restart: always
```

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

余量 50 连接用于：PG 维护会话（VACUUM、CREATE INDEX）、pg_dump 备份、Prometheus postgres_exporter、DBA 排障。

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
├── migration/                        # MySQL/H2 语法（dev profile）
│   ├── V1__init_schema.sql
│   ├── V2__performance_indexes.sql
│   ├── V3__orchestration_tables.sql
│   ├── V4__request_id_unique.sql
│   ├── V5__risk_settlement_tables.sql
│   ├── V6__refund_approval_tables.sql
│   └── V7__add_undo_log.sql
└── migration-pg/                     # PostgreSQL 专用（prod profile）
    ├── V1__init_schema.sql
    ├── V2__performance_indexes.sql
    ├── V3__orchestration_tables.sql
    ├── V4__request_id_unique.sql
    ├── V5__risk_settlement_tables.sql
    ├── V6__refund_approval_tables.sql
    └── V7__add_undo_log.sql
```

> **重要**：`migration-pg` 与 `migration` 平级（非子目录），因为 Flyway 9.x 的 classpath scanner 会递归扫描子目录，若 `migration-pg` 放在 `migration/` 下会导致版本号冲突（`Found more than one migration with version N`）。

### 4.2 MySQL → PostgreSQL 语法转换对照

表：MySQL 到 PostgreSQL 语法转换对照表

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
| `BOOLEAN` / `TRUE` / `FALSE` | （不变） | PostgreSQL 原生支持 |
| `DECIMAL(36,18)` | （不变） | 标准 SQL，PostgreSQL 原生支持 |
| `TIMESTAMP` | （不变） | 标准 SQL，PostgreSQL 原生支持 |

### 4.3 迁移脚本编写规范

1. **版本号**：`V<n>__<description>.sql`，`<n>` 递增整数，`<description>` 用下划线分隔的英文
2. **幂等性**：`CREATE TABLE/INDEX` 加 `IF NOT EXISTS`，`ALTER TABLE ADD COLUMN` 加 `IF NOT EXISTS`
3. **可逆性**：每个 V<n> 应有对应的 U<n> 回滚脚本（生产环境禁用 Flyway clean）
4. **大小**：单脚本 < 500 行，大变更拆分为多个版本
5. **测试**：新脚本需在本地 PostgreSQL 实例验证后再合入

### 4.4 Flyway 命令

命令示例：Flyway 常用命令

```bash
# 查看迁移状态
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} \
       -locations=classpath:db/migration-pg info

# 执行迁移
flyway -url=jdbc:postgresql://localhost:5432/nexus_gateway \
       -user=nexus_gateway -password=${DB_PASSWORD} \
       -locations=classpath:db/migration-pg migrate

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
| 全量备份 | 每日 02:00 | 30 天 | `pg_dump --format=custom` | S3 (nexus-backups/pg/) |
| WAL 归档 | 实时 | 7 天 | `archive_command` | S3 (nexus-backups/pg/wal/) |
| 跨月备份 | 每月 1 日 | 12 个月 | 全量备份的副本 | S3 (nexus-backups/pg/monthly/) |

### 5.2 备份脚本

见 `deploy/scripts/backup-postgres.sh`，功能：
- `pg_dump --format=custom --compress=9` 压缩备份（custom 格式支持并行恢复和选择性恢复）
- 上传 S3（AWS CLI，启用服务器端加密 SSE-KMS）
- 保留 30 天，超期自动删除（S3 Lifecycle Policy 或脚本清理）
- 备份完成写入 `nexus-backup-metrics` 文件供监控

### 5.3 恢复脚本

见 `deploy/scripts/restore-postgres.sh`，功能：
- 从 S3 下载指定日期的备份文件
- `pg_restore --clean --if-exists` 恢复（先删后建，幂等）
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
./deploy/scripts/restore-postgres.sh \
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
| RTO（恢复时间目标） | < 30 分钟 | 下载备份 + pg_restore + 应用重启 |

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
2. 从 PostgreSQL 导出数据：`pg_dump --data-only --column-inserts`
3. 转换为 H2 兼容 INSERT 语句（注意 BOOLEAN / TIMESTAMP 格式）
4. 通过 H2 Console 导入数据

> **警告**：H2 为内存数据库，回滚仅作为临时应急，数据持久化需尽快恢复 PostgreSQL。

## 附录 A：变更记录

| 日期 | 版本 | 变更 | 负责人 |
|------|------|------|--------|
| 2026-08-09 | v2.0.0 | P2-T8 初始版本：H2 → PostgreSQL 16 生产化 | 后端工程师 |