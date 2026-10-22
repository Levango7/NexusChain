# 方案 PLAN-026：桥持久化 —— 跨链消息存储落库（Phase 6C 第八切片）

> 前置摸底（2026-10-23，roadmap #33）：桥的资金安全状态**已经落库**——
> 幂等键（lock/mint/burn/unlock 四操作全用 + 24h TTL + DB 唯一约束兜底）、
> Saga（每步 save + 崩溃恢复 `recoverIncompleteSagas` + 指数退避重试）、
> 紧急暂停（含启动恢复 `findAll`）、保险基金流水、重放 nonce（ReplayProtection）。
> 真正缺的是**消息层**：`MessageStore` 只有内存实现。

## 一、问题

`MessageStore` 支撑消息中继的三个正确性机制：

| 机制 | 依赖的状态 | 内存实现的问题 |
|------|-----------|----------------|
| 消息去重 | `messageId → 已存在` | 重启后重复中继 ⇒ 目标链重复执行 |
| 顺序保证 | per 源链 `max(nonce)` | 重启后 nonce 水位回退 ⇒ 乱序/重放窗口 |
| 执行追溯 | `messageId → txHash` | 重启后仲裁/对账没有证据 |

接口注释自己就声明："生产环境应使用 Redis / 数据库实现；
InMemoryMessageStore 仅供测试与单实例开发使用" —— 但数据库实现一直不存在。

## 二、方案

### 2.1 存储：`bridge_messages` 表（实体 `BridgeMessageRecord`）

- `message_id` 主键（去重靠它 + 插入冲突兜底）；
- `(source_chain, nonce)` 复合索引支撑 `max(nonce)` 查询；
- `execution_tx_hash` + `status=EXECUTED` 落执行结果；
- `version` 乐观锁列（与 SagaInstance 同风格）。

**payload 不存 JSON**：按 `(payload_type, payload_data, payload_encoded_data)`
三列存储。原因：`MessagePayload` 的 `type`/`data` 是 final 字段且没有无参构造器，
直接反序列化要给它加 Jackson 注解 —— 三列存储避免把传输类与序列化框架耦合。

**signatures** 是 hex 字符串列表，以换行符连接存储（hex 字符不含换行，无损可逆）。

### 2.2 `JdbcMessageStore`：与 InMemory 语义逐条对齐

| 方法 | 语义（两者一致） | 并发语义（Jdbc 特有） |
|------|------------------|------------------------|
| `save` | 已存在 ⇒ false | 先查后插；插入冲突由主键兜底，捕获 `DataIntegrityViolationException` ⇒ false（先到者胜） |
| `getMaxNonce` | 空链 / null ⇒ -1 | `coalesce(max(nonce), -1)` 下沉到 SQL |
| `recordExecution` | 不存在 ⇒ false；存在 ⇒ 落哈希 + `EXECUTED` | 乐观锁保护，并发执行由上层幂等检查拦截 |
| `findById` / `existsById` / `getExecutionTxHash` / `clear` | 同 | — |

### 2.3 装配开关（默认保持既有行为）

```yaml
nexus:
  bridge:
    messaging:
      store: ${BRIDGE_MESSAGE_STORE:memory}   # memory（默认）| jpa
```

`MessageStoreConfig` 两个互斥条件 Bean：`memory`（默认，`matchIfMissing=true`）
与 `jpa`。**默认不变**的原因：messaging 模块（`MessageRelayer`/`MessageExecutor`）
目前只在测试里构造、未装配进生产流（见 §四边界）—— 开关让"消息模块上线"
变成一个配置动作，而不是一次代码改动。

### 2.4 迁移双轨（与 V0 同模式）

nexus-bridge **运行时不用 Flyway**（schema 由 Hibernate `ddl-auto=update` 维护，
`application.yml:102` 有明确声明）；`db/migration/V0..V5` 是 CI Flyway 预检用的
"未接线"历史文件。因此：

- 运行时：实体上 `@Table` + `ddl-auto=update` 自动建表；
- 预检：`V5__bridge_messages.sql`（MySQL 8 / H2 兼容语法，与 V0/V3 的写法一致）；
- 两处字段保持一致（V0 头注的要求）。

## 三、测试

`JdbcMessageStoreTest`（6 例，H2 + `@DataJpaTest`，全绿）：

1. 保存新消息 ⇒ true；`findById` 往返（payload 类型/数据、签名列表、状态）；
2. 相同 `messageId` 重复保存 ⇒ false（去重）；
3. per 源链 `max(nonce)` 隔离；空链 / null ⇒ -1；
4. `recordExecution` 落哈希 + `EXECUTED`；消息不存在 ⇒ false；
5. **重启语义**：同一 Repository 上新建第二个 store 实例（模拟进程重启）
   ⇒ 去重 / nonce 水位 / 执行追溯全部保住，重复保存仍被拒绝；
6. `clear` 清空全部。

全套 `:nexus-bridge:test` 591 例，0 失败。

**踩坑记录（Spring Boot 4 测试切片模块化）**：`@DataJpaTest` 移到
`spring-boot-data-jpa-test`（包名 `org.springframework.boot.data.jpa.test.autoconfigure`），
`@AutoConfigureTestDatabase` 移到 `spring-boot-jdbc-test`
（包名 `org.springframework.boot.jdbc.test.autoconfigure`）——
与 build.gradle 里 `@AutoConfigureMockMvc ⇒ spring-boot-starter-webmvc-test` 的先例一致。

## 四、明确边界（勿夸大）

- **本切片不把 messaging 模块装配进生产流**：`MessageRelayer`/`MessageExecutor`
  仍只在测试里构造。store=jpa 装配后也不会立即产生生产效果 —— 这是"消息模块上线"
  的前置地基，不是上线本身。
- **liquidity / daily-limit 仍是内存态**：`DefaultLiquidityManager`（池余额/基线）与
  `DailyLimitAuditService`（日限额历史）同样未装配进生产流，持久化留给它们的装配切片。
- **CircuitBreaker 状态重启清零**：是否需要跨重启取决于安全语义（清零可能反而更保守），
  已登记、未决策。
- **单机 H2 测试**：6 例测试跑在 H2 上；未在真 PostgreSQL/MySQL 上验证 DDL 差异
  （`TEXT` 列类型在 MySQL 8 与 H2 下等价，PostgreSQL 走 `ddl-auto` 由 Hibernate 方言处理）。

## 五、后续顺序

1. messaging 模块装配（生产流接入 MessageRelayer/MessageExecutor + store=jpa）；
2. `DefaultLiquidityManager` / `DailyLimitAuditService` 持久化 + 装配；
3. 多机验收（用户明确要求做，用本机可用资源：WSL2 / Windows Sandbox 等真实网络边界）。
