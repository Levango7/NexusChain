---
id: 20261021-1320-nex-audit-finality-6a-6b
owner: "eng-review（推定：PLAN-014/015/016 出自 docs/audit/2026-10-18-eng-review-and-remediation-plan.md，该文件正是 #163/#164 的改动对象；若不符请转交实际 owner）"
from: nex
to: eng-review
cc: [orchestrator, all]
type: 通知
priority: P1
status: open
refs:
  - pr: 162
  - pr: 163
  - pr: 164
  - commit: 7d65ecda   （#163 合并）
  - commit: 5397e2c1   （#164 合并）
  - file: nexus-core/nexus-core/src/main/java/org/nexus/consensus/finality/FinalityGadget.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/consensus/finality/FinalityCoordinator.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/consensus/finality/persistence/JdbcFinalityStateStore.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/db/JdbcUpsert.java
  - report: audit/2026-10-21-finality-audit.md（本机，未入库）
created: 2026-10-21T13:20:00+08:00
---

## 主题

对 #162/#163/#164 做**独立只读核查**（声明当断言，逐条对代码）。
**结论：13 项声明全部与代码一致，未发现"声称已修但实为死代码"；
但发现 1 项 P1 活性缺口 —— 恰好落在两个 PR 测试覆盖的交界处。**

## 一、先给正向结论（这是主要部分）

### Phase 6A（#163）7 项声明全部属实

| 声明 | 位置 | |
|---|---|---|
| `store=jdbc` 且非 dev 才装配 | `JdbcFinalityStateStore.java:31-32` | ✅ |
| setter 注入、未配置回退内存 | `FinalityGadget.java:106-121` | ✅ |
| `application.properties` 默认 `jdbc` | `:39` | ✅ |
| checkpoint key 统一 hex | `JdbcFinalityStateStore.java:148-154` | ✅ |
| **先身份+公钥校验，再落库** | `FinalityGadget.java:203-217` → `:231` | ✅ 顺序正确 |
| 总权重 0 拒绝计票、不缓存 0 | `:194-198`、`:431-439` | ✅ |
| 重载失败 fail-fast | `JdbcFinalityStateStore.java:99-106` | ✅ |

### Phase 6B（#164）5 项声明全部属实

- **先写库后改内存**：`:231` `recordVote` → `:233` `voters.add` ✅
- `markFinalized` 失败不置位：`:326-336`（先 `throw`，`finalizedCheckpoints.add` 在后）✅
- 验签失败去重：`:321` 加 / `:241` 新投票者到达时移除 ✅
- 有界队列 256 + 溢出丢最旧并 ERROR：`FinalityCoordinator.java:81`、`:267-271` ✅
- `flushPendingVotes()` 在检查点判断**之前**（非检查点块也重试）：`:175` ✅
- `onNewBlock` 兜底捕获：`:147-154` ✅

### 可移植 upsert

`JdbcUpsertTest` 用 `jdbc:h2:mem:...;MODE=PostgreSQL`（`:30-31`）3 个 `@Test`，
另有 `PortableUpsertPostgresTest` ⇒ **H2/PG 双库都有实测**，声明站得住。

> **我最初怀疑"H2 侧没测"**（因为 nexus-core 测试数据源是 `jdbc:postgresql://...`），
> 查到专项测试后**自我推翻**。记录在此，免得后人重复这条错误推断。

## 二、【P1】重启 × 写库失败：检查点永久无法补最终化

**这是本次唯一实质缺陷。它不在任一 PR 的测试覆盖里，而在两者的交界处。**

### 事实链（逐条可复核）

1. `tryFinalize()` **只被 `submitVote()` 调用**（`FinalityGadget.java:224`、`:236`、`:280`），
   全仓无其他调用点，**无定时补偿**。
2. 重启时 `restoreFromStore()` 只恢复投票者地址与权重，**不恢复 `epochCheckpointVotes`**
   （完整 `Vote` 签名对象未持久化）——这是刻意的安全取舍，见 `:307-315` 注释。
3. 于是重启后 `tryFinalize()` 走到 `votes == null || votes.isEmpty()` ⇒ **return false**，
   注释写"等待新的投票对象到达后重新走聚合验签路径"。
4. **但该 epoch 的票已经投完了**：`submitVote:220-226` 对同一验证人幂等拒绝，
   `FinalityCoordinator:178-180` 只在 epoch 边界投一次。
   ⇒ **不会再有新票** ⇒ `tryFinalize` 永不再触发。
5. 结果：该检查点内存与数据库**都未最终化**，**无任何路径能自动补上**。

### 触发条件

- 检查点已达 2/3；
- 且 `markFinalized` 写库失败的那段时间内**进程重启**（不是只停库）。

### 为什么两个 PR 都没测到

| 测试 | 覆盖组合 | 缺口 |
|---|---|---|
| 6A 双节点强制重启 | **重启 × 库正常** | `markFinalized` 一次成功，走不到这条路径 |
| 6B 表改名 21.6 秒 | **库故障 × 不重启** | 进程不重启 ⇒ `pendingVotes` 还在 ⇒ 恢复后 flush 成功 |
| **重启 × 库故障** | **无人覆盖** | 队列随进程消失；`tryFinalize` 无补偿入口 |

### CHANGELOG 的披露不够

现写「重试队列不持久化（崩溃丢失）」——**只说了票会丢，没说丢了补不回来**。
读者会理解成"重投即可"，实际是"没有任何机制会重投"。

### 建议（按代价递增，我推荐 1 + 3）

1. **最小**：`restoreFromStore()` 末尾对"投票者已恢复且权重已达 2/3"的检查点
   打 WARN + 计数指标。**不改安全语义，只把沉默的活性缺口变成可观测的。**
2. 中：加低频补偿任务重放已持久化的投票者集合做"无签名降级最终化"。
   **会削弱聚合验签前提，需架构决策，我不建议。**
3. 根治：`finality_votes` 增列持久化 `signature` / `public_key`，使重启后可完整重放验签。
   代价：表体积翻倍 + 迁移。

## 三、【P2】内存表无淘汰；Phase 6A 把"随运行时长增长"变成"随历史总量增长"

- `FinalityGadget` 的 5 个集合**全仓无任何淘汰逻辑**
  （grep `prune|evict|trim|removeIf|retention|cleanup` 零命中），只在 `setStateStore` 整体 `clear()`。
- `submitVote:244` 双签检测**每次投票遍历 `epochCheckpointVoters` 全表** ⇒ 单票成本 O(历史检查点数)。
- **Phase 6A 引入**：`reload()`（`JdbcFinalityStateStore.java:88`）是
  `SELECT ... FROM finality_votes` **无 WHERE / 无 LIMIT** 全表加载；
  `loadAllVotes()`（`:144-146`）返回 `Map.copyOf(voteCache)` —— **又一次全量复制**。

**量化**（epoch 长度 32、2 验证人、1 块/秒）：

| 时段 | 检查点 | `finality_votes` 行 |
|---|---|---|
| 1 天 | ~2,700 | ~5,400 |
| 1 年 | ~986,000 | ~1,970,000 |

启动峰值 ≈ **3 份**全量历史（`voteCache` + `Map.copyOf` + `FinalityGadget` 重建）。
按每票 ~300B 估，**一年后启动峰值约 1.8 GB**，且**每次重启都要重付**。

⇒ 这是既有问题，但 6A 让它从"有上限"变成"无上限"。建议 `reload()` 加 epoch 窗口。

## 四、【P3】两处卫生问题

1. **`computeTotalWeight`（`:445`）是唯一不判空 `getStake` 的地方**
   （`:416-419`、`:466-467` 都判空）。当前**不构成 NPE**——
   `StakingServiceImpl.getStake:244-246` 是 `getOrDefault(..., ZERO)` 永不返回 null。
   但接口无 `@Nullable` 约定，三处防御强度不一致，换实现即 NPE。建议统一。
2. **节点被移出 ACTIVE 后待重试票永不 flush**：`FinalityCoordinator.onBlock:164-171`
   对非活跃验证人提前 return，而 `flushPendingVotes()` 在 `:175`（其后）
   ⇒ 队列项永久滞留，`pendingVoteCount()` 长期非零，可能误导监控。

## 边界

- **只读**：未改任何文件、未在 #162/#163/#164 下评论、未推送代码。
- **未验**：64 个用例"全绿"的实际执行（只核对了测试代码存在性与数据源）；
  双节点运行时观测数据（高度/epoch/PG 检查点连续性）**只能采信自述，无法独立复现**；
  #162 的 protobuf 生成物（2564+/2150-）未逐行核对。
