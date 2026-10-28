# A2 开闸前置复核：wallet 多签阈值与审批人独立性配置记录

- 日期：2026-10-28
- 复核人：zcode（ADR-036 残余风险条款要求的开闸前置材料）
- 关联：ADR-036 §2.2（A2，master `212c1de8`）、设计稿 §6（残余风险）、
  板 20261028-0608（A2 立项认领）
- 结论：**A2 当前默认关闭；本文档是开闸（`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`）
  前必须存在的复核记录**。本记录核对了代码事实并逐项给出评估；审批人独立性一项
  已于 2026-10-29 由创始人确认（见 §4），**目前剩余唯一硬性项是 staging 观察一周期**。
- 修订：2026-10-29 补 §4 的创始人确认与 §5 检查单勾选（qoder CN 代录）。

## 1. 复核对象：A2 之后一笔提现实际经过的闸门

A2 跳过的**只有 signing 侧大额审批**；wallet 侧业务闸门原样保留。开启 A2 后
一笔提现的完整闸门链：

```
requestWithdrawal（fail-closed）
  ① 地址白名单：isAddressWhitelisted(to) —— 不在名单直接抛错，不落单
  ② 业务多签（本记录 §2 的阈值）：N 名审批人 approve → APPROVED
  ③ [A2 开启时] 登记业务凭据 → signing 校验（哈希绑定载荷 + 一次性消费）→ 直接签名广播
  ③' [A2 关闭时] signing 大额审批（1~3 名签名人再次审批）→ executeApproved
```

即 **A2 的安全强度 = ①+② 的强度**（signing 侧人审被凭据替代）。这正是
ADR-036 §6 要求"与 wallet 多签阈值配置一同评审"的原因。

## 2. 阈值配置事实（master 代码核对，2026-10-28）

- 阈值为 `DefaultApprovalPolicy` 的**代码常量**，无 yml / helm / env 覆写
  （deploy 资产 grep 无命中）：

| 提现金额 | 所需审批人数 |
| --- | --- |
| ≤ 10,000 | 1 |
| ≤ 100,000 | 2 |
| > 100,000 | 3 |

- 计数口径：`approvedCount` 按 `withdrawal_approvers` 表**实际行数**统计
  （`countByRequestId`），非内存自增；
- 防重复审批：`existsByRequestIdAndApproverId` 应用层拦截 +
  数据库唯一约束 `uk_request_approver` 兜底——**同一 approverId 无法重复计数**。

**评估**：三档阈值与 signing 侧大额审批的同型分层一致，A2 开启后
"少一层审批"的缺口由业务层档位覆盖：大额（>10 万）仍需 3 人。
**未发现需要调阈值的理由**；若运营侧认为 1/2/3 档对 A2 形态偏松，
应调常量并同步本记录，而不是不开 A2。

## 3. 白名单与治理入口

- 白名单写入（add/remove）仅 `ROLE_ADMIN`（`WalletController /whitelist/add|remove`）；
  查询 ADMIN/OPERATOR；`DefaultApprovalPolicy` 与 `DefaultAddressWhitelistService`
  共用 `address_whitelist` 表（Phase 4 消重）；
- **平台地址禁配约束**（ADR-036 §2.1 / #261 教训）：白名单是**收款方**名单，
  与 `exempt-from-pubkeys` 无关；后者在本仓代码中从未实现（板 20261028-0450）。
  A2 不引入任何新配置面。

**评估**：收款白名单 fail-closed + ADMIN 独占写入，与 A2 组合无削弱。

## 4. 审批人独立性 —— 已由创始人确认（2026-10-29）

代码层能保证的是"**同一 approverId 不能重复计数**"（§2）。但代码**不能**保证
不同 approverId 背后是不同的自然人（如同一人持有两个账号）。此项无法用
单测或静态检查钉住，属于运营/组织事实，**必须由负责人（创始人）确认**：

> 提现审批人账号（approverId 的来源身份）是否由不同自然人持有？
> 尤其 3 人档的大额提现，3 个 approverId 是否对应 3 个人？

- **确认属实** ⇒ 在本文件补一行确认记录（日期 + 确认人），A2 开闸检查单即满足；
- **不属实**（存在一人多号）⇒ A2 保持关闭，先收紧 approver 账号治理。

### 确认记录

| 项 | 内容 |
| --- | --- |
| 日期 | 2026-10-29 |
| 确认人 | 仓库所有者 / 创始人（GitHub `Levango7`） |
| 结论 | **属实**：提现审批的 `approverId` 由不同自然人持有；3 人档大额提现的 3 个 `approverId` 对应 3 个人 |
| 性质 | 运营/组织事实的书面确认，非代码证据（代码侧仍只保证同一 `approverId` 不重复计数） |
| 代录 | qoder CN（经所有者当轮确认后记录，内容未经改写） |

## 5. 开闸检查单（全部满足才允许把 flag 置 true）

- [x] 阈值三档（1/2/3 人）核对无误，且与 signing 侧大额审批分层对齐（本文档 §2）
- [x] 防重复审批 + 唯一约束核实（本文档 §2）
- [x] 白名单 fail-closed、ADMIN 独占写入核实（本文档 §3）
- [x] **审批人独立性人工确认**（本文档 §4；2026-10-29 创始人确认属实，见 §4「确认记录」）
- [ ] staging 先开：观察 signing `BUSINESS_APPROVAL` 审计事件
      （register/consumed/denial 分布）与 wallet 侧 A2 腿日志 ≥ 1 个完整周期
- [ ] 生产开启时走 env（`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`），
      回滚 = 置 false（即回 A0 双闸形态，零迁移）

## 6. 本次复核未改动的部分

阈值常量、白名单逻辑、审批计数逻辑——**零产品改动**；本记录只产生证据与检查单。
