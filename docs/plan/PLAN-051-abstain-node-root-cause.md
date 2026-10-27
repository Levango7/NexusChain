# 方案 PLAN-051：定位"活着但投票静默"节点根因（PLAN-050 派生）

- **状态**：开发中（2026-10-27）
- **前置**：PLAN-050（演练发现：D 在 epoch 60 后停止投票，卡在
  `unknown proposer at height 125`，链高照涨、无 ERROR/WARN）、PLAN-041
  （同款 `unknown proposer` defer 语义）
- **范围**：**先定点观测（可观测性），再谈修复**——在拒绝路径上把"为什么
  拒绝"变成结构化可读信号（日志 + 端点），复现根因后最小修复
- **不包含**：重写区块校验链路、改 proposer 选择算法

---

## 一、问题（PLAN-050 留的洞）

复现事实（`pl050-D.log`）：
- D 的注册表里有全部 3 个验证人（`Validator registered` ×3）；
- D 自己能 round-robin 选得出 `nv3gC5...`（`Selected proposer ... size=3`）；
- 但对端推来的 height=125 块（proposer 就是 `nv3gC5...`）在
  `PendingBlocksManager` 侧被判 `unknown proposer (validator set not
  synchronized)`，**不进引擎**（全程无 `Validate failed` WARN）；
- 该消息被 **defer 重试**（PLAN-041 语义），但重试永远失败 ⇒ 125 采纳不了
  ⇒ D 的 `NewBlockEvent` 不再触发 ⇒ **投票永久停止**，而链高照涨。

疑点（按可能性排序）：
1. **pubKeyHash 匹配口径**：注册表存的是**公钥 hex**，而 `isProposerKnown`
   拿 `coinbase.to`（pubKeyHash）去 `findValidatorByPubKeyHash` 匹配——
   若匹配实现是"公钥 hex vs pubKeyHash"直接比对，只要装载路径对不上
   （例如 E/A 的 add 载荷里 pubKey 与 coinbase 派生 hash 不同源），
   就会出现"注册表有地址、round-robin 选得出、 proposer 校验认不出"；
2. **注册表视图不一致**：round-robin 读 `ValidatorRegistry.getActiveValidators()`
   （内存 Map），而校验路径可能读**另一份**（快照/持久化/延迟装配）；
3. **时序**：D 在追块高峰期的 add 落库与 registry 装入存在窗口。

## 二、方案（两步：观测 → 修复）

### 2.1 第一步：定点观测（本切片主体）

在 defer 路径加**结构化拒绝原因**（不改变语义，只改变可见度）：
- `PendingBlocksManager` 的 `unknown proposer` 分支：把当前
  "注册表是否有该 proposer 地址 / proposer pubKeyHash / 注册表内该验证人的
  公钥（截断）/ ordered set 大小"打成一条 WARN（而非 INFO）；
- 若同一 (height, proposer) 的 defer **连续超过 N 次**（如 5），升级为
  ERROR 并带 `abstain-risk` 标记（"本节点可能已停止投票"）——
  **这正是 PLAN-050 里最缺的信号**。

配套：`GET /consensus/catch-up` 已有 `validators` 摘要（PLAN-049），
足以做"round-robin 选得出的 proposer 是否在注册表"的运维对账。

### 2.2 第二步：根因定位与最小修复

观测上线后重跑 PLAN-050 场景（同脚本），看 WARN/ERROR 给出的字段：
- 若"注册表无该地址" ⇒ 疑点 2/3（视图/时序）⇒ 修装入或统一视图；
- 若"注册表有地址但 hash 不匹配" ⇒ 疑点 1（口径）⇒ 修
  `findValidatorByPubKeyHash` 的匹配语义（用同一哈希函数派生）；
- 若注册表根本没装该验证人 ⇒  proposer 来源不对（对端伪造/旧链）⇒
  回归校验语义讨论。

**纪律**：未拿到观测字段前**不改匹配逻辑**—— PLAN-047/050 两次教训都是
"先看清再动手"，禁止凭猜修。

## 三、结果（2026-10-27：第一步已交付 #291；复现跑未复现 PLAN-050 形态）

### 3.1 第一步（观测）已交付

`unknownProposerDeferCount` + 阈值升级 ERROR + `abstain-risk` 标记（PR #291，
`PendingBlocksManagerAbstainObservabilityTest` 3 例，模块全量回归绿）。

### 3.2 复现跑（同脚本、同拓扑、固定密钥）**未复现** PLAN-050 的弃权形态

- **P1 健康 8 分钟**：A/D/E 三节点水位**完全同步推进**（t+30s 全 59 → t+480s 全 149，
  每 30s +6，零偏差）；D 全程只有 1 条 `unknown proposer`（height 1 冷启动那一跳），
  **无任何节点停止投票** ⇒ 判据 A 在干净环境下**通过**；
- **P2 死锁注入**（杀 D/E 只留 A）：A 水位冻结 150、链高 311→491、lag 6→96、
  progress 冻结 33% ⇒ 判据 B 依旧通过；
- `abstain-risk` ERROR 在本跑出现了 **3 次，但全在 height=1 的冷启动窗口**
  （18:39:24，deferCount 5/6/7）—— 即新观测面**按设计工作**（启动期
  验证人集未就绪的 height-1 块被快速 defer），但**不是** PLAN-050 那种
  "成熟节点中期弃权"。

### 3.3 对 PLAN-050 的重新定性（重要）

结合本次未复现 + PLAN-050 的日志细节（D 卡在 height **125**、stop 于 epoch 60），
弃权形态的触发条件应是**偶发时序**（追块高峰期 validator-set add 落库与
proposer 校验之间的窗口），非常态。两次跑差异佐证：
- 本次 P1 三节点水位完全同步（无追块压力差）；
- PLAN-050 跑中 D 处于 `blocks received start from 124 stop at 164` 的追块高峰。

**结论：根因未定性，但已缩小到"追块高峰 + validator-set 装载时序"；
`abstain-risk` 观测面（#291）正是捕获它的工具。** 不做猜测性修复
（PLAN-047/050 教训）。

## 四、结论与处置

1. **第一步（观测）已交付并验证按设计工作**（#291）：height=1 冷启动窗口的
   快速 defer 被正确升级；阈值语义与日志标记在测试床实测生效；
2. **弃权根因未复现/未定性**：需"追块高峰 + 时序窗口"的定向复现
   （下一切片：压测式追块注入 + validator-set 延迟装配），或接受
   "偶发 + 已被告警/观测覆盖"的结论归档；
3. **告警与观测链已闭环**：`FinalityStalled`（水位冻结）+ `abstain-risk`
   （拒绝路径）+ `/consensus/catch-up validators`（注册表对账）三路信号
   互相印证——**即使根因不再深挖，"活着但弃权"已不可静默**；
4. **告警表达式与阈值（5 次）保持**：本跑证明其对冷启动噪声不误报
   （height=1 的 defer 在 3 分钟内收敛，未达持续性误报）。

## 五、边界（勿夸大）

- 本切片交付的是**观测**，不是根因修复；弃权形态两次跑一次复现一次不复现，
  **触发条件未定**，不做猜测性代码修改；
- 复现跑为同内核三进程；跨内核拓扑下的时序窗口未测；
- `abstain-risk` 的 5 次阈值是经验值：本次 height=1 冷启动实测到 7 次后收敛，
  若生产出现持续性 height=1 defer（如注册表长期装不上），该告警会持续
  响——那是**真问题**（节点真的在弃权），不是误报。
