# 方案 PLAN-037：桥 messaging 装配第一切片（roadmap #33）

- **状态**：切片 1 已交付（2026-10-25）：装配开关 + Bean 生命周期 + 就绪观测；
  **未接真实外部链**，未启动消息循环
- **前置**：PLAN-026（消息存储落库 memory/jpa 开关）、PLAN-016/021/025（资金安全链路）
- **范围**：`nexus-bridge` 内 `MessageRelayer` / `MessageExecutor` 的**生产装配**：
  开关默认关、Bean 化、可观测
- **不包含**：ChainAdapter 真实链接入、消息循环与重试策略、流动性持久化、
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

## 五、后续（切片 2 起）

1. ChainAdapter 装配（模拟 + 真实两档，开关控制）；
2. 消息循环：入库 → 阈值验签 → 执行 → recordExecution → 失败重试/退避；
3. 观测端点（`/consensus/finality-outbox` 同款风格）；
4. `DefaultLiquidityManager` 持久化与 `CircuitBreaker` 跨重启语义（#33 剩余）。
