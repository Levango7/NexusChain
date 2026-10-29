# ADR 草案：`signTransfer` 业务单号幂等键（`requestKey`）

- 状态：**Draft（待评审，不动契约、不动迁移）**——本草案只定设计；评审通过后才进入实现 PR
- 日期：2026-10-29
- 前置：nex `#339` 裁决二（"根治是给 signTransfer 加业务单号幂等键——那是 signing 契约变更，
  属 A2 / 签名单，需 zcode 那条线配合，请勿在本 lane 自行改动"）；ADR-036 §2.2（A2
  `businessApprovalId` 已落地形态）；`#294` / `#300`（同源"结果未知不得当没出重放"原则）；
  板 `20261030-0026-zcode-yield-s1`（分工：幂等键草案 = zcode 本线）
- 作者：zcode

---

## 0. 一句话

给 `POST /api/v1/transfers/sign` 增加**可选的业务单号幂等键**（`requestKey`）：
signing 侧在**广播之前**以唯一约束登记该键，广播之后回写结果（txHash / 失败原因）；
同键重放时**返回已记录的结果而不是再签再播**。不携带该键的调用行为与现版逐字节一致。

## 1. 目标与非目标

**目标**

1. 把"同一笔业务不会因为响应丢失而被二次广播"从**调用方纪律**升级为
   **服务端强制不变量**——客户端无法可靠区分"未动账"与"已广播但响应丢失"，
   这不是调用方能闭合的洞；
2. 幂等键覆盖**直签路径**（低于大额阈值、直接签名广播的调用），这是当前唯一
   没有任何服务端状态机保护的广播入口；
3. 键与载荷**绑定**：同一键换载荷必须被拒，防止幂等键被复用成第二笔的通行证；
4. 与 A2（`businessApprovalId`）、大额审批流（`approvalId` + CAS 状态机）、
   nex 在途的 S1/S2/S3 **正交组合**，不改动它们的语义。

**非目标**

- 不改变大额审批流（`SigningApprovalService` 的 PENDING/APPROVED/EXECUTING/EXECUTED
  状态机）——审批路径已有 `tryMarkExecuting` CAS + `executeApproved` 的 EXECUTING 卡死
  保护，本草案只补**直签路径**缺的那一层；
- 不取代 A2 凭据：A2 解决"这笔业务确实已被批准"，本草案解决"这笔业务只被播一次"，
  两者可同时携带、各管各的；
- 不在本草案内决定 gateway 五路径 / wallet 提现何时开始传键（那是各方自己的接线排期，
  见 §8 演进）；
- 不引入新的鉴权或密钥语义。

## 2. 缺陷：为什么需要（master `1201e2d6` 源码级证据）

### 2.1 双花的完整时序

直签路径（`nexus-signing-service/.../controller/TxController.java`，
`doSignAndBroadcast`，master 现状）：

```
请求 → platformKeystore 校验 → nonceAllocator.claim(address, …)   【:464 原子认领，持久】
     → TxUtils.ClientToTransferAccount(…, nownonce)                【:480 构造+签名+广播】
     → noncePool.add(address, new NonceState(txhash, …))           【:493 在飞台账】
     → 返回 {statusCode:2000, data:txHash}
```

**第 ③ 步是不可逆点**。此后到调用方收到响应之间任何断裂——Feign 读超时、连接重置、
gateway 进程重启、CircuitBreaker 熔断——调用方都拿不到 txHash。而重试会从
`nonceAllocator.claim` 拿到**下一个** nonce，构造**一笔新交易**并再次广播。
两笔都上链 ⇒ 双花。

### 2.2 现有三层防护为什么都漏

1. **gateway 调用方侧链接表**（`SigningApprovalExecutionService.signOrAwait`）：
   进方法先 `repository.findByRequestKey(requestKey)`（`:103`）——但
   `ApprovalExecutionLink` **只在响应为 2001（待审批）时才落库**（`repository.save`
   在 `:127`，位于 `isAwaitingApproval` 分支内）。
   直签成功（2000）的请求**不产生任何行**，于是同键重试查不到记录、
   再次调用 `signTransfer`（`:110`）⇒ 二次广播。**该去重对直签路径形同虚设。**
2. **Feign 降级分类**（`HttpSigningServiceClient`：`:61` `@CircuitBreaker …
   fallbackMethod="signTransferFallback"`，`:127` 降级方法返回 `null`）：
   调用方把 `null` 判"失败/未动账"（`SigningTransfers.fromClassify` 与
   `SigningApprovalExecutionService` 均如此）。**熔断可以发生在广播之后**，
   "null = 确定未动账"是错的——这正是 nex `#339` F4/F5 语义混淆的同一根源。
   即使 S2 把客户端分类改对（区分"确定未动账"与"结果未知"），**"结果未知"时
   调用方仍然不能安全重试**——客户端在原理上无法闭合这个洞。
3. **A2 凭据**（`BusinessApprovalService.tryConsume`）：Server-side CAS 单次消费，
   语义正确，但只覆盖**携带 `businessApprovalId` 的 wallet A2 流**；gateway 五路径
   与所有不带凭据的调用完全不设防。

> **结论**：审批路径有 `approvalId` + CAS 状态机做服务端幂等；A2 凭据路径有
> `businessApprovalId` + CAS 消费做服务端幂等；**直签路径什么都没有**。
> `requestKey` 就是直签路径缺的那个服务端状态机。

## 3. 契约变更（草案形态，评审用）

### 3.1 扩展：`signTransfer` 增可选参数

```
POST /api/v1/transfers/sign        （x-www-form-urlencoded，沿用现有风格）
  fromPubkey:    必填（不变）
  toPubkeyHash:  必填（不变）
  amount:        必填（不变）
  businessApprovalId: 可选（A2，不变）
+ requestKey:    可选  ← 新增。调用方业务单号，全局唯一，≤128 字符
```

- **缺省行为**：`requestKey` 为空 ⇒ 与 master 现版逐字节一致（A2 草案同款
  "可选参数零行为变化"承诺，且是契约门禁的回归断言）；
- legacy 端点 `POST /ClientToTransferAccount` **不接**该参数（与 A2 同口径：
  legacy 只保兼容，不扩能力）。

### 3.2 新增服务端幂等登记表（signing Flyway **V6**）

signing-service 的 Flyway 是**独立版本序列**（V1 审批表 / V2 undo / V3 nonce 分配 /
V4 nonce 对账 / V5 A2 凭据登记），下一个版本号为 **V6**（wallet 的 V6–V8 属
wallet 自己的历史，不冲突）。

```
signing_idempotency_record
  id              BIGINT PK IDENTITY
  request_key     VARCHAR(128)  NOT NULL UNIQUE     -- uk_request_key
  payload_hash    CHAR(64)      NOT NULL            -- canonical 摘要，见 3.4
  state           VARCHAR(16)   NOT NULL            -- CLAIMED / COMPLETED / FAILED
  tx_hash         VARCHAR(128)  NULL                -- COMPLETED 时写入
  fail_code       INT           NULL                -- FAILED 时写入（沿用 SigningResponses 码）
  fail_reason     VARCHAR(512)  NULL
  created_at      TIMESTAMP     NOT NULL
  completed_at    TIMESTAMP     NULL
```

- **不设过期清理**（登记行是审计事实，不是可丢弃缓存）；给
  `state` 建普通索引即可，无高频查询压力（按主键/唯一键命中）。

### 3.3 状态机：`CLAIMED → COMPLETED | FAILED`

```
请求携带 requestKey
  ├─ 唯一约束冲突（键已存在）
  │    ├─ 既有行 COMPLETED + payload_hash 一致 ⇒ 原样回放 {2000, txHash}   【不重签】
  │    ├─ 既有行 FAILED    + payload_hash 一致 ⇒ 原样回放该失败响应         【不重签】
  │    ├─ 既有行 CLAIMED   ⇒ 返回 5005（见 D4：结果未知，禁止重试）        【不重签】
  │    └─ 既有行 payload_hash 不一致 ⇒ 4000 拒绝（见 D5：同键换载荷）      【不重签】
  └─ 键不存在
       ⇒ INSERT（state=CLAIMED）【广播前】
       → nonceAllocator.claim → TxUtils 构造签名广播
       → 成功：UPDATE state=COMPLETED, tx_hash=…
       → 失败响应（5000/4000/4030/502 等资金未动账类）：
              UPDATE state=FAILED, fail_code/fail_reason=…，原样返回该失败
└─ 无 requestKey ⇒ 既有路径逐字节不变
```

**关键不变量**：`CLAIMED` 行的写入与广播的相对顺序 = **先登记后广播**。
这样任何重放落在"登记成功但结果未回写"的窗口里时，服务端能明确回答
"可能已广播，禁止重试"，而不是再播一笔。

### 3.4 载荷绑定（复用 A2 的 canonical 算法）

- 摘要覆盖 `fromPubkey / toPubkeyHash / 归一金额 / requestKey`，直接复用
  `org.nexus.sdk.signing.BusinessApprovalPayloads.canonicalHash(...)` 的
  双侧共享实现（A2 已验证该算法可跨服务复用，无需新造）；
- 复用 A2 的口径：**同键同摘要 ⇒ 幂等回放；同键异摘要 ⇒ 拒绝**。

## 4. 待评审决策

- **D1｜键放在服务端还是只做调用方去重**：本草案取**服务端**。
  理由见 §2.2——客户端去重在"响应丢失"场景原理上不可闭合（调用方无法区分
  未动账 / 已广播）。这是本草案存在的前提，若评审否决 D1 则整案撤销。
- **D2｜新建表 vs 复用 `signing_business_approval`**：本草案取**新建
  `signing_idempotency_record`**。理由：V5 实体的 javadoc 已明确论证
  "批准凭据"与"审批状态机"应当分离（单一职责 + 单向迁移）；同理，"幂等登记"
  的生命周期（CLAIMED/COMPLETED/FAILED，无审批含义）与"已批准待消费凭据"
  （REGISTERED/CONSUMED）语义不同，混表会让两种 CAS 互相污染。
- **D3｜`CLAIMED` 卡死窗口的处理**：`UPDATE` 超时 / 进程崩在"登记后、回写前"
  ⇒ 行滞留 `CLAIMED`。本草案取**不自动回收、返回终态**：重放见 `CLAIMED` ⇒
  返回 **5005**（新状态码，语义"该单号已提交、结果未知，禁止重试，人工核对"）。
  与 `#294`/`#300` 及 A2 的 5003（"可能已广播"）同一原则——**"可能已出"
  永远不得当"没出"重放**。滞留行的清理交给运维/对账，不做自动续传。
- **D4｜5005 是否需要区分于 5003**：5003 在 A2 已定义为"凭据已消费（可能已广播）"。
  本草案建议**新码 5005**而非复用 5003，便于审计区分来源（幂等重放 vs 凭据消费），
  但**调用方两侧统一映射到 `EXECUTED_UNVERIFIED`**（即nex 裁决的三态映射口径，
  `SigningResponses` 增 `STATUS_IDEMPOTENT_REPLAY = 5005` 并在 `classify` 归入
  失败-未知分支）。若评审认为多一码成本高于审计收益，可改为复用 5003 + reason 字段区分。
- **D5｜载荷绑定的强度**：同键异摘要 ⇒ 4000 拒绝（硬错）。另一种选择是"以既有行为准、
  忽略新载荷"，本草案**不取**——那等于允许用旧键播一笔新载荷。
- **D6｜覆盖面**：第一阶段只覆盖 `signTransfer` 直签路径。
  `executeApproved`（`/api/v1/transfers/sign/approved`）**暂不接**——它已有
  `tryMarkExecuting` CAS + EXECUTING 卡死 + `markExecuted` 的状态机保护，
  二次执行会被 CAS 拦在 APPROVED→EXECUTING 之外；是否补接留待评审。
  （注意：`markExecuted` 失败时签名已广播、状态不一致需人工介入——那条路径的
  幂等是审批状态机的事，与本草案正交。）
- **D7｜谁生成键**：**调用方各自生成，signing 只登记不生成**。
  gateway 侧已有现成键 `SigningTransfers.requestKey(requestType, businessId, fallbackId)`
  （`execution:<类型>:<业务ID>`），直接透传即可，无需新键空间。
  wallet 侧用提现单号。生成方与命名权留各服务，本草案只定
  signing 侧的登记/回放契约。

## 5. 并发与多副本语义

- **并发同键**：两个请求同时到达，唯一约束 `uk_request_key` 使只有一个 `INSERT`
  成功；失败方按"键已存在"分支读既有行（此时通常是 `CLAIMED` ⇒ 双方都拿到
  5005，无人触发广播——正确结局）。与 A2 登记侧 `DataIntegrityViolationException`
  竞争处理同款（`SigningApprovalExecutionService` `:128` 在用）；
- **prod 3 副本**：登记表是共享 DB（与 `signing_business_approval` / nonce 分配表
  同库），唯一约束是跨副本的唯一仲裁者——不依赖任何进程内状态；
- **与 nonce 分配的顺序**：**先 `requestKey` 登记，后 `nonceAllocator.claim`**。
  这样被拦截的重放不会消耗 nonce，`nonce_allocation` 的水位因幂等拦截而单调干净。
  （反过来做会让重放烧一个 nonce 再失败，人为制造 nonce 空洞。）

## 6. 与在途工作的关系（避免撞车）

- **nex S1（结算执行腿收款地址）**：改的是 settlement 引擎取值与预校验前移，
  位于 gateway/settlement 侧，**不碰 signing 契约** ⇒ 正交；
- **nex S2（FAILED 语义拆分）**：改的是 gateway `SigningApprovalExecutionService`
  对"确定未动账 / 已执行未知 / 不可判定"三分。本草案**不冲突且互补**：
  S2 让调用方在"结果未知"时不再误判为"可安全重试"；本草案让调用方即使误判重试，
  服务端也拒绝二次广播。S2 是客户端分类修正，requestKey 是服务端兜底，
  两者叠合才把洞闭死（S2 单独留"结果未知时调用方该干什么"的问题，本草案 answer
  是"可以重试，服务端会安全地返回上次结果"）；
- **nex S3（`reverseSettlement` + 对账 `:REV` 排除）**：settlement 侧账务与对账，
  与 signing 契约正交；
- **A2 凭据**：两个可选参数独立——`businessApprovalId` 管批准、`requestKey`
  管单次广播。同时携带时顺序 = 凭据 CAS 消费成功 → requestKey 登记 → 广播；
  凭据"已消费"先于幂等登记判定，保证 A2 的 5003 语义与 5005 语义不相互遮蔽
  （凭据先被消费掉，重放时 5003 先触发，不会走到幂等分支）。

## 7. 契约门禁与测试（评审通过后的实现清单）

1. **SDK**：`SigningServiceFeignClient.signTransfer` 增可选 `requestKey` 参数
   （`@RequestParam(required=false)`，沿用 `businessApprovalId` 的加法形态）；
   `SigningResponses` 增 `STATUS_IDEMPOTENT_REPLAY = 5005` 与 classify 分支；
2. **契约测试**（nexus-sdk 侧）：不携带 `requestKey` 的响应逐字节回归；
   5005 归入"失败-未知动账"分类（映射 `EXECUTED_UNVERIFIED`）；
3. **signing 单测**：`TxController` / `BusinessApprovalService` 同级新建
   `IdempotencyRecordService` 的单测，覆盖：首签成功回写、重放回放 txHash、
   `CLAIMED` 重放得 5005、同键异摘要得 4000、并发同键唯一约束仲裁、
   无参零行为变化；
4. **迁移预检**：新增 `V6__idempotency_record.sql` 进 Flyway 迁移预检门禁
   （本仓有 `Flyway 迁移预检` 专用 CI 腿，且 H2/保留字有前科——建表语句
   避免列名 `value` 等保留字，见 `h2-flyway-reserved-column-name` 教训）；
5. **审计**：登记/回放/拒绝三类事件进 `AuditLogService`（复用
   `AuditEvent.Type.SIGN_TRANSFER`，`reason` 字段区分 `idempotent_replay` /
   `idempotent_conflict`）。

## 8. 演进与回滚

- **分阶段**：迁移 + 服务端登记 + SDK 参数先上（`requestKey` 缺省不传 ⇒ 零影响）；
  各调用方按各自排期开始传键——gateway 直签路径 / wallet 提现路径可分批接；
- **回滚**：调用方停止传键即退回现版行为；表可保留（无交易依赖它）。
  若要彻底回退，删除调用方传键代码即可，`V6` 表不回滚（Flyway 不做反向迁移）；
- **开关**：不设功能开关——可选参数本身就是开关（与 A2 的 flag 形态不同，
  A2 需要开关是因为它改变了审批流走向；本草案只加守卫，无走向变化）。

## 9. 显式不做（留给评审的边界）

- 不做自动续传 / 自动重试协调（`#294` 原则：结果未知交人工）；
- 不给 `executeApproved` 补键（D6，除非评审决定）；
- 不做跨服务的全局幂等中间件（bridge 已有 `V2__idempotency_keys.sql`，
  那是 bridge 域的幂等，与 signing 域不该共用一张表）；
- 本草案**只定设计**：不提交任何 `.java` / `.sql` / SDK 变更；实现 PR 评审通过后另开，
  并按板面规则先认领。

---

### 评审人需要回答的最小问题集

1. D1 是否成立（服务端幂等是否必要）？
2. D4 5005 新码 vs 复用 5003？
3. D6 `executeApproved` 是否纳入第一阶段？
4. `requestKey` 的最大长度与字符集约束（本草案暂取 128 / 可见 ASCII）？
5. `CLAIMED` 滞留行的人工核对入口落在哪个对账视图（与 nex S3 的对账改造
   一并考虑，避免做两套）？
