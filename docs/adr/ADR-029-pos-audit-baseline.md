# ADR-029：PoS 共识现状审计基线（NexFinality 前置文档）

- **状态**：Accepted
- **日期**：2026-08-11
- **作者**：InsCode 审计（基于源码实证）
- **关联**：ADR-001（研究层冻结）、ADR-030（NexFinality 创意共识，拟定）

## 背景

在启动 NexFinality 创意共识设计前，必须先精确回答：**现有 PoS 链哪些是真闭环、哪些是缺口**。
本文档基于对 `nexus-core/nexus-core/src/main/java/org/nexus/consensus/pos/` 的逐方法审计，
纠正 README 中过时的「PoS 验签待补」表述。

## 审计证据与结论

### ✅ 已真闭环（可直接复用，不要重写）

| 组件 | 文件 | 实现深度 | 证据 |
|---|---|---|---|
| 出块提案 | `PosConsensusEngine.propose()` | 完整 | 验证人绑定→选提案者→轮次判定→构建区块→签名→广播→奖励，全程 fail-closed（151–208 行） |
| 区块验签 | `PosConsensusEngine.validate()` | 完整 | 8 步校验：coinbase 提取→验证人查询→ACTIVE 状态→质押门槛→时间窗→罚没检查→Ed25519 验签（224–292 行） |
| Ed25519 验签 | `verifyBlockSignature()` | 真实 | 真用 `Ed25519DsaSigner.verify()`，nNonce+blockNotice 重构签名，fail-closed 三条路径拒绝 |
| 质押管理 | `StakingServiceImpl` | 存在 | 质押/解押/查询（`getStake` 被 validate 调用） |
| 罚没 | `SlashingService` | 存在（134 行） | `slash(Validator, Offense)` 接口+实现 |
| 验证者注册 | `ValidatorRegistry`（196 行） | 存在 | 状态机（ACTIVE/SLASHED）、`getMinStakeAmount` |
| 出块奖励 | `PosRewardDistributor` | 存在 | `distributeBlockReward(address, fees)` |
| P2P 同步 | `SyncManager`（240 行） | 存在 | 孤儿块同步、`getStatus` 高度对齐、区块收发（gRPC） |
| 测试 | `pos/` 下 9 个测试类 | 较完整 | 含 `PosConsensusIntegrationTest` |

**核心结论**：PoS 基础层（出块+验签+质押+罚没+同步）**已是能跑的链**，不是骨架。

### ❌ 缺口（NexFinality 要补的，且不可回避）

| 缺口 | 影响 | 证据 |
|---|---|---|
| **无最终性（finality）** | 支付链致命：交易永理论上可回滚，无法承诺"不可逆结算" | 全库 grep `finaliz/2-3/supermajority/vote` 仅有 DPoS 投票历史代码，PoS 无 BFT 投票 |
| **无双投票证据惩罚链** | 验证者作恶（同一高度双签）无链上追责 | `SlashingService.Offense` 枚举存在，但无"投票证据→slash"触发器 |
| **共识→合约层断开** | 合约无法读取"当前高度是否已最终化" | `ContractContext` 无 `isFinalized()`；结算合约无法等终局 |
| **验证者集变更不走治理** | 链的去中心化演进退化为运维操作 | `ValidatorRegistry` 增删与 `governance/` 子系统无联动 |
| **签名聚合缺位** | BFT 投票每块 N 个签名，验算开销 O(N) 不可扩展 | 当前单签 Ed25519，无 BLS 聚合 |

### 🔧 需修正的文档/债务

1. README「PoS 调度器与验签待补」**已过时**：验签已闭环，调度器 `PosMiningScheduler` 存在（80 行）。
   → 建议改为「PoS 已闭环，缺最终性层」。
2. `nNonce + blockNotice` 拼接存签名是**非标准做法**（把签名拆进两个字段），需评估是否沿用或改标准结构。

## NexFinality 的起点（据此文档推导）

```
现有资产 = 出块层（PoS 真闭环）
要补的   = 最终性小工具（Finality Gadget）：
            ① BFT 投票子协议（2/3 质押权重）
            ② BLS 聚合签名（现成库 blst，不自研）
            ③ 检查点最终化标记（写入 StateRoot）
            ④ 三条连接轴（最终性→合约 / 最终性→slashing / 治理→验证者集）
```

**设计纪律**（写入规格的硬约束）：
- 密码学原语全部复用（Ed25519 留作出块签名；BLS 用 supranational/blst 现成库做投票聚合）——**零自研密码学**
- 创意空间限定在协议结构与产品语义（双层确认、确认度% 展示、结算 SLA），不在数学构造

## 风险

- **乐观执行风险**：最终性前的乐观确认交易被回滚 → 需明确产品化的"乐观通道仅用于小额、大额必须等终局"的分层语义
- **BLS 依赖风险**：Java 绑定增加构建复杂度（JNA/原生库）→ 需单独的构建 ADR

## 参考文献

- Ethereum Gasper（LMD-GHOST + Casper FFG 组合）——混合共识的成熟范式
- Narwhal/Bullshark —— 现成密码学 + 协议结构创意的获奖路线
- 本仓库 `PosConsensusEngine.java`（#L151-292）——现有闭环证据
