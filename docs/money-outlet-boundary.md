# 资金出口边界声明（Money-outlet boundary）

> 建立于 2026-10-12，依据三路只读审计（出款双链 C1）。
> 目的：**在代码之外把"钱从哪里出去、谁批、怎么对账"写成一句能引用的话** ——
> 此前仓库里**没有任何**关于两条出款链边界的声明（审计明确核实：找不到一处），
> 而本仓已有两次"平行实现导致重复动账"的真事故（长款双向动账、结算双实现）。
> 客户尽调问"你们有几条出款链、谁批、怎么对账"时，本文是唯一权威答案。

## 1. 一句话边界

**平台有两条互不相通的资金出口，各自独立审批、独立账本、独立审计；任何一笔提现只走其中一条，
两条链之间不存在自动衔接，也禁止新增衔接。**

| | **链 A：商户结算出款（自动提现）** | **链 B：托管钱包提现** |
|---|---|---|
| 业务语义 | 把**商户在平台的结算余额**打到商户绑定的收款账户（银行卡/支付宝/平台结算账户） | 把**托管钱包里的加密资产**提到指定地址（链上转账） |
| 代码位置 | `nexus-gateway` → `org.nexus.gateway.fundreport`（`AutoWithdrawService` / `AutoWithdrawScheduler` / `PayoutExecutor`） | `nexus-wallet-service` → `org.nexus.walletsvc.approval` + `custody`（`DefaultWithdrawalApprovalService` / `DefaultCustodyService`） |
| 触发方 | 系统按规则（`auto_withdraw_rules`）定时执行 + 商户配置/停用规则；另有手动 `POST /api/v1/accounts/{merchantId}/withdraw` 走同一账本（不经出款执行器） | 人工：`POST /api/v1/wallet/withdrawal/{request,approve,reject,execute}`（OPERATOR/APPROVER） |
| 审批 | 无独立审批流；闸门 = **必须有生效出款账户**（`MerchantPayoutAccount.status=ACTIVE`），无则拒绝扣账（fail-closed） | 多级审批：按金额 1/2/3 人（`DefaultApprovalPolicy`，阈值 1 万/10 万），`PENDING→APPROVED→(EXECUTING)→EXECUTED/FAILED` |
| 账本 | `nexus_gateway.merchant_accounts` + `account_transactions`（法币账） | `nexus_wallet.custody_balances` + `withdrawal_requests`（托管账） |
| 链上动作 | 默认**人工打款**（`ManualPayoutExecutor`，登记待打款）；真实通道（银行代付/渠道代付）为可插拔实现 | 经 signing-service 签名广播（平台热钱包密钥库；**不是** MPC 冷钱包路径） |
| 审计表 | `nexus_gateway.audit_events`（`AUTO_WITHDRAW_*` 事件） | `nexus_wallet.wallet_audit_events`（`WITHDRAWAL_*` 事件） |
| 幂等/引用命名 | `AUTO_WITHDRAW_<date>_<ruleId>`、回滚 `AUTO_WITHDRAW_ROLLBACK_*`、人工凭据 `MANUAL_PAYOUT_*` | 请求号 `WD-<uuid>`（唯一键） |
| 跨服务调用 | **无**（不调 wallet-service，不发链上交易） | **无**（只调 signing-service 签名；不调 gateway 的商户账本） |

> 核实口径：两张审计表、两个库、两套 reference 前缀**互不相交**；gateway→wallet 的 Feign 提现方法
> 在 gateway 生产代码中**零调用**（唯一真实调用是健康探针 `isAddressWhitelisted`）；
> wallet-service 无任何对 gateway 的资金调用。（2026-10-12 审计，证据见 CHANGELOG v2.70.0）

## 2. 不变量（改代码时必须同时成立）

1. **一笔业务只走一条链**：商户结算余额 → 链 A；托管加密资产 → 链 B。
   跨链转账（法币 → 加密资产）不在本系统内自动发生，必须由人工在两条链之外完成并各自留痕。
2. **不新增链**：任何新的出款需求先判"属于 A 还是 B"；要新建第三条链必须同步更新本文 +
   在两条既有链上加互斥闸门（参照 `DefaultSettlementService` 的 `nexus.clearing.legacy-batch-settlement.enabled`
   防误接闸门写法：默认 false + 误接即抛错，错误信息里写明权威路径）。
3. **reference / 幂等键命名空间不重叠**：新增前缀必须是新的、且写明归属链；
   链 A 的键落在 `account_transactions.reference`（**无唯一约束**，靠调用方语义去重），
   链 B 的键落在 `withdrawal_requests.request_id`（有唯一键，可依赖数据库兜底）。
4. **fail-closed 优先于"看起来成功"**：任何"账户/凭据/审批不可用"的情形都必须拒绝执行并留痕，
   不得降级为"记账成功、链上未发生"（本仓为此已修过 MockConnector 静默假成功、桥广播假哈希两处）。
5. **审计旁路**：审计写失败绝不阻断资金流程，但**资金动作必须有审计点**（新链新增时必须补齐）。

## 3. 已知开放风险（截至 2026-10-12）

| # | 风险 | 影响 | 状态 |
|---|------|------|------|
| 1 | 提现 `execute` 曾可被并发/重复调用各自广播 | 同一笔提现重复出款 | **已修**（v2.71.0：`APPROVED→EXECUTING` 原子抢占 + 两条回归门禁） |
| 2 | 自动提现调度无 ShedLock、无开关 | 多副本重复出款 | **已修**（v2.71.0：`@SchedulerLock` + `nexus.auto-withdraw.enabled`） |
| 3 | 出款户名一致性校验因无解析器 Bean 而恒跳过 | 可绑定非同名账户（二清环节失守） | **已修**（v2.71.0：生产改由 `MerchantRepository` 取商户名，护栏真正生效） |
| 4 | `POST /api/v1/wallet/custody/rebalance` 可不经审批做冷→热链上转账 | 内部流动性操作无审批留痕 | **未修**（建议：引入 `CUSTODY_REBALANCE` 审批类型 + 必填 approvalId） |
| 5 | `withdrawFromCold` 一次调用触发**两次**链上动作（消耗一次提现审批 + 冷热调拨） | 语义重叠，误接即重复动账 | **未修**（零生产入口；建议按不变量 2 拆成单一动作或删除） |
| 6 | 提现审批链不校验/不冻结托管余额 | 理论上可超额提现 | **未修**（建议：执行前比对 `custody_balances`，不足 fail-closed） |
| 7 | 大额提现（≥1 万）在 signing 侧转人工审批后，wallet 侧直接置 FAILED | 语义错（不是失败而是待审批），两条审批流未打通 | **未修**（建议：识别 2001/PENDING 语义，新增 `PENDING_MANUAL_REVIEW` 而非 FAILED） |
| 8 | `ManualPayoutExecutor` 的"台账"只在进程内存 | 重启失忆、无核销端点 | **未修**（建议：落库 `payout_tasks` + 核销端点） |
| 9 | `SettlementTransferService` DEBIT 分支不经出款闸门 | 若库中存在存量 PENDING 行会直接扣账 | **未修**（无生产者；建议加与 #2 同款防误接闸门） |

## 4. 改动检查清单（动任一条链前先过一遍）

- [ ] 这次改动属于 A 还是 B？是否会让同一笔钱同时被两条链处理？
- [ ] 新增/修改的 reference 前缀是否与另一条链的命名空间冲突？（本文 §1 表格 + `grep` 两侧代码）
- [ ] 资金动作是否 fail-closed？失败路径是否留痕（审计事件）？
- [ ] 多副本下的幂等：DB 唯一键 / 条件 UPDATE / ShedLock 至少命中一种？
- [ ] 是否需要在本文 §1 表格与 §3 风险表同步更新？
- [ ] 涉及审批口径（几级、金额阈值）变更时，客户合规侧是否需要知会？
