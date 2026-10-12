# ⚠️ 本目录已废弃（DEPRECATED，2026-10-12）

`db/migration-pg` 是**当年**把生产库迁到 PostgreSQL 时建立的迁移集，早已停止维护：

| 事实 | 证据 |
|------|------|
| 版本冻结 | 本目录最新为 **V3**，而 `db/migration`（MySQL）已到 **V4**（`V4__wallet_audit_events.sql`） |
| 无引用 | 任何 profile 都**不再**指向本目录（`application-prod.yml` 已改为 `classpath:db/migration`） |
| 会启动失败 | 此前 `application-prod.yml` 指向本目录 + `ddl-auto: validate` ⇒ 生产 profile 缺 `wallet_audit_events` 表，**启动即失败** |
| 与已决方向相反 | ADR-033（2026-10-03 Accepted）= OceanBase 社区版（**MySQL 模式**） |

细节与处置口径见 `nexus-gateway/src/main/resources/db/migration-pg/DEPRECATED.md`
（同一决策，两处必须保持一致）：**新迁移一律写 `../migration`；本目录只留档、不删除、不演进。**
