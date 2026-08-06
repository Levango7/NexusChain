# NexusChain Phase 4 微服务化方案：wallet-service 数据库持久化 + Seata AT 接入

## 第1章 背景与目标

### 1.1 项目背景

NexusChain 是一个区块链支付编排平台，位于 `F:\Nexus\NexusChain`。截至 Phase 3（v1.5.0），服务演进状态如下：

- **Phase 1+2（v1.4.0）**：signing-service / wallet-service / bridge 独立部署 + Nacos 服务发现 + Sentinel 熔断 + OpenFeign 声明式调用
- **Phase 3（v1.5.0）**：gateway 接入 Seata 分布式事务（AT 模式）+ Micrometer 链路追踪 + Feign fallback 绑定
- **Phase 3 遗留技术债（决策点 D8）**：wallet-service 仍使用 `ConcurrentHashMap` / `AtomicReference` / `CopyOnWriteArraySet` 进程内内存存储，未接入数据库持久化，也未接入 Seata AT 分支事务。该决策在 Phase 3 明确留待 Phase 4 处理。

### 1.2 Phase 4 目标

1. **数据库持久化**：wallet-service 引入 Spring Data JPA + Flyway，用数据库表替代所有内存存储结构（`ConcurrentHashMap` / `AtomicReference` / `CopyOnWriteArraySet`），实现重启后状态不丢失。
2. **Seata AT 接入**：wallet-service 作为 RM（Resource Manager）接入 Seata AT 模式，建立 `undo_log` 表，在跨服务事务中自动生成回滚日志；关键写操作方法标注 `@GlobalTransactional` / `@Transactional`，与 gateway（TM）协调全局事务。
3. **测试改造**：现有 106 个单元测试从 Mock 内存存储改为 Mock Repository；新增 `@SpringBootTest` + H2 集成测试；新增 Seata 事务回滚测试。

### 1.3 范围与约束

**本方案范围**：

- 仅涉及 `nexus-wallet-service` 模块
- 仅做方案设计，不写实现代码
- 方案具体到类名、方法名、表名、字段名

**技术约束**：

- SCA 版本矩阵：Spring Boot 3.2.5 / Spring Cloud 2023.0.3 / SCA 2023.0.1.0 / Seata 2.0.0
- Java 17
- 数据库：生产 MySQL 8.x（与 gateway 同构），开发 / 测试 H2 in-memory（与 gateway `application-dev.yml` 同构）
- Flyway migration 版本号：wallet-service 使用独立数据库 `nexus_wallet`，版本号从 `V1` 起独立编号（与 gateway 的 `nexus_gateway` 库 V1-V7 互不干扰）
- 参考 gateway 的 Seata 接入方式（`V7__add_undo_log.sql` + `@GlobalTransactional` + `@Transactional` 双标注）

**不在本方案范围**：

- signing-service / bridge 的持久化改造（各自后续 Phase 处理）
- wallet-service 的 warm 钱包完整实现（当前 `DefaultCustodyService` 仅 hot/cold，warm 为预留）
- 链上真实交易替换 SIMULATED txHash（独立里程碑）

---

## 第2章 问题罗列

### 2.1 内存存储位置清单

通过逐文件审查 `nexus-wallet-service/src/main/java/org/nexus/walletsvc/` 下全部 Java 文件，共发现 **5 处进程内内存存储**，分布在 4 个类中：

**表：内存存储位置对照表**

| # | 类名 | 字段名 | 存储类型 | 键 / 值类型 | 业务概念 | 所在包 |
|---|------|--------|----------|-------------|----------|--------|
| 1 | `DefaultCustodyService` | `hotBalance` | `AtomicReference<BigDecimal>` | 单值 | 热钱包托管余额 | `custody` |
| 2 | `DefaultCustodyService` | `coldBalance` | `AtomicReference<BigDecimal>` | 单值 | 冷钱包托管余额 | `custody` |
| 3 | `DefaultAddressWhitelistService` | `entries` | `ConcurrentHashMap<String, WhitelistEntry>` | address → entry | 地址白名单（含首次提币延迟） | `whitelist` |
| 4 | `DefaultWithdrawalApprovalService` | `requests` | `ConcurrentHashMap<String, WithdrawalRequest>` | requestId → request | 提现审批请求（多签工作流） | `approval` |
| 5 | `DefaultApprovalPolicy` | `whitelist` | `CopyOnWriteArraySet<String>` | address set | 审批策略白名单（地址放行集合） | `approval` |

**关键观察**：

- `WithdrawalRequest` DTO（位于 `org.nexus.sdk.wallet.WithdrawalRequest`）内含 `List<String> approvers` 字段，内存中随 request 一同存储，持久化时需拆为独立的一对多关联表。
- `DefaultCustodyService.seedBalances(BigDecimal hot, BigDecimal cold)` 是测试用种子方法，持久化后需改为通过 Flyway seed data 或 Repository 初始化。

### 2.2 双重白名单存储问题

**问题**：`DefaultAddressWhitelistService.entries`（#3）与 `DefaultApprovalPolicy.whitelist`（#5）存储的是同一业务概念——「允许提现的地址白名单」，但当前是两套完全独立的内存存储，互不同步：

- `DefaultAddressWhitelistService`：面向 `WalletController` 的白名单管理端点（add / remove / check），含 merchantId、label、首次提币延迟等元数据。
- `DefaultApprovalPolicy`：面向 `DefaultWithdrawalApprovalService.requestWithdrawal()` 的 `isAddressWhitelisted(to)` 校验，仅存 address 字符串集合，通过 `addToWhitelist()` / `removeFromWhitelist()` 手动维护。

**影响**：向 `DefaultAddressWhitelistService` 加入白名单的地址，不会被 `DefaultApprovalPolicy` 识别为白名单地址，导致 `requestWithdrawal()` 仍会抛 `IllegalStateException("address not whitelisted")`。两套存储必须统一为一张物理表。

### 2.3 未接入 Seata 的影响

**当前状态**：wallet-service 的 `build.gradle` 已引入 `spring-cloud-starter-alibaba-seata`，`application.yml` 已配置 `seata.tx-service-group: nexus-tx-group`，但：

1. **无数据源**：`application.yml` 无 `spring.datasource` 配置，Seata AT 模式无法代理数据源、无法生成 `undo_log`，AT 分支事务实际未生效。
2. **无 `undo_log` 表**：无 Flyway migration，无 `undo_log` 表 DDL。
3. **无 `@Transactional` / `@GlobalTransactional` 标注**：全部 Service 方法为纯内存操作，无事务边界。

**跨服务事务风险场景**：

```
gateway.refund() (@GlobalTransactional, TM)
  → wallet-service.executeApprovedWithdrawal()   # 更新审批状态 EXECUTED
    → signing-service.signTransfer()              # 签名 + 广播上链
```

当 `signing-service.signTransfer()` 成功（交易已上链）但 `wallet-service` 状态更新失败（如进程崩溃）时，由于 wallet-service 未接入 Seata AT，gateway 的全局事务回滚无法回滚 wallet-service 的状态变更，导致「链上已转账但审批记录仍为 APPROVED」的数据不一致。Phase 4 接入 AT 后，wallet-service 的分支事务自动生成 `undo_log`，全局回滚时自动恢复审批状态。

### 2.4 当前 @Transactional 方法

**审查结果**：wallet-service 当前 **没有任何** `@Transactional` 方法。全部 Service 方法为纯内存读写，无事务边界。Phase 4 需为所有写操作方法补充事务标注。

### 2.5 依赖与配置现状

**表：wallet-service 依赖与配置现状**

| 维度 | 当前状态 | Phase 4 目标 |
|------|----------|-------------|
| `build.gradle` JPA | ❌ 无 `spring-boot-starter-data-jpa` | ✅ 引入 |
| `build.gradle` Flyway | ❌ 无 `flyway-core` | ✅ 引入 |
| `build.gradle` JDBC 驱动 | ❌ 无 H2 / MySQL 驱动 | ✅ 引入（runtimeOnly） |
| `build.gradle` Seata | ✅ 已有 `spring-cloud-starter-alibaba-seata` | 保持 |
| `application.yml` datasource | ❌ 无 | ✅ 新增（MySQL 生产 / H2 dev） |
| `application.yml` jpa | ❌ 无 | ✅ 新增（ddl-auto: validate, open-in-view: false） |
| `application.yml` flyway | ❌ 无 | ✅ 新增（enabled, classpath:db/migration） |
| `application.yml` seata | ✅ 已有基础配置 | ✅ 补充 datasource 代理配置 |

---

## 第3章 需求分析

### 3.1 持久化需求

**FR-P1**：wallet-service 重启后，托管余额（hot / cold）、地址白名单、提现审批请求及其审批人列表必须完整恢复，不得丢失。

**FR-P2**：托管余额的增减（`depositToCold` / `withdrawFromCold` / `rebalance`）必须原子化持久化，并发操作通过乐观锁（version 字段）或行级锁保证一致性，不得出现余额脏读 / 脏写。

**FR-P3**：地址白名单的软删除（`removeWhitelist` 置 `active=false`）必须持久化，重启后仍为非活跃状态。

**FR-P4**：提现审批请求的状态流转（PENDING → APPROVED → EXECUTED / FAILED / REJECTED）必须持久化每一步，且审批人列表（`withdrawal_approvers` 一对多）与主请求记录外键关联。

**FR-P5**：`DefaultApprovalPolicy.isAddressWhitelisted()` 与 `DefaultAddressWhitelistService.isWhitelisted()` 必须查询同一物理表，消除双重存储。

### 3.2 分布式事务需求

**FR-T1**：wallet-service 作为 Seata AT 模式的 RM，其数据源必须被 Seata 代理（`SeataAutoDataSourceProxy`），所有写操作自动生成 `undo_log` 分支事务日志。

**FR-T2**：`DefaultWithdrawalApprovalService.executeApprovedWithdrawal()` 是跨服务事务的关键节点（更新审批状态 + Feign 调 signing-service 签名广播），必须纳入全局事务：

- 当通过 gateway 的 `@GlobalTransactional` 方法（如 `refund`）经 Feign 调用到达 wallet-service 时，wallet-service 自动加入 gateway 开启的全局事务（xid 通过 Feign header 传播）。
- 当通过 `WalletController` 端点直接调用（管理后台场景，无上游全局事务）时，wallet-service 需自行开启全局事务，保证「审批状态更新 + signing-service 签名广播」的原子性。

**FR-T3**：`DefaultCustodyService.depositToCold()` / `withdrawFromCold()` 涉及余额变更，必须标注 `@Transactional`（本地分支事务），在全局事务上下文中自动注册为 AT 分支。

**FR-T4**：全局事务回滚时，wallet-service 的 `undo_log` 必须能正确还原 `custody_balances`、`withdrawal_requests`、`address_whitelist` 表的变更。

### 3.3 测试需求

**FR-E1**：现有 106 个单元测试不得因持久化改造而删除或跳过，改为 Mock Repository 接口（替代当前 Mock 内存存储 / 直接操作 Service 内部字段的方式），保持单测覆盖率和行为契约不变。

**FR-E2**：新增集成测试（`@SpringBootTest` + H2 in-memory + Flyway），覆盖 Repository 层 CRUD、Entity 映射、Flyway migration 执行、Service 与 Repository 的端到端交互。

**FR-E3**：新增 Seata 事务回滚测试，验证当 signing-service 调用失败时，wallet-service 的 `withdrawal_requests` 状态变更和 `custody_balances` 余额变更被自动回滚（`undo_log` 生效）。

---

## 第4章 设计方案

### 4.1 数据库表设计

wallet-service 使用独立数据库 `nexus_wallet`，共 4 张业务表 + 1 张 Seata `undo_log` 表。

#### 4.1.1 custody_balances（托管余额表）

替代 `DefaultCustodyService` 的 `hotBalance` / `coldBalance` 两个 `AtomicReference<BigDecimal>`。采用以 `tier` 为主键的多行设计（而非单行表），便于未来扩展 WARM 层级。

**表：custody_balances 字段说明表**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `tier` | `VARCHAR(16)` | `PRIMARY KEY` | 托管层级：`HOT` / `WARM` / `COLD` |
| `balance` | `DECIMAL(36,18)` | `NOT NULL DEFAULT 0` | 余额，36 位总精度 / 18 位小数（覆盖链上最小单位） |
| `updated_at` | `TIMESTAMP` | `NOT NULL` | 最后更新时间 |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | 乐观锁版本号（@Version） |

**索引**：主键 `tier` 即为唯一索引，无需额外索引。

**Seed data**：Flyway V2 预置 `HOT` 和 `COLD` 两行，balance 初始为 0。

#### 4.1.2 address_whitelist（地址白名单表）

替代 `DefaultAddressWhitelistService.entries`（#3）和 `DefaultApprovalPolicy.whitelist`（#5），统一为单表。

**表：address_whitelist 字段说明表**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT` | `PRIMARY KEY AUTO_INCREMENT` | 自增主键 |
| `address` | `VARCHAR(128)` | `NOT NULL UNIQUE` | 钱包地址（业务唯一键） |
| `label` | `VARCHAR(256)` | `NULL` | 地址标签 |
| `merchant_id` | `VARCHAR(64)` | `NOT NULL` | 商户 ID |
| `added_at` | `TIMESTAMP` | `NOT NULL` | 加入白名单时间 |
| `first_withdrawal_available_at` | `TIMESTAMP` | `NULL` | 首次提币放行时间（addedAt + delay） |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | 是否活跃（软删除标记） |
| `created_at` | `TIMESTAMP` | `NOT NULL` | 记录创建时间 |
| `updated_at` | `TIMESTAMP` | `NOT NULL` | 记录更新时间 |

**索引**：

- `UNIQUE KEY uk_address (address)` — 地址唯一，软删除通过 `active=false` 实现
- `INDEX idx_merchant_active (merchant_id, active)` — 按商户查询活跃白名单（`listByMerchant`）

#### 4.1.3 withdrawal_requests（提现审批请求表）

替代 `DefaultWithdrawalApprovalService.requests`（#4）。

**表：withdrawal_requests 字段说明表**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT` | `PRIMARY KEY AUTO_INCREMENT` | 自增主键 |
| `request_id` | `VARCHAR(64)` | `NOT NULL UNIQUE` | 业务请求 ID（`WD-<uuid>`） |
| `to_address` | `VARCHAR(128)` | `NOT NULL` | 目标提现地址 |
| `amount` | `DECIMAL(36,18)` | `NOT NULL` | 提现金额 |
| `currency` | `VARCHAR(16)` | `NOT NULL` | 币种 |
| `status` | `VARCHAR(32)` | `NOT NULL DEFAULT 'PENDING'` | 状态：PENDING/APPROVED/REJECTED/EXECUTED/FAILED |
| `required_approvers` | `INT` | `NOT NULL` | 所需审批人数 |
| `approved_count` | `INT` | `NOT NULL DEFAULT 0` | 已审批人数 |
| `chain_tx_hash` | `VARCHAR(128)` | `NULL` | 链上交易哈希（EXECUTED 后填充） |
| `rejection_reason` | `VARCHAR(256)` | `NULL` | 拒绝原因（REJECTED / FAILED 时填充） |
| `created_at` | `TIMESTAMP` | `NOT NULL` | 创建时间 |
| `executed_at` | `TIMESTAMP` | `NULL` | 执行时间 |
| `updated_at` | `TIMESTAMP` | `NOT NULL` | 更新时间 |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0` | 乐观锁版本号 |

**索引**：

- `UNIQUE KEY uk_request_id (request_id)` — 业务唯一键
- `INDEX idx_status (status)` — 按状态查询（如查询所有 PENDING 请求）

#### 4.1.4 withdrawal_approvers（提现审批人表）

替代 `WithdrawalRequest.approvers`（`List<String>`，内存中嵌在 request 内）。一对多关联到 `withdrawal_requests`。

**表：withdrawal_approvers 字段说明表**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT` | `PRIMARY KEY AUTO_INCREMENT` | 自增主键 |
| `request_id` | `VARCHAR(64)` | `NOT NULL` | 关联提现请求 ID |
| `approver_id` | `VARCHAR(64)` | `NOT NULL` | 审批人 ID |
| `approved_at` | `TIMESTAMP` | `NOT NULL` | 审批时间 |

**索引与约束**：

- `UNIQUE KEY uk_request_approver (request_id, approver_id)` — 防止同一审批人对同一请求重复审批
- `CONSTRAINT fk_approver_request FOREIGN KEY (request_id) REFERENCES withdrawal_requests(request_id)` — 外键关联

#### 4.1.5 undo_log（Seata AT 回滚日志表）

与 gateway `V7__add_undo_log.sql` 结构完全一致，由 Seata RM 自动读写，应用层不直接操作。

**表：undo_log 字段说明表**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `branch_id` | `BIGINT` | `NOT NULL` | 分支事务 ID |
| `xid` | `VARCHAR(128)` | `NOT NULL` | 全局事务 ID |
| `context` | `VARCHAR(128)` | `NOT NULL` | undo_log 上下文（如序列化方式） |
| `rollback_info` | `LONGBLOB` | `NOT NULL` | 回滚信息 |
| `log_status` | `INT` | `NOT NULL` | 0: normal, 1: defense |
| `log_created` | `DATETIME(6)` | `NOT NULL` | 创建时间 |
| `log_modified` | `DATETIME(6)` | `NOT NULL` | 修改时间 |

**索引**：`UNIQUE KEY ux_undo_log (xid, branch_id)`

### 4.2 Entity 与 Repository 层

#### 4.2.1 Entity 类设计

新增 4 个 JPA Entity 类，位于 `org.nexus.walletsvc.entity` 包（新建）。

**表：Entity 类设计对照表**

| Entity 类 | 映射表 | 主键 | 特殊映射 |
|-----------|--------|------|----------|
| `CustodyBalanceEntity` | `custody_balances` | `tier` (String) | `@Version` 乐观锁；`balance` 用 `BigDecimal` |
| `WhitelistEntryEntity` | `address_whitelist` | `id` (Long) | `address` 唯一约束；`active` Boolean |
| `WithdrawalRequestEntity` | `withdrawal_requests` | `id` (Long) | `requestId` 唯一约束；`status` 用 `@Enumerated(EnumType.STRING)`；`@Version` 乐观锁 |
| `WithdrawalApproverEntity` | `withdrawal_approvers` | `id` (Long) | `requestId` 外键关联 |

**WithdrawalStatus 枚举**：`withdrawal_requests.status` 字段映射为 `WithdrawalRequest.WithdrawalStatus` 枚举（已存在于 `org.nexus.sdk.wallet.WithdrawalRequest`），通过 `@Enumerated(EnumType.STRING)` 持久化。

**Entity 与 DTO 的转换**：`WithdrawalRequestEntity` 与 SDK DTO `WithdrawalRequest` 之间需双向转换。新增 `WithdrawalRequestMapper` 工具类（位于 `org.nexus.walletsvc.entity` 包），负责：

- `toDto(WithdrawalRequestEntity entity, List<WithdrawalApproverEntity> approvers)` → `WithdrawalRequest`
- `toEntity(WithdrawalRequest dto)` → `WithdrawalRequestEntity`

#### 4.2.2 Repository 接口设计

新增 4 个 Spring Data JPA Repository 接口，位于 `org.nexus.walletsvc.repository` 包（新建）。

**CustodyBalanceRepository**：

```
interface CustodyBalanceRepository extends JpaRepository<CustodyBalanceEntity, String>
    // 主键为 tier (String)
    Optional<CustodyBalanceEntity> findByTier(String tier)
```

**WhitelistEntryRepository**：

```
interface WhitelistEntryRepository extends JpaRepository<WhitelistEntryEntity, Long>
    Optional<WhitelistEntryEntity> findByAddress(String address)
    List<WhitelistEntryEntity> findByMerchantIdAndActiveTrue(String merchantId)
    boolean existsByAddressAndActiveTrue(String address)
```

**WithdrawalRequestRepository**：

```
interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequestEntity, Long>
    Optional<WithdrawalRequestEntity> findByRequestId(String requestId)
    List<WithdrawalRequestEntity> findByStatus(WithdrawalStatus status)
```

**WithdrawalApproverRepository**：

```
interface WithdrawalApproverRepository extends JpaRepository<WithdrawalApproverEntity, Long>
    List<WithdrawalApproverEntity> findByRequestId(String requestId)
    boolean existsByRequestIdAndApproverId(String requestId, String approverId)
```

### 4.3 Flyway Migration

wallet-service 的 Flyway migration 位于 `nexus-wallet-service/src/main/resources/db/migration/`，版本号从 `V1` 起独立编号（独立数据库 `nexus_wallet`，与 gateway 的 `nexus_gateway` 库互不干扰）。

**表：Flyway migration 文件清单**

| 文件 | 内容 | 说明 |
|------|------|------|
| `V1__init_wallet_tables.sql` | 创建 4 张业务表 | `custody_balances` / `address_whitelist` / `withdrawal_requests` / `withdrawal_approvers` |
| `V2__seed_custody_balances.sql` | 预置托管余额初始行 | INSERT `HOT` (balance=0) 和 `COLD` (balance=0) |
| `V3__add_undo_log.sql` | 创建 `undo_log` 表 | 与 gateway `V7` 结构一致 |

**SQL 风格约定**（与 gateway 对齐）：

- 所有 `CREATE TABLE` 使用 `IF NOT EXISTS` 保证幂等
- 字符集 `utf8mb4`，引擎 `InnoDB`
- `DECIMAL(36,18)` 统一用于金额 / 余额字段
- `TIMESTAMP` 用于业务时间字段，`DATETIME(6)` 仅用于 `undo_log`（Seata 约定）

### 4.4 Service 层迁移策略

#### 4.4.1 DefaultCustodyService 改造

**改造要点**：

- 删除 `AtomicReference<BigDecimal> hotBalance` 和 `coldBalance` 字段
- 注入 `CustodyBalanceRepository`
- `getHotBalance()` / `getColdBalance()` → `repository.findByTier("HOT"/"COLD").map(CustodyBalanceEntity::getBalance).orElse(BigDecimal.ZERO)`
- `depositToCold()` / `withdrawFromCold()` / `transferColdToHotInternal()` → 查询 Entity → 修改 `balance` → `repository.save()`，依赖 `@Version` 乐观锁防并发覆盖
- `seedBalances()` → 标记 `@Deprecated`，改为通过 Flyway V2 seed data 或 Repository 初始化（测试中可通过 Repository 直接 save）
- 标注 `@Transactional`（本地分支事务，在全局事务上下文中自动注册为 AT 分支）

**方法事务标注**：

**表：DefaultCustodyService 方法事务标注**

| 方法 | 标注 | 理由 |
|------|------|------|
| `depositToCold` | `@Transactional` | 余额变更，需原子化 + AT 分支 |
| `withdrawFromCold` | `@Transactional` | 余额变更 + 审批消耗，需原子化 + AT 分支 |
| `transferColdToHotInternal` | `@Transactional`（private，由 public 方法传播） | 内部转账 |
| `rebalance` | `@Transactional` | 可能触发多次余额变更 |
| `getHotBalance` / `getColdBalance` | 无（只读） | 查询操作 |
| `isColdCustody` / `getCustodyTier` | 无 | 骨架实现，无 DB 操作 |

#### 4.4.2 DefaultAddressWhitelistService 改造

**改造要点**：

- 删除 `ConcurrentHashMap<String, WhitelistEntry> entries` 字段
- 注入 `WhitelistEntryRepository`
- `addWhitelist()` → 构造 `WhitelistEntryEntity` → `repository.save()`，地址唯一约束由 DB 保证（重复添加抛 `DataIntegrityViolationException`，Service 层转为业务异常）
- `removeWhitelist()` → `findByAddress()` → 置 `active=false` → `save()`
- `isWhitelisted()` → `repository.existsByAddressAndActiveTrue(address)`
- `checkFirstTimeWithdrawal()` → `findByAddress()` → 校验 `active` + `firstWithdrawalAvailableAt`
- `listByMerchant()` → `repository.findByMerchantIdAndActiveTrue(merchantId)`
- 返回类型从内部 `WhitelistEntry` 改为 `WhitelistEntryEntity`（或保持 `WhitelistEntry` DTO + 转换，需评估控制器序列化兼容性）

**方法事务标注**：`addWhitelist` / `removeWhitelist` 标注 `@Transactional`；查询方法无标注。

#### 4.4.3 DefaultWithdrawalApprovalService 改造

**改造要点**：

- 删除 `ConcurrentHashMap<String, WithdrawalRequest> requests` 字段
- 注入 `WithdrawalRequestRepository` 和 `WithdrawalApproverRepository`
- `requestWithdrawal()` → 构造 `WithdrawalRequestEntity`（status=PENDING）→ `repository.save()`
- `approve()` → `findByRequestId()` → 校验状态 → 插入 `WithdrawalApproverEntity`（唯一约束防重复审批）→ 更新 `approvedCount` → 达阈值则置 APPROVED → `save()`
- `reject()` → `findByRequestId()` → 校验状态 → 置 REJECTED + rejectionReason → `save()`
- `executeApprovedWithdrawal()` → `findByRequestId()` → 校验 APPROVED → Feign 调 signing-service → 成功置 EXECUTED + chainTxHash / 失败置 FAILED → `save()`
- `getRequest()` → `findByRequestId()` + 加载 approvers → 转换为 DTO
- 标注 `@GlobalTransactional`（见 4.5.2 事务边界分析）

**方法事务标注**：

**表：DefaultWithdrawalApprovalService 方法事务标注**

| 方法 | 标注 | 理由 |
|------|------|------|
| `requestWithdrawal` | `@Transactional` | 纯本地写，无需全局事务 |
| `approve` | `@Transactional` | 纯本地写（审批人 + 状态） |
| `reject` | `@Transactional` | 纯本地写 |
| `executeApprovedWithdrawal` | `@GlobalTransactional` + `@Transactional` | 跨服务调用 signing-service，需全局事务保证原子性 |

#### 4.4.4 DefaultApprovalPolicy 改造（消除双重白名单）

**改造要点**：

- 删除 `CopyOnWriteArraySet<String> whitelist` 字段
- 注入 `WhitelistEntryRepository`
- `isAddressWhitelisted()` → `repository.existsByAddressAndActiveTrue(address)`，与 `DefaultAddressWhitelistService.isWhitelisted()` 查询同一物理表
- `addToWhitelist()` / `removeFromWhitelist()` → 标记 `@Deprecated`，白名单管理统一通过 `DefaultAddressWhitelistService`（管理端点）进行。`DefaultApprovalPolicy` 仅保留查询职责，不再承担白名单写入。
- `getRequiredApprovers()` 保持不变（纯金额阈值计算，无 DB 操作）

**注意**：`DefaultApprovalPolicy` 实现的是 SDK 接口 `org.nexus.sdk.signing.ApprovalPolicy`，该接口定义了 `isAddressWhitelisted()` 和 `getRequiredApprovers()`。改造后 `isAddressWhitelisted()` 委托 Repository 查询，接口契约不变。

### 4.5 Seata AT 接入

#### 4.5.1 数据源代理配置

Seata AT 模式要求数据源被 `SeataAutoDataSourceProxy` 代理。SCA 2023.0.1.0 + seata-spring-boot-starter 2.0.0 在 `seata.enabled=true` 时自动代理 Spring Boot 的主数据源，无需手动配置 `DataSourceProxy`。需确保：

- `application.yml` 中 `seata.enabled: true`（已有）
- `seata.tx-service-group: nexus-tx-group`（已有，与 gateway 一致）
- `seata.service.vgroup-mapping.nexus-tx-group: default`（已有）
- 数据源由 HikariCP 管理（Spring Boot 默认），Seata 自动代理 HikariDataSource

#### 4.5.2 事务边界分析

**图：wallet-service 跨服务事务边界示意图**

```
场景 A：gateway 退款流程（gateway 是 TM）
  gateway.PaymentServiceImpl.refund()  [@GlobalTransactional, TM 开启全局事务]
    → Feign → wallet-service.executeApprovedWithdrawal()  [xid 传播, RM 分支事务]
      → Feign → signing-service.signTransfer()  [xid 传播, RM 分支事务]
  gateway 提交/回滚全局事务 → 所有 RM 分支提交/回滚

场景 B：管理后台直接调 wallet-service（wallet-service 是 TM）
  WalletController.executeWithdrawal()
    → DefaultWithdrawalApprovalService.executeApprovedWithdrawal()  [@GlobalTransactional, TM 开启全局事务]
      → Feign → signing-service.signTransfer()  [xid 传播, RM 分支事务]
  wallet-service 提交/回滚全局事务 → 所有 RM 分支提交/回滚
```

**@GlobalTransactional 标注策略**：

- `DefaultWithdrawalApprovalService.executeApprovedWithdrawal()`：标注 `@GlobalTransactional(timeoutMills = 120000, rollbackFor = Exception.class)` + `@Transactional`。
  - 场景 A：gateway 已开启全局事务，Seata 检测到已存在 xid，wallet-service 加入当前全局事务（不嵌套新建），`@GlobalTransactional` 注解在已存在事务上下文中退化为分支事务参与方。
  - 场景 B：无上游全局事务，wallet-service 作为 TM 新开启全局事务，signing-service 作为 RM 加入。
- 其余纯本地写方法（`requestWithdrawal` / `approve` / `reject` / `depositToCold` / `withdrawFromCold` / `rebalance` / `addWhitelist` / `removeWhitelist`）：仅标注 `@Transactional`。在全局事务上下文中自动注册为 AT 分支；无全局事务时为普通本地事务。

#### 4.5.3 undo_log 表 Flyway migration

`V3__add_undo_log.sql` 与 gateway `V7__add_undo_log.sql` 结构完全一致（Seata AT 标准表结构），`IF NOT EXISTS` 保证幂等。

#### 4.5.4 与 gateway 的协调

**表：wallet-service 与 gateway Seata 角色协调对照表**

| 维度 | gateway | wallet-service |
|------|---------|----------------|
| Seata 角色 | TM（Transaction Manager） | RM（Resource Manager）+ 候选 TM |
| `@GlobalTransactional` | `PaymentServiceImpl.refund` / `SubscriptionServiceImpl` | `DefaultWithdrawalApprovalService.executeApprovedWithdrawal` |
| `tx-service-group` | `nexus-tx-group` | `nexus-tx-group`（一致） |
| `undo_log` 表 | `nexus_gateway` 库 | `nexus_wallet` 库 |
| xid 传播 | Feign header（Seata 自动） | Feign header（Seata 自动） |

**关键约束**：两个服务必须使用相同的 `tx-service-group`（`nexus-tx-group`），且指向同一个 Seata Server（`NEX_SEATA_SERVER`），否则全局事务无法跨服务协调。当前配置已满足（两者 `application.yml` 均配置 `nexus-tx-group` + `localhost:8091`）。

### 4.6 测试改造方案

#### 4.6.1 现有 106 个单元测试改造

**当前测试方式**：直接实例化 Service（`new DefaultCustodyService(...)`），通过 `seedBalances()` 等方法操作内部内存字段，或 Mock `SigningServiceFeignClient` 等 Feign 客户端。

**改造策略**：

- **Mock Repository**：用 `@Mock` / `@InjectMocks`（Mockito）Mock 4 个 Repository 接口，替代直接操作内存字段。例如 `DefaultCustodyServiceTest` 中 `seedBalances(hot, cold)` 改为 `when(custodyBalanceRepository.findByTier("HOT")).thenReturn(Optional.of(new CustodyBalanceEntity("HOT", hot)))`。
- **保持行为契约**：测试断言不变（仍验证余额、状态流转、异常抛出等），仅调整 Arrange 阶段（从操作内存字段改为 Mock Repository 返回值）和 Act 阶段（Service 方法签名不变）。
- **乐观锁测试**：新增 `OptimisticLockException` 场景测试，Mock `repository.save()` 抛 `OptimisticLockingFailureException`，验证 Service 层处理（重试或抛业务异常）。

**改造工作量估算**：106 个测试中，约 70 个涉及 `DefaultCustodyService` / `DefaultAddressWhitelistService` / `DefaultWithdrawalApprovalService` / `DefaultApprovalPolicy` 的测试需改造 Arrange 阶段；约 36 个纯逻辑测试（如 `DefaultApprovalPolicy.getRequiredApprovers` 金额阈值计算、地址格式校验）无需改造。

#### 4.6.2 新增集成测试

新增 `@SpringBootTest` + H2 in-memory 集成测试，位于 `nexus-wallet-service/src/test/java/org/nexus/walletsvc/integration/`。

**测试配置**：

- `application-test.yml`：H2 in-memory 数据源 + Flyway `classpath:db/migration` + Seata 禁用（`seata.enabled=false`，集成测试不依赖 Seata Server）
- 使用 `@ActiveProfiles("test")` 激活测试配置

**集成测试覆盖范围**：

**表：集成测试类清单**

| 测试类 | 覆盖范围 |
|--------|----------|
| `CustodyBalanceRepositoryIT` | `CustodyBalanceRepository` CRUD + 乐观锁 + Flyway seed data |
| `WhitelistEntryRepositoryIT` | `WhitelistEntryRepository` CRUD + 唯一约束 + 软删除 + 按商户查询 |
| `WithdrawalRequestRepositoryIT` | `WithdrawalRequestRepository` + `WithdrawalApproverRepository` CRUD + 外键约束 + 防重复审批 |
| `CustodyServiceIT` | `DefaultCustodyService` 端到端（Repository + Entity）+ 余额变更 + 冷钱包上限 |
| `WhitelistServiceIT` | `DefaultAddressWhitelistService` 端到端 + 首次提币延迟 |
| `WithdrawalApprovalServiceIT` | `DefaultWithdrawalApprovalService` 端到端 + 状态流转 + 审批人累计 |
| `FlywayMigrationIT` | Flyway V1/V2/V3 全部 migration 成功执行 + 表结构验证 |
| `WalletControllerIT` | `WalletController` 端到端（`@SpringBootTest` + `MockMvc`）+ 全部 REST 端点 |

#### 4.6.3 Seata 事务回滚测试

新增 Seata 事务回滚测试，位于 `nexus-wallet-service/src/test/java/org/nexus/walletsvc/seata/`。

**测试策略**：由于 Seata Server 在单元 / 集成测试环境中不可用，采用以下两种方式：

**方式 1：Mock Seata 行为（单元层）**

- Mock `SigningServiceFeignClient.signTransfer()` 抛异常
- 验证 `executeApprovedWithdrawal()` 中 `withdrawal_requests` 状态变更被回滚（`@Transactional` 回滚 + `@GlobalTransactional` rollbackFor 触发）
- 验证 `withdrawal_requests` 记录状态仍为 APPROVED（而非 FAILED），因为事务回滚

**方式 2：Testcontainers + Seata Server（集成层，可选）**

- 使用 Testcontainers 启动 Seata Server 2.0.0 容器 + MySQL 容器
- `@SpringBootTest` 真实启动 wallet-service + Seata 代理数据源
- 模拟跨服务事务：wallet-service 开启 `@GlobalTransactional` → 更新 `withdrawal_requests` → 调 signing-service（Mock 或 Testcontainers）失败 → 全局回滚
- 验证 `undo_log` 表记录被清理 + `withdrawal_requests` 状态还原
- **注意**：方式 2 需要 Docker 环境，标记为 `@EnabledIfEnvironmentVariable(named="NEX_SEATA_IT", matches="true")`，CI 环境可选执行

---

## 第5章 任务拆分

### 5.1 任务清单

每个任务 2-4 小时，列出 `target_files` 和依赖关系。

**表：Phase 4 任务拆分**

| ID | 任务 | 工时 | target_files | blocked_by |
|----|------|------|--------------|------------|
| T1 | build.gradle 添加 JPA / Flyway / JDBC 依赖 + application.yml 添加 datasource / jpa / flyway / seata 配置 + application-dev.yml (H2) + application-test.yml | 2h | `nexus-wallet-service/build.gradle`, `nexus-wallet-service/src/main/resources/application.yml`, `nexus-wallet-service/src/main/resources/application-dev.yml`(新建), `nexus-wallet-service/src/test/resources/application-test.yml`(新建) | — |
| T2 | 编写 Flyway migration：V1（4 张业务表）+ V2（seed custody_balances）+ V3（undo_log） | 2h | `nexus-wallet-service/src/main/resources/db/migration/V1__init_wallet_tables.sql`(新建), `V2__seed_custody_balances.sql`(新建), `V3__add_undo_log.sql`(新建) | T1 |
| T3 | 创建 4 个 Entity 类 + WithdrawalRequestMapper | 3h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/entity/CustodyBalanceEntity.java`(新建), `WhitelistEntryEntity.java`(新建), `WithdrawalRequestEntity.java`(新建), `WithdrawalApproverEntity.java`(新建), `WithdrawalRequestMapper.java`(新建) | T1 |
| T4 | 创建 4 个 Repository 接口 | 1h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/repository/CustodyBalanceRepository.java`(新建), `WhitelistEntryRepository.java`(新建), `WithdrawalRequestRepository.java`(新建), `WithdrawalApproverRepository.java`(新建) | T3 |
| T5 | DefaultCustodyService 改用 Repository + @Transactional 标注 | 3h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/custody/DefaultCustodyService.java` | T4 |
| T6 | DefaultAddressWhitelistService 改用 Repository + @Transactional 标注 | 2h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/whitelist/DefaultAddressWhitelistService.java` | T4 |
| T7 | DefaultWithdrawalApprovalService 改用 Repository + @GlobalTransactional / @Transactional 标注 | 3h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultWithdrawalApprovalService.java` | T4 |
| T8 | DefaultApprovalPolicy 改用 WhitelistEntryRepository（消除双重白名单） | 2h | `nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultApprovalPolicy.java` | T4, T6 |
| T9 | 单元测试改造：Mock Repository 替代 Mock 内存存储（约 70 个测试） | 4h | `nexus-wallet-service/src/test/java/org/nexus/walletsvc/custody/DefaultCustodyServiceTest.java`, `.../whitelist/DefaultAddressWhitelistServiceTest.java`, `.../approval/DefaultWithdrawalApprovalServiceTest.java`, `.../approval/DefaultApprovalPolicyTest.java` | T5, T6, T7, T8 |
| T10 | 新增集成测试：Repository IT + Service IT + FlywayMigrationIT + WalletControllerIT | 4h | `nexus-wallet-service/src/test/java/org/nexus/walletsvc/integration/*.java`(新建) | T2, T5, T6, T7, T8 |
| T11 | 新增 Seata 事务回滚测试（方式 1 Mock + 方式 2 Testcontainers 可选） | 3h | `nexus-wallet-service/src/test/java/org/nexus/walletsvc/seata/WithdrawalRollbackTest.java`(新建), `SeataIntegrationTest.java`(新建) | T7, T10 |

**总工时**：29h（约 4 个工作日）

### 5.2 依赖关系图

**图：Phase 4 任务依赖关系图**

```
T1 (依赖/配置)
 ├── T2 (Flyway migration)
 │    └── T10 (集成测试)
 ├── T3 (Entity)
 │    └── T4 (Repository)
 │         ├── T5 (CustodyService 改造) ──┐
 │         ├── T6 (WhitelistService 改造) ┤
 │         │    └── T8 (ApprovalPolicy 改造)
 │         └── T7 (WithdrawalApprovalService 改造)
 │              └── T9 (单元测试改造)
 │                   └── T10 (集成测试)
 │                        └── T11 (Seata 回滚测试)
 └── T7
```

**关键路径**：T1 → T3 → T4 → T7 → T9 → T10 → T11（最长 20h）

**可并行任务**：

- T2 与 T3 可并行（T1 完成后）
- T5 / T6 / T7 可并行（T4 完成后）
- T8 在 T6 完成后启动（依赖白名单 Repository 设计确定）

---

## 第6章 风险点

### 6.1 乐观锁并发冲突

**风险**：`custody_balances` 表并发更新（如 `rebalance` 与 `depositToCold` 同时执行）时，`@Version` 乐观锁可能抛 `OptimisticLockingFailureException`。

**缓解**：Service 层捕获 `OptimisticLockingFailureException`，重试 2-3 次（指数退避）；超过重试次数抛业务异常，由上层（Sentinel / 全局事务）处理。重试逻辑封装在 `CustodyServiceRetryHelper` 或通过 Spring Retry（`@Retryable`）实现。

### 6.2 Seata AT 与 H2 兼容性

**风险**：Seata AT 模式的 `undo_log` 表使用 `LONGBLOB` 类型，H2 in-memory 数据库对 `LONGBLOB` 的支持有限（H2 用 `BLOB` / `VARBINARY`）。Flyway migration 在 H2 上执行 `LONGBLOB` 可能失败。

**缓解**：Flyway migration 使用 H2 兼容的列类型定义，或通过 Flyway placeholder / profile 区分 MySQL 和 H2 的 DDL。具体方案：`V3__add_undo_log.sql` 中 `rollback_info` 列使用 `LONGBLOB`（MySQL）并在 H2 profile 下通过 `spring.flyway.scripts` 或单独的 H2 兼容脚本处理。gateway 的 `application-dev.yml` 已用 H2 + Flyway 成功执行 `V7__add_undo_log.sql`，可参考其方案（gateway 已验证可行）。

### 6.3 @GlobalTransactional 传播语义

**风险**：`executeApprovedWithdrawal` 标注 `@GlobalTransactional`，在 gateway 已开启全局事务时（场景 A），Seata 2.0.0 的传播行为需验证：是加入当前全局事务（期望）还是嵌套新建新事务（非期望）。

**缓解**：Seata 2.0.0 `@GlobalTransactional` 在已存在 xid 时默认加入当前全局事务（不嵌套），与 `@Transactional` 的 `REQUIRED` 传播类似。Phase 3 的 `seata-poc` 已验证此行为（`.codeartsdoer/specs/microservice-phase3/seata-poc/`）。Phase 4 需在 T11（Seata 回滚测试）中显式覆盖场景 A（gateway → wallet-service 传播）和场景 B（wallet-service 自启）。

### 6.4 WithdrawalRequest DTO 与 Entity 转换

**风险**：`WithdrawalRequest` 是 SDK 共享 DTO（`org.nexus.sdk.wallet.WithdrawalRequest`），被 `WalletController` 直接返回给调用方。改为 Entity 后，控制器返回类型需从 DTO 转换，可能影响 JSON 序列化字段顺序 / 兼容性。

**缓解**：`WalletController` 返回类型保持 `WithdrawalRequest` DTO，Service 层内部用 Entity，通过 `WithdrawalRequestMapper` 转换。DTO 字段集和 JSON 结构不变，调用方无感知。

### 6.5 DefaultApprovalPolicy 注入改造的循环依赖

**风险**：`DefaultApprovalPolicy`（实现 `ApprovalPolicy` 接口）注入 `WhitelistEntryRepository`，而 `DefaultWithdrawalApprovalService` 注入 `ApprovalPolicy`。若 `WhitelistEntryRepository` 的实现链路反向依赖 `ApprovalPolicy`，可能形成循环依赖。

**缓解**：`WhitelistEntryRepository` 是 Spring Data JPA 接口代理，不依赖任何 Service / Policy，无循环依赖风险。改造后依赖链为 `DefaultWithdrawalApprovalService → ApprovalPolicy(DefaultApprovalPolicy) → WhitelistEntryRepository`，单向无环。

### 6.6 内存 seedBalances 测试方法废弃

**风险**：`DefaultCustodyService.seedBalances()` 被 106 个单元测试中大量测试调用以初始化余额。废弃后这些测试编译失败。

**缓解**：T9（单元测试改造）统一将 `seedBalances()` 调用改为 Mock `CustodyBalanceRepository.findByTier()` 返回值。`seedBalances()` 方法标记 `@Deprecated` 但暂保留（内部委托 Repository save），待 T9 完成后在后续清理中删除。

---

## 第7章 决策点

### D1：Flyway 版本号起始

**决策**：wallet-service 从 `V1` 起独立编号（V1 / V2 / V3），不复用 gateway 的 V7+。

**理由**：wallet-service 使用独立数据库 `nexus_wallet`，Flyway 版本号是 per-database 的，与 gateway 的 `nexus_gateway` 库完全隔离。独立编号清晰表达「这是 wallet-service 自己的 schema 演进」。

### D2：白名单统一为单表

**决策**：`DefaultAddressWhitelistService` 和 `DefaultApprovalPolicy` 统一查询 `address_whitelist` 单表，消除双重内存存储。

**理由**：两套存储表达同一业务概念（提现地址白名单），双重维护必然导致不一致。统一为单表后，`DefaultApprovalPolicy` 仅保留查询职责（`isAddressWhitelisted`），白名单写入统一通过 `DefaultAddressWhitelistService`（管理端点）。

### D3：executeApprovedWithdrawal 标注 @GlobalTransactional

**决策**：`DefaultWithdrawalApprovalService.executeApprovedWithdrawal()` 标注 `@GlobalTransactional` + `@Transactional`，利用 Seata 传播机制同时支持场景 A（gateway TM）和场景 B（wallet-service 自启 TM）。

**理由**：该方法跨服务调用 signing-service，必须保证「审批状态更新 + 签名广播」原子性。仅标注 `@Transactional` 在场景 B（无上游全局事务）下无法回滚 signing-service 的操作。`@GlobalTransactional` 在已存在 xid 时加入当前事务（场景 A），不存在时新建（场景 B），两种场景均覆盖。

### D4：托管余额表设计——多行 vs 单行

**决策**：`custody_balances` 采用以 `tier` 为主键的多行设计（HOT / COLD 各一行），而非单行表（id=1, hot_balance, cold_balance）。

**理由**：多行设计便于未来扩展 WARM 层级（当前预留），且每行独立 `@Version` 乐观锁，并发更新 HOT 和 COLD 互不阻塞。单行表会导致 HOT 和 COLD 更新共享同一行同一 version，不必要的锁冲突。

### D5：approver 列表——独立表 vs JSON 列

**决策**：审批人列表用独立表 `withdrawal_approvers`（一对多），而非在 `withdrawal_requests` 中用 JSON 列存储。

**理由**：独立表支持 `UNIQUE(request_id, approver_id)` 防重复审批的 DB 级约束，支持按 approver_id 查询（审计需求），且 Seata AT 的 `undo_log` 对独立表的回滚更清晰。JSON 列无法表达这些约束，且 AT 回滚需解析 JSON diff。

### D6：集成测试 Seata 策略——禁用 vs Testcontainers

**决策**：集成测试默认 `seata.enabled=false`（不依赖 Seata Server），Seata 事务回滚测试分两层：方式 1（Mock Seata 行为，默认执行）+ 方式 2（Testcontainers + Seata Server，CI 可选执行）。

**理由**：Testcontainers + Seata Server 需要 Docker 环境，本地开发不一定具备。方式 1 覆盖事务回滚逻辑正确性，方式 2 覆盖真实 Seata Server 集成。CI 环境通过环境变量开关启用方式 2。

### D7：WhitelistEntry 返回类型——Entity vs DTO

**决策**：`DefaultAddressWhitelistService` 返回类型从内部 `WhitelistEntry` 改为 `WhitelistEntryEntity`（JPA Entity），`WalletController` 直接返回 Entity。

**理由**：`WhitelistEntry` 当前是简单 POJO（无 JPA 注解），改为 `WhitelistEntryEntity` 后字段集一致，JSON 序列化结构不变。避免引入额外的 DTO 转换层。若后续需隐藏内部字段，再抽取 `WhitelistEntryDTO`。

### D8：seedBalances 废弃策略

**决策**：`DefaultCustodyService.seedBalances()` 标记 `@Deprecated`，内部委托 `CustodyBalanceRepository.save()`，T9 完成测试改造后在后续清理中删除。

**理由**：直接删除会导致 106 个单元测试编译失败，需与 T9 同步推进。`@Deprecated` 保留过渡期，编译警告驱动测试改造。

---

## 第8章 验收标准

### 8.1 持久化验收

- **AC-P1**：`nexus-wallet-service/src/main/resources/db/migration/` 下存在 `V1__init_wallet_tables.sql`、`V2__seed_custody_balances.sql`、`V3__add_undo_log.sql`，且 `./gradlew :nexus-wallet-service:bootRun`（dev profile, H2）启动时 Flyway 成功执行全部 migration，无报错。
- **AC-P2**：wallet-service 重启后，`GET /api/v1/wallet/custody/balance` 返回的 hot / cold 余额与重启前一致（数据持久化生效）。
- **AC-P3**：`POST /api/v1/wallet/whitelist/add` 加入白名单后重启，`GET /api/v1/wallet/whitelist/check?address=...` 返回 `whitelisted: true`（白名单持久化生效）。
- **AC-P4**：`POST /api/v1/wallet/withdrawal/request` → `approve` → 重启 → `execute`，状态流转正确（审批请求持久化生效）。
- **AC-P5**：`DefaultCustodyService` / `DefaultAddressWhitelistService` / `DefaultWithdrawalApprovalService` / `DefaultApprovalPolicy` 源码中不再出现 `ConcurrentHashMap` / `AtomicReference` / `CopyOnWriteArraySet`（`grep` 验证为 0 匹配）。

### 8.2 Seata AT 验收

- **AC-T1**：`nexus_wallet` 库中存在 `undo_log` 表，结构字段与 gateway `V7__add_undo_log.sql` 一致。
- **AC-T2**：`DefaultWithdrawalApprovalService.executeApprovedWithdrawal()` 方法上存在 `@GlobalTransactional` 和 `@Transactional` 注解（`grep` 验证）。
- **AC-T3**：场景 A（gateway.refund → wallet-service.executeApprovedWithdrawal → signing-service）：signing-service 失败时，wallet-service 的 `withdrawal_requests` 状态变更自动回滚（状态恢复为 APPROVED），`undo_log` 记录被清理。
- **AC-T4**：场景 B（直接调 wallet-service.executeApprovedWithdrawal → signing-service）：signing-service 失败时，wallet-service 自启全局事务回滚，`withdrawal_requests` 状态恢复为 APPROVED。

### 8.3 测试验收

- **AC-E1**：`./gradlew :nexus-wallet-service:test` 全部通过，单元测试数量 ≥ 106（不删除现有测试，允许新增）。
- **AC-E2**：`nexus-wallet-service/src/test/java/org/nexus/walletsvc/integration/` 下存在 8 个集成测试类（见 4.6.2 表），全部通过。
- **AC-E3**：`nexus-wallet-service/src/test/java/org/nexus/walletsvc/seata/` 下存在 Seata 事务回滚测试，方式 1（Mock）默认通过，方式 2（Testcontainers）在 `NEX_SEATA_IT=true` 环境下通过。
- **AC-E4**：JaCoCo 覆盖率不下降（与 Phase 3 基线对比，新增 Entity / Repository / 改造 Service 均有测试覆盖）。

### 8.4 配置验收

- **AC-C1**：`nexus-wallet-service/build.gradle` 包含 `spring-boot-starter-data-jpa`、`flyway-core`、`h2`（runtimeOnly）、`mysql-connector-j`（runtimeOnly）依赖。
- **AC-C2**：`nexus-wallet-service/src/main/resources/application.yml` 包含 `spring.datasource`、`spring.jpa`、`spring.flyway` 配置段。
- **AC-C3**：`nexus-wallet-service/src/main/resources/application-dev.yml` 存在，配置 H2 in-memory 数据源 + H2 dialect。
- **AC-C4**：`seata.enabled: true` + `seata.tx-service-group: nexus-tx-group` 与 gateway 一致（已有，保持）。

---

## 附录 A：调研数据

### A.1 内存存储原始位置

```
DefaultCustodyService.java:47    private final AtomicReference<BigDecimal> hotBalance = new AtomicReference<>(BigDecimal.ZERO);
DefaultCustodyService.java:48    private final AtomicReference<BigDecimal> coldBalance = new AtomicReference<>(BigDecimal.ZERO);
DefaultAddressWhitelistService.java:46   private final Map<String, WhitelistEntry> entries = new ConcurrentHashMap<>();
DefaultWithdrawalApprovalService.java:58 private final Map<String, WithdrawalRequest> requests = new ConcurrentHashMap<>();
DefaultApprovalPolicy.java:41            private final Set<String> whitelist = new CopyOnWriteArraySet<>();
```

### A.2 gateway 参考文件

- `nexus-gateway/src/main/resources/db/migration/V7__add_undo_log.sql` — undo_log 表 DDL
- `nexus-gateway/src/main/resources/db/migration/V1__init_schema.sql` — 表设计风格参考
- `nexus-gateway/build.gradle` — JPA / Flyway / JDBC 依赖配置参考
- `nexus-gateway/src/main/resources/application.yml` — datasource / jpa / flyway / seata 配置参考
- `nexus-gateway/src/main/resources/application-dev.yml` — H2 dev profile 配置参考
- `nexus-gateway/src/main/java/org/nexus/gateway/service/PaymentServiceImpl.java:202` — `@GlobalTransactional` + `@Transactional` 双标注参考

### A.3 SDK 共享类型

- `org.nexus.sdk.wallet.WithdrawalRequest` — 提现请求 DTO（含 `WithdrawalStatus` 枚举 + `List<String> approvers`）
- `org.nexus.sdk.wallet.WalletTier` — 钱包层级枚举（HOT / WARM / COLD）
- `org.nexus.sdk.signing.ApprovalPolicy` — 审批策略接口（`isAddressWhitelisted` / `getRequiredApprovers`）