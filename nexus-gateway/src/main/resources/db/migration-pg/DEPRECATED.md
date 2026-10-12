# ⚠️ 本目录已废弃（DEPRECATED，2026-10-12）

`db/migration-pg` 是**当年**把生产库迁到 PostgreSQL 时建立的迁移集，但它早已停止维护：

| 事实 | 证据 |
|------|------|
| 版本冻结 | 本目录最新为 **V17**，而 `db/migration`（MySQL）已到 **V96** |
| 无引用 | 任何 profile 都**不再**指向本目录（`application-prod.yml` 已改为 `classpath:db/migration`） |
| 会启动失败 | 此前 `application-prod.yml` 指向本目录 + `ddl-auto: validate` ⇒ 生产 profile 缺表缺列、**根本起不来** |
| 与已决方向相反 | ADR-033（2026-10-03 Accepted）= OceanBase 社区版（**MySQL 模式**）；bridge / signing-service 的默认数据源本来就是 MySQL |

## 现在怎么办

- **新迁移一律写入 `../migration`**（MySQL 方言）——dev / CI / prod 共用同一套脚本。
- 生产库族 = **MySQL 8**（客户可用 OceanBase MySQL 模式）；建库建账号见
  `deploy/config/init-mysql-dbs.sh`；部署片段见 `docker-compose.prod.yml` 的 `mysql` 服务
  与 `deploy/helm`（`dependencies.mysql` / `mysqlDatabase`）。
- 本目录**保留仅供历史查阅**（例如核查当年 PG 版本的表结构）。
  **不要删除**其中的历史 SQL（审计需要），也**不要**在其中的基础上继续演进。

判据（下次有人问"要不要顺手补一下 PG 迁移"）：只有出现**明确的 PG 生产需求**
（客户指定 PG 且有维护承诺）时才重启这条路径——那需要同时恢复 `db/migration-pg` 的
全量重写与 CI 真库验证，属独立立项，不是"顺手补几条"。
