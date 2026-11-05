# 决策：支付扩展交易（0x10–0x1a）要不要上链、怎么上

> **状态：待裁决**。本文只做决策材料与成本核算，不代替 orchestrator / 创始人拍板，不实现任何共识升级。
>
> **基线**：`origin/master` = `1fcd9be5`（2026-11-06 实测；代码行号均在 e7af302f..1fcd9be5 区间内复验）。
>
> **归并声明**：本文合并并取代以下材料作为**当前口径**——zcode [`20261105-0642`](agent-board/20261105-0642-zcode-r1-route-options-material.md)、nex [`20261105-0435`](agent-board/20261105-0435-nex-claim-settlement-decision-materials.md) 中与 R1 相关的法币结算事实、[PLAN-014](plan/PLAN-014-state-execution-wiring.md) Phase 3、审计 [`2026-10-19`](audit/2026-10-19-nexus-core-narrative-reality-routes.md) §5 与 [`2026-10-18`](audit/2026-10-18-eng-review-and-remediation-plan.md)。原材料为 append-only 历史记录，**不删除、不修改**。
>
> **来源标注**：`[zcode 0642]`、`[nex 0435]`、`[audit 1019]`、`[实测]`。材料与实测冲突时以实测为准，并在 §2 保留冲突记录。

---

## 0. 结论（倾向 B，待裁决）

**倾向方案 B：声明链只承载基础转账（0x00–0x0f），0x10–0x1a 标为保留未启用类型；A 作为后续独立立项。**

两条最关键证据：

1. **状态根不承诺余额，放开即增发且不可检测。** 状态根只对 `Account.getIdHexString()` 做 Merkle（[Block.java:128-131](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/Block.java#L128-L131)），而 `Account.getId()` = `pubkeyHash + uint32(blockHeight)`（[Account.java:55-60](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/account/Account.java#L55-L60)）——不含余额/nonce。`MerkleRule` 对 0x10–0x1a 无 case（[:148-292](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/validate/MerkleRule.java#L148-L292)，末 case 0x0f 在 :282），`AccountRule` 对 16+ 只走 `UpdateOtherAccount` 且不校验余额（[:246](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/validate/AccountRule.java#L246)、[PackageMiner.java:78-142](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/consensus/pow/PackageMiner.java#L78-L142)），但 `Miner:145` / `PosConsensusEngine:537-538` 仍把 `tx.getFee()` 加进 coinbase。⇒ 只放开类型门禁 = 发送方不扣款、矿工照样记手续费。[实测]
2. **执行层与支付状态层并不存在。** `processTransaction` 在生产代码中零调用（main 仅定义于 [PaymentTransactionProcessor.java:138](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/PaymentTransactionProcessor.java#L138)），状态写链下 `PaymentStateStore`（[:51](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/PaymentTransactionProcessor.java#L51)，如 `putChannel` 在 :204），不进 `StateDB`/状态根；服务读路径仍是模拟数据（§1）。A 的真实成本是重建支付状态层 + 共识升级，不是一个门禁。[实测]

---

## 1. 现状基线（复验后）

| # | 事实 | 锚点 | 结论 |
|---|---|---|---|
| 1 | 类型门禁 `15 >= type >= 0`，否则 `code=5000 "Type check error"`；注释写明「必须走独立共识升级，不能随业务开关调整」 | [TransactionCheck.java:114-125](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/command/TransactionCheck.java#L114-L125) | 生效于区块接受的 `AccountRule → TransactionFormatCheck`，RPC `verifyTransfer` 同路径 |
| 2 | **第二道墙**：入池守卫对支付扩展类型直接 `false` | [TransactionPool.java:93-106](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/TransactionPool.java#L93-L106)，判定在 :101-104 | A 必须同时改；只改门禁仍进不了池 |
| 3 | `MerkleRule` switch 无 0x10–0x1a 分支、无 `default` | MerkleRule.java:148-292 | 静默 no-op：不扣费、nonce 不增、状态根不承诺 |
| 4 | `GAS_TABLE` **已含** 11 个扩展槽位 | [Transaction.java:90-101](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/account/Transaction.java#L90-L101)，扩展注释 :97-100 | A 不需要“补表” |
| 5 | `processTransaction` 生产零调用 | main 仅 :138 定义；测试 `BridgeFullLifecycleIntegrationTest` 11 处、`PaymentFlowIntegrationTest` 6 处 | 执行未接线 |
| 6 | 入口服务大量构造占位交易 | 11 处 `txPool.add`（§4 表）；`BRIDGE_MINT` 全零 from + 空签名 [BridgeService.java:499-511](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/BridgeService.java#L499-L511)，`CHANNEL_OPEN` 空签名 [PaymentChannelService.java:115-127](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/PaymentChannelService.java#L115-L127)；`BRIDGE_LOCK` 已真实签名 :177-180 | v2.77 只修写路径 fail-closed，读路径仍假 |
| 7 | 读路径仍在造假 | 通道 `state=OPEN`、余额 0 [PaymentChannelService.java:231-240](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/PaymentChannelService.java#L231-L240)；桥 `LOCKED` [BridgeService.java:661-666](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/BridgeService.java#L661-L666)；稳定币零仓位 [StableCoinService.java:251-269](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/StableCoinService.java#L251-L269)；批量 `UNKNOWN` [BatchTransferService.java:163-182](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/service/BatchTransferService.java#L163-L182) | B 也必须收口 |
| 8 | 区块版本锁死、矿工继承父块版本 | [BasicRule.java:111](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/core/validate/BasicRule.java#L111)、[Miner.java:112](file:///F:/Nexus/nc-wt-payext/nexus-core/nexus-core/src/main/java/org/nexus/consensus/pow/Miner.java#L112) | 版本号激活不可行 |
| 9 | 外部审计 P1-1 原话 | 用户转述：2026-11-04「类型定义了、服务写了、接口开了、链拒绝了、没人说破」 | 原文**未在本仓库检索到**（`docs/audit/` 无 2026-11 文件） |

---

## 2. 材料复验（逐条：材料称什么 / 实测什么 / 是否一致）

| # | 材料主张 | 来源 | 实测 | 判定 |
|---|---|---|---|---|
| 1 | 门禁 `15 >= type >= 0`；注释要求独立共识升级 | [zcode 0642] §一 | `TransactionCheck.java:119-125`，注释 :114-118 | 一致（行号偏移系基线 `776989c3`→`1fcd9be5`） |
| 2 | `MerkleRule` 只到 0x0f | [zcode 0642] §一；[audit 1019] §5 | switch :148-292，末 case 0x0f :282-291，无 default | 一致 |
| 3 | 「GAS_TABLE 补 0x10–0x1a 条目」 | [zcode 0642] §二 表 #1 | `GAS_TABLE` 已含 11 槽（Transaction.java:90-101） | **不一致**：此项工作量为 0，A 清单应删除 |
| 4 | `processTransaction` 生产无调用点 | [zcode 0642] §二 #4；[audit 1019] §5；README:260 | main 仅 :138；调用只在两个集成测试 | 一致 |
| 5 | `TransactionPool.isAdmissible` 是入池守卫 | [zcode 0642] §一.3 | :93-106，:101-104 拒 0x10–0x1a | 一致；且确为**第二道墙** |
| 6 | 「三个 REST 仍构造占位交易」 | [zcode 0642] §一 | 实测 5 服务 11 处 `txPool.add`；`BRIDGE_LOCK` 已真实签名 | **部分过时**：数量与“全部占位”均不成立 |
| 7 | 法币结算与链上支付类型无耦合 | [nex 0435] 法币矩阵 | `DefaultClearingEngine.java:192-201` 在记账前 `canTransferTo` fail-closed；`nexus-settlement` 无 0x10–0x1a 引用 | 一致（支持 B 无损） |
| 8 | 0x19/0x1a 无 case ⇒ 凭空增发 | [audit 1019] §5 | MerkleRule 无 case + Miner:145 / PosConsensusEngine:537-538 | 一致；实际覆盖**全部** 0x10–0x1a |
| 9 | A「大」、B「小、1–2 天」 | [zcode 0642] §二/§三 | 文件面：A ≥ 17 个、B ≈ 10 个；人日无依据 | **未核实**（无排期证据，不建议引用） |
| 10 | 是否硬分叉 | 材料未直接标注 | 区块接受语义变更 ⇒ 老节点拒新类型区块 = 硬分叉 | 本文补充 |

> 结论：zcode 材料的**方向判断可用**，但三处需要更正——GAS_TABLE 已就绪、占位交易不止 3 个且部分已签名、人日估算无依据。nex 材料只提供「法币侧与链上类型解耦」这一条支撑，不构成 R1 的独立方案。

---

## 3. 两方案成本对比

| 指标 | 方案 A：立项共识升级 | 方案 B：声明只承载基础转账 |
|---|---|---|
| 需改文件 | **≥ 17 个**（共识/执行 11 + 服务 5 + SDK/explorer/文档若干） | **≈ 10 个**（服务 5 + 控制器/契约 4 + 文档） |
| 关键门禁 | `TransactionCheck:119-125`、`TransactionPool:101-104`、`MerkleRule:148-292`、`AccountRule:246`、`PackageMiner:78-142` | 无共识改动 |
| 状态根 | **必须改语义**（承诺余额/nonce），否则增发不可检测 | 不改 |
| 是否硬分叉 | **是**（区块接受语义变更） | 否 |
| 老节点行为 | 激活高度后拒新类型区块，需协调升级 | 不变 |
| 回滚难度 | **高**：已产出/已固化新区块类型无法回退；只能回滚未激活前的软件 | **低**：纯文档 + 接口语义 |
| 产品叙事 | 兑现「支付结算基础设施」 | 收口为「链做基础转账底座 + 链下复式账本/法币渠道」 |
| 主要风险 | 分叉、增发、验签失败、状态层重建 | 对买方尽调的“名不副实”需明确声明化解 |

### 3.1 方案 A 逐处改动（文件:行 / 风险 / 是否影响已上线路径）

| # | 改动 | 锚点 | 风险 | 影响 transfer/vote/deposit | 激活要求 |
|---|---|---|---|---|---|
| A-1 | 类型门禁 15→0x1a | TransactionCheck.java:119-125 | 改变区块接受语义 | 否（仅新增分支） | 高度/规则激活 |
| A-2 | 入池守卫放行扩展类型 | TransactionPool.java:101-104 | 第二道墙；只改 A-1 无效 | 否 | 同上 |
| A-3 | `MerkleRule` 补 11 个 case（每类状态语义） | MerkleRule.java:148-292 | 共识核心；错一个即状态分歧 | 否（不改既有 case） | 共识升级 |
| A-4 | 状态根承诺余额/nonce（或支付子根） | Block.java:128-131、Account.java:55-60 | **全局共识变更**，影响所有账户 | **是**（所有路径状态根变化） | 需版本化根方案 |
| A-5 | `AccountRule` 增加余额/手续费校验 | AccountRule.java:182-246 | 引入此前不存在的拒绝条件 | 否 | 共识升级 |
| A-6 | `PackageMiner.UpdateOtherAccount` 支持 16–26 | PackageMiner.java:78-142 | 不扣费/nonce 不增则重放 | 否 | 共识升级 |
| A-7 | coinbase 手续费只计入已执行交易 | Miner.java:145、PosConsensusEngine.java:537-538 | 增发；需与 CoinbaseRule:75 对齐 | 否 | 共识升级 |
| A-8 | `processTransaction` 接入区块执行 | PaymentTransactionProcessor.java:138 | 链下 store 不能进共识 | 否 | 执行接线 |
| A-9 | 三/五服务改真实签名 | BridgeService.java:499-511、PaymentChannelService.java:115-127、ChannelSettlementService.java:404 等 | 当前占位签名必然验签失败（SignatureRule:46-48） | 否 | 服务改造 |
| A-10 | 读路径改查真实状态 | PaymentChannelService:231-240、BridgeService:661-666、StableCoinService:251-269、BatchTransferService:163-182 | 需要新状态索引 | 否 | 服务改造 |
| A-11 | 升级治理/兼容矩阵/分叉向量测试 | docs/adr、各 test | 升级顺序错误 = 分叉 | 间接 | 全 lane |

### 3.2 方案 B 改动与「对现有支付业务是否无损」

| # | 改动 | 锚点 | 说明 |
|---|---|---|---|
| B-1 | 读路径收口：不再返回模拟 `OPEN`/`LOCKED`/零仓位 | §1 #7 四处 | 返回明确「未启用/不支持」而非假数据 |
| B-2 | 写端点显式拒绝（保留或删除路由，见 D3） | 13 个写端点（§4） | 删除占位构造死代码，错误码稳定 |
| B-3 | 文档统一口径 | README:260、ARCHITECTURE:102、rpc-doc:339-353、v2.0.0-roadmap:37、PLAN-014:9 | 明确「保留类型、未启用」 |
| B-4 | SDK/explorer 跟口径 | TxUtils.java、CrossChainPage.tsx | 标注“仅编码/不可上链”，跨链页空态说明 |

**无损性证据**：网关链上出金走 `signingClient.signTransfer`（基础 TRANSFER，[SigningApprovalExecutionService.java:147](file:///F:/Nexus/nc-wt-payext/nexus-gateway/src/main/java/org/nexus/gateway/approval/SigningApprovalExecutionService.java#L147)）；法币结算在 `DefaultClearingEngine.java:192-201` 记账前 fail-closed，且 `nexus-settlement` 无 0x10–0x1a 引用 [nex 0435]。⇒ B 不改变现有网关复式账本与法币渠道链路，只收口「链上支付扩展」这一层。

---

## 4. 会打破什么

| 类别 | 具体清单 | A | B |
|---|---|---|---|
| 门禁测试 | `TransactionCheckGateTest:26-37`（拒 0x19/0x1a）、`:39-44`（0x0f 对照） | 必须反转/删除 | 保留，补「明确不支持」断言 |
| 区块门禁测试 | `PaymentExtensionBlockGateTest:54-66` | 反转 | 保留 |
| 入池守卫测试 | `TransactionPoolGuardTest:21-33`（11 类清单）、`:61-71`（拒扩展类型） | 反转 | 保留并补常量 |
| 集成测试 | `PaymentFlowIntegrationTest` 11 个 @Test（100/129/157/185/213/243/266/291/337/374）；`BridgeFullLifecycleIntegrationTest` 11 处 `processTransaction`（98-166） | 需接入真实执行后重写 | 仅断言改为“不支持” |
| SDK | `TxUtils.java` 7 个 raw 构造（266/289/319/341/364/383/415）、5 个 `ClientTo*`（635/651/667/683/699）、4 个高层（909/938/965/993）、4 个查询（1022/1027/1032/1037） | 保留并补状态查询 | 加 `@Deprecated`/“仅编码” javadoc |
| Explorer | `rpc.ts:226-227`、`index.ts:242-246`、`CrossChainPage.tsx:27-36` | BRIDGE_* 上链后页面才有数据 | 空态说明，不删页面 |
| 对外承诺 | README:260、ARCHITECTURE:102、rpc-doc:339-353、`docs/v2.0.0-roadmap.md:37`（“跨链桥 3 链已交付”）、PLAN-014:9/26/210-219、CHANGELOG:38-39 | 需改写为“已完成” | **必须修正**：现文案与链侧现实冲突 |
| 写端点 | 13 个（Bridge 3、Channel 6、StableCoin 2、Batch 1、RPC broadcast 1） | 全量改造 | 全量显式拒绝 |

---

## 5. 激活与兼容（仅 A 需要）

| 机制 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| 硬分叉高度 | 共识参数 `paymentExtensionActivationHeight`，高度前一律拒 | 确定、可测试、无外部依赖 | 需全节点协调；老节点在高度后拒块 = 真分叉 |
| 链上治理参数 | 治理投票写入启用高度/白名单 | 无需改 genesis，可延后 | 当前治理执行是占位（`docs/v2.0.0-roadmap.md:39`）；引入治理依赖 |
| 区块版本号 | `block.nVersion` 标记新规则 | 语义直观 | **不可行**：`BasicRule:111` 强制等于 genesis，`Miner:112` 继承父块 |

**混跑一致性要求（选 A 必须同时满足）**：
1. 激活高度前，**已升级节点也必须拒绝** 0x10–0x1a 区块，否则与老节点状态根分歧；
2. 状态根必须承诺余额/nonce（或独立支付子根），否则不同执行实现的差异不可检测，增发无法发现；
3. 验签必须全节点一致：占位零签名/零 from 交易在 `SignatureRule:46-48` 必然失败，服务必须先真实签名；
4. 链下 `PaymentStateStore` 只能做索引/缓存，不得参与共识判定。

---

## 6. 切片建议（每片可独立验收）

### A 路线（4 片）

| 片 | 内容 | 验收标准 |
|---|---|---|
| A1 状态承诺 v2 | Block/Account 状态根增加余额/nonce 承诺（版本化根） | 篡改余额但不改账户 ID 的区块必须被拒；历史区块重放仍一致 |
| A2 执行与记账 | MerkleRule 11 case + AccountRule 余额校验 + PackageMiner + processor 接线 | 11 类交易逐类：发送方扣款/扣费一次、nonce +1、coinbase 合计等于实扣手续费；畸形交易拒绝 |
| A3 门禁与激活 | TransactionCheck + TransactionPool + 激活高度 + 双版本行为 | 高度前拒、高度后收；老节点/新节点混跑行为有测试向量；回滚脚本可验证 |
| A4 服务与外围 | 5 服务真实签名、读路径查真实状态、SDK/explorer | 本地网 open→close 通道端到端成功；explorer 能看到 BRIDGE_*；无占位签名 |

### B 路线（4 步，顺序固定）

| 步 | 内容 | 验收标准 |
|---|---|---|
| B1 读路径收口 | 4 个服务查询改为明确「未启用/不支持」 | 任一查询不再返回 `OPEN`/`LOCKED`/零仓位假数据；响应含稳定错误码 |
| B2 写端点收口 | 13 个写端点移除占位构造，显式拒绝并指向本文 | 无 `txPool.add` 扩展类型调用；错误码/文案有测试 |
| B3 文档口径 | README/ARCHITECTURE/rpc-doc/roadmap/PLAN-014/CHANGELOG | 全仓检索不再出现“支付扩展可上链/已交付”表述 |
| B4 SDK/explorer | TxUtils 标注仅编码；explorer 跨链页空态说明 | SDK 编译通过且 javadoc 明示不可上链；页面空态可解释 |

> 分期建议（待裁决）：**先 B 后 A**。B 不锁死 A：类型编号保留，将来做 A 时以本文 + B 的声明为起点。

---

## 7. 待裁决清单（不预设结论）

| # | 问题 | 选项 | 影响 | 建议（仅供参考） |
|---|---|---|---|---|
| D1 | 路线选 A / B / 先 B 后 A | ①A ②B ③先 B 后 A | 决定是否启动共识升级与资源投入 | 先 B 后 A；B 成本低且不锁死 |
| D2 | 若 A：状态根是否升级为承诺余额 | ①只加支付子根 ②全局改状态根 ③不改（则 A 不成立） | 影响所有账户与历史重放 | 独立立项，不与支付扩展捆绑 |
| D3 | 若 B：13 个写端点如何处置 | ①保留路由返 501/NOT_SUPPORTED ②删除路由 ③仅内部禁用 | 影响 SDK/调用方兼容 | 保留路由 + 明确错误码，避免破坏调用方 |
| D4 | 0x10–0x1a 编号是否永久保留 | ①保留（防重编号）②回收 | 影响未来 A 与 SDK | 保留，文档标注“保留未启用” |
| D5 | 若 A：激活机制 | ①硬分叉高度 ②链上治理参数 ③延后 | 决定升级协调成本 | 高度激活；治理未落地前不选② |
| D6 | 责任归属 | ①A 共识部分 nex 主笔 ②B 由 TRAE 宝/单 lane 落地 ③另指派 | 决定排期与 owner | B 单 lane；A 需跨 lane 立项 |

---

## 8. 附录：证据索引与未核实项

**核心证据（文件:行）**
- `nexus-core/.../command/TransactionCheck.java:114-125`
- `nexus-core/.../core/TransactionPool.java:93-106`
- `nexus-core/.../core/validate/MerkleRule.java:148-292`
- `nexus-core/.../core/account/Transaction.java:90-101`
- `nexus-core/.../core/account/Account.java:55-60`
- `nexus-core/.../core/Block.java:128-131`
- `nexus-core/.../core/validate/AccountRule.java:182-246`
- `nexus-core/.../consensus/pow/PackageMiner.java:78-142`
- `nexus-core/.../consensus/pow/Miner.java:112,145`
- `nexus-core/.../consensus/pos/PosConsensusEngine.java:537-538`
- `nexus-core/.../core/validate/CoinbaseRule.java:65-77`
- `nexus-core/.../core/validate/BasicRule.java:111,184-205`
- `nexus-core/.../core/PaymentTransactionProcessor.java:51,138,204`

**未核实**
- 外部审计 2026-11-04 P1-1 原文（本仓库 `docs/audit/` 无 2026-11 文件，仅有用户转述）；
- A/B 人日与排期（材料给出“1–2 天/大”，无工时依据）；
- 多机升级演练与分叉向量（受 R5 资源约束，尚未执行）；
- SDK 测试对扩展类型的覆盖（仅见常量引用，未见断言，未逐行核实）。
