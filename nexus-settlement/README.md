# nexus-settlement

## 模块职责

清结算与风控层。承担支付系统的清结算、对账、差错处理、实时风控、欺诈检测、资金归集等核心金融后处理职责。

| 子包 | 职责 |
| --- | --- |
| `clearing` | 清结算引擎：批量净额结算、单笔结算、对账触发 |
| `reconciliation` | 对账：与链上数据、银行渠道对账并产出差错报告 |
| `risk` | 风控引擎：规则链模式实时评估交易、动态增删规则 |
| `risk.rules` | 内置风控规则：大额阈值、频率限制、黑名单地址 |
| `funds` | 资金归集：单笔归集、自动归集、冷钱包转移 |

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Spring Data JPA
- Jackson 2.15.4
- Lombok 1.18.32
- H2 2.2.224（测试）

## 接口清单

### clearing（清结算引擎）

- `ClearingEngine`
  - `batchClear(SettlementBatch)` → `SettlementBatch`
  - `settle(ClearingOrder)` → `ClearingOrder`
  - `reconcile(ReconciliationReport)` → `ReconciliationReport`
- 骨架实现：`DefaultClearingEngine`（`@Service`）

### reconciliation（对账）

- `ReconciliationService`
  - `reconcileWithChain()` → `ReconciliationReport`
  - `reconcileWithBank()` → `ReconciliationReport`
  - `reportDiscrepancy(ReconciliationReport)` → `ReconciliationReport`
- 骨架实现：`DefaultReconciliationService`（`@Service`）

### risk（风控引擎）

- `RiskEngine`
  - `evaluate(Transaction)` → `RiskDecision`
  - `addRule(RiskRule)`
  - `removeRule(ruleId)`
- `RiskRule`
  - `getRuleId()` → `String`
  - `check(Transaction)` → `boolean`
- `RiskDecision`（枚举）：`APPROVED` / `REJECTED` / `PENDING_REVIEW` / `FROZEN`
- 骨架实现：`DefaultRiskEngine`（`@Service`，规则链模式）
- 内置规则（`risk.rules`）：
  - `AmountThresholdRule`（大额交易拦截）
  - `VelocityRule`（频率限制）
  - `BlacklistRule`（黑名单地址）

### funds（资金归集）

- `FundSweepService`
  - `sweep(CollectionOrder)` → `CollectionOrder`
  - `autoSweep()` → `int`
  - `transferToCold()` → `int`
- 骨架实现：`DefaultFundSweepService`（`@Service`）

## 实体清单

- `SettlementBatch`：批次号、交易列表、结算金额、币种、状态（PENDING/SETTLED/FAILED）、创建时间
- `ClearingOrder`：订单 ID、商户 ID、金额、币种、结算周期、状态、创建时间
- `ReconciliationReport`：对账日期、匹配数、差错数、差错明细
- `CollectionOrder`：归集订单 ID、源地址、目标地址、金额、币种、状态、创建时间

## 构建

```bash
gradle build
```

## 状态

骨架阶段。所有 `@Service` 实现方法体均保留 `TODO` 注释，待后续填充业务逻辑。