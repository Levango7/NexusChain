# nexus-core 叙事/现实 路线文档（批次⑥）

- **状态**：待拍板（第 2/3/4/5 项对外口径与"补齐"排期）＋ 先手项已施工（nonce 上界；叙事对齐 4 处）
- **日期**：2026-10-19
- **依据**：`docs/audit/2026-10-18-eng-review-and-remediation-plan.md` §4.6（7 项叙事脱节）
  与 §6 批次⑥（"按'收敛声明'或'补齐实现'两条路线之一推进，建议先出一页路线文档再动代码"）
- **定位**：本文件是**决策支持文档**——逐项给出本次代码复核的现状取证、"收敛声明 / 补齐实现"
  两条路线的具体内容与成本、建议。除第 6 项（nonce，已附测试）外，本文件不改变任何链上行为。

---

## 0. 结论摘要（7 项一览）

| # | 审计发现 | 现状（本次复核，以代码为准） | 建议路线 | 成本 |
|---|---|---|---|---|
| 1 | Merkle 校验失败仍写链 | 同步路径为 **PLAN-012 A-2 审核决策**（轻校验 + 信任签名 + 写链 + 异步对账）；"fail-closed"直译会复现 PLAN-011 的同步死锁 | 收敛（文档精确化，已完成）+ 补齐（A-2/A-3 完整状态重放，独立专项） | 补齐 3–4d |
| 2 | "Groth16"实为 Schnorr | **真实 Groth16 已上线**（方案 C：`zk-groth16-service` Rust arkworks，Java 远程 fail-closed 对接）；本地 BouncyCastle 路径为 Schnorr 降级备胎 | 收敛（3 处架构文档漂移已修 + ADR-001 注记） | 已修 |
| 3 | KZG 为 mock | SHA-256 模拟 + **防误声明**（`kzg-mode=real` 拒绝启动）；代码级 P3 降级声明完备 | 收敛（保持现有诚实标注，无需改动）+ 长期补齐（c-kzg/blst 绑定） | 长期项 |
| 4 | 欺诈证明未锚定 | 单步二分 + 挑战窗 + bond/slashing 完整；但批次/挑战状态在**进程内存**，L1 客户端默认**内存模拟**（`real-l1-enabled=false`） | 收敛（对外标注"L2 内部机制 + L1 模拟"，机制真实/锚定未完成）+ 补齐（持久化 + 真实 L1） | 补齐 3–5d |
| 5 | 0x10–0x1a 支付扩展 opcode 断层 | **类型/单规则预检存在，完整链路被门禁拒绝**（2026-10-20 修正）：`BasicRule` 预检 + 处理器逻辑齐备，但完整区块/RPC 路径被 `TransactionCheck.TransactionFormatCheck` 的 `type<=15` 门禁以 `Type check error` 拒绝，**当前不可上链**；`processTransaction` 生产无调用点，0x19/0x1a 仅日志无状态效果 | 收敛（已执行：口径/注释/门禁测试，PLAN-014 Phase 3）；"可上链/执行接线"属独立共识变更，另立项 | 已收敛（0.5–1d） |
| 6 | nonce 无上界 | 校验点 `TransactionCheck.TransactionVerify`（区块验证 `AccountRule` 调用），无上界 | **补齐（本次已修）**：`MAX_TX_NONCE` 上界 + 回归测试 | 已修 |
| 7 | DB 无版本化迁移 | LevelDB 无 meta 版本键；7 张 PG 表（`JdbcPaymentStateStore` 4 / `JdbcFinalityStateStore` 2 / `ValidatorSetPersistence` 1）全部 `CREATE TABLE IF NOT EXISTS`、无版本 | 补齐（设计草案见 §7，建议独立下一批） | 1–2d |

> **对外口径统一建议**：README 现状已大体诚实（"资产承载限制"节 + 逐组件诚实声明）。建议全仓
> 采用同一措辞框架——**生产默认路径 / 降级备胎（明示限制）/ 未接线或模拟（标注实验性）**，
> 避免"叙述按未来、实现按现状"的缝隙。

---

## 1. Merkle 失败仍写链 —— 现状即审核决策（收敛为主，补齐为辅）

**审计原文**：`PendingBlocksManager:92-110`——Merkle 根校验失败时并未 fail-closed，块照样写入。

**本次复核**：当前代码与该描述一致，但**这已是 PLAN-012 A-2 审核决策的落地形态**：

- `PendingBlocksManager.java:92-110`：同步路径 merkle 状态校验失败 → 明确 WARN 日志 →
  **完整写链**（`stateDB.writeBlock`，best/高度认知恢复）→ 触发 `MerkleTreeManager.writeBlockToCache`
  → `MerkleHandler` 对账协议（`GET_TREE_NODES`/`TREE_NODES`）异步对账。
- 决策背景（`docs/plan/PLAN-012-validation-semantics.md`，2026-08-14 Approved）：同步节点在
  收到块时状态树尚未到达，"同步方做完整状态校验"与"状态未重放"矛盾 → **必死锁**（PLAN-011
  实证）。标准实践 = 同步方轻校验（签名+结构+父链）→ 写链 → 状态重放 → 事后校验
  （失败回滚 + 拉黑）。
- **"fail-closed"直译 = 复现同步死锁**，不是正确修法；正确闭环 = A-2/A-3 完整实施
  （状态重放 + 事后校验 + 回滚接线），PLAN-012 已把"持续同步+确认"记为 PLAN-013 候选。

**两条路线**：

| 路线 | 内容 | 建议 |
|---|---|---|
| 收敛声明 | 在架构/安全文档中把同步路径语义写明："轻校验 + 信任签名 + 异步对账（PLAN-012 A-2），最终一致性由对账/重放保障"；本文档即为口径来源 | ✅ 本次已做（README 已有资产限制节；本文件固化口径） |
| 补齐实现 | 完成 PLAN-012 A-2/A-3：`StateReplayManager`（写链后重放交易 → 状态树重建 → 事后校验 → 失败回滚/拉黑）；随后同步路径才可讨论"最终 fail-closed（重放校验层）" | 建议立项（与 #5 执行接线同一批：同属"状态执行接线"主题） |

## 2. "Groth16"实为 Schnorr —— 三层现实，真实路径已上线（收敛，已完成）

**现状（`DefaultZkProofSystem.java`）**：

1. **远程真实（生产路径）**：配置 `zk.prover.remote-prove-url` / `remote-verify-url` 后，
   prove/verify 走 `zk-groth16-service`（Rust arkworks，BN254 双线性配对）——**真实 Groth16**；
   服务不可用 **fail-closed**（prove 抛异常 / verify 返回 false），不降级（:150-166、:213-244）。
2. **本地降级备胎**：`l2/zk/groth16/`（BouncyCastle + R1CS）是 Schnorr 简化实现，代码注释
   自标"本地 Schnorr 降级路径"（:228），仅未配置远程服务时生效。
3. **mock**：`MOCK|` 前缀明确标记，`zk.prover.mock.allow-verify=false`（默认）下 verify
   一律拒绝（:45-56）。

**收敛动作（本次已执行，4 处）**：`ARCHITECTURE.md` ZK 段 2 处（原"支持未来接入 Groth16"
与"Groth16 真实实现（BouncyCastle）"的误导性表述）；`docs/roadmap-unresolved.md` #6 行；
`docs/adr/ADR-001-research-layer-freeze.md` P5-T4/T5 后续更新注记。README 无需改动（已诚实）。

## 3. KZG 为 mock —— 已诚实标注 + 防误配置（收敛，无需改动）

**现状（`l2/blob/Eip4844BlobCarrier.java`）**：KZG 承诺/证明为 SHA-256 模拟，类头有完整
"P3 降级声明（A1-R6 确认）"（:17-24）；`l2.blob.kzg-mode=real` **拒绝启动**（:96-108），
防"声称真实但实际模拟"的错误安全声明。README/ARCHITECTURE 抽查未发现"真实 KZG"超范围表述。

**结论**：叙事无漂移（代码即口径）。长期补齐（接 c-kzg/blst 真实 BLS12-381）是独立项目，
非文档问题——不建议本批次动。

## 4. 欺诈证明未锚定 —— 机制真实、锚定未完成（收敛为口径 + 补齐排期）

**现状（`l2/FraudProof*.java`）**：单步二分证明（`FraudProof`，O(log n)）+ 挑战窗 +
challenge bond + 罚没奖励 + 多挑战者冲突解决（first-valid-wins）实现完整。
**但**：`FraudProofVerifier` 的批次/挑战状态为进程内存（`batchSubmitTime`/`batchStore`/
`submitterStore`/`challengeBonds` 等 ConcurrentHashMap，:68-81）；L1 侧
`DefaultL2BridgeContract` 默认注入**内存模拟** L1 客户端（`real-l1-enabled=false`，:26）。
即：**L2 内部挑战机制真实；"锚定到链上状态"（持久化 + 真实 L1 合约交互）未完成**。

**路线**：收敛＝对外标注"Optimistic 机制在 L2 进程内运行、L1 交互默认模拟（可配置真实）"；
补齐＝批次/挑战状态持久化（PG/LevelDB）+ `real-l1-enabled=true` 全链路真机验证。
（README L2 节"资产承载限制"已覆盖大方向；如需精确到"锚定未完成"，建议在此立项时补一句。）

## 5. 0x10–0x1a 支付扩展 —— "类型/单规则预检存在，完整链路被门禁拒绝"（已修正）

**现状**：类型定义齐备（`core/account/Transaction.java:130-141`，CHANNEL_OPEN 0x10 …
SUBSCRIPTION_AUTH 0x1a）；`BasicRule` 有单规则预检（`BasicRule.java:187-205`）；
`PaymentTransactionProcessor.processTransaction` 分发 0x10–0x18 有逻辑、0x19/0x1a 仅日志
（`:161-170`），**但 `processTransaction` 生产无任何调用点**。

**2026-10-20 修正（PLAN-014 Phase 3 侦察）**：上文"验证在链"只对 `BasicRule` 单规则成立。
完整区块/RPC 路径先经 `AccountRule` 的 `TransactionFormatCheck`，其硬编码 `type <= 15`
门禁（`TransactionCheck.java:119-125`）会以 `Type check error` 拒绝 0x10–0x1a；因此这些
类型**当前不可上链**，不是"上链后不执行"。（本文档原始结论"验证在链、执行未接线"据此修正。）

**风险提示**：若直接放宽门禁，`Miner.java:145` 会为每笔交易给 coinbase 记手续费，而
`MerkleRule` 对 0x19/0x1a 无 case（不扣费、不递增 nonce）→ 凭空增发；且
`hashMerkleState` 只承诺账户 ID + 高度，状态根发现不了（详见 PLAN-014 Phase 3 记录）。

**收敛（已执行）**：修正 README / ARCHITECTURE / 本文档 / CHANGELOG 与代码注释口径；
补 `TransactionCheckGateTest` / `PaymentExtensionBlockGateTest` 门禁回归。
**补齐（另立项）**：若要"区块接受但不执行"的 validate-only，属共识变更（激活高度、
费率/余额/nonce、状态根承诺、全节点升级），不能与"无共识变更"混谈。

## 6. nonce 上界 —— ✅ 本次已修（补齐）

**缺口**：`TransactionCheck.TransactionVerify`（被区块验证规则 `AccountRule.java:176` 调用）
仅校验 `nownonce >= trannonce` → "Nonce is too small"（防重放），**无上界**：
`Long.MAX_VALUE` 级 nonce 会把账户钉死（后续任何 nonce 都"too small"），并使协议内部递增
（`StateDB.applyIncubate` 系统账户 `nonce++`，StateDB.java:890）在边界回绕为负。

**修复**：`TransactionCheck` 新增 `public static final long MAX_TX_NONCE = Long.MAX_VALUE - 1024`
（1024 余量覆盖内部递增空间）；超界拒绝 `Nonce out of range`。回归测试
`TransactionNonceBoundTest`（5 用例：上界内通过 / 上界+1 拒绝 / `Long.MAX_VALUE` 拒绝 /
too-small 语义保持 / 正常递增通过）。

## 7. DB schema 版本化 —— 设计草案（补齐，建议独立下一批）

**现状**：LevelDB（`db/Leveldb.java`，`@PostConstruct` 单例，无 meta 版本键）；
PG 侧 7 张表全部 `CREATE TABLE IF NOT EXISTS`、无版本记录（`JdbcPaymentStateStore:72-86`、
`JdbcFinalityStateStore:57-61`、`ValidatorSetPersistence:45`）。

**设计草案**（供拍板）：

1. **PG**：新增共享工具 `SchemaMeta.ensure(JdbcTemplate, store, version)`——创建
   `nexus_schema_meta(store TEXT PRIMARY KEY, version INT NOT NULL, updated_at TIMESTAMP)`；
   各 store 在初始化时注册自己的 `store/version`：记录不存在 → 写入当前版本；存在且
   相符 → 通过；存在且低于 → 按迁移脚本升级（首批可只支持"版本相等"并 fail-fast 提示）；
   高于 → 拒绝启动（防旧代码开新库）。
2. **LevelDB**：`Leveldb.init()` 写入/校验 `__meta/schema_version` 键，语义同上。
3. **迁移政策**：additive-only（只加列/表，不改语义）；破坏性变更必须携带显式迁移脚本
   （命名 `migrations/pg/NNN-desc.sql`，由运维在升级窗口执行）；启动时校验版本，
   不匹配即拒绝服务并输出指引。
4. **验收**：单测覆盖"缺失→写入 / 相符→通过 / 不符→拒绝"三分支；文档
   `docs/db-schema-versioning.md` 落政策。

## 8. 拍板记录（2026-10-19，全部按建议执行）

| 决策 | 结论 | 落地 |
|---|---|---|
| 第 2/3 项表述 | 维持现状（已完成收敛） | ✅ 审计轮次已修 |
| 第 4/5 项对外口径 | (a) 按 §4/§5 措辞补精确标注 | ✅ PR #150：README L255/L257 + ARCHITECTURE L2 节现状注记 |
| 第 1/5 项"补齐" | (a) 合并立项"状态执行接线"专项 | ✅ `docs/plan/PLAN-014-state-execution-wiring.md`（一次设计、Phase 0–5 分期交付） |
| 第 4 项"补齐" | 随"资产承载"产品节奏，并入专项 | ✅ 并入 PLAN-014 Phase 4（欺诈证明锚定 + 真机 L1 链路） |
| 第 7 项 | (a) 下一批即做 | ✅ PR #151：`SchemaMeta` + LevelDB `__meta/schema_version` + `docs/db-schema-versioning.md`（PLAN-014 Phase 0） |

> 本文件正文保留 2026-10-19 审计时点原文；决策以本表为准。

---

**附：本文件涉及文件索引（仓库根相对路径）**

- `nexus-core/nexus-core/src/main/java/org/nexus/core/PendingBlocksManager.java`
- `nexus-core/nexus-core/src/main/java/org/nexus/l2/zk/DefaultZkProofSystem.java`
- `nexus-core/nexus-core/src/main/java/org/nexus/l2/blob/Eip4844BlobCarrier.java`
- `nexus-core/nexus-core/src/main/java/org/nexus/l2/FraudProofVerifier.java` / `DefaultL2BridgeContract.java`
- `nexus-core/nexus-core/src/main/java/org/nexus/core/PaymentTransactionProcessor.java`
- `nexus-core/nexus-core/src/main/java/org/nexus/command/TransactionCheck.java`（本次修改）
- `nexus-core/nexus-core/src/test/java/org/nexus/command/TransactionNonceBoundTest.java`（本次新增）
- `docs/plan/PLAN-012-validation-semantics.md`（#1 决策原文）
