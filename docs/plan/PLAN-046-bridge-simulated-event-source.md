# 方案 PLAN-046：桥消息链路的模拟事件源前段（roadmap #33 切片 4「演示口径」）

- **状态**：开发中（2026-10-27）
- **前置**：PLAN-026（消息存储 memory/jpa）、PLAN-037 切片 1-3（装配开关 + 中继循环 +
  模拟链适配器）、PLAN-038（#33 余项处置决策）
- **范围**：`ChainEventSource` 接口 + `SimulatedChainEventSource`（内存态演示）+
  `BridgeEventSourceWorker`（poll → format → 真实签名入库）+ 装配与观测
- **不包含**：真实外部链接入（RPC 订阅/事件日志——需链凭证与网络出口）、多签
  门限的真实验证人集合、跨进程游标持久化

---

## 一、问题（roadmap #33 欠账的最后一环）

PLAN-037 §七 自己留的账：#33 的"消息前段（真实链事件源 → 落库）"未交付；
同时写了"模拟事件源如需要可另立测试床演示小切片，但不算生产链路"。

现状盘点（PLAN-037 交付物）：
- store（memory/jpa）、relayer（签名/验签/顺序/去重）、executor、中继循环、
  模拟链适配器、`GET /api/v1/bridge/messaging` 观测——**中段和后段都通了**；
- 唯独**没有东西在产事件**：所有既有测试都是手工 `formatter.formatMessage(...)` +
  `relayer.relayMessage(msg, key)` 灌一条消息。生产流打开
  `relay-enabled=true` 后，中继循环只能对着空 store 空转。

## 二、方案

### 2.1 `ChainEvent`（新 record）

"源链上发生了一件事"的最小表达：sourceChain / targetChain / sourceContract /
targetContract / payload / nonce。**不含** messageId、签名、状态——那些是中继层
职责；nonce 与 `MessageStore.getMaxNonce` 的顺序检查同口径。

### 2.2 `ChainEventSource`（新接口）

`List<ChainEvent> pollEvents(int maxEvents)` + `getSourceChain()` /
`getTargetChain()` / `describe()`。**getter 不得有副作用**（不得通过 poll 偷事件，
否则观测会消耗事件——实现约定 + 测试锁定）。

### 2.3 `SimulatedChainEventSource`（演示实现；测试床专用）

内存态"源链"：每次 poll 产出 ≤ maxEvents 条（默认 1），nonce 从
`start-nonce` 起严格单调，负载 `sim-event:<chain>:<nonce>:<epochMillis>`。

**如实边界（写进类注释）**：
- 内存态，nonce 游标不落库；重启后从 `start-nonce` 重新起跳 ⇒ 若 store 是
  jpa（持久化），旧 nonce 会被顺序检查拒绝——**预期行为**（中继层拒绝乱序），
  由 worker 逐事件容错跳过；真实部署应由真实事件源从链上游标恢复；
- 不是真实链：不连外部链、不要凭证、产出不含真实资金语义；
- 单签：单 relayer 私钥签名，不构成多签门限。

### 2.4 `BridgeEventSourceWorker`（新；前段驱动）

`@Scheduled`（默认 10s）+ `@SchedulerLock`（ShedLock，多副本只一格跑）：
poll → `formatMessage` → `relayMessage`（真实签名 + 入库，RELAYED）→ 由既有
`MessageRelayWorker` 继续消费。**不重复**中继/执行逻辑。

容错语义（与既有 worker 同口径）：
- **逐事件容错**：单条转换/中继失败（乱序、超时、存储抖动）跳过并计数，不抛出；
- **poll 容错**：事件源抛异常本轮返回 0，下轮重试；
- `lastError` / `failedCount` 暴露到观测端点。

### 2.5 装配与观测

`MessagingRelayConfig` 新增两个 Bean（均需 `relay-enabled=true` **且**
`event-source.enabled=true`，缺省 false）：
- `ChainEventSource` = 模拟源（source/target 链与合约、`start-nonce` 可配）；
- `BridgeEventSourceWorker`（`batch-size` 默认 1；`relayer-private-key` 未配置且
  开关开启 ⇒ **fail-fast**，与 `required-signatures` 的 fail-fast 同风格）。

`GET /api/v1/bridge/messaging` 追加 `eventSource` 子对象（开关开启时）：
sourceChain/targetChain/sourceType + poll/relayed/failed 计数 + lastNonce/lastError。
**快照按请求实时构建**（worker.snapshot()），不做成单例 Bean——启动时构造会把
计数冻死在 0（PLAN-044 的"缓存 vs 快照"教训）。

## 三、测试（25 例）

- `SimulatedChainEventSourceTest` 5：nonce 单调、maxEvents 钳制（≤0 视为 1）、
  负载可追溯、必填 fail-fast、**getter 无副作用**；
- `BridgeEventSourceWorkerTest` 6：事件→签名入库（RELAYED 1 签）、连续轮 nonce
  递增、存储失败逐事件容错不死循环、乱序跳过并计数、poll 失败返回 0+lastError、
  快照活值；
- `BridgeMessagingControllerTest` 3（改写既有 2 例 + 新增 1）：关闭时不带
  eventSource 字段、开启时完整快照、**eventSource 是活值不是 0**；
- 既有 `MessagingSimulatedLoopTest`/`MessagingRelayConfigTest`/`MessageRelayWorkerTest`
  共 11 例不回归。

## 四、边界（勿夸大）

- **这是测试床演示切片，不是生产链路**：模拟源是进程内内存态；它验证的是
  "事件 → 消息 → 签名 → 入库 → 中继 → 执行"的**代码路径连通性**，
  不验证任何真实链事实；
- 真实链接入仍缺：链凭证、网络出口、事件过滤器/游标、重放保护——未做；
- 多签门限（`required-signatures>1`）在本切片只能用**同一个** relayer 密钥
  重复签名凑数（或保持 1）；真实验证人集合属未来切片；
- 未做测试床实机联调（本机起 bridge 应用跑 event-source 开关的全流程）——
  §五留作后续。

## 五、后续

1. 测试床实机联调：起 bridge（`relay-enabled=true` + `event-source.enabled=true` +
   `execute-enabled=true` + `simulated-target-chains=L2-sim`），观察
   `/api/v1/bridge/messaging` 的 eventSource 计数与 store 落库（可选，演示价值）；
2. 真实链事件源： replacing the simulated `ChainEventSource` Bean（接口已留好插座）。
