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

## 三、测试

- `PendingBlocksManager` defer 路径单测：连续 N 次同一 height defer ⇒
  升级日志级别的可观测行为（用日志捕获或注入的计数断言）；
- 现有 `PendingBlocksManagerStorageRetryTest` / `PendingBlocksManagerOutboxRestoreCapTest`
  与 `ConsensusRuleTest`（若有）不回归。

## 四、边界（勿夸大）

- 本切片**可能只交付观测而修不了根因**（若根因在协议层而需要共识语义变更，
  另立治理议程）——如实登记；
- 测试床复现依赖三节点拓扑（现成资产）；
- 不改 proposer 选择、不改 defer 语义（只加可见度）。
