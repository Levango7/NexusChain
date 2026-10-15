# PLAN：网关调度器内存态持久化（KEK 轮换进度 + TCC/SAGA 参与方注册表）

状态：**立项（待批准实施）**
日期：2026-10-13
前置：#97（escrow/预授权/KEK 调度器补锁）、#98（AutoReversal 可见性）、#99（交易对账审计补锁）已合入 master `32a50b62`；V98 订阅扣款幂等表方案 D 在途。

## 1. 问题

网关有两处调度器把**跨请求、跨重启必须存活的状态放在单个 JVM 的 `ConcurrentHashMap`**，
在三副本部署（`deploy/helm/values-prod.yaml:21` `nexus-gateway.replicaCount: 3`）下：

| # | 状态 | 位置 | 后果 |
|---|---|---|---|
| A | KEK 轮换迁移进度 `migrationProgressMap` | `security/encryption/KeyRotationScheduler.java:48` | 轮换/迁移进度仅本副本可见；Pod 重调度后进度丢失，`migrateDeks` 断点位置归零 |
| B | TCC 参与方注册表 `actionRegistry` | `transaction/TccTransactionManager.java:44` | 见 3.1：进程重启后恢复调度器查不到 action |
| C | SAGA 步骤注册表 `stepRegistry` | `transaction/SagaTransactionManager.java:59` | 同 B，`retryCompensate` 查不到 steps |

### 3.1 关键风险：B/C 的"查不到就降级标 FAILED"

`TccTransactionManager.retryConfirm`（`:256-266`）与 `retryCancel`（`:301-311`）在
注册表 miss 时（注释即写明 "service may have restarted"）**直接把日志标 `FAILED` 返回，
不做任何资源释放**；`SagaTransactionManager.retryCompensate`（`:250-254`）同理。

`TransactionRecoveryScheduler`（`transaction/TransactionRecoveryScheduler.java`，`@Scheduled(fixedDelay=60000)`）
又正是**靠这两个方法**做超时恢复。于是重启后出现的最坏路径是：

> Try 已成功预留资源（如冻结余额）→ 服务重启 → 注册表空 →
> 恢复调度器扫描到 TRY 超时 → `retryCancel` miss → **标 FAILED，冻结永不释放**。

即：**该释放的资源被静默放弃**，且日志上"看起来处理过了"。这不是"重复执行"型缺陷，
而是"该做的没做还标成处理完"型缺陷——资金/库存会永久挂起。

### 3.2 KEK：进度丢失不至于资损，但破坏轮换语义

`migrationProgressMap`（A）丢失不会直接资损：`migrateDeks`（`:128`）按
`kekVersion + id 游标`重新扫描，重建进度对象后会从头迁（幂等，`:129-133` 有兜底重建）。
但两处问题：
1. `checkKekRotationPeriod`（`:63` cron，**无 `@SchedulerLock`**）遍历的正是这张内存表来决定
   "是否继续迁移 / 是否需轮换"，三副本各看各的表 → 判断不一致；
2. `initiateRotation` 生成的 `MigrationProgress` 只登记在本副本，另两副本 `getMigrationProgress` 永远拿不到
   → 运维观测到的进度取决于打到哪个副本。

## 2. 为什么"直接补 @SchedulerLock"是错的（#97/#99 的经验不适用）

#97/#99 的修法（给 `@Scheduled` 加 `@SchedulerLock`）成立的前提是：
**任务本身无状态，多个副本重复跑才是问题，锁掉多余副本即可。**

但 A/B/C 是**每-JVM 内存状态**：`@SchedulerLock` 只能保证"某一时刻只有一个副本执行"，
**不能让状态跨副本共享**。更糟的是它可能锁住一个**恰好没有该状态的副本**：

- 三副本，`retryCancel` 的 action 只注册在副本 1 的内存里；
- `@SchedulerLock` 恰好让副本 2 拿到锁 → 副本 2 的注册表空 → 走 miss 分支 → **标 FAILED**；
- 不但没修复，反而把"副本 1 本可成功释放"的机会锁没了。

结论：**A/B/C 的正确修法是"先把状态落到共享存储，再谈加锁"**。
（这也是 #97 已收敛的结论：locking 与 in-memory state 是正交的两件事。）

## 3. 已有基建（本设计尽量复用，零新增中间件）

| 组件 | 位置 | 状态 |
|---|---|---|
| `TransactionLog` 实体（含 `payload TEXT`、`version` 乐观锁列） | `transaction/TransactionLog.java:59,78` | 成熟；`payload` 列**当前闲置**，可直接承载序列化的参与方描述符 |
| `TransactionLogRepository`（`findByTransactionTypeAndStepStatusAndUpdatedAtBefore` 等） | `transaction/` | 已有超时扫描/按 txId 查询 |
| `TccRegistrationBean` 风格的本副本装配 | `security/encryption/`（KEK 侧） | 轮换进度表可按同风格落库 |
| Flyway 迁移（gateway：master 现最高 V98，在途 #106 排到 V102） | `nexus-gateway/src/main/resources/db/migration/` | **V99–V102 已被在途 #106 占 ⇒ `kek_rotation_progress` 实落 V103**（2026-10-15 改号） |
| `@SchedulerLock`（ShedLock） | 已在 #97/#99 铺开 | 持久化**之后**再给 `checkKekRotationPeriod` 补，顺序不能反 |

## 4. 设计

### 4.1 TCC/SAGA 参与方描述符持久化（解决 B/C，最高优先）

`TccAction`（`transaction/TccAction.java`）是带闭包的接口（`tryAction/confirmAction/cancelAction`），
**不可序列化**，因此不能直接把 action 存库。改造分两步：

1. **落库什么**：在 Try 阶段把**重建 action 所需的全部输入**写进 `TransactionLog.payload`
   （已存在的列）：参与方标识 `participantId` + `businessReference` + 端点/方法描述符 + 幂等键。
   对本地 bean 型参与方，`payload` 存 `{beanName, methodName, argsJson}`；对远程 HTTP 参与方，
   存 `{url, verb, bodyJson, idempotencyKey}`。
2. **重建什么**：新增 `TccActionResolver`——`resolve(TransactionLog) → Optional<TccAction>`：
   优先查本进程注册表（快速路径，保持现状零回归）；miss 时按 `payload` 描述符重建一个
   **可重放的 action**（本地 bean 反射调用 / 远程 HTTP 调用，二者都要求被调方幂等，
   这与现有 `TccAction` 契约的"必须幂等"完全一致）。
3. **miss 语义收敛（关键）**：`retryConfirm`/`retryCancel`（`:256`/`:301`）与
   `retryCompensate`（`:250`）的 miss 分支**不再标 `FAILED`**，改为：
   - 能按 `payload` 重建 → 正常执行（回到现有成功/失败分支）；
   - 描述符缺失或重建失败 → 标**新增状态 `PENDING_MANUAL`** + `errorMessage`
     写明"需人工确认资源是否已释放" + 发告警；**绝不静默当失败**。
   - `TransactionStepStatus` 枚举（`transaction/TransactionStepStatus.java`）新增 `PENDING_MANUAL`；
     恢复调度器的超时扫描**排除** `PENDING_MANUAL`（避免每 60s 反复重试已交人工的项）。

> 安全取向：与 #97 定的"宁可漏收、不可双扣"同源——此处是"宁可显式挂起 + 告警，
> 不可静默标 FAILED 假装处理过"。资金释放要么真做，要么显式留痕给人。

### 4.2 KEK 轮换进度持久化（解决 A，次优先）

新增表 `kek_rotation_progress`（Flyway **V103**；立项时记 V99，落地时 V99–V102 已被在途 #106 占用）：

```sql
CREATE TABLE kek_rotation_progress (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    old_version        INT          NOT NULL,
    new_version        INT          NOT NULL,
    total_records      BIGINT       NOT NULL,
    migrated_records   BIGINT       NOT NULL DEFAULT 0,
    failed_records     BIGINT       NOT NULL DEFAULT 0,
    last_processed_id  BIGINT       NOT NULL DEFAULT 0,
    completed          BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_krp_old_version UNIQUE (old_version)
);
```

- `KeyRotationScheduler` 的 `migrationProgressMap` 降级为**本副本写穿缓存**：
  读 = 先查库（权威），命中则回填缓存；写 = 每次批量游标推进后 upsert 到库。
  `lastProcessedId` 成为**跨副本共享断点**，任一副本接着迁都从同一游标续。
- 迁移完成判定（`:187-198` 的 `countByKekVersionAndStatus==0` → `archiveKek`）本就基于库，
  天然多副本安全；只需保证 `completed` 标志位也落库，避免副本各自重复触发 `archiveKek`（幂等）。
- **持久化之上**再给 `checkKekRotationPeriod`（`:63`）补 `@SchedulerLock`——此时锁才是有意义的
  （锁住的是"谁来做本轮检查"，状态已在库里）。

### 4.3 落地顺序（严格串行，避免把内存态问题掩盖成并发态问题）

1. TCC/SAGA：`payload` 落库 + `PENDING_MANUAL` 状态 + Resolver（先做，资金语义）
2. KEK：`kek_rotation_progress` 表 + 写穿缓存
3. 两者都落库后，才给 `checkKekRotationPeriod` 补 `@SchedulerLock`
4. 最后才谈 task #24 的全局门禁（"新增 `@Scheduled` 必须带锁或显式豁免"）——门禁应包含
   一条豁免说明：允许"内存态尚未持久化的调度器"显式登记排队，而不是硬要它加锁。

## 5. 测试计划

1. **TCC 重启恢复**（核心红→绿）：Try 成功预留 → 丢弃进程内注册表（新 manager 实例）→
   恢复调度器扫描 → 断言**资源被真正释放**（Cancel 被调用），且日志状态为 `SUCCESS`/`CANCEL`，
   **不是 `FAILED`**。
2. **TCC 描述符缺失**：Try 成功但 `payload` 为空 → 恢复 → 断言状态 = `PENDING_MANUAL` + 告警，
   且**不重复触发**（第二趟扫描跳过）。
3. **SAGA 重启恢复**：同 1，断言逆向补偿真的执行。
4. **KEK 断点续传跨副本**：本副本迁前 100 条 → 新建第二个 scheduler 实例（共享库）→
   续迁剩余 → 断言 `lastProcessedId` 从库里的断点继续，不从头扫。
5. **KEK 归档幂等**：两副本同时判定完成 → `archiveKek` 只生效一次。

## 6. 验收

- 恢复调度器对"注册表 miss"**不再产出假 FAILED**；资金释放要么真发生、要么 `PENDING_MANUAL` 留痕。
- KEK 迁移进度在任意副本可查、可续；Pod 重启不丢断点。
- 三副本下 `checkKekRotationPeriod` 单次执行（补锁后）。

## 7. 风险与边界

| 风险 | 等级 | 缓解 |
|---|---|---|
| `payload` 描述符与实际 action 行为漂移（改了方法签名忘了改描述符） | 中 | Resolver 重建失败即 `PENDING_MANUAL` + 告警；加"重建后与注册表版行为等价"的单测 |
| 远程参与方幂等性依赖对端 | 中 | `TccAction` 契约本就要求幂等；Resolver 发远程请求必须带 `idempotencyKey`（复用 `businessReference`） |
| `PENDING_MANUAL` 堆积无人处理 | 中 | 接入现有告警通道；恢复调度器日志按 `PENDING_MANUAL` 计数打点 |
| V99 与其它在途迁移号冲突 | 低 → **已发生**（2026-10-15） | 立项时 master 最高 V98，本表记 V99；落地时**在途 #106 已排到 V99–V102**。同号双份会被 Flyway `Found more than one migration with version` 直接把 migrate 判红 ⇒ 本表改落 **V103**（改前对全部本地/远端 ref 现查过号）。教训：号不是"立项时占"，是**提交前必须再查一次** |
| 反射重建本地 bean 的 argsJson 反序列化安全 | 低 | 仅允许 `participantId` 白名单内的 bean；argsJson 走 Jackson，不开 `default typing` |

## 8. 明确不做

- 不把闭包 `TccAction` 直接序列化（Java 闭包不可序列化，硬塞会引入更脆的序列化契约）。
- 不做跨副本的"实时注册表同步"（引入共识成本；持久化 + 按需重建已够）。
- 不改 TCC/SAGA 的编排语义与幂等契约（只补"重启后可恢复"这一维度）。

## 9. 工作量

约 4-6 天：TCC/SAGA 持久化 + Resolver + 测试 2.5 天；KEK 表 + 写穿缓存 + 测试 1.5 天；
补锁 + 门禁豁免登记 + 文档 1 天。
