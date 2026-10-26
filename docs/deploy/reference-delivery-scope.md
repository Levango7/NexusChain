# 参考交付范围口径（Reference Delivery Scope）

- **决策**：2026-10-26，仓库所有者拍板（经 zcode 会话代记录）
- **口径**：**最小集交付**。Kafka / Sentinel Dashboard / Zipkin Server 一律**不进**参考交付，均为可选扩展。
- **事实底座**：L3 两轮 kind 预演（2026-10-13/14，v2.73.0/v2.74.0，合计 45 项缺陷修复、24/24 Pod Ready）
  实证：**没有** Kafka / Sentinel Dashboard / Zipkin Server 时，全栈可安装、可启动、健康检查通过。
- **效力**：本文是客户装环境清单的权威口径；若与 compose/helm/文档冲突，以本文为准（发现冲突请投勘误或 PR 修正本文）。

---

## 一、基础设施最小集（必须有）

| 组件 | 用途 |
|---|---|
| MySQL 8 | 业务五库（gateway/wallet/signing/bridge + seata）+ nacos_config |
| PostgreSQL 16 | 仅 nexus-core 的链存储（`RDBMSBlockChainImpl` JDBC） |
| Redis | 幂等存储 / 共享状态 |
| Nacos ×3 | 配置与服务发现 |
| Seata | 分布式事务 TC（store 表 DDL 需预建，见下"工程收口项"） |

## 二、三个可选扩展：不装时什么在工作、什么没有

### 1. Kafka — 不进交付

- **不装时在工作的**：事件溯源与 webhook 死信队列走 memory 存储；限流、对账、结算、支付主链全部不受影响。
- **不装时没有的**：事件溯源的持久化回放与跨实例事件总线；webhook 死信的跨重启持久化（memory 版重启即失）。
- **部署面现状（已核，2026-10-26）**：
  - helm/K8s 路径：chart 已显式兜底（L3 缺陷 #43：`NEXUS_EVENTSOURCING_STORE` / `NEXUS_WEBHOOK_DLQ_STORE` 默认 `memory`）；
  - **compose 路径：`docker-compose.prod.yml` 已于 2026-10-26 同款兜底**；dev compose 由 dev profile 的 memory 开关兜底；
  - **残留陷阱（登记为工程收口项）**：代码默认仍是 kafka——`KafkaEventStore` 与 `DeadLetterQueueService` 均
    `matchIfMissing=true`，且 base `application.yml:353` 显式 `dlq.store: kafka`。**绕开 chart/compose 的裸跑
    （`java -jar` 直启）在无 Kafka 环境仍会踩中**。收口方向：把缺省语义反转为 memory（fail-safe），另立小切片。
- **什么时候加**：需要事件溯源回放 / 高吞吐事件总线时。加法 = 增加 Kafka 容器（compose profile 或 chart 依赖）
  并把上述两个 store 值改回 `kafka`。

### 2. Sentinel Dashboard — 不进交付

- **不装时在工作的**：限流/熔断规则本身走 Nacos 数据源持久化与推送（bridge 的 `sentinel-datasource-nacos`
  已于 v2.74.0 补齐），规则照常生效；dashboard 失联不再被判 DOWN（L3 修复：健康指示器不再因
  dashboard 不可达而 503 杀 Pod）。
- **不装时没有的**：控制台可视化、运行时动态改规则的界面。
- **什么时候加**：需要可视化运维面时。加法 = sentinel-dashboard 容器 + 各服务 yml 的 `sentinel.dashboard` 地址。

### 3. Zipkin Server — 不进交付

- **不装时在工作的**：全链路 tracing 依赖经 `spring-boot-zipkin`（Boot 4 拆分模块，L3 修复过 bridge 缺
  Tracer bean 的问题）；无 server 时 span 导出失败**不阻断启动、不报错刷屏**（L3 预演 24/24 Ready 即无 Zipkin）。
- **不装时没有的**：跨服务调用链可视化。
- **什么时候加**：需要排障全链路时。加法 = zipkin server 容器 + zipkin endpoint 配置。

## 三、工程收口项（不在本文范围，逐项另立 PR）

1. Kafka store 缺省语义反转：`KafkaEventStore` / `DeadLetterQueueService` 的 `matchIfMissing=true` → `false`
   并让 base yml 显式声明 memory（消灭"裸跑即踩中"的残留陷阱，见 §二.1 ⚠️）。
2. Seata store 表 DDL 并入安装脚本（L3 遗留：compose 注释曾误称 auto-DDL，官方
   `script/server/db/mysql.sql` 需安装路径预建）。
3. 客户要求任一扩展时：对应 compose profile / chart 依赖另立 PR，并在本文 §二 登记启用步骤。

## 治理记录

| 日期 | 动作 |
|---|---|
| 2026-10-26 | 建档；口径拍板（最小集）；prod compose 补 store env 兜底；登记收口项 1-3 |
