# ADR-030：NexFinality 创意共识 —— 支付结算链的确定性最终性层

- **状态**：Proposed（已基于 ADR-029 审计基线推导）
- **日期**：2026-08-11
- **依赖**：ADR-029（PoS 现状审计）、ADR-001（研究层冻结）
- **设计纪律**：零自研密码学原语；创意集中于协议结构与产品语义

---

## 1. 决策

在现有 PoS 出块层之上新增 **NexFinality 最终性层（Finality Gadget）**：
验证者对检查点（checkpoint）区块做 BFT 投票，达到 2/3 质押权重阈值即"最终化"，
最终化的区块及其全部祖先永不回滚。**为支付结算场景优化确定性最终性**。

## 2. 为什么不改用现成共识（如 Tendermint）

| 方案 | 适用性 | 决策 |
|---|---|---|
| 直接集成 Tendermint | 需重写整个链为 Go/Cosmos 架构，丢弃现有 Java 生态与全部模块 | ❌ 破坏性过大 |
| 自研全新共识协议 | 需密码学家 + 数年同行评审 | ❌ 违反设计纪律 |
| **保留现有 PoS 出块，叠加最终性层** | 利用已审计闭环的出块/验签能力，创意只在最终化机制 | ✅ **本方案** |

类比：以太坊不是放弃 PoW/PoS 而是叠加 Casper FFG。这是成熟范式。

## 3. 状态机设计

### 3.1 双层确认模型（产品创意核心）

```
交易生命周期：
PENDING → OPTIMISTIC（1 块确认，乐观）→ FINALIZING（投票累积中）→ FINALIZED（永不可逆）
                │                              │
                └─ 小额支付可用 ──┘            └─ 大额结算必须等到此 ──┘
```

| 状态 | 含义 | 适用场景 |
|---|---|---|
| OPTIMISTIC | 进入最优链，未被最终化 | 小额、低价值、可容忍重组的支付（如打赏、微支付） |
| FINALIZED | 2/3 质押权重投票通过 | 大额结算、法币出金、跨链桥锁定 |

**产品语义**：确认度 = 已投票权重 / 总质押权重（0%→100% 渐进），前端可实时展示进度条。
这是对公链"确认数"范式的支付场景优化——用户不关心"几个块"，关心"这笔钱稳没稳"。

### 3.2 BFT 投票流程

```
每个 epoch = 32 个块（可配置治理参数）
                   │
     ┌─────────────┼─────────────┐
     │             │             │
  出块提案      投票轮         最终化判定
  (现有 PoS)   (新增 BFT)     (新增逻辑)
     │             │             │
  Proposer    每个 ACTIVE      ≥2/3 质押权重同意？
  出块        验证者对当前    ├─ 是：检查点及祖先 FINALIZED
  (签名)      epoch 检查点    │      奖励投票者
              投票 (BLS 签名)  └─ 否：进入下一 epoch，
                                   未决检查点过期可重投
```

## 4. 密码学选型（全部现成，不自研）

| 用途 | 算法/库 | 来源 | 为何安全 |
|---|---|---|---|
| 出块签名（留用） | Ed25519 | 已有 `Ed25519DsaSigner` | 已审计、已在使用 |
| 投票签名 | BLS12-381 | supranational/blst（Java 绑定） | 行业标准，可聚合 N 个签名为 1 个常数大小 |
| 聚合验证 | BLS 聚合 | blst 提供 `aggregate_verify` | 2/3 多数派验算从 O(N) 降到 O(1) |
| 随机数（轮换） | 链上 VRF（nexus-oracle 已有） | 现有模块 | 无需新增 |

**BLS 依赖说明**：新增构建依赖 `supranational/blst` Java 绑定（JNA），
需单独的构建配置 ADR 记录，部署时打包原生库（Linux/Windows/macOS）。

## 5. 核心数据结构

```java
// 投票
record Vote(
    long epoch,              // 所属 epoch
    Bytes32 checkpointHash,  // 最终化目标块哈希
    Address validator,       // 投票者
    BLSSignature signature   // BLS 签名
) {}

// 最终化记录（写入 StateDB）
record FinalityRecord(
    Bytes32 checkpointHash,
    long epoch,
    BigDecimal votedWeight,   // 已投票质押权重
    BigDecimal totalWeight,   // 总质押权重（用于计算确认度%）
    Instant finalizedAt,
    boolean isFinalized
) {}

// 双签证据（用于 slashing）
record EquivocationEvidence(
    Vote voteA, Vote voteB,   // 同一验证者对同一 epoch 两个不同检查点的投票
    Address offender
) {}
```

## 6. 三条连接轴（线→面）

```
        ┌─────────────────┐
        │   治理 / 升级层   │
        └────────▲────────┘
                 │ 治理→验证者集轴（新）
   ┌─────────────┼─────────────┐
   │             │             │
   ▼             ▼             ▼
┌────────┐  ┌──────────┐  ┌──────────┐
│ PoS    │  │NexFinality│  │ 合约层   │
│ 出块层  │──│  最终性层 │──│WASM/EVM │
└────────┘  └─────┬────┘  └──────────┘
                  │
        ┌─────────┴─────────┐
        │   最终性→slashing   │
        │   最终性→合约       │
        └───────────────────┘
```

| 轴 | 连接 | 改动点 |
|---|---|---|
| 最终性→合约 | `ContractContext.isFinalized(height)` → 结算合约等终局 | `ContractContext` 加方法 + 结算合约模板 |
| 最终性→slashing | 检测双签证据 → 自动 slash | `SlashingService` 加 `submitEvidence()` |
| 治理→验证者集 | 增删验证者走链上提案 | `ValidatorRegistry` 调用纳入治理 dispatcher |

## 7. 安全论证草纲（非形式化）

### 7.1 安全性（safety）
**定理**：若总质押权重的 <1/3 为拜占庭，则最终化 checkpoint 永不冲突。

**论证**：两个冲突 checkpoint 最终化需各获 ≥2/3 权重投票，
交集 ≥ 2/3+2/3-1 = 1/3 权重对两者都投了票——即同一验证者双签。
双签可通过 `EquivocationEvidence` 被 slash，理性验证者不会同时签名 —
故在 <1/3 拜占庭假设下，冲突最终化不可能发生。

### 7.2 活性（liveness）
**论证**：若某 epoch 投票未达 2/3，检查点过期但不回滚已最终化的祖先；
下一个 epoch 重新对同高度或更高高度投票。只要 >2/3 权重在线且诚实，
最终化将在有限时间内完成（不出块也会跳过失效 epoch，不阻塞链）。

## 8. 测试策略

| 层 | 内容 | 类型 |
|---|---|---|
| 单元 | Vote 聚合验证、FinalityRecord 状态迁移、双签检测 | JUnit |
| 对抗 | 模拟 <1/3 拜占庭节点的双签/离线/分叉投票 | 集成测试 |
| 压力 | 100+ 验证者投票的吞吐/延迟 | 性能测试 |
| 回归 | 现有 PoS 出块/验签在加最终性层后无回归 | 复用现有测试套件 |

## 9. 实施里程碑（与协议无关的提前工作）

| 里程碑 | 内容 | 周期 | 依赖 |
|---|---|---|---|
| M0 | BLS blst 依赖引入 + Java 构建配置 | 1-2 周 | 无 |
| M1 | `Vote`/`FinalityRecord`/`EquivocationEvidence` 核心类型 | 1 周 | M0 |
| M2 | BFT 投票子协议（无聚合，先跑通单签收集） | 2-3 周 | M1 |
| M3 | BLS 聚合签名集成 + 阈值验算优化 | 2-3 周 | M2 |
| M4 | 双签证据 → slashing 联动 | 1-2 周 | M2 |
| M5 | `ContractContext.isFinalized()` + 结算合约改造 | 1 周 | M2 |
| M6 | TLA+ 形式化规格（核心不变式验证） | 持续 | M1 后启动 |

## 10. 风险与未决问题

| 风险 | 等级 | 缓解 |
|---|---|---|
| BLS blst Java 绑定的原生库打包复杂度 | 中 | M0 阶段先验证多平台可行性 |
| 治理子系统与验证者集联动的攻击面 | 中 | 治理执行加 timelock（已有），新增守护者 veto |
| Epoch 边界处的重组窗口 | 中 | 乐观通道仅用于小额，产品层明确提示 |

---

## 11. 与现有模块的关系（复用清单）

- `PosConsensusEngine` —— 复用：`propose()`/`validate()` 保持，最终性层在其之上
- `SyncManager` —— 扩展：新增同步类型 `GetFinalityProofs`（获取最终化证明）
- `SlashingService` —— 扩展：新增 `submitEvidence()` 端点
- `ContractContext` —— 扩展：新增 `isFinalized()` 查询
- `nexus-oracle` —— 联动：VRF 用于验证者轮换随机数
- `nexus-gateway` —— 联动：RPC 返回 `finalityStatus: OPTIMISTIC|FINALIZING|FINALIZED`
