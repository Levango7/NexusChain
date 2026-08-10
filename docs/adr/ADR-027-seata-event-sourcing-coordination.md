# ADR-027: Seata 分布式事务与事件溯源协调

- **状态**：Accepted（2026-08-09）
- **决策人**：P3-T7 工程师，依据 Phase 3 路线图授权
- **关联任务**：P3-T7（Seata 分布式事务与事件溯源协调）
- **关联文档**：
  - [ADR-026-nacos-ha-decision.md](ADR-026-nacos-ha-decision.md)（Seata 配置中心依赖 Nacos HA）
  - `.codeartsdoer/specs/microservice-phase3/design.md` §4.2.4（TCC 设计）
  - `nexus-gateway/src/main/java/org/nexus/gateway/event/sourcing/SagaCoordinator.java`（策略入口）
- **前置条件**：
  - P3-T3 事件溯源 + CQRS 已完成（PaymentEvent/PaymentAggregate/EventStore/SagaCoordinator）
  - P3-T4 gateway Seata 接入已完成（@GlobalTransactional 标注 PaymentServiceImpl.refund / SubscriptionServiceImpl.charge）
  - P3-T5 signing-service TCC 接口已完成（SigningTccAction Try/Confirm/Cancel）

---

## 1. 背景（Context）

### 1.1 双机制并存的现状

NexusChain v2.0.0 Phase 3 同时引入了两套一致性保障机制：

| 机制 | 引入任务 | 强度 | 适用边界 | 实现位置 |
|------|----------|------|----------|----------|
| Seata AT / TCC | P3-T4 / P3-T5 | 强一致（同步、可回滚） | 跨服务写操作（余额、订单、nonce） | `@GlobalTransactional`、`@LocalTCC` |
| 事件溯源 + CQRS | P3-T3 | 最终一致（异步、不可回滚） | 状态通知、投影更新、审计重放 | `EventStore` / `SagaCoordinator` / `PaymentProjection` |

两套机制均已落地，但**何时用哪一种、能否在同一业务流中混用、混用时谁主谁辅**此前仅散落在代码注释中，缺乏统一决策文档。本 ADR 显式界定边界，避免后续业务开发误用导致一致性破坏或性能损耗。

### 1.2 触发因素

1. **退款场景的混合需求**：退款既需要跨 gateway + wallet + settlement 强一致回滚（余额必须实时准确），又需要异步通知 analytics 投影更新（可容忍秒级延迟）——单一机制无法覆盖。
2. **签名服务 TCC + 事件补偿**：SigningTccAction Cancel 阶段释放 nonce 后，需产出 `SigningCancelledEvent` 供审计/监控，TCC 与事件补偿需协调顺序。
3. **避免误用**：若将通知类操作误用 Seata AT，会把秒级延迟的异步流程强行同步化，主链路 RT 放大 5-10×；若将余额扣减误用事件溯源，则丢失强一致，超卖/双花风险不可接受。

### 1.3 已有代码资产

| 资产 | 路径 | 角色 |
|------|------|------|
| `SagaCoordinator` | `nexus-gateway/.../event/sourcing/SagaCoordinator.java` | 策略入口，已定义 `ConsistencyStrategy` 枚举与 `strategyFor(operation)` 路由 |
| `PaymentEvent` 体系 | `nexus-gateway/.../event/sourcing/Payment{Created,Processing,Succeeded,Failed,Refunded}Event.java` | 事件溯源事件类型 |
| `EventStore` / `InMemoryEventStore` / `KafkaEventStore` | 同上目录 | 事件存储抽象与实现 |
| `PaymentProjection` | `nexus-analytics/.../projection/PaymentProjection.java` | CQRS 读模型投影（消费 `payment-events` Kafka topic） |
| `SigningTccAction` | `nexus-signing-service/.../tcc/SigningTccAction.java` | TCC 接口（Try 预锁定 nonce / Confirm 签名广播 / Cancel 释放 nonce） |
| `@GlobalTransactional` | `PaymentServiceImpl.refund` / `SubscriptionServiceImpl.charge` | Seata AT 全局事务边界 |

---

## 2. 决策（Decision）

**采用"按场景分类 + 混合模式"策略**：按一致性要求、性能要求、复杂度三维度分类，明确每类场景的机制选择；同一业务流中允许 Seata 主事务 + 事件补偿混合，但必须由 `SagaCoordinator` 显式协调顺序。

### 2.1 场景分类总览

```
┌─────────────────────────────────────────────────────────────────┐
│                  NexusChain 一致性机制选择                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  强一致跨服务写 ──→ Seata AT / TCC                               │
│  （余额、订单、nonce、链上转账）                                  │
│      │                                                          │
│      ├── 支付退款（gateway + wallet + settlement）               │
│      ├── 资金归集（settlement + wallet）                         │
│      ├── 提币审批（wallet + signing）                            │
│      └── 订阅扣款（gateway + wallet）                            │
│                                                                 │
│  最终一致异步通知 ──→ 事件溯源 + CQRS                            │
│  （投影、webhook、审计、分析）                                    │
│      │                                                          │
│      ├── 支付状态变更通知（PaymentCreated/Processing/            │
│      │   Succeeded/Failed 事件）                                 │
│      ├── 桥跨链操作通知（BridgeLocked/BridgeMinted 事件）        │
│      ├── Webhook 投递通知                                        │
│      └── 分析投影更新（CQRS 读模型 PaymentProjection）           │
│                                                                 │
│  混合模式 ──→ Seata 主事务 + 事件补偿                            │
│  （强一致写完成后异步产出事件）                                    │
│      │                                                          │
│      ├── 退款：Seata 回滚 + PaymentRefundedEvent                 │
│      └── TCC Cancel：nonce 释放 + SigningCancelledEvent          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Seata AT 强一致场景

| 场景 | 跨服务边界 | 一致性要求 | 为什么不能用事件溯源 |
|------|-----------|-----------|---------------------|
| **支付退款** | gateway + wallet + settlement | 余额必须实时准确，退款金额扣减与余额恢复原子完成 | 事件溯源最终一致期间存在"余额已扣减但退款未到账"窗口，用户可重复发起退款 → 资金损失 |
| **资金归集** | settlement + wallet | 归集金额从热钱包转入冷钱包，转账与账本更新原子 | 归集期间账本与钱包实际余额不一致将导致后续对账失败 |
| **提币审批** | wallet + signing | 审批通过后 nonce 锁定 + 签名广播原子，失败必须回滚 nonce | nonce 锁定后未广播将导致后续交易 nonce 冲突，链上交易全部卡死 |
| **订阅扣款** | gateway + wallet | 扣款与订单状态更新原子，失败必须回滚订单 | 事件溯源下"订单已扣款但订阅未续期"将引发用户投诉 |

**实现方式**：

- AT 模式：`@GlobalTransactional(timeoutMills = 120000)` 标注在 `PaymentServiceImpl.refund` / `SubscriptionServiceImpl.charge`
- TCC 模式：`@LocalTCC` + `@TwoPhaseBusinessAction` 标注在 `SigningTccAction`（提币审批场景，Try 预锁定 nonce / Confirm 签名广播 / Cancel 释放 nonce）
- undo_log 表：gateway / wallet-service 已通过 Flyway migration 创建（P3-T4）

### 2.3 事件溯源最终一致场景

| 场景 | 事件类型 | 消费方 | 为什么不能用 Seata |
|------|---------|--------|-------------------|
| **支付状态变更通知** | `PaymentCreatedEvent` / `PaymentProcessingEvent` / `PaymentSucceededEvent` / `PaymentFailedEvent` | analytics 投影 / webhook 投递 / 商户回调 | 通知可容忍秒级延迟，强行同步化会使主链路 RT 从 200ms 放大到 1-2s |
| **桥跨链操作通知** | `BridgeLockedEvent` / `BridgeMintedEvent` | analytics 跨链统计 / 监控告警 | 跨链操作本身耗时 10-30min，事件通知属事后记录，无需与主操作同事务 |
| **Webhook 投递通知** | `WebhookDispatchedEvent` | 商户系统 | 商户系统可达性不可控，不能阻塞主链路；失败由 Kafka 重试 + DLQ 兜底 |
| **分析投影更新** | 全部 PaymentEvent 子类 | `PaymentProjection` → `PaymentReadModel` | CQRS 读模型重建是离线/异步过程，与命令侧事务解耦 |

**实现方式**：

- 事件产出：`SagaCoordinator.afterPaymentCreateCommitted` / `afterPaymentSucceeded` / `onPaymentProcessing` / `onPaymentFailed`
- 事件存储：`EventStore` 接口，生产用 `KafkaEventStore`（topic `payment-events`），测试用 `InMemoryEventStore`
- 投影消费：`PaymentProjection.@KafkaListener` 消费后更新 `PaymentReadModel`
- 重放重建：`EventReplayService.replay(aggregateId)` 从事件流重建聚合根状态

### 2.4 混合模式：Seata 主事务 + 事件补偿

混合模式是本 ADR 的核心创新，适用于"强一致写 + 异步通知"复合场景。

#### 2.4.1 退款场景（Seata AT + 事件补偿）

```
时间轴 ──────────────────────────────────────────────────────────→

[T0] 退款请求进入 PaymentServiceImpl.refund
     │
[T1] @GlobalTransactional 开启 Seata AT 全局事务
     │  ├── gateway: 更新订单状态 PAID → REFUNDED
     │  ├── wallet: 扣减平台热钱包余额
     │  └── settlement: 记录退款流水
     │
[T2] 链上转账签名 + 广播（通过 SigningServiceFeignClient）
     │
[T3] 全局事务提交（Seata TC 协调所有分支提交）
     │
     ├── 成功 ──→ [T4] SagaCoordinator.afterRefundCommitted
     │              │  └── 产出 PaymentRefundedEvent → Kafka
     │              │       └── PaymentProjection 异步投影更新
     │              │            └── PaymentReadModel.state = REFUNDED
     │              │
     │              [T5] Webhook 投递（异步，Kafka 消费方）
     │
     └── 失败 ──→ [T4'] Seata 全局回滚
                    │  ├── gateway: 订单状态恢复 PAID
                    │  ├── wallet: 余额恢复
                    │  └── settlement: 退款流水删除
                    │
                    [T5'] SagaCoordinator.compensateRefundFailure
                       │  └── 产出 PaymentFailedEvent → Kafka
                       │       └── PaymentProjection 投影记录失败状态
                       │
                       [T6'] 不产出 PaymentRefundedEvent
                            （避免投影误认为退款成功）
```

**关键协调规则**（由 `SagaCoordinator` 强制）：

1. **事件产出在 Seata 提交后**：`afterRefundCommitted` 仅在 `@GlobalTransactional` 提交后调用（AfterCommit 钩子），避免脏读——若 Seata 回滚则事件不产出，投影不会误认为退款成功。
2. **回滚时产出补偿事件**：`compensateRefundFailure` 产出 `PaymentFailedEvent` 携带退款失败原因，供投影记录失败状态，不产出 `PaymentRefundedEvent`。
3. **事件补偿不参与 Seata 事务**：事件产出失败（Kafka 不可达）不影响 Seata 提交，由 Kafka 重试 / DLQ 兜底，避免事件存储层失败拖垮主链路。

#### 2.4.2 TCC Cancel + 事件补偿

```
[T0] SigningTccAction.prepareSignTransfer (Try)
     │  └── NoncePool.lockNonce → nonce 标记 LOCKED
     │
[T1] 主事务其他分支失败 → Seata TM 调用 Cancel
     │
[T2] SigningTccAction.cancelSignTransfer
     │  └── NoncePool.cancelNonce → nonce 恢复 AVAILABLE
     │
[T3] 产出 SigningCancelledEvent → Kafka
     │  └── 审计/监控消费方记录"签名已取消，nonce 已释放"
     │
[T4] 不产出 SigningConfirmedEvent
```

**关键协调规则**：

1. **Cancel 阶段先释放资源后产出事件**：nonce 释放是强一致操作（TCC Cancel 幂等），事件产出在释放后，避免事件先到但 nonce 未释放的虚假状态。
2. **事件仅用于审计**：`SigningCancelledEvent` 不参与业务决策，仅供监控/审计，失败不影响 Cancel 成功。

### 2.5 决策矩阵

按一致性要求、性能要求、复杂度三维度量化评分（1=低，3=高），给出每类场景的推荐机制。

| 场景 | 一致性要求 | 性能要求 | 复杂度 | 推荐机制 | 理由 |
|------|-----------|---------|--------|---------|------|
| 支付退款 | 3（强一致） | 2（中） | 3（高，跨 3 服务） | **Seata AT + 事件补偿** | 余额必须原子；通知可异步 |
| 资金归集 | 3（强一致） | 2（中） | 2（中，跨 2 服务） | **Seata AT** | 账本与钱包必须一致；无通知需求 |
| 提币审批 | 3（强一致） | 2（中） | 3（高，TCC 三阶段） | **Seata TCC** | nonce 锁定需 Cancel 回滚；事件补偿审计 |
| 订阅扣款 | 3（强一致） | 2（中） | 2（中，跨 2 服务） | **Seata AT** | 订单与扣款原子；通知异步 |
| 支付状态通知 | 1（最终一致） | 3（高，不阻塞主链路） | 1（低） | **事件溯源** | 通知可容忍秒级延迟 |
| 桥跨链通知 | 1（最终一致） | 3（高） | 1（低） | **事件溯源** | 跨链操作本身耗时 10-30min |
| Webhook 投递 | 1（最终一致） | 3（高） | 1（低） | **事件溯源** | 商户可达性不可控 |
| 分析投影更新 | 1（最终一致） | 3（高，离线） | 1（低） | **事件溯源 + CQRS** | 读模型重建异步 |

**决策规则**：

```
if (一致性要求 == 3) {
    if (需要异步通知 || 需要审计) {
        return SEATA_AT_OR_TCC + 事件补偿;  // 混合模式
    } else {
        return SEATA_AT_OR_TCC;  // 纯强一致
    }
} else {
    return 事件溯源;  // 纯最终一致
}
```

---

## 3. 实施细节（Implementation）

### 3.1 SagaCoordinator 策略路由

`SagaCoordinator.strategyFor(operation)` 已实现策略路由：

| operation | 返回策略 | 调用方法 |
|-----------|---------|---------|
| `CREATE` | `SEATA_AT_STRONG` | `afterPaymentCreateCommitted` |
| `SUCCEED` | `EVENT_SOURCING_EVENTUAL` | `afterPaymentSucceeded` |
| `NOTIFY` | `EVENT_SOURCING_EVENTUAL` | `onPaymentProcessing` / `onPaymentFailed` |
| `REFUND` | `HYBRID_SEATA_WITH_EVENT_COMPENSATION` | `afterRefundCommitted` / `compensateRefundFailure` |

### 3.2 Seata 全局事务 ID 关联

为支持跨服务 trace 关联与审计，`SagaCoordinator` 增强支持 Seata XID 关联：

- 全局事务 ID（XID）由 Seata TM 在 `@GlobalTransactional` 开启时生成，通过 RPC header 传播到分支事务
- 事件产出时记录 XID 到事件元数据，便于关联事件与 Seata 事务
- 详见 `SagaCoordinator.afterRefundCommitted` 的 `globalTxId` 参数

### 3.3 混合模式集成测试

| 测试类 | 路径 | 验证内容 |
|--------|------|---------|
| `RefundSagaIntegrationTest` | `nexus-gateway/src/test/java/org/nexus/gateway/event/sourcing/RefundSagaIntegrationTest.java` | 退款 Seata 回滚 + PaymentRefundedEvent 产出 + 投影更新 + 事件重放 |
| `TccEventCompensationTest` | `nexus-gateway/src/test/java/org/nexus/gateway/event/sourcing/TccEventCompensationTest.java` | SigningTccAction Try/Confirm/Cancel + SigningCancelledEvent 产出 + 协调顺序 |

测试使用 `InMemoryEventStore`（`nexus.event-sourcing.store=memory`）避免依赖真实 Kafka，Mock Seata TM 行为验证回滚/提交时序。

---

## 4. 后果（Consequences）

### 4.1 正面后果

1. **一致性边界显式化**：每类场景的机制选择有据可查，新增业务场景按决策矩阵归类即可，避免 ad-hoc 决策。
2. **性能与一致性平衡**：通知类操作不阻塞主链路，主链路 RT 保持 200ms 级；强一致写由 Seata 保障，无超卖/双花风险。
3. **审计能力增强**：事件溯源保留全部状态变更历史，可重放重建任意时刻状态，满足金融审计要求。
4. **故障隔离**：事件存储层（Kafka）故障不影响 Seata 主事务提交，主链路可用性不依赖 Kafka。

### 4.2 负面后果与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 事件产出在 Seata 提交后，存在"事务已提交但事件未产出"窗口 | 投影延迟或丢失 | Kafka 重试 + DLQ + 对账任务定期扫描 Seata 事务日志补产事件 |
| 混合模式增加代码复杂度 | 开发者需理解 Seata + 事件溯源两套机制 | `SagaCoordinator` 封装协调逻辑，业务代码仅调用单一入口 |
| TCC Cancel 幂等性要求 | Cancel 可能被 TM 重试 | `NoncePool.cancelNonce` 已处理无锁定记录场景（返回 false 不抛异常） |
| 事件重放可能与 Seata 当前状态不一致 | 重放得到历史状态，非当前状态 | 重放仅用于审计/读模型重建，不参与业务决策；当前状态以 Seata 保护的命令侧为准 |

### 4.3 验证方式

- **单元测试**：`RefundSagaIntegrationTest` / `TccEventCompensationTest` 验证混合模式时序
- **全量测试**：`.\gradlew.bat test --continue -x jacocoTestReport` BUILD SUCCESSFUL
- **生产观测**：Seata 控制台查看全局事务成功率 + Kafka consumer lag 监控事件产出延迟

---

## 5. 替代方案（Alternatives）

### 5.1 纯 Seata（所有场景都用 AT/TCC）

- **优点**：一致性最强，无最终一致窗口
- **缺点**：通知/投影操作强行同步化，主链路 RT 放大 5-10×；商户 webhook 可达性不可控，会拖垮主链路
- **否决理由**：性能不可接受，且 CQRS 读模型重建无法在事务内完成

### 5.2 纯事件溯源（所有场景都用事件）

- **优点**：架构统一，无 Seata 运维负担
- **缺点**：余额扣减最终一致期间存在超卖/双花窗口；nonce 锁定无法回滚；金融场景不可接受
- **否决理由**：一致性不满足金融级要求

### 5.3 Saga 模式（纯补偿事务，不用 Seata）

- **优点**：无 Seata 依赖，架构轻量
- **缺点**：补偿逻辑需手写，跨 3 服务的补偿链复杂度爆炸；无框架级事务管理，易出错
- **否决理由**：Seata 已落地且成熟，重新实现 Saga 框架 ROI 低

---

## 6. 后续演进（Future Work）

1. **对账任务**：定期扫描 Seata 事务日志与事件存储，补产遗漏事件（缓解 4.2 风险 1）
2. **Seata TCC + 事件补偿扩展**：当前仅 SigningTccAction，后续可扩展到 WalletTccAction（钱包扣款 TCC）
3. **事件版本演进**：事件 schema 演进策略（schema registry + 兼容性检查）待 ADR-028
4. **跨链桥 Seata 集成**：当前桥操作纯事件溯源，若未来需要强一致跨链锁定可引入 Seata TCC

---

## 7. 参考

- Seata 官方文档：[https://seata.apache.org/docs/overview/what-is-seata](https://seata.apache.org/docs/overview/what-is-seata)
- 事件溯源模式：Martin Fowler, *Event Sourcing* [https://martinfowler.com/eaaDev/EventSourcing.html](https://martinfowler.com/eaaDev/EventSourcing.html)
- CQRS 模式：Greg Young, *CQRS Documents*
- NexusChain 内部：`SagaCoordinator` Javadoc、`SigningTccAction` Javadoc、`PaymentProjection` Javadoc