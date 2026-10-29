# ADR-036: 平台共用出款地址下的双审批终态化（A0）与「业务已批准」显式契约（A2 立项）

- **状态**：A0 Accepted（随防双花打捞落地，2026-10-27）；A2 Proposed（立项，未启动）
- **日期**：2026-10-27
- **触发**：PR #261（`exempt-from-pubkeys` 地址级豁免）前提复核失败（板
  `20261027-0306-nex-review-b2-premise-fails-gateway-path`）+ 用户 D2 裁决
  「③(a)：A0 终态 + A2 立项」（板 `20261027-0557-zcode-reply-b2-premise-accepted`）
- **范围**：`nexus-wallet-service` 提现审批链、`nexus-gateway` 侧 `SigningApprovalExecutionService`
  覆盖的全部出款业务路径、`nexus-signing-service` 的 `requiresApproval` 判定；
  本 ADR 只产出决策与 A2 的工程蓝图，**不含 A2 的代码实现**

---

## 1. 背景与问题

### 1.1 平台出款地址是「多业务共用」的物理事实

平台只有一个热钱包公钥。以下六类出款**都从同一地址发起**（证据取自
`SigningApprovalExecutionService:98` 的 `@param fromPubkey 平台出款公钥`）：

| 调用点 | 业务 | 该路径自身有无多签 |
| --- | --- | --- |
| `DefaultWithdrawalApprovalService`（wallet 提现） | 用户提现 | **有**（wallet 侧 1–3 人多签） |
| `PaymentServiceImpl:756` | 支付 / 退款 | 无 |
| `SubscriptionServiceImpl:139 / :319` | 订阅扣款（两处） | 无 |
| `ChainConnector:223` | 链支付 | 无 |
| `ConsortiumConnector:152 / :340` | 联盟链转账 | 无 |
| `DefaultOnChainExecutionChannel:209` | 通用链上执行通道 | 无 |

对后五类业务，signing 的大额审批（`requiresApproval`）是**唯一一道闸门**。

### 1.2 #261 的豁免粒度是地址，而风险的粒度是业务

`SigningApprovalService.requiresApproval(amount, currency, fromPubkey)`：

```java
if (fromPubkey != null && exemptFromPubkeySet().contains(fromPubkey.trim())) {
    return false;          // 名单内地址：不需要审批
}
```

只要 `fromPubkey` 命中，**任何业务**都跳过审批。配置该名单后的后果：

| 业务 | 豁免前 | 豁免后 |
| --- | --- | --- |
| wallet 提现 | wallet 多签 1–3 人 + signing 2 人 | wallet 多签 1–3 人 ✅（去重，符合预期） |
| gateway 支付 / 退款 | 仅 signing 2 人 | **0 人，直接签名** ❌ |
| gateway 订阅扣款 | 仅 signing 2 人 | **0 人** ❌ |
| 链支付 / 联盟链 / 通用通道 | 仅 signing 2 人 | **0 人** ❌ |

即「控制并未丢失」只在 wallet 路径上成立；在 gateway 的五类业务上控制**完全丢失**。
(b2) 的设计前提「一个地址 = 一种业务」在本仓不成立，故 #261 冻结，且
**已配置的豁免名单必须保持为空**。

### 1.3 重复审批的真实代价与「去重」的正确位置

wallet 提现确实存在双重审批（wallet 多签 + signing 大额审批），有去重价值。
但去重必须落在**「证明这笔已被业务层批准」**上，而不是「豁免这个地址」——
后者无法区分是哪条业务在出款。这正是 (a) 方案（本 ADR 的 A2）。

## 2. 决策

### 2.1 A0（Accepted，已落地）：双审批作为终态语义，不做地址级豁免

- **每一笔从平台热钱包出款的交易都保留 signing 侧审批**（`requiresApproval`），
  名单 `exempt-from-pubkeys` **禁止配置平台钱包地址**；#261 保持冻结。
- 大额提现走 signing 2001（转人工审批）时，按 (c)-lite（PR #285）的口径：
  审批单**复用**（不新建悬空审批）、提现单停在 `AWAITING_APPROVAL`，
  审批通过后以同一 `requestId` 重入执行；
- 上次执行尝试结果不明（响应丢失 / TTL 超时回退）时按 **`EXECUTED_UNVERIFIED`**
  处理（V7 `signing_execution_attempted_at` 防双花，打捞自 #280），
  **不判 FAILED、不自动重试**，交人工核对；
- 由此「双审批」从「重复劳动」变成「纵深防御」：wallet 多签管业务意图，
  signing 审批管链上密钥的最后一道人审。两者职责不同，**不是冗余**。

**代价（接受）**：wallet 大额提现比 gateway 其他业务多一道人审；这是共用地址
架构下的必要成本，直到 A2 落地。

### 2.2 A2（**Accepted，2026-10-28 立项实施**）：signing 提供「业务已批准」显式契约

> **状态更新（2026-10-28）**：用户拍板立项（板 20261028-0608）。实施按
> `docs/adr/A2-business-approval-design-draft-2026-10-27.md` 执行，D1-D5 取推荐项：
> 登记端点 `POST /api/v1/signing/business-approvals` + `signTransfer` 可选
> `businessApprovalId` + 独立表 `signing_business_approval`（signing **V5**）+
> canonical sha256（`A2-v1`，nexus-sdk 双侧共享）+ wallet 开关
> `nexus.signing.business-approval.enabled`（**默认 false = 旧行为逐字节不变**）。
> **一处对草案 §2.2 的强化**：凭据**已被消费**时 signing 返回 5003 终止而**不回落人审**
> ——"校验失败一律回落"若适用于已消费凭据，会为同一笔提现保留二次放款通道
> （#294/#300 同源原则："可能已出"不得当"没出"重放）；其余校验失败
> （不存在 / 哈希不匹配 / 过期）仍回落旧流程（有人审兜底）。

**目标**：把豁免粒度从「地址」收紧到「单笔业务」——既去掉 wallet 的重复审批，
又不误伤 gateway 其他业务。

**契约草案**：

1. `signTransfer` 新增可选参数 `businessApprovalId`（wallet 侧已 APPROVED 的提现单号）；
2. signing 侧校验该审批**存在且状态为已批准**，才跳过自己的大额审批；
   校验失败或参数缺失 ⇒ **行为与旧版完全一致**（fail-closed，安全默认）；
3. 豁免的生存期绑定该 `businessApprovalId` 的生命周期（审批被消耗 / 撤销即失效），
  天然杜绝「一次豁免、永久豁免」。

**A2 的工程拆解（待立项评审细化）**：

- Feign 契约变更：`SigningServiceFeignClient.signTransfer` 签名扩展（SDK 需同步，
  注意 WalletDtoTest 契约基数）；
- signing 侧校验实现：审批状态查询 + 幂等键归并（与 (c)-lite 的
  `signingApprovalId` 复用机制对齐，避免第二套审批状态机）；
- gateway 五类业务路径**显式不带** `businessApprovalId`——它们的豁免资格需另行
  决策（订阅扣款等人审替代方案），**不在 A2 范围内默认获得**；
- 可观测：每次按 `businessApprovalId` 跳过审批必须留审计日志（谁批准、批了哪笔）。

**明确的风险**：A2 引入「业务层证明可替代密钥层人审」的口子，其安全性完全取决于
业务层审批的强度。因此 A2 必须与 wallet 多签的阈值配置一同评审，且
**gateway 侧的五类业务不得援引同一机制**，除非各自补上等强的人审。

### 2.3 明确不做（本轮 / 永久）

- **地址级豁免（#261 的 (b2)）永久否决**：只要平台钱包是多业务共用地址，该设计
  在本仓就无法收敛到安全形态；
- **方案 1（豁免条件加 requestType 维度）**：需要 `signTransfer` 接收 requestType，
  改动面（wallet + gateway 双侧 Feign 契约）大于 A2 且语义更弱（仍按类型豁免而非
  按单笔豁免），不采纳；
- **wallet 提现去重之外的任何路径调优**：gateway 五类业务的审批人审是唯一闸门，
  不在去重范围内。

## 3. A0 已落地的验证证据

- `WithdrawalDoubleSpendGuardTest` 7 例钉住「曾尝试执行 ⇒ 绝不重试 / 5000·5001
  才清标记 / 结果不明不判 FAILED」；
- `DefaultCustodyServiceTest` 2 例钉住「审批闸返回非 EXECUTED ⇒ 托管余额不动，
  `EXECUTED_UNVERIFIED` 时异常带 `POSSIBLY ALREADY BROADCAST`」；
- Flyway V7 迁移 `signing_execution_attempted_at`（CI Flyway 预检覆盖）。

## 4. 与既有裁决的关系

- 板 `20261025-0955`（五、三种结果不能混为一谈）确立了
  `AWAITING_APPROVAL` / `EXECUTED_UNVERIFIED` / 失败三态语义，本 ADR 的 A0 是其在
  「是否豁免审批」维度上的延伸；
- 板 `20261027-0306` §四给出三个改法，本 ADR 采纳其方案 2（即 (a)）作为 A2，
  方案 1 显式否决（§2.3）；
- #261 保持冻结；若有人重启该方向，必须先推翻本 ADR 的 §1 事实链（即证明
  平台出款地址不再被 gateway 五类业务共用），否则按本 ADR 直接拒绝。

## 5. 后续动作（2026-10-27 晚更新；2026-10-29 增结算执行腿 P0 一行）

| 项 | 责任 | 状态 |
| --- | --- | --- |
| A0 落地（防双花打捞 PR #294） | zcode | ✅ 已落地（master `45b1bbb8`） |
| A2 实施设计草案（评审材料） | zcode | ✅ 已成稿：`docs/adr/A2-business-approval-design-draft-2026-10-27.md`，含 5 条待评审决策 |
| A2 立项评审（阈值配置 + 契约设计 + 人力） | 待评审人/用户 | 材料已备；评审通过后并入本文 §2.2 并排期 |
| gateway 五类业务的人审替代方案 | 待认领 | 未启动（A2 前不得动）；"五路径零行为变化"回归断言已作为 A2 草案 §4 不变式 6 固化，随 A2 P2 常驻测试 |
| **⚠️ 结算执行腿收款地址 P0（板 20261029-2057 nex 裁决 F3；zcode 已源码级复核确认——见下）** | nex lane（S1/S2/S3） | **生产切流阻断项**：看板 `20261029-2345-zcode-lane-redivision` 认领分工 |

> **F3 复核结论（zcode，2026-10-29，对 master `1201e2d6` 源码级核实，补 nex 未实跑的两项）**：
> `Base58Utility.decode` 对非字母表字符**逐字符 `INDEXES[c] < 0 ⇒ AddressFormatException` 必抛**
> （`:` 不在字母表）⇒ `WalletUtils.addressToPubkeyHash("MERCHANT:M001")` 必返 `null` ⇒
> `DefaultOnChainExecutionChannel.executeProduction` 返 `failure` ⇒ 引擎落 `FAILED`（发生在
> `ledger.bookSettlement` **之后**）。读侧缺失已复核一致（`ClearingOrder.getPayeeAddress()` 存在、
> `DefaultClearingEngine` 零调用）。**另坐实 nex 未核 ④**：兜底地址 `"1MerchantSettlement" + merchantId`
>（`OrderServiceImpl:247`）含 `l`（字母表不含 `l`）⇒ **S1 的前置校验必须同时拦「空」与「不可解析」**，
> 并处理该兜底地址来源。当前被 sandbox 掩盖（gateway `platform-pubkey` 为空 ⇒ `isSandboxMode()` 恒 true），
> **gateway platform-pubkey 一旦下发立即触发**。S1 修法 = 取 `order.getPayeeAddress()` +
> 前置校验前移到 `bookSettlement` 之前（空/不可解析 ⇒ 直接 FAILED，零账务概念变更）。

> 说明：原独立的「gateway 五路径人工复核计划」**并入 A2 草案的不变式与测试**——
> 现网 `exempt-from-pubkeys` 机制从未在代码中实现（仅存在于板讨论），无需独立巡检；
> 若 A2 立项，五路径的"不带凭据 ⇒ 仍走签名审批"断言随 P2 落地为常驻测试。
