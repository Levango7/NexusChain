# 方案 PLAN-037：桥 messaging 装配与中继循环（roadmap #33）

- **状态**：**切片 1 + 切片 2 + 切片 3 已交付（2026-10-25/26）**：切片 1 =
  装配开关 + Bean 生命周期 + 就绪观测（#249）；切片 2 = 中继循环（#250）；
  切片 3 = 模拟链装配 + 观测端点 + 注册表键语义修正（本 PR）。**仍未接真实
  外部链**（消息前段：外部链事件 → 落库，属切片 4）
- **前置**：PLAN-026（消息存储落库 memory/jpa 开关）、PLAN-016/021/025（资金安全链路）
- **范围**：`nexus-bridge` 内 `MessageRelayer` / `MessageExecutor` / 中继循环 /
  适配器注册表的**生产装配**：开关默认关、Bean 化、可观测、可回归
- **不包含**：真实链事件源（消息前段）、流动性持久化、断路器跨重启
  （roadmap #33 其余欠账）

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
- `requiredSignatures` / `messageTimeout` 外置（复用既有
  `@ConfigurationProperties` Bean `messageConfig`；`required-signatures<=0` 为
  非法配置 ⇒ 启动 fail-fast——见本节末修正注记）；
- `BridgeMessagingStatus` 只读快照：开关/签数/超时/store 实现/适配器数。

**为什么切片 1 只做装配**：真实链适配器（ChainAdapter）涉及外部链凭证与
网络出口，本机无授权也无外部链；先把"插座"实体化并纳入回归，
切片 2 再接模拟/真实 adapter + 消息循环（执行/落库/观测）。

> **2026-10-26 修正（全上下文集成测试发现）**：本切片最初自建 `MessageConfig`
> Bean，与既有 `messageConfig`（`@Configuration +`
> `@ConfigurationProperties("nexus.bridge.messaging")`，自 1.9.2）类型重复 ⇒
> `relay-enabled=true` 时完整上下文启动失败（`NoUniqueBeanDefinitionException`）。
> 已改为复用既有绑定 Bean；"0 签静默回落 2 签"同步改为启动 fail-fast。修正由
> PLAN-038 引入的 `BridgeMessagingEndpointIntegrationTest` 发现并锁定。

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

## 六、切片 3：模拟链装配 + 观测端点 —— 已交付（2026-10-26）

**两个交付物 + 一个装配缺口修正**：

1. **`SimulatedChainAdapter`**（`org.nexus.bridge.adapter`）：内存态模拟链，
   不连任何外部链——`sendTransaction` 返回 `SHA-256(chainId‖tx‖序号)` 派生的
   确定性伪 txHash；回执可查（已提交）；模拟高度=已提交交易数。
2. **观测端点 `GET /api/v1/bridge/messaging`**（`BridgeMessagingController`）：
   返回装配快照（开关/签数/超时/store/适配器数）；装配关闭时返回
   `{relayEnabled:false, reason}`——端点恒可用，一处回答"插座通没通电"。
   安全：随既有白名单在 `SecurityConfig` 放行（与 `/status` 同口径）。
3. **装配缺口修正（键语义）**：`MessageExecutor` 以 `message.targetChain` 查
   适配器，但切片 1/2 的注册表键是 Spring 收集 `Map<String,ChainAdapter>` 的
   **bean 名**（如 `ethereumAdapter`）——真实消息永远匹配不到 ⇒ 本切片改为
   按 `ChainAdapter.getChainId()` 归键；容器适配器（真实链 `@Component`）
   优先，`simulated-target-chains` 的模拟链补齐、**不覆盖同名链**。

**装配参数**：`nexus.bridge.messaging.simulated-target-chains`（逗号分隔，
默认空=不装配）、`execute-enabled`（默认 false）——两者组合出"模拟闭环"：
`relay-enabled=true` + `simulated-target-chains=L2-sim` + `execute-enabled=true`
⇒ 消息可全链路走到 EXECUTED（无真实链）。

**测试（本切片新增 5 例，模块全量 606 例通过）**：

| 用例 | 断言 |
|---|---|
| `SimulatedChainAdapterTest`（2） | 伪哈希格式/派生化/回执/模拟高度；chainId 透传 |
| `MessagingRelayConfigTest` 新增（2） | 模拟链装配进注册表；同名链真实优先不覆盖 |
| `BridgeMessagingControllerTest`（2） | 关闭 ⇒ relayEnabled=false+reason；开启 ⇒ 完整快照 |
| `MessagingSimulatedLoopTest`（1） | **端到端**：签名入库 → 循环 → 模拟链执行 → EXECUTED+记账；幂等 |

**边界（勿夸大）**：

- **不是"已接链"**：模拟链交易/回执均为内存态（进程重启即丢）；真实链接入
  仍需部署方配置真实适配器（RPC 凭证在网络出口侧）；
- **消息前段仍未交付**：外部链事件 → 构造消息 → 落库，依赖真实链事件源
  （模拟事件源如需要可另立"测试床演示"小切片，但不算生产链路）；
- 真实 `@Component` 适配器（Ethereum/BSC/Polygon/Avalanche/Solana）在
  relay-enabled=true 时会被收集并按 chainId 归键——**无凭证时执行会失败
  并按失败隔离置 FAILED**（`execute-enabled` 默认 false 挡住此路径）。

## 七、后续

1. 消息前段（真实链事件源 → 落库；模拟事件源可选做测试床演示）。
2. #33 剩余项已由 **PLAN-038** 收口：日限额持久化已交付；`DefaultLiquidityManager`
   暂不持久化、`CircuitBreaker` 保持重启复位（决策与 revisit 条件见 PLAN-038 §2.3）。
