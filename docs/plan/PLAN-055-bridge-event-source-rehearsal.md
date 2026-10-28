# 方案 PLAN-055：桥消息前段测试床联调（PLAN-046 §五 留项）

- **状态**：已交付（2026-10-28）；暴露并修复一个**从未被发现**的调度缺陷
- **前置**：PLAN-046（`ChainEventSource` 接口 + `SimulatedChainEventSource` +
  `BridgeEventSourceWorker` + event-source 开关，已合并）、PLAN-037（relay-exec 装配）
- **范围**：本机起 bridge 应用、打开 `relay-enabled=true` + `event-source.enabled=true`，
  观测"事件 → 消息 → 落库 → 中验 → 执行"闭环与 `/api/v1/bridge/messaging` 的
  `eventSource` 活值字段
- **不包含**：真实外部链接入（无凭证/无授权）、产物部署

---

## 一、问题

PLAN-046 的 25 例单测走的是"进程内替身"，而 §五 第 1 条明写：
**未做测试床实机联调**。装配开关类缺陷（Bean 缺失/属性名不匹配/端口/白名单）
历史上多次靠"全上下文集成测试"才暴露（PLAN-038 修复的
`NoUniqueBeanDefinitionException` 即此类）——本项就是给 event-source 补这一层。

## 二、实验设计（判据预登记）

拓扑：本机单 bridge 应用 + nexus-core 的 PG（55433，`nexuschain`）；
`nexus.bridge.messaging.store=memory`、`relay-enabled=true`、
`event-source.enabled=true`、`event-source.source-chain=L1-sim`、
`event-source.target-chain=L2-sim`、`simulated-target-chains=L2-sim`、
`execute-enabled=true`、`event-source.relayer-private-key=<hex>`、
`required-signatures=1`。

**预登记判据**：
1. **启动**：应用正常启动，`GET /api/v1/bridge/messaging` 返回
   `{"relayEnabled":true, ..., "eventSource":{...}}`（eventSource 字段存在）；
2. **前段**：≥1 个事件经 poll→format→sign→入库（`eventSource.relayedCount ≥ 1`、
   `lastNonce` 从 0 增长）；
3. **中段+执行**：`store` 中消息状态终态为 `EXECUTED`，`getExecutionTxHash` 非空
   （模拟链事务哈希）；relay 循环日志出现 `executed, txHash=`；
4. **幂等**：同一消息不重复执行（`runOnce` 二次处理数为 0，或 txHash 不变）。

## 三、结果（2026-10-28）

### 3.1 判据 1（启动 + 端点字段）✅
- 应用 50.7s 启动成功；`GET /api/v1/bridge/messaging` 返回
  `{"relayEnabled":true,"storeType":"InMemoryMessageStore","targetAdapters":6,
  "eventSource":{"sourceEnabled":true,"sourceChain":"L1-sim","targetChain":"L2-sim",
  "sourceType":"SimulatedChainEventSource",...}}`——eventSource 字段与 PLAN-046 一致。

### 3.2 暴露意外但重要的缺陷：**两个调度循环从未执行过**

首轮运行 `eventSource.pollCount 恒 0、relayedCount 0`，日志定位：

```
net.javacrumbs.shedlock.spring.aop.LockingNotSupportedException:
  Can not lock method returning primitive value
  at ...MethodProxyScheduledLockAdvisor$LockingInterceptor.invoke
  at ...BridgeEventSourceWorker$$SpringCGLIB$$0.runOnce(<generated>)
  at ...ScheduledMethodRunnable.runInternal
```

**ShedLock 4.x 拒绝 primitive 返回类型的 `@SchedulerLock` 方法**——
AOP 在调用前抛异常，`runOnce` 永不执行。`MessageRelayWorker.runOnce()`
（PLAN-026/037 装配的 relay 主循环）同样中招。

⇒ **两条链路的真实历史状态**：自 `relay-enabled=true` 装配以来，
事件源前段与中继后段**在真实部署里从未运行过**；单测全绿是因为测试用
`new` + 手工调 `runOnce()`，绕开 Spring AOP。这正是 PLAN-046 §五留
"未做测试床联调"要防的第 2 类缺陷（第 1 类 = PLAN-038 的
`NoUniqueBeanDefinitionException`）。

### 3.3 处置：返回类型装箱（int → Integer）

- 两处 `runOnce()`：`public int` → `public Integer`（自动装箱，语义零变化）；
- 20 例 messaging 单测不回归（`MessageRelayWorkerTest` 5 /
  `BridgeEventSourceWorkerTest` 6 / `MessagingSimulatedLoopTest` 1 /
  `BridgeMessagingControllerTest` 3 / `MessagingRelayConfigTest` 5）。

### 3.4 复验判据 2/3/4 ✅（复跑）

- **前段**：`eventSource` 计数稳定增长（t+120s: pollCount=42、relayedCount=84、
  lastNonce=83、failedCount=0）；
- **中段 + 执行**：日志 `relay: message 0x... executed, txHash=0x...`
  每条入库消息都被 `MessageRelayWorker` 验签 → 执行到 `L2-sim` 模拟链 → 记账；
- **幂等**：同 `BridgeEventSourceWorkerTest` 单测覆盖（执行完成后下一轮 0 条）；
- **顺带修复**：`nexus-bridge/build.gradle` 补 `runtimeOnly 'org.postgresql:postgresql'`——
  bootJar 原先不含 PG 驱动，单 jar 直跑即 `Cannot load driver class`。

## 四、结论与处置

1. **本切片达到目的**：抓到一个"单测全绿但生产从未执行"的真缺陷
   （ShedLock primitive 返回拒绝），已修复并复验四判据全过；
2. **方法论确认**：装配开关类代码必须走"真起应用"层——
   单测用 `new` 绕开 AOP 是这类缺陷的系统性盲区；
3. **未做**：多副本 ShedLock 竞争（本机单实例）；jpa 档 store 的跨重启语义
   （PLAN-026/038 已单测覆盖）。

## 五、边界（勿夸大）

- **测试床演示口径**：`SimulatedChainEventSource` 是进程内内存态事件源，
  验证的是"装配 + 数据通路"连通性，**不验证任何真实链事实**；
- 本机单应用实例（非多副本、无 ShedLock 跨进程竞争验证）；
- store 用 memory 档（重启丢失），jpa 档的跨重启语义已在 PLAN-026/038 单测覆盖。
