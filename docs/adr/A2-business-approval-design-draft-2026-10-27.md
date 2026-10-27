# A2 实施设计草案：`businessApprovalId` 单笔豁免契约（立项评审材料）

- 状态：**Draft / 待立项评审**（评审通过后并入 ADR-036 §2.2 并改名去掉 draft）
- 日期：2026-10-27
- 前置：ADR-036（A0 Accepted / A2 Proposed）；板 20261027-0306（#261 前提失效）、
  板 20261027-0557（D2 拍板 A0+A2）、#294（同源"结果未知"fail-closed 原则）
- 作者：zcode（认领见板 20261027-2335）

## 0. 一句话

给 `signTransfer` 增加**可选的单笔业务批准凭据**（`businessApprovalId`）：
signing 校验"这一笔业务审批确实已通过"后**跳过自己的大额审批**；
其余一切情形（缺少参数 / 校验失败 / 已消费 / 已过期）**行为与现版逐字节一致**（fail-closed）。

## 1. 目标与非目标

**目标**

1. 消除 wallet 提现的**双审批**（wallet 己方多签 + signing 大额审批）——
   同一笔资金动作、同一批审批人重复劳动；
2. 豁免粒度 = **单笔**（与 #261 的"地址豁免"根本不同），凭据一次性消费、
   绑定载荷、有生存期；
3. gateway 五类共享出资路径（支付/退款/订阅/chain-pay/consortium）**零行为变化**：
   它们不携带凭据，继续走 signing 大额审批（A0 的强制闸门不变）。

**非目标**

- 不改变 #261 的冻结结论（地址级豁免永久否决，见 ADR-036 §2.3）；
- 不给 gateway 路径"顺带"豁免（那需要各自等强的人审替代，另行评审）；
- 不引入 wallet 自签 proof 形态（原因见 §3.1）。

## 2. 契约变更

### 2.1 新增：业务批准登记（wallet → signing，`POST`）

```
POST /api/v1/signing/business-approvals
body: {
  "businessApprovalId": "WD-2026-...",        // wallet 提现单号（全局唯一）
  "payloadHash": "<sha256 hex>",              // canonical payload 摘要，见 2.3
  "amount": "50000.000000000000000000",
  "currency": "USDT",
  "fromPubkey": "0x...",
  "toPubkeyHash": "...",
  "expiresAt": "2026-10-27T16:00:00Z",        // 由 wallet 派生自提现单 TTL
  "approvers": ["..."]                        // 审批人快照（审计用，不参与校验）
}
→ 2000 {statusCode:2000, data:{businessApprovalId, state:"REGISTERED"}}
```

- **幂等**：同 `businessApprovalId` 重复登记，`payloadHash` 相同 ⇒ 幂等返回；
  `payloadHash` 不同 ⇒ **拒绝**（409 语义 / statusCode 5002），防"同一单号换载荷"；
- 登记本身**不触发任何资金动作**，不读平台密钥库。

### 2.2 扩展：`signTransfer` 增可选参数

```
POST /api/v1/transfers/sign?fromPubkey=..&toPubkeyHash=..&amount=..
     [&businessApprovalId=WD-2026-...]        // 新增，可选
```

signing 侧分派：

| 条件 | 行为 |
| --- | --- |
| 未携带 `businessApprovalId` | **现版逻辑，逐字节不变**（大额 ⇒ 建 signing 审批返 2001） |
| 携带且校验全通过 | 跳过自身审批 → 直接签名 + 广播 → 凭据置 CONSUMED（CAS 单次）→ 审计 |
| 携带但任何校验不过（不存在/过期/已消费/哈希不匹配/金额不一致） | **回落到现版逻辑**（建 signing 审批，返 2001）；凭据不动；审计记拒绝原因 |

> 注意：校验失败**不是**报错拒绝整笔，而是"降级为旧流程"——这保证
> wallet 侧即便错误地携带凭据也不会让一笔合法提现卡死，同时不产生任何豁免。

### 2.3 载荷绑定

`payloadHash = SHA-256( canonical( "A2-v1" | fromPubkey | toPubkeyHash | amount(36,18 定标) | businessApprovalId ) )`

- signing 在 `signTransfer` 时**重算**并与登记值比对；
- 定标规则：`amount.stripTrailingZeros().toPlainString()` 归一（与 SDK 口径一致），
  避免 `50000` vs `50000.00` 误判；
- 用哈希而非逐字段比对：扩展字段时不必再改比对逻辑，且登记表无需存敏感明文
  （金额/地址仍需存用于审计展示，故实际两者都存，哈希用于**校验**）。

## 3. 关键设计决策（评审点）

### 3.1 D1：登记制 vs 回调查询 vs 自签 proof —— 推荐**登记制**

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| **登记制**（本草案） | 无服务间环依赖；signing 侧自持"已批准"事实，校验是本地查询；离线可验 | signing 多一张表；wallet 多一次调用（可失败重试，幂等） | ✅ 推荐 |
| 回调查询（signing 同步调 wallet 查审批） | 事实单一来源 | signing → wallet 反向依赖（调用环）；wallet 抖动会阻塞签名路径；权限面扩大 | ❌ |
| wallet 自签 proof（JWS/HMAC） | 无状态 | **A1 教训**：共享 `JWT_SECRET` 可伪造 subject ⇒ 同款载体不构成独立信任；引入密钥管理面 | ❌ |

安全性说明：登记接口与 `signTransfer` 同级信任（服务间认证 + 网络边界），
**不声称比现网更高**——A2 的安全性增量来自"业务审批替代密钥人审"这一决策本身，
而不是来自载荷防伪。防伪解决的是**篡改**（改金额/改收款人），不是**伪造来源**。

### 3.2 D2：凭据生命周期 —— 推荐**一次性消费 + wallet 派生 TTL，过期不续**

- 消费 = CAS 置 `CONSUMED`（并发下只有一个 signTransfer 能消费成功，其余回落旧流程）；
- `expiresAt` 由 wallet 按提现单 TTL 给出（与 wallet 审批超时同源）；
- 过期后凭据**终态失效**，不提供续期 API（要重批就重新走 wallet 审批 → 新单号）；
- 与 #294 的教训对齐：**不存在"回到 APPROVED 可重试"的中间态**——
  消费即终局，避免第二套 TTL 回退语义。

### 3.3 D3：为什么不复用 signing 既有审批表 —— 推荐**独立表**

`signing_approval_request` 的状态机语义是"**等待**签名人批准"（含 TTL 回退、
EXECUTING 中间态）；A2 凭据语义是"**已经**批准、待消费"。两者混用会把两种
生命周期（回退 vs 终局）绑在一起——正是 #294 防双花修复反复绕开的坑。
独立表 `signing_business_approval`（signing 迁移 **V5**）：

```sql
CREATE TABLE IF NOT EXISTS signing_business_approval (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    business_approval_id VARCHAR(64)  NOT NULL,   -- wallet 提现单号
    payload_hash        CHAR(64)     NOT NULL,   -- sha256 hex
    amount              DECIMAL(36,18) NOT NULL,
    currency            VARCHAR(16)  NOT NULL DEFAULT 'USDT',
    from_pubkey         VARCHAR(512) NOT NULL,
    to_pubkey_hash      VARCHAR(128) NOT NULL,
    approvers_json      TEXT,                    -- 审批人快照（审计）
    state               VARCHAR(16)  NOT NULL DEFAULT 'REGISTERED', -- REGISTERED/CONSUMED
    consumed_at         TIMESTAMP    NULL,
    expires_at          TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_approval_id (business_approval_id),
    INDEX idx_state_expires (state, expires_at)
);
```

## 4. 不变式（实现时必须钉住，逐条配测试）

1. `businessApprovalId` 缺失 ⇒ signing 行为与 ADR-036 前**完全一致**
   （契约测试：golden 用例对比响应形状与 statusCode）；
2. 凭据**单次消费**：并发两个 signTransfer 持同一凭据，只有一个豁免成功；
3. 载荷任一字段不符 ⇒ **不豁免**（回旧流程），凭据不消费；
4. 过期凭据 ⇒ 不豁免；**不可续期**；
5. 消费后重放 ⇒ 不豁免（回落旧流程），不报 5xx；
6. gateway 五路径（`PaymentServiceImpl` / `SubscriptionServiceImpl` /
   `ChainConnector` / `ConsortiumConnector` / `DefaultOnChainExecutionChannel`）
   **不带该参数**，且各配一条"不带凭据 ⇒ 仍走签名审批"的回归断言；
7. 每次"凭据豁免成功"必须留审计：`businessApprovalId`、`payloadHash`、
   审批人快照、消费时间（谁批准、批了哪笔）。

## 5. 分阶段落地（若立项）

| 阶段 | 内容 | 开关 | 影响面 |
| --- | --- | --- | --- |
| P1 | signing 侧：表（V5）+ 登记端点 + 校验/消费逻辑 + 审计 + 契约测试 | 新增端点默认 404 之外的路径**零行为变化** | signing 独立 |
| P2 | wallet 侧：提现单 APPROVED 后登记 + `signTransfer` 携带凭据 | `nexus.signing.business-approval.enabled` **默认 false** | wallet |
| P3 | 开闸 + 巡检（凭据命中率、拒绝原因分布）；gateway 排除断言常驻 | 配置开闸 | 全链路 |

每阶段独立可回滚：P1 只加不改；P2 关开关即回旧行为；P3 关开关即停。

## 6. 残余风险（照 ADR-036：必须与 wallet 多签阈值同评审）

- A2 把"密钥层人审"换成"业务层审批"，**安全强度 = wallet 审批强度**
  （审批人是否独立、是否可被单点批量批准）——因此 A2 开闸的前置条件是
  wallet 侧多签阈值配置的复核记录，不能只过工程评审；
- 登记接口的调用方若被攻破，等于绕过 signing 审批（但攻击者本就能调
  `signTransfer` 造大额单——审批闸的价值正是在此拦住；登记制把这层价值
  的校验点放在 signing 本地，需保证**只有已 APPROVED 的提现单才会去登记**，
  该约束由 P2 的 wallet 侧实现承担，须配代码评审 + 测试）；
- 与 gateway 的边界：本草案**不开**给 gateway 任何豁免；若未来要开，
  需先为各自路径设计等强人审（订阅扣款等），另立 ADR。

## 7. 评审决策清单（请评审人逐条裁决）

- [ ] **D1**：登记制（推荐）/ 回调查询 / 自签 proof；
- [ ] **D2**：一次性消费 + wallet 派生 TTL + 不续期（推荐）；
- [ ] **D3**：独立表 `signing_business_approval`（signing V5）+ 不复用审批状态机（推荐）；
- [ ] **D4**：`payloadHash` canonical 规则（`A2-v1` 前缀 + 定标金额）照准；
- [ ] **D5**：立项后是否由 zcode 继续实施（本草案作者），或另派。
