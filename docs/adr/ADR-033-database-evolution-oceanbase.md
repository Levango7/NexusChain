# ADR-033: 数据库演进方向——OceanBase（MySQL 模式）

- **状态**：Accepted（2026-10-03，用户定夺：「将来也用 OceanBase 吧……暂时就用阿里的」；TiDB 不选）
- **日期**：2026-10-03
- **决策人**：项目所有者（Levango7）
- **实施时点**：**未来正式承载真实资金/多副本部署时**；现阶段不迁移、不引入连接依赖

---

## 1. 决策

数据层演进目标定为 **OceanBase 社区版（MySQL 模式）**，淘汰备选 TiDB。理由：

1. **强一致分布式**：OceanBase 基于 Paxos 多副本，RPO=0/RTO<8s（金融级容灾口径）——
   这是「异地灾备/多活」路线（ADR-034）在数据层的唯一硬前置；
2. **MySQL 模式高度兼容**：本仓库全部业务 SQL 为 MySQL 方言（Flyway V1-V90、
   JPA/hibernate dialect、`sumMerchantAmountSince` 等 JPQL）——迁移路径是
   「换连接串+验证」，不是重写；
3. 用户对阿里生态的明确倾向（原话定夺，见状态行）。

## 2. 现阶段约束（诚实声明）

- 单实例 MySQL/PG 对当前负载绰绰有余，OceanBase 最小部署（3 副本）的资源需求
  （内存/磁盘）在现阶段是纯负担——**没有多副本部署就没有引入它的收益**；
- 本 ADR 是**方向锁定**而非立即执行：所有新 schema/SQL 继续保持 MySQL 兼容，
  即默认已在为 OceanBase MySQL 模式铺路。

## 3. 迁移前置清单（实施时逐项核销）

| # | 前置项 | 现状 |
| --- | --- | --- |
| 1 | Flyway 全部迁移脚本通过 OceanBase MySQL 模式执行（`ENGINE=InnoDB`/`TINYINT(1)`/`COMMENT` 语法） | 预期兼容，需实测 V1-V90 逐一跑通 |
| 2 | JPA dialect 切换验证（hibernate MySQL dialect → OB MySQL 模式） | 配置层改动 |
| 3 | 连接池/驱动兼容（MySQL Connector/J 对 OB 代理模式） | 实测项 |
| 4 | `system time`/时区行为一致 | 实测项 |
| 5 | CI 增加一个 OceanBase 兼容冒烟 job（Docker 起 OB all-in-one，跑 Flyway 迁移+核心单测） | 未来项 |
| 6 | 多副本部署形态落地（ADR-034 第 2 步之后） | **收益成立的前提** |

## 4. 后果

- 正面：数据层 SPOF 解除路径清晰；与 HA 路线（ADR-034）正交推进互不阻塞；
- 负面：迁移本身有一次性的风险窗口（数据搬迁+双写切换），需要独立演练；
- 中性：TiDB 不再评估（用户已排除，避免双线研究成本）。
