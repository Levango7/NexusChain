# A2 开闸前置复核：wallet 多签阈值与审批人独立性配置记录

- 日期：2026-10-28
- 复核人：zcode（ADR-036 残余风险条款要求的开闸前置材料）
- 关联：ADR-036 §2.2（A2，master `212c1de8`）、设计稿 §6（残余风险）、
  板 20261028-0608（A2 立项认领）
- 结论：**A2 当前默认关闭；本文档是开闸（`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`）
  前必须存在的复核记录**。本记录核对了代码事实并逐项给出评估；审批人独立性一项
  已于 2026-10-29 由创始人确认（见 §4）；staging 观察已完成（见 §7），
  **但观察是在 §7.4 的 5 项缺陷修复之上做的 —— master 现状必须先合入这些修复，才谈开闸**。
- 修订：2026-10-29 补 §4 的创始人确认与 §5 检查单勾选（qoder CN 代录）。
- 修订：2026-10-29 补 §7 staging 观察记录与 5 项缺陷（qoder CN）。

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
- [x] staging 先开：观察 signing `BUSINESS_APPROVAL` 审计事件
      （register/consumed/denial 分布）与 wallet 侧 A2 腿日志 ≥ 1 个完整周期
      —— 2026-10-29 在 kind staging 完成，数据见 §7。**注意口径**：本次观察是跑在
      §7.4 所列 5 项修复之上的本地构建上；**master 现状（未含这些修复）不可开闸**，
      理由见 §7.4 缺陷 1/2/3。
- [ ] 生产开启时走 env（`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`），
      回滚 = 置 false（即回 A0 双闸形态，零迁移）

## 6. 本次复核未改动的部分

阈值常量、白名单逻辑、审批计数逻辑——**零产品改动**；本记录只产生证据与检查单。

## 7. staging 观察记录（2026-10-29，kind，qoder CN）

### 7.1 环境与偏差

集群 `kind-nexus-staging`（3 节点，k8s v1.35.0）；`nexus-signing-service` 与
`nexus-wallet-service` 各 1 副本、镜像由 master `619da18f` 本地构建（GHCR 的
`:staging` 标签停在 v2.75.0，**不含 A2**，故未用）；MySQL 8.0 / Redis 7 /
Nacos 2.4.3 / Seata 2.5.0 以宿主 Docker 容器提供，服务经 `172.18.0.1` 接入。
`SPRING_PROFILES_ACTIVE=prod`，`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`。

与生产的**刻意偏差**（均不影响本文档结论，逐条列出以免误读）：
1. 链节点是替身（只应答 `sendNonce`/`sendTransaction`/`transactionConfirmed`），
   不是 nexus-core 真链 —— 因此「链上是否真的落账」未被验证，`chainTxHash`
   仅证明签名腿返回成功；
2. 平台密钥用 NexusChain 原生 keystore（Ed25519 + Argon2id，由仓库 `WalletUtils`
   生成），未走 MPC 3 节点 + DKG 仪式；
3. 单副本（生产 3 副本 / HPA），本轮不覆盖副本间并发；
4. Nacos 为空库（`nexus-common.yaml` 等共享配置不存在），服务靠本地 yml 启动。

另需澄清口径：helm 侧 PR #326 只是把 `NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`
写进 `values-staging`，**不含部署与观察数据**；本 §7.2–7.3 才是观察本身。

### 7.2 观察到的 A2 审计分布（signing，40 分钟窗口）

| type | outcome | reason | 次数 |
|---|---|---|---|
| BUSINESS_APPROVAL | SUCCESS | `registered` | 3 |
| BUSINESS_APPROVAL | SUCCESS | `consumed` | 3 |
| BUSINESS_APPROVAL | SUCCESS | `revert_after_failed_broadcast` | 2 |
| BUSINESS_APPROVAL | DENIED | `consume_already_consumed` | 1 |
| SIGN_TRANSFER | FAILURE | `nonce_fetch_failed` / `tx_build_failed` | 各 1 |

wallet 侧 A2 腿日志同步命中：`A2 凭据登记失败，回落既有 signing 审批流`、
`A2 携凭据签名结果不明（可能已广播，需人工核对）`、`Withdrawal UNVERIFIED`。

### 7.3 关键断言实测

- **成功腿**：3 人审批 → `execute` → `status=EXECUTED`、
  `chainTxHash=e6147d912a6542d594d113bfd3988da12286bea2a4d4fa50b109d900a92e3810`；
  `signing_business_approval.state=CONSUMED`。
- **防双花（A2 的核心安全属性）**：拿已消费的 `businessApprovalId` 再签名 →
  `HTTP 409` + `statusCode=5003`，**没有回落人审**。
- **回落语义**：注册未成功（403）时回落 legacy，凭据零消费、无资金动作；
  广播前失败时 `revert_after_failed_broadcast` 把凭据退回 `REGISTERED`（可重试）。
- **表状态**：`CONSUMED=2 / REGISTERED=3 / 过期仍 REGISTERED=0`。

### 7.4 观察中暴露的 5 个缺陷（均已在本地构建修复并复验）

| # | 缺陷 | 观察到的后果 | 修复 |
|---|---|---|---|
| 1 | wallet `application.yml` 缺 `nexus.security.jwt.secret: ${JWT_SECRET:}`（signing/gateway 都有） | prod 档位 `@Value` 恒空 → 每进程随机密钥 → **wallet 全部受保护端点恒 403** | 补该行 |
| 2 | wallet 出向 Feign 不带 `Authorization`（全仓仅 gateway 有拦截器） | wallet→signing 的 `sign` 与 A2 `business-approvals`（均要求 `ROLE_SIGNER`）恒 403 = **执行腿从未成功过** | 新增 `FeignJwtRequestInterceptor`（与 gateway 同约定，roles 默认 `SIGNING_SERVICE`，signing 侧归一化为 `SIGNER`） |
| 3 | `withdrawal_requests.rejection_reason VARCHAR(256)` 装不下 Feign 异常文案（实测 319 字符） | FAILED/UNVERIFIED **终态永远落不了库**：行停在 `EXECUTING`、端点 500、原因只剩日志 | Flyway V8 加宽到 1024 |
| 4 | `wallet_audit_events.detail VARCHAR(512)` 同型溢出且被 catch 忽略 | 资金相关状态转换的审计**静默丢失** | 同 V8 加宽到 2048 |
| 5 | `NonceAllocator` 建行用 `INSERT … SELECT … WHERE NOT EXISTS`，Seata AT 解析器不支持 | 全局事务内**首次签名必 500**（`NotSupportYetException`）；AT 全回滚还会抹掉 A2 凭据的登记/消费 | 拆成 `UPDATE` + `INSERT`（语义不变，主键兜并发） |

复验结果（与 prod 同口径：Seata 开启、只注入 `JWT_SECRET`、nonce 表清空：
A2 成功腿仍 `EXECUTED`；失败腿落库 `EXECUTED_UNVERIFIED` + 原因 551 字符 +
审计 557 字符；重放仍 `409/5003`。

### 7.5 未解决的观察项（不计入本检查单，另立议题）

- **失败三态映射**：signing 的「明确未动账」以 HTTP 5xx + `statusCode=5000` 表达，
  而 wallet 侧 Feign 把任何非 2xx 变异常 → 一律判 `EXECUTED_UNVERIFIED`，
  **A2 设计里「明确失败 ⇒ FAILED」的分支在生产路径上不可达**，可重试失败被升级为
  人工核对。属资金语义裁定，需 owner 决定（改 signing 的 HTTP 状态 vs wallet 解析响应体）。
- **审计不落盘**：`nexus-core` jar 内 `logback.xml` 遮蔽了服务的 `logback-spring.xml`
  （`Found resource [logback.xml] at …nexus-core-…jar!`），prod 档位的
  `/var/log/nexus/signing-audit.json` 从未生成，审计只在 stdout；root 级别为 DEBUG。
- **地址/公钥词表错位**：wallet 的 `nexus.wallet.platform-address` 被当作
  signing 的 `fromPubkey` 使用（必须是 pubkey），提现 `toAddress` 被当作
  `toPubkeyHash` 使用（必须是 40-hex pubkeyhash，base58 地址会 `tx_build_failed`）。
- 观察窗只覆盖单副本、单笔并发；3 副本下的 CAS/ShedLock 行为未验。

