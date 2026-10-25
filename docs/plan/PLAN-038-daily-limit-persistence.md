# 方案 PLAN-038：#33 余项收口——日限额持久化与余项处置决策

- **状态**：已交付（2026-10-26）；新区测试 + 模块全量回归
- **前置**：PLAN-026（消息存储 memory/jpa 开关先例）、PLAN-037（桥装配系列）、
  roadmap #33 ②③
- **范围**：`BridgeServiceImpl` 日限额计数器 + `DailyLimitAuditService` 快照历史
  的持久化；#33 其余两项（`DefaultLiquidityManager` / `CircuitBreaker`）的处置决策
- **不包含**：日限额语义变更（滚动窗口/谓词保持原样）、配额预留移入业务事务
  （明确保持"先占后补偿"契约）、V6 之外的 schema 变更

---

## 一、问题（#33 ② 的深挖）

roadmap 原记录："`DefaultLiquidityManager`（池余额/基线）与 `DailyLimitAuditService`
（日限额历史）仍为内存态且未装配"。深挖后发现**真实风险面比记录更大**——日限额的
**计数器本身**（`BridgeServiceImpl.dailyUsed`）也是内存态：

1. **重启绕过**：`dailyUsed` 是进程内 `AtomicLong`（lazy 24h 重置）。重启 ⇒ 当日
   用量归零 ⇒ **当日限额可被"重启"绕过**（安全类控制失效）；
2. **多副本各持一份**：prod bridge `replicaCount=3`（FundConservationChecker 头注），
   日限额实际是"每副本 100B"而非"全局 100B"——**可允出 N 倍**；
3. **审计历史易失**：午夜快照存内存 map，重启即丢；且多副本各自为政
   （`getFullHistory()` 因副本而异）。

## 二、方案

### 2.1 计数器：`DailyUsageStore` 抽象（memory/jpa 双档，镜像 MessageStore 先例）

- **接口**（`org.nexus.bridge.safety.DailyUsageStore`）：`currentUsed` /
  `tryReserve(amount, limit, now)` / `release(amount, now)`；语义与历史逐条一致：
  滚动 24h 窗口、`已用 + amount > limit` 拒绝（严格大于）、下限 0、过窗读 0 /
  下次写滚动。写操作必须**立即提交、独立于调用方业务事务**（见下）。
- **`InMemoryDailyUsageStore`（默认档）**：与原 `AtomicLong + CAS + lazy reset`
  行为等价；包级构造器可指定初始窗口起点（确定性时间测试）。
- **`JdbcDailyUsageStore`（jpa 档）**：单行表 `bridge_daily_usage`（id=1）：
  - **REQUIRES_NEW 独立提交**——忠实复刻"配额先占、业务回滚由 afterCompletion
    补偿归还"的既有契约。若让写参与业务事务，回滚会自动退回配额、再叠加补偿
    ⇒ **双重归还、限额被低估**（这是本设计的关键决策点）；
  - **行级悲观写锁**（`findByIdForUpdate`）⇒ 多副本并发预留串行化 ⇒ **全局限额**；
  - 冷启动插入竞争由主键约束 + 有界重试兜底。
- **`BridgeServiceImpl`**：字段 `@Autowired(required = false)` 注入 store（字段初值
  为 InMemory，单测/嵌入式零改动，沿用 `circuitBreaker` 同款注入惯例）；
  `getStatus / reserveDailyQuota / releaseDailyQuota` 改走 store；删除
  `dailyUsed / dailyResetTime / resetDailyIfNeeded`。
- **开关**：`nexus.bridge.daily-usage.store=memory|jpa`（默认 memory=既有行为
  零变化；生产应配置 jpa）。

### 2.2 审计历史：快照落库

- 新表 `bridge_daily_limit_snapshot`（`date_key` PK / `used` / `captured_at`）。
- `DailyLimitAuditService`：午夜快照写库（**同日覆盖幂等**——多副本重复触发
  无副作用）；读取仓库优先、内存降级（仓库不可用/单测与历史一致）。
- 迁移 `V6__daily_usage_and_audit.sql`（与实体字段一致；CI Flyway 预检 +
  ddl-auto 双轨，同 V5 模式）。

### 2.3 余项处置决策（#33 ②③）

- **`DefaultLiquidityManager`（池余额/基线）：暂不持久化/装配。**
  侦察结论：该类**零生产使用点**（全仓库仅其单测引用）——生产资金流由
  `BridgeService` 日限额 + 链上执行器（`BridgeOnChainExecutor`）承担；
  持久化一个未接线的组件属于投机性建设。
  **revisit 条件**：真实多链部署需要"按链备付金/流动性管理"时——先接线生产流，
  再按 `DailyUsageStore` 同款模式落库。
- **`CircuitBreaker` 跨重启：保持"重启即复位"，不加持久化。**
  理由：其设计定位是**进程内的瞬时故障门闩**（`acquirePermission /
  recordSuccess / recordFailure`），跨重启的"持久冻结"语义已由 DB 级紧急暂停
  （`DefaultEmergencyPauseService` + `FundConservationChecker`）承担——两者职责
  不同，不应叠加。revisit：若治理要求"熔断必须人工解除"，应扩展紧急暂停路径
  而非本类。

## 三、测试

| 用例 | 断言 |
|---|---|
| `InMemoryDailyUsageStoreTest`（4） | 积累 / 严格大于谓词 / 归还钳制 0 / 过窗读 0+写滚动 / 过窗归还空操作 |
| `JdbcDailyUsageStoreTest`（5） | 语义对齐 + **重启不清零**（同仓库新实例）+ **多副本共享**（新实例继续预留、原实例可见）+ 过窗 |
| `BridgeDailyUsageWiringTest`（3） | getStatus 读 store / 预留拒绝映射 `BridgeException` / 归还委托 |
| `DailyLimitAuditServicePersistenceTest`（2） | 快照落库 + 重启可读 / 同日幂等覆盖 |
| `BridgeMessagingEndpointIntegrationTest`（1，新） | **全上下文冒烟**：Security 过滤链放行 + relay-enabled=true 全装配 + jpa 日限额 store 选择 + 端点快照字段 |
| `MessagingRelayConfigTest`（更新） | 绑定注册器接入（plain context 绑定基础设施）+ 0 签改为**启动 fail-fast** |
| 既有回归 | `BridgeStateMachineTest`（超限路径）、`DailyLimitAuditServiceTest`（内存降级路径）等 |

> 全上下文测试立了一功：它发现并锁定了 **PLAN-037 切片 1 的 `MessageConfig`
> 双 Bean 启动缺陷**（`relay-enabled=true` 时完整上下文
> `NoUniqueBeanDefinitionException`）——修正记录见 PLAN-037 §二 末注记。

模块全量回归见 CHANGELOG。

## 四、边界（勿夸大）

- **默认档仍是 memory**（既有行为零变化）；"重启不清零 + 全局限额"需部署方
  配置 `jpa` 后生效——与消息存储同款开关哲学（生产应配 jpa）；
- 窗口语义未变（**滚动 24h**，非自然日）——审计快照仍按自然日（Asia/Shanghai）
  记录（历史口径保留，与滚动窗口的错位属既有事实，本次不改）；
- jpa 档每笔跨链操作多一次短事务（配额预留）；当前量级（桥日限额 ~1e11 最小单位）
  无压力，未做批量聚合优化；
- **memory 档白天重启仍会清零**（与历史一致，仅在 jpa 档消除）。
