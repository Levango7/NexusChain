# NexusChain Analytics

NexusChain 数据智能层模块，提供链上数据分析、实时监控、商业智能与数据导出能力。

## 模块定位

`nexus-analytics` 是 NexusChain 的数据消费侧聚合层，向上为运营 / 商户 / 监管提供
分析能力，向下消费 `nexus-core` 与 `nexus-gateway` 产生的链上数据与业务事件。

模块当前为**接口骨架**：所有接口契约已固化，骨架 `@Service` 实现以 TODO 标注待填充点，
便于后续按子能力并行开发。

## 技术栈

| 项 | 版本 |
|----|------|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Gradle | 8.5（与根项目一致） |
| Jackson | 2.15.4 |
| Lombok | 1.18.32 |

## 包结构

```
org.nexus.analytics
├── onchain      链上数据分析（交易图谱 / 地址聚类 / 资金流向）
├── monitoring   实时监控（节点健康 / 区块传播 / 内存池 / 告警）
├── bi           商业智能（交易统计 / 用户分群 / 报告）
└── export       数据导出（CSV / JSON / PARQUET）
```

## 接口清单

### onchain — 链上数据分析

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `TransactionGraphService` | 交易图谱构建、路径发现、地址聚类 |
| 实体 | `AddressCluster` | 地址簇（簇 ID / 地址列表 / 标签 / 置信度） |
| 实体 | `FundFlowTrace` | 资金流向（源 / 目标 / 路径 / 金额 / 时间戳） |
| 骨架 | `DefaultTransactionGraphService` | `@Service` 占位实现 |

**核心方法**

- `buildGraph(address, depth)` — 以地址为根构建 N 跳子图
- `findPath(from, to)` — 查找资金路径
- `getCluster(address)` — 查询地址所属簇

### monitoring — 实时监控

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `ChainMonitorService` | 节点 / 区块 / 内存池监控 |
| 接口 | `AlertService` | 告警产生、确认、查询 |
| 接口 | `AlertRule` | 告警规则求值 |
| 实体 | `Alert` | 告警事件（含 `Level` / `State` 内嵌枚举） |
| 骨架 | `DefaultAlertService` | `@Service` 占位实现 |

**核心方法**

- `ChainMonitorService.monitorNodeHealth() / monitorBlockPropagation() / monitorMempool()`
- `AlertService.raiseAlert(Alert) / acknowledgeAlert(id) / getActiveAlerts()`
- `AlertRule.evaluate(Metric) → Optional<Alert>`

### bi — 商业智能

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `TransactionStatisticsService` | 交易统计与报告生成 |
| 接口 | `UserSegmentation` | 用户分群 |
| 实体 | `StatisticsReport` | 统计报告 |
| 骨架 | `DefaultStatisticsService` | `@Service` 占位实现 |

**核心方法**

- `dailyVolume(date)` — 日交易量
- `topMerchants(topN)` — Top N 商户
- `failureRate()` — 失败率
- `avgLatency()` — 平均确认时延
- `UserSegmentation.segment(userId) / getSegmentProfile(segmentId)`

### export — 数据导出

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `DataExportService` | 链上数据 / 报告异步导出 |
| 枚举 | `ExportFormat` | CSV / JSON / PARQUET |
| 骨架 | `DefaultDataExportService` | `@Service` 占位实现 |

**核心方法**

- `exportChainData(start, end, format) → CompletableFuture<String>`
- `exportReport(reportId) → CompletableFuture<String>`

## 构建

```bash
gradle build
```

模块独立 `settings.gradle` 已配置 `rootProject.name = 'nexus-analytics'`，
可单独构建，也可由根项目 `settings.gradle` 通过 `include 'nexus-analytics'` 收编。

## 后续路线

- [ ] 接入图存储（Neo4j / JanusGraph）实现 `TransactionGraphService`
- [ ] 接入时序数据库（Prometheus / VictoriaMetrics）实现 `ChainMonitorService`
- [ ] 接入列式仓库（ClickHouse / Druid）实现 `TransactionStatisticsService`
- [ ] 接入对象存储（OSS / S3）实现 `DataExportService` 流式写入