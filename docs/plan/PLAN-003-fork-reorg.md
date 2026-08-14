# 方案 PLAN-003：分叉重组（Fork Reorg）

- **状态**：Approved（2026-08-14 审核通过）
- **日期**：2026-08-14
- **前置**：PLAN-001（验证人同步/确定性出块）、PLAN-002（出块抑制/区块传播写入）已完成
- **目标**：多节点共享单链时，节点收到更长分叉链后**切换主链**（放弃本地短链、采纳对端长链），实现高度收敛

## 审核决策（2026-08-14）

| 决策点 | 结论 |
|---|---|
| 方案选型 | **A. 受控切换 + 护栏**（分叉检测 + 回滚分叉段 + 写入长链 + 最终化护栏） |
| 切换阈值 | **严格更高 + 可回溯**（对端高度严格大于本地，且分叉点可回溯到本地已确认链） |
| 回滚范围 | **全回滚 + 重放**（分叉段 deleteBlock 到分叉点，状态按长链重放） |
| 最终化护栏 | **完全禁止**（已 2/3 权重最终化的链段不可切换） |
| 验证方式 | 单测 → 集成 → 真机（2 节点） |

---

## 一、问题实证（2 节点真机观察）

```
A 节点：出块至高度 38（自己的链，hash 链 X）
B 节点：出块至高度 18（自己的链，hash 链 Y），收到 A 的 blocks 1-38

B 写入 A 链时的错误：
  PendingBlocksManager: validate the block fail error = cannot find parent [hash of A-block-18]

根因：B 先出了自己的短链 Y（1-18），A 的长链 X（1-38）的块 19
父块是 A 的块 18（hash 与 B 的块 18 不同），在 B 的 stateDB 中找不到
→ 分叉未被识别，B 无法采纳 A 的长链。
```

**当前架构事实**（代码已确认）：
- `PendingBlocksManager.addPendingBlocks`：`popLongestChain → validateBlock → writeBlock`
- `ConsensusRule.validateBlock`：`stateDB.getBlock(block.hashPrevBlock)`，父块缺失即 `failed to find parent block`
- `StateDB`：有 `deleteBlock`（`blocksCache.deleteBlock`），但**无完整的分叉切换逻辑**
- `stateDB.getLastConfirmed()`：写链基于"已确认高度"之后追加，不处理"同高度不同 hash"分叉
- 出块抑制已实现（PLAN-002）：B 已知 A 更高后暂停本地出块，但**已出的短链 Y 无法回滚**

---

## 二、目标

| 验收标准 | 达成条件 |
|---|---|
| 高度收敛 | B 收到 A 的长链 X（更高）后，**切换主链到 X**，本地最佳高度 = A 的高度 |
| 无重复出块 | 切换期间 B 抑制本地出块（已由 PLAN-002 保证） |
| 状态一致 | 切换后账户/交易状态按 X 链重新执行，不与 Y 链残留冲突 |
| 单调回退安全 | 已**最终化**（NexFinality 2/3 权重）的检查点**永不回滚** |

---

## 三、设计方案（候选）

### 方案 A：最长链切换 + 分叉检测（推荐）

```
分叉检测（新增，PendingBlocksManager 写链前）：
  收到的块 b 父块缺失（stateDB.getBlock(b.hashPrevBlock) == null）时：
    1. 若 b.nHeight > 本地 best 高度 → 可能是更长分叉链
    2. 收集该分叉链（从 b 沿 hashPrevBlock 回溯直到本地已确认链）
    3. 分叉链长度 > 本地链长度 → 执行切换

链切换（新增，StateDB 或独立 ReorgManager）：
    1. 冻结出块（scheduler 已抑制）
    2. 回滚本地分叉段（从 best 沿 hashPrevBlock 删除直到分叉点，deleteBlock）
    3. 写入分叉链（从分叉点之后逐个 writeBlock + 状态重放）
    4. 更新 best/latestConfirmed

安全护栏：
    - 已最终化检查点（FinalityGadget.isFinalized）所在链不可切换
    - 切换点必须在分叉高度以下
```

### 方案 B：按"父块缺失"直接采纳（最小实现）

```
PendingBlocksManager：父块缺失时若 b.nHeight 显著高于本地 best
（> 本地高度），直接清空本地缓存重放对端链（粗暴但简单）。
风险：账户状态回滚不精确、无最终化保护。
```

### 方案 C：共享存储单源（架构级，重）

```
多节点共用同一链存储（PG 单源），从根上消除"各自链"。
代价：需重构 StateDB/LevelDB 缓存语义，侵入最大。
```

**推荐**：方案 A（分叉检测 + 受控切换 + 最终化护栏），方案 C 作为远期。

---

## 四、核心改动点（方案 A）

| 文件 | 改动 |
|---|---|
| `PendingBlocksManager` | 父块缺失时触发分叉检测而非直接报错 |
| `StateDB` | 新增 `rollbackTo(height)`（回滚分叉段）+ `switchToChain(blocks)` |
| 新增 `ReorgManager` | 分叉收集/长度比较/切换编排（含最终化护栏） |
| `FinalityGadget` | 暴露 `isFinalizedOnChain(checkpointHash)` 供护栏查询 |
| `ConsensusRule` | 父块缺失由"直接失败"改为"可能分叉，交 ReorgManager" |

## 五、实施清单

| # | 任务 | 预估 |
|---|---|---|
| 1 | `StateDB.rollbackTo(height)`：回滚本地分叉段（deleteBlock 链式） | 1-2 天 |
| 2 | `ReorgManager`：分叉链收集 + 长度比较 + 切换编排 | 2 天 |
| 3 | 最终化护栏：`FinalityGadget.isFinalizedOnChain` + 切换前检查 | 0.5 天 |
| 4 | `PendingBlocksManager` 接线（父块缺失 → ReorgManager） | 0.5 天 |
| 5 | 单元测试：分叉检测/切换/回滚/护栏 | 1 天 |
| 6 | 2 节点真机验证：B 收 A 长链后收敛 | 1 天 |

**预估总工期**：6-7 天（含验证）

## 六、风险与缓解

| 风险 | 缓解 |
|---|---|
| 回滚时账户状态不一致 | 切换前冻结出块 + 逐块重放 + 集成测试覆盖状态校验 |
| 分叉检测误判（孤儿块 vs 真分叉） | 以"更高高度 + 父链可回溯到本地确认链"双重条件 |
| 已最终化检查点被回滚 | 最终化护栏硬性禁止（NexFinality 语义：不可逆） |
| 与 PLAN-002 出块抑制交互 | 切换完成后清除"落后"标记，恢复出块 |

## 七、测试计划

| 层 | 内容 |
|---|---|
| 单元 | `ReorgManagerTest`：短链分叉检测/长链切换/等长不切换/孤儿不误判 |
| 单元 | `StateDBRollbackTest`：回滚高度边界/状态清理 |
| 集成 | 双链构造 → B 切换 → 高度收敛 + 状态一致 |
| 真机 | 2 节点 A/B：B 先出短链，A 更长 → B 收敛至 A 高度 |

## 八、待审核决策点

1. **方案选型**：A（受控切换+护栏，推荐）vs B（粗暴重放）vs C（共享存储）？
2. **分叉阈值**：对端链高多少才切换（建议：严格更高 + 已确认分叉点）？
3. **回滚范围**：本地分叉段全部回滚 vs 保留缓存？
4. **最终化护栏**：已最终化链段是否完全禁止切换（强烈建议是）？
5. **验收方式**：单测+集成先行，真机 2 节点后验？

请审核并给出决策，通过后按第五节清单执行。

---

## 真机验证结果（2026-08-14）与遗留

**已验证**：B 收到 A 完整链（blocks 1-30）、ReorgManager 触发（Fork skip orphan）、出块抑制生效。

**遗留（PLAN-005）**：B 启动未从共享 PG 加载 A 已写的链 → 分叉点在各自 genesis 后
（父链不同，非真分叉），无法重组。ReorgManager 逻辑正确（单测 4 用例），
真机收敛需先解决"共享链启动加载"（节点启动继承 PG 已有链）。
