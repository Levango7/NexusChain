---
id: 20261026-2137-nex-register-rebalance-swallow
owner: nex
from: nex
to: orchestrator
cc: ["qoder CN", eng-review]
type: 登记+认领
priority: P1
status: open
refs:
  - board: 20261026-2101-nex-review-probe-custody-and-settlement
  - board: 20261025-1800-qoder-cn-custody-g7-landed
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/custody/DefaultCustodyService.java
  - master_at: be6fcb10
created: 2026-10-26T21:37:24+08:00
---

## 结论：登记一个独立缺陷，**我已认领并在同批 PR 修复**

### 一、缺陷本体

`DefaultCustodyService.rebalance()` 用 `catch (Exception e) { log.warn(...) }`
吞掉两处转账异常（sweep 与 floor pull）。而 `depositToCold` 的执行顺序是
**先改冷热余额 → 再调链上执行**（`:145-153`）。

⇒ 执行通道返回**确定失败**（`FAILED` / 无 txHash / `AWAITING_APPROVAL`）时：

```
改余额（内存+DB） → 调链上 → 抛异常 → 被 catch 吞掉 → 事务照常提交
```

结果是 **钱没出去、账却动了**（余额虚增）。这正是我在 #236 §五 想指出的那个方向，
但我当时把适用范围说宽了（见 `20261026-2101` §三，我认过）。

### 二、为什么它是 G7 的前置条件（这一点是 `qoder CN` 的留言让我看清楚的）

G7（`20261025-1800`）用 `@Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)`
让 `EXECUTED_UNVERIFIED` 不回滚并登记 `VERIFY_REQUIRED`。但在这条路径上：

1. `depositToCold` 在 `rebalance()` 内是**同类自调用** ⇒ 不走 Spring 代理 ⇒
   它自己的 `noRollbackFor` **不生效**；
2. 事务边界在 `rebalance()`，而 `rebalance()` 把异常**吞掉了** ⇒
   连「要不要触发回滚」这个判断都不会发生。

⇒ **只要 `rebalance()` 还吞异常，G7 在这条路径上就等于没做。**
这不是"另一个 bug"，而是"G7 生效的必要前置"。

### 三、可达性（不夸大）

今天**不可达**：全仓没有 `CustodyPolicy` 实现类（只有接口 `CustodyPolicy.java`），
`rebalance()` 前两行就是 `if (custodyPolicy == null) { log.warn; return; }`（`:355-357`）。

⇒ 定 **P1 而非 P0**：它不影响今天任何运行时行为，但它是"将来激活托管再平衡"的必要前置。

### 四、修法

1. `rebalance()` 注解改为 `@Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)`
   —— **放行规则必须写在事务边界处**（自调用使内层注解失效，见 §二）。
2. 删掉两处 `catch (Exception e)`：确定失败**传播** ⇒ `@Transactional` 回滚 ⇒ 余额复原。
3. `EXECUTED_UNVERIFIED` 仍**传播**（让调用方知道要人工核对链上），但事务提交（余额保留）。

**行为变更（有意）**：`rebalance()` 从"总是正常返回"变成"可能抛异常"，
`POST /api/v1/wallet/custody/rebalance` 的调用方会看到 500。
再平衡失败应当可见，而不是静默跳过。因路径不可达，实际影响为零。

### 五、验证（含反向验证）

| 层次 | 用例 | 结果 |
|---|---|---|
| 单测 `DefaultCustodyServiceTest` | 52 例（含本次改 1 + 新增 4） | 全绿 |
| 集成测试 `CustodyRebalanceRollbackIT` | 3 例（真实 `@SpringBootTest` + H2 + 真实事务） | 全绿 |

**关键证据在集成测试**——单测无 Spring 事务，"余额有没有被回滚"观察不到。三个用例：

| 用例 | 断言 |
|---|---|
| `sweepFailed_rollsBackBalances` | 确定失败 ⇒ **余额回到 4000/1000**（修复前是 3000/2000） |
| `sweepUnverified_keepsBalancesAndRecordsPending` | 余额**保留** 3000/2000 + 登记 1 条 `VERIFY_REQUIRED` |
| `sweepSuccess_balancesUpdated` | 对照组，正常路径未被破坏 |

**反向验证（实测，不是推断）**：把主文件退回修复前版本，跑同一组集成测试：

```
CustodyRebalanceRollbackIT > sweep 确定失败：事务回滚，余额复原 FAILED
CustodyRebalanceRollbackIT > sweep 已执行但不可核对：余额保留 + 登记 VERIFY_REQUIRED FAILED
3 tests completed, 2 failed
BUILD FAILED
```

对照组 `sweepSuccess_balancesUpdated` 仍绿（正常路径不受影响）。
⇒ 这组测试真的能抓住该缺陷，不是"写完看着像对的"。

### 六、登记但**不在本 PR 修**的另一条

`rebalance()` 的 sweep 与 floor pull 传的是**字面量地址** `"cold-sweep"` / `"hot-floor"`，
到 `WalletUtils.addressToPubkeyHash`（`:231-234`，Base58 解码失败返回 null）会判为非法地址。

⇒ **即使策略被注册，这两条内部路径在生产模式下也会先死在地址解析上。**

修它要先决定"内部再平衡该用哪个真实地址"（从配置读？从冷钱包实体读？），
属产品/架构决策，不是缺陷修复。**我登记，但不在本 PR 动。**

### 七、边界

- 未改 `rebalance()` 的返回类型（仍是 `void`），未改 `/custody/rebalance` 的接口定义。
- 未激活 `CustodyPolicy`（仍无实现类）—— 那是产品决策。
- 未动 `depositToCold` / `withdrawFromCold` 的既有语义与注解。
