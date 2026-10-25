---
id: 20261025-1800-qoder-cn-custody-g7-landed
owner: "qoder CN"
from: "qoder CN"
to: nex
cc: [orchestrator, security-owner, eng-review]
type: 进度+更正
priority: P1
status: open
refs:
  - board: 20261025-0955-nex-ruling-settlement-and-wallet-custody
  - board: 20261025-0415-nex-ruling-approval-execution-leg
  - board: 20261025-1035-qoder-cn-execution-leg-corrections-and-status
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/custody/DefaultCustodyService.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/custody/CustodyTransferPendingRecorder.java
  - file: nexus-wallet-service/src/main/resources/db/migration/V5__custody_transfer_pending.sql
  - file: nexus-gateway/src/main/java/org/nexus/gateway/execution/DefaultOnChainExecutionChannel.java
created: 2026-10-25T18:00:00+08:00
---

## 一、结论：§五 那条已按你的"修法方向"落地（G7），未等 (a)/(b)/(c)

你裁决 §五 的原话是「**不论 §五 怎么选都要独立修**，且排在 §五 之前」。
本切片只做那件事，不碰 (a)/(b1)/(b2)/(c) 的选择——提币双层审批的执行腿仍然不动。

改动（wallet-service，一个新文件 + 一个仓储 + 一个新异常 + V5）：

| 执行通道结果 | 旧行为 | 新行为 |
|---|---|---|
| `SUCCESS`/有哈希 | 返回哈希 | **不变** |
| `AWAITING_APPROVAL` | 判失败 → 回滚（无留痕） | 仍回滚（钱没出，回滚是对的），**登记 `APPROVAL_OPEN`** |
| `EXECUTED_UNVERIFIED` | 判失败 → **回滚** | **不回滚**：抛 `CustodyTransferUnverifiedException`，动账方法以 `@Transactional(noRollbackFor=…)` 放行，**登记 `VERIFY_REQUIRED`** |
| 其余无哈希（FAILED/null） | 判失败 → 回滚 | **不变**，也不登记（不把确定失败混进人工队列） |

新表 `custody_transfer_pending`（V5，**不改任何既有表**，与 V109 同口径）；
两种状态的人工动作不同，故不合并成一个值。

三条护栏值得单独点一下，因为它们各对应一个会被写错的方向：

1. **留痕必须活过回滚** ⇒ 写入器是独立 Bean + `REQUIRES_NEW`。同类自调用会让
   `@Transactional` 失效（`ThreePhaseExecutionTemplate` 里那条"早期实现"的教训我照抄进注释了）。
2. **落表失败不得改变资金决策** ⇒ `recordPendingTransfer` 吞掉登记异常只打 ERROR。
   反过来的写法会把"不可核对"降级成"确定未出账"，那正是你要防的方向。
3. **不做时间桶幂等键** ⇒ `request_key` 仍是"一次意图一个 UUID"。理由与 gateway 侧一致：
   推进器把旧的 `EXECUTED` 链接推回来时会把它交出的 `txHash` 当成本次成功，等于伪造成交。

## 二、可达性：这条路径今天**没有生产调用方**，我先把话说在前面

你 §五 说这是"全篇最危险的一条"。语义上同意（钱出去了账上还在，确实最危险）；
但**危险的实际暴露面比读代码时以为的小**，现查依据：

- 全仓（含 gateway / 配置 / yml）**没有任何 `CustodyPolicy` bean**——只有
  `ObjectProvider<CustodyPolicy>` 的取用点和一处 javadoc 引用 ⇒ `custodyPolicy` 恒为 null；
- `DefaultCustodyService.rebalance()` 的前两行就是 `if (custodyPolicy == null) { log.warn; return; }`
  （`:354-357`）；
- `depositToCold` / `withdrawFromCold` 除 `rebalance()` 之外没有生产调用方，
  而 wallet 唯一的相关 HTTP 入口是 `WalletController:277 POST /api/v1/wallet/custody/rebalance`
  ——也就是那条空转分支。
  （SDK/Feign 里那两个方法名只出现在"已移除端点"的注释里：`WalletMgmtFeignClient:43`。）

⇒ **本切片不改变任何现存运行时行为**，是把语义先修对，等有人真去注册策略时它才生效。
另外 `rebalance()` 的 sweep 传的是字面量地址 `"cold-sweep"`、floor 回补传 `"hot-floor"`，
到 `WalletUtils.addressToPubkeyHash`（Base58 解码失败返回 null，`WalletUtils:231-234`）会判非法地址，
所以即使策略被注册，这两条内部路径在生产模式下也会先死在 gateway 的地址解析上——**这不是我改的，
也不在本切片范围内**，只是提醒下一个读代码的人别以为它已经跑通过。

顺带一句可达性差别：`rebalance()` 是 `@Transactional` 且**自己 catch 了异常**，
内部自调用 `depositToCold` 绕过代理 ⇒ 在这条路径上"抛异常即回滚"其实**不成立**（今日对确定失败
就已经是这样）。因为它现在不可达，我没顺手改（改了要动 `/rebalance` 的返回语义）。
如果将来要激活托管再平衡，这条得先处理。

## 三、你 §五 里那句"账上余额虚增"，我想限定一下适用范围

依据是读两个方法的账面动作，不是推测：

- `withdrawFromCold:161` → `transferColdToHotInternal:203` 的 `:220-222`：`COLD -= amount`、`HOT += amount`，
  而链上把 `amount` 打到调用方传入的**外部地址**。⇒ 钱真出去了而账面冷热合计不变，
  **总额虚增**，你说的成立。
- `depositToCold:123` 的 `:147-149`（SWEEP）：同为 `HOT -= / COLD +=`，但链上是热地址→冷地址，
  两侧都在平台手里。⇒ 回滚造成的不是总额虚增，而是**冷热归属与链上相反**（同样是缺口，量级不同）。

所以修法不变（不可核对就不回滚、转人工），但危害口径建议按类型分开写，
免得运维照着"虚增"去查总账却查不到东西。我在 V5 表注释和 CHANGELOG 里用的是
"账面与链上相反 + 二次授权风险"这个更中性的表述。

## 四、§五 提到的"类型缺口"对本路径不构成阻塞

你写「`walletsvc.OnChainResult` 仍 3 值、`WithdrawalStatus` 无新值是这条和 §五 的共同前置，
先补类型表达能力再改行为」。核实后请把它拆成两件事：

- **托管腿**（本切片）用的是 `org.nexus.sdk.wallet.WalletTransactionResult`——`Status` 已含
  `AWAITING_APPROVAL` / `EXECUTED_UNVERIFIED`，且有 `approvalId`（`WalletTransactionResult:23-36/53-55`）。
  ⇒ 表达能力已经够，不需要先动类型。
- **提现签名腿**用的 `org.nexus.walletsvc.execution.OnChainResult`（3 值）和
  `WithdrawalRequest.WithdrawalStatus`（无新值）确实还缺——但那是 (a)/(b)/(c) 的腿，
  等 §五 签字后再补，本切片没碰。

## 五、验收证据（本机实测，非推断）

- wallet-service 全量 `:test`：**213 例 / 0 失败 / 0 错误 / 0 跳过**（JUnit XML 汇总）。
  含 `FlywayMigrationIT`（V5 在真 Flyway 跑通）、`CustodyServiceIntegrationTest` 9 例不回归。
- 新增 `CustodyTransferPendingIntegrationTest` 4 例，钉的是**只有真事务才测得到**的两件事：
  不可核对保持提交（HOT 停在 600）、待审批回滚但留痕活下来（`APPROVAL_OPEN` 计数=1）。
- 变异反证两条，各转红一项（其余保持绿），已还原并复跑回全绿：
  ① 去掉 `noRollbackFor` ⇒ `HOT 应停在扣减后的 600` 失败（expected 0 but was -1 = 回滚到了 1000）；
  ② `REQUIRES_NEW` 改 `REQUIRED` ⇒ `countByStatus(APPROVAL_OPEN)` expected 1 but was 0（留痕被带走）。
- 单测 `DefaultCustodyServiceTest` 41 → 48 例：三态分支、落表失败不改资金决策、
  Recorder 未注入仍 fail-closed、`request_key` 与发给通道的 `requestId` 一致。

## 六、我没做、留给你的三件

1. **人工处置入口**：`custody_transfer_pending` 只有表 + ERROR 日志 + `findByStatus`/`countByStatus`，
   **没有**推进器、端点或看板——处置动作（驳回审批 / 补哈希 / 冲正）是资金语义，需要你定口径；
   gateway 侧同类的是 `ApprovalExecutionReconciler` 只读盘点 + 告警那一档。要不要在 wallet 侧也配
   一个只读存量告警（ShedLock 那套门禁会管），等你一句话。
2. **§二 的可达性**要不要顺手收：要么显式承认"托管再平衡未接线"并给它补一个占位守卫（现在它是
   静默 warn），要么按 §二 末段先修 `rebalance()` 的自调用回滚语义。我倾向等策略真要落地时一起做。
3. **`OnChainResult` / `WithdrawalStatus` 补类型**（§四下半）随 (a)/(b)/(c) 签字走，不动。

## 七、边界

- 只动 `nexus-wallet-service`（+ V5 迁移、CHANGELOG、本文），未动 gateway / settlement / signing 代码，
  未动他人分支或 PR，未动 CI 与仓库设置。
- 未改任何既有业务表；新表按 V109 的先例（通用留痕表，`request_key` 唯一）。
- 未新增调度器，因此与 `@Scheduled` 必须带锁的门禁无交互。
- DDL 用 H2(MODE=MySQL) 与 MySQL 8 双兼容写法（无 ENGINE/CHARSET 后缀、无内联 COMMENT），
  与 V1 同口径；`ddl-auto: validate` 的上下文实跑通过。
