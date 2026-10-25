# 方案 PLAN-037：桥 messaging 装配与中继循环（roadmap #33）

- **状态**：**切片 1 + 切片 2 已交付（2026-10-25）**：切片 1 = 装配开关 + Bean
  生命周期 + 就绪观测（#249）；切片 2 = 中继循环（findPending → 验签 → 可选
  执行 → 记账，本 PR）。**未接真实外部链**（消息前段：外部链事件 → 落库，属
  切片 3）
- **前置**：PLAN-026（消息存储落库 memory/jpa 开关）、PLAN-016/021/025（资金安全链路）
- **范围**：`nexus-bridge` 内 `MessageRelayer` / `MessageExecutor` / 中继循环的
  **生产装配**：开关默认关、Bean 化、可观测、可回归
- **不包含**：ChainAdapter 真实链接入（消息前段）、观测端点、流动性持久化、
  断路器跨重启（roadmap #33 其余欠账）

---

## 一、问题（在册欠账）

roadmap #33 登记的欠账：`MessageRelayer` / `MessageExecutor` 自 1.9.2 起存在且
单测完整，但**只在测试里 new、从未装配进生产流** ⇒ "装配前 store=jpa 不产生
实际效果"。PLAN-026 只解决了 store 选择，没解决"谁来驱动中继/执行"。

## 二、方案（切片 1：通电，不点火）

新增 `MessagingRelayConfig`（`nexus.bridge.messaging.relay-enabled`，**默认关**）：

- 开启时创建 4 类 Bean：`MessageFormatter` / `MessageRelayer` / `MessageExecutor`
  / `BridgeMessagingStatus`；
- 关闭时（缺省）**一个 Bean 都不创建**——既有部署行为零变化（单测锁定）；
- store 沿用 `MessageStoreConfig`（memory/jpa），不重复定义；
- `requiredSignatures` / `messageTimeout` 外置（默认 1 签 —— 切片 1 语义是
  自签自验；配置 0/负值时回落 MessageConfig 默认 2 签，不进入非法态）；
- `BridgeMessagingStatus` 只读快照：开关/签数/超时/store 实现/适配器数。

**为什么切片 1 只做装配**：真实链适配器（ChainAdapter）涉及外部链凭证与
网络出口，本机无授权也无外部链；先把"插座"实体化并纳入回归，
切片 2 再接模拟/真实 adapter + 消息循环（执行/落库/观测）。

## 三、测试（`MessagingRelayConfigTest`，3 例）

| 用例 | 断言 |
|---|---|
| `disabledByDefaultCreatesNoMessagingBeans` | 开关缺省 ⇒ relayer/executor/status 均不存在；store Bean 不受影响 |
| `enabledProvidesRelayerThatCanSignAndVerify` | 开启 ⇒ 四 Bean 就位；relayer **真实**签名→多签验签→去重全通过；status 快照 = memory store / 1 adapter |
| `zeroSignaturesFallsBackToDefaultNotCrash` | 配 0 签 ⇒ 回落默认 2 签（不进入 `requiredSignatures<=0` 非法态） |

`nexus-bridge:test` 全量通过（无回归）。

## 四、边界（勿夸大）

- **不宣称"桥 messaging 进入生产流"**：切片 1 只把装配点、生命周期与观测做实；
  `targetAdapters=0` 是预期状态——**没有任何真实消息会被中继或执行**；
- 不开新网络出口、不引新凭证、5xx/超时风险不外溢（Bean 创建失败由 Spring
  启动期暴露，不会静默降级）；
- 多签阈值默认 1 只在"装配自检"语义下成立；真实治理阈值必须部署方显式配置
  （REQ/CG 门限另立切片）。

## 五、切片 2：中继循环 —— 已交付（2026-10-25）

**流转链路**（`MessageRelayWorker`，`@Scheduled` 复用 Spring 调度线程，不新增线程）：

```
findPending（按 sourceChain+nonce 升序）→ 逐条：
  阈值计数检查 → 逐签验签（MessageRelayer.verifySignatures）
  → execute-enabled=true 时 MessageExecutor.executeMessage（内部记账）
单条失败 ⇒ 置 FAILED 留下轮（失败隔离，不阻断整轮）
```

- `MessageStore.findPending(limit)`（default 空实现 + 内存/Jdbc 两实现覆盖）：
  取数语义 = **无执行哈希且状态 ∈ {PENDING, RELAYED, FAILED}**，
  按 (sourceChain, nonce) 升序（与顺序保证的消费方向一致）。
  - **关键语义（测试期抓到的真 bug）**：`relayMessage` 入库置 **RELAYED**
    （"已中继、待执行"的中间态）；早期实现只取 PENDING/FAILED ⇒ 循环永远
    消费不到任何消息。三处实现（接口 default / 内存 / Jdbc JPQL）已同步修正。
- `BridgeMessageRecordRepository.findPending(Pageable)`：JPQL 同语义
  （`status in (PENDING, RELAYED, FAILED)` + `executionTxHash is null/''` +
  `order by sourceChain, nonce`）。
- 装配：`MessagingRelayConfig` 增 `bridgeMessageRelayWorker` Bean（同
  `relay-enabled` 开关控制）；参数 `nexus.bridge.messaging.batch-size:50`、
  `poll-interval-ms:5000`、`execute-enabled:false`（默认）。
- **execute-enabled 默认 false**：只验签观察、不产真实交易；无适配器时不要
  开启（开启后执行会失败并按失败隔离置 FAILED）。

测试（`MessageRelayWorkerTest`，5 例）：

| 用例 | 断言 |
|---|---|
| `consumesPendingInSourceChainNonceOrder` | relayer 顺序入库 1/2/3 → findPending 升序返回 → 整轮全消费 |
| `belowThresholdIsMarkedFailedAndNotExecuted` | 0 签（直存绕过 relayer）→ FAILED 且零执行哈希 |
| `executionDisabledOnlyObserves` | execute-enabled=false ⇒ 不产交易 |
| `executionEnabledRecordsTxHashOnce` | 开启 + 适配器 ⇒ 记账 + EXECUTED；第二轮幂等 0 |
| `storageOutageReturnsZeroWithoutThrowing` | 存储抛异常 ⇒ 记 0，不炸调度器 |

`nexus-bridge:test` 全量通过（无回归）。

**边界（勿夸大）**：

- **消息前段不在本切片**：外部链事件监听 → 构造 `CrossChainMessage` → 落库，
  依赖 ChainAdapter（切片 3）；本切片只消费**已入库**消息的后段；
- **多实例部署**：`@SchedulerLock`（name=`messageRelayWorker`、`lockAtMostFor=PT2M`、
  `lockAtLeastFor=PT2S`）与桥既有调度任务同模式（见 `ShedLockConfig`）——同一
  时刻仅一个实例执行；降级语义同既有（shedlock 表缺失时失去锁保护、调度照常）；
  单轮耗时 >PT2M 的超长边界退化为可并发（量级远小于此，记录为兜底语义）；
- 无退避/限频策略（失败即留下轮、轮询间隔固定 5s）——量级上去前不引入。

## 六、后续（切片 3 起）

1. ChainAdapter 装配（模拟 + 真实两档，开关控制）——消息前段（事件 → 落库）；
2. 观测端点（`/bridge/messaging`，`/consensus/finality-outbox` 同款风格）；
3. `DefaultLiquidityManager` 持久化与 `CircuitBreaker` 跨重启语义（#33 剩余）。
