# migration-pg — PostgreSQL 方言迁移（⚠️ 已停止维护）

## 状态：DEPRECATED（2026-10-05 标注）

**本目录不是投产路径，请勿据此认为系统支持 PostgreSQL。**

## 事实

| 项 | 值 |
|---|---|
| 本目录最新版本 | **V17**（`V17__*.sql`） |
| MySQL 方言目录（`../migration/`）最新版本 | **V92** |
| **滞后** | **75 个版本** |
| 默认/生产数据库 | **MySQL**（`application.yml` → `jdbc:mysql://...`，driver `com.mysql.cj.jdbc.Driver`） |
| 演进方向 | **OceanBase 社区版（MySQL 模式）**，见 `docs/adr/ADR-033-database-evolution-oceanbase.md` |

## 为什么停止维护

`ADR-033`（2026-10-03，项目所有者定夺）将数据层演进目标锁定为
**OceanBase 社区版（MySQL 模式）**，并明确：

> "MySQL 模式高度兼容：本仓库全部业务 SQL 为 MySQL 方言（Flyway V1-V90、
> JPA/hibernate dialect 等）——迁移路径是『换连接串+验证』，不是重写。"

即：**演进路线走 MySQL 兼容，不引入 PostgreSQL 分支**。因此本目录失去维护价值。

## 处置建议（待 owner 定夺）

1. **删除本目录** —— 最干净。若未来确需 PG 支持，应基于届时最新的 MySQL 迁移重新生成，
   而非修补这份滞后 75 版的旧快照。
2. **保留但维持本标注** —— 若出于某种原因需保留历史快照。

**不推荐**：继续手工同步——75 个版本的差距意味着同步成本高于重新生成，
且没有任何调用方需要它（当前无任何配置引用 `migration-pg`）。

---

*标注人：Nexus 🔍 ｜ 2026-10-05*
