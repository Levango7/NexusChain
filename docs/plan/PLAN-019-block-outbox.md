# 方案 PLAN-019：持久化区块 outbox（Phase 6C 第三切片）

> 状态：实现完成，验收见 §四
> 前置：[PLAN-017](PLAN-017-account-store-degradation.md)（Phase 6C 第一切片：
> `PendingBlocksManager` 的内存重试队列）、[PLAN-018](PLAN-018-finality-persistence.md)
> （Phase 6C 第二切片：最终性侧的持久化 outbox，**两者是同构但独立的两套东西**）

---

## 一、问题与目标

PLAN-017 第一切片给 `PendingBlocksManager` 加了**内存**重试队列：区块写库失败时
留在内存里，每 5 秒重试一次。诚实边界写在当时的文档里：「队列不持久化，进程崩溃即丢」。

**本切片**把这一层补成持久化 outbox：

- 写库失败时，除了内存队列，还把「写入意图」（区块本体）落进
  `pending_block_outbox` 表；
- 进程重启后由 `restoreFromOutbox()` 捞回来重新投进采纳路径，
  不必等 P2P 同步再拉一遍；
- 补写成功后清掉对应行。

### 1.2 查证源码后修正的认知（重要，别按旧说法理解）

动手前先查了写链路径，发现两个**既有事实**，它们决定了本切片的真实边界：

**F1：写库失败被 `RDBMSBlockChainImpl.writeBlock` 吞掉。**

```java
// RDBMSBlockChainImpl.writeBlock
Boolean result = txTmpl.execute((TransactionStatus status) -> {
    try { writeHeader(block); writeBody(block); }
    catch (RuntimeException e) {
        logger.error("RDBMSBlockChainImpl: uncaught exception", e);
        status.setRollbackOnly();
        return false;          // ← 异常被吞，返回 false
    }
    return true;
});
```

而 `StateDB.writeBlock` 拿到 `false` 后**跳过该块**并打印
「will be retried on next confirmation pass」（A2 审计修复，2026-08-31），
由 StateDB 自己的确认轮次补写。

⇒ 结论：**"区块写库失败"既不抛异常、也不进任何重试队列**，
它由 `StateDB` 的确认轮次机制兜住。PLAN-017 第一切片里
`PendingBlocksManager` 的 `catch (DataAccessException)` 实际只覆盖
**读**失败（父块查询/校验读），不覆盖写失败。

**F2：接收侧把"存储不可用"当成"区块无效"丢掉。**

`SyncClient.receiveBlocks` 里 `rule.validateBlock(b)` 的结果若是
`account store unavailable` 标记（PLAN-018 引入），旧逻辑走
`logger.error("invalid block received")` 后 `continue` —— **区块被静默丢弃**；
而校验过程直接抛异常时，异常会逃出 `@Async` 方法，同样丢掉。
两者都只能靠后续 P2P 同步把区块重新拉回来。

⇒ 本切片因此包含两件事：**① 持久化 outbox（把"待补写"这件事持久化）；
② 接收侧不再丢区块（存储故障时改交 PendingBlocksManager 走重试/outbox 通道）。**

| | 最终性票（PLAN-018） | 区块（本切片） |
|---|---|---|
| 谁签的 | **本节点**签发 | 别人产生 |
| 丢了能补回来吗 | **不能** —— 别人没法替你签 | **能** —— P2P 同步会重新拉 |
| 持久化 outbox 的价值 | 保住不可再生的票 | 省掉一次同步风暴 + 让「待补写」可观测 |

所以本切片的收益**低于** PLAN-018：它防的是「进程崩溃时丢掉已收到但未落库的区块」，
而这个损失原本可以靠 P2P 同步兜底。它**不**解决「数据库整体不可用期间区块不落库」
（那时 outbox 表本身也写不进去，仍靠内存队列兜底）。

### 1.1 先说清楚价值边界（别夸大）

## 二、根因与修复

### 2.1 存储层

新增 `org.nexus.core.pending.PendingBlockOutbox`（接口，default 方法 ⇒ 未装配时
调用方自动退化为纯内存队列）+ `JdbcPendingBlockOutbox`（JDBC 实现）。

表结构（additive-only、幂等 DDL、`SCHEMA_VERSION=1` 不升版本）：

```sql
CREATE TABLE IF NOT EXISTS pending_block_outbox (
  block_hash VARCHAR(128) NOT NULL PRIMARY KEY,   -- 区块哈希 hex
  height     BIGINT NOT NULL,
  payload    TEXT NOT NULL,                        -- 区块 JSON（codec.encodeBlock）
  created_at BIGINT NOT NULL,
  attempts   INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512))
```

- 载荷用 `TEXT` 存 JSON：编码器 `JSONEncodeDecoder` 产出的就是 JSON 文本，
  与 `JdbcFraudProofStateStore` 的批次 JSON 同款，不必再 base64/hex 一层。
- 组件**可选装配**：没有 `JdbcTemplate`（dev profile / 无库）时**禁用**而不是启动失败，
  调用方退化为内存队列（与 PLAN-017 行为一致）。

### 2.2 `PendingBlocksManager` 接线

- 写库/校验失败 → 内存队列 **+** 持久化 outbox（`enqueueStorageRetry`）。
- 写链成功 → 清掉 outbox 行。
- 启动后（`@Scheduled(initialDelay=20s)`）→ `restoreFromOutbox()` 把待补写区块
  重新投进采纳路径（内部仍走校验 + 写链 + 清理）。
- 观测：`GET /consensus/block-outbox` 返回 `memoryPending` / `databasePending` /
  `enabled`。

### 2.3 接收侧不再丢区块（F2 的修复）

`SyncClient.receiveBlocks` 现在区分三种结果：

| 校验结果 | 旧行为 | 新行为 |
|----------|--------|--------|
| 通过 | 交 manager | 不变 |
| `Result.Error`（区块无效） | 丢弃 | 不变 |
| `account store unavailable` 标记 | **丢弃** | 交 manager → 重试 + outbox 捕获 |
| 抛异常（读失败等） | **异常逃出 @Async，整批丢弃** | 交 manager → 重试 + outbox 捕获 |

### 2.4 一个必须处理的坑：「缓存命中 ≠ 已落库」

`stateDB.writeBlock(b)` 会**先把区块放进内存缓存、再写库**。写库失败时缓存里已经有了，
于是重试时 `stateDB.hasBlockInCache(hash)` 为 true → 直接 `continue` 跳过写入 ——
**如果此时把 outbox 行删掉，就等于把「写入意图」悄悄丢了**（这正是 PLAN-017 记录的窗口）。

因此缓存命中分支改成：

- 查一次 `stateDB.hasBlock(hash)`（真库）确认它**真的**落库了 → 才清 outbox 行；
- 没落库 → **保留** outbox 行（等后续补写），并显式 WARN（诚实暴露，不静默丢弃）。

对应测试：`PendingBlocksManagerStorageRetryTest.cachedButUnpersistedBlockKeepsItsOutboxEntry`。

## 三、测试

| 测试文件 | 覆盖点 |
|----------|--------|
| `JdbcPendingBlockOutboxTest`（新，5 例） | 待补写区块**跨重启**可捞回并解码；重复入队幂等（不产生重复行）；remove 清行；失败计数累加；无 JdbcTemplate 时**禁用**而非抛异常 |
| `PendingBlocksManagerStorageRetryTest`（+2 例） | 写库失败 → 进 outbox；补写成功 → outbox 行被清；**缓存命中但未落库 → outbox 行保留**（不被当成已完成） |

core 全量：**1625 个用例，19 跳过**（其中 `PortableUpsertPostgresTest` 因本机 Docker 忙而瞬时失败，
单独重跑通过 —— Testcontainers 起容器失败，与本次改动无关）；`spotbugsAll` 全模块 **0 finding**。

## 四、真实 PG 验收

环境：同主机双进程 + 容器 PostgreSQL 16（**不是多机高可用**）。
脚本：`C:\Users\winge\AppData\Local\Temp\opencode\p6c-pg-outage.ps1`（临时目录，不入库），
证据原始输出：`p6c-s3-account-console.log`。

### 4.1 主验收：账户表不可用（链继续出块，只有账户读路径坏）

故障注入：把 `account` 表**改名**（读账户失败 ⇒ 梅克尔状态校验返回
`account store unavailable` 标记），链表（`header`/`transaction`）正常 ⇒ 链继续出块。

- 故障时长 **31.5 秒**。
- **故障期间** `GET /consensus/block-outbox`：

  | 节点 | memoryPending | databasePending |
  |------|---------------|-----------------|
  | A | 0 | **1** |
  | B | 1 | **1** |

  ⇒ 收到的区块**没有再被静默丢弃**（F2 已修），而是被 `PendingBlocksManager` 捕获，
  并**持久化进 `pending_block_outbox` 表**（每节点 1 行）。
- **恢复后**：`memoryPending=0`、`databasePending=0` ⇒ outbox 被补写并清空；
  链继续推进到 7726/7726；`header` 表 7735 行。
- 其它：`ERROR` 行 A=2/B=0；`pendingBlock` 超时 0；区块采纳失败 0；
  旧 epoch 的最终性查询全程 `FINALIZED`（2000/2000）。

### 4.2 对照与不可达场景（诚实记录）

| 故障 | 结果 | 说明 |
|------|------|------|
| **PG 整体停机** | `memory=0 db=-1`（读不到行数），链停摆 | 库整体不可用 ⇒ 链停止出块（PLAN-017 的 fail-closed）⇒ 没有新区块可捕获；且此时 outbox 表本身也写不进去（`db` 读不到） |
| **只让 `header` 写入失败**（BEFORE INSERT 触发器） | `memory=0 db=0`，**捕获数为 0** | 写失败被 `RDBMSBlockChainImpl` 吞掉（F1）⇒ `PendingBlocksManager` 根本看不到失败；该场景由 `StateDB` 的确认轮次机制兜住（事后链高度一致、无缺口） |

⇒ 本切片覆盖的是**「区块无法被校验/处理」**（读/账户路径故障），
**不覆盖**「写库失败」——后者是 `StateDB` 的既有机制（见 §5.1）。

### 4.3 结论

1. 接收侧在存储故障时不再丢区块（F2），改由 `PendingBlocksManager` 接管。
2. 待补写区块会**持久化**进 `pending_block_outbox`（故障期 `databasePending=1`），
   恢复后自动补写并清零（`databasePending=0`）。
3. 全程 `pendingBlock` 超时 0、区块采纳失败 0、最终性查询正常。

未证明（勿外推）：多机/多副本、真实网络分区、主机断电、PG 主备切换、
outbox 表自身的复制与故障、"写库失败"路径（见 §4.2）。

## 五、明确边界（勿夸大）

1. **写库失败不由本 outbox 兜底**：它被 `RDBMSBlockChainImpl.writeBlock` 吞成
   `false`，再由 `StateDB` 跳过该块、靠自己的确认轮次补写（F1）。
   这条既有路径本身值得单独审视（"跳过"意味着**该高度区间在 DB 里短暂缺块**，
   且 `StateDB` 的补写只在后续确认轮次发生），已登记为 roadmap 新项。
2. **数据库整体不可用时 outbox 表也写不进去** ⇒ 只剩内存队列兜底，进程崩溃仍丢区块。
2. 本切片**不**解决「数据库不可用期间区块不落库」的持久性问题（那是数据库自己的问题）。
3. 区块丢了原本可由 P2P 同步兜底 ⇒ 本切片的净收益是「少一次同步 + 可观测」，
   不是「数据不可恢复」。
4. `restoreFromOutbox()` 只在启动后跑一次（`outboxRestored` 标记）；若这次恢复因
   数据库未就绪而失败，会在下一轮调度再试（失败不置位标记）。
5. outbox 行**没有过期清理**：若某区块永远写不进去（例如链分叉后被丢弃），
   行会滞留。可观测端点能看出滞留量；清理策略（按高度/时间淘汰）留待后续。
6. 未覆盖：多副本共享一个 PG 时谁负责补写（与 PLAN-018 的同名边界）、
   outbox 表自身的复制与故障、多机环境。

## 六、后续顺序

1. **3 节点多主机混沌测试**（真正的多机、网络分区、主机断电）——本切片与 PLAN-018
   都已具备"被测对象"；
2. **real-L1 / 桥资金链路持久化**；
3. 性能、灾备与外部安全验证。

### 6.1 本切片派生出的两个新缺口（已登记到 roadmap）

| # | 缺口 | 说明 |
|---|------|------|
| **F1** | 「区块写库失败」被吞成 `false`，`StateDB` 跳过该块 | 跳过意味着**该高度区间在 DB 里短暂缺块**，补写依赖后续确认轮次；失败原因只进日志，运维看不到聚合视图 |
| **F2** | 接收侧把「存储不可用」当「区块无效」丢弃 | 本切片已修（接收侧改交 manager）；但同类判断还在其它路径上（见 roadmap） |
