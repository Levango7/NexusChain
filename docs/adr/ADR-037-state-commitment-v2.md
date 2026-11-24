# ADR-037：版本化账户状态承诺 V2（A1 / R2 独立项目）

- **状态**：Accepted，实施中
- **日期**：2026-11-24
- **Owner**：ZCode（由用户指派 A lane；R2 仍作为独立项目交付）
- **范围**：`Block` 的账户状态根、`MerkleRule` 验块、PoW/PoS 出块和历史重放；不包含 0x10–0x1a 类型放行、支付执行或 `Account.getId()` 改造

## 1. 问题

旧 `Block.calculateMerkleState` 只对 `Account.getId()` 构造 Merkle 叶子，而 ID 仅为 `pubkeyHash + uint32(blockHeight)`：

1. 余额、nonce、孵化成本、抵押和投票变化不会改变状态根，无法检测同 ID 的余额篡改。
2. 账户列表来自 `HashMap`，旧算法不排序；同一账户集合以不同顺序输入时根不同。
3. 直接修改 `Account.getId()` 或旧算法会改变全部历史区块的重放语义，不能作为可兼容升级方案。

## 2. 决策

引入独立的 `StateCommitment`，由区块高度选择算法：

- `height < activationHeight`：逐字节调用旧 `Block.calculateMerkleState`，历史行为不变。
- `height >= activationHeight`：使用本 ADR 的 V2 编码、排序和 Merkle 规则。
- 配置项为 `nexus.core.state-commitment-v2.activation-height`，默认 `Long.MAX_VALUE`（不激活）。生产只有在协调升级方案明确后才能设置有限高度。
- 本高度只控制账户状态承诺，**不是**支付扩展的 `paymentExtensionActivationHeight`；A3 必须另行定义支付扩展门禁，不得共享一个隐式开关。
- PoW、PoS、同步验块和 `StateReplayManager` 全部通过 `MerkleRule` 使用同一个 `StateCommitment`，禁止各自实现不同版本。

## 3. V2 账户叶子编码

每个账户编码为下列字段的连续字节，之后转为小写十六进制 ASCII 作为现有 `MerkleTree` 的叶子内容：

| 顺序 | 字段 | 字节数 | 编码 |
|---:|---|---:|---|
| 1 | 版本标记 | 1 | 固定 `0x02` |
| 2 | pubkeyHash 长度 | 2 | 无符号大端，单位字节 |
| 3 | pubkeyHash | 可变 | 原始字节 |
| 4 | blockHeight | 4 | 无符号大端 `uint32` |
| 5 | nonce | 8 | 大端 64 位位模式 |
| 6 | balance | 8 | 大端 64 位位模式 |
| 7 | incubatecost | 8 | 大端 64 位位模式 |
| 8 | mortgage | 8 | 大端 64 位位模式 |
| 9 | vote | 8 | 大端 64 位位模式 |

64 位字段使用既有 `BigEndian.encodeUint64`，只承诺其精确位模式，不在本项目中改变负数或余额校验语义。空列表根为 `SHA3-256([0x02])`，与旧 32 字节零根区分。

以下输入必须 fail closed，禁止用零根掩盖错误：

- null 账户或 null pubkeyHash；
- pubkeyHash 长度超过 `uint16`；
- blockHeight 超出 `uint32`；
- 同一状态集合中重复 pubkeyHash。

## 4. 排序与 Merkle 规则

1. 账户按 pubkeyHash 的**无符号字节字典序**排序；相同 pubkeyHash 直接拒绝，不按插入顺序双计。
2. 叶子沿用现有 `TreeNode` / `MerkleTree` 算法。由于输入为小写十六进制 ASCII，不依赖平台默认字符集或账户对象哈希。
3. V2 不改变交易 Merkle 根和孵化 Merkle 根。

## 5. 兼容、激活与回滚

- 激活前升级节点必须继续接受历史根；测试以旧 `Block.calculateMerkleState` 的逐字节结果作为兼容向量。
- 激活后所有出块/验块节点必须使用同一 V2 实现。未升级节点无法验证新根，因此有限高度激活仍属于协调硬分叉。
- 默认不激活意味着代码合入本身不改变链行为。有限高度只能在节点升级、测试向量和回滚预案完成后下发。
- 已按 V2 产出的区块不能通过回退软件变成旧根；回滚只能发生在激活高度之前。

## 6. A1 验收

- 激活前旧根逐字节一致；高度边界准确。
- 激活后余额、nonce、incubatecost、mortgage、vote 任一变化均改变根。
- 同 ID 余额或 nonce 篡改导致 `MerkleRule.verifyStateRoots` 拒绝。
- 同一账户集合任意乱序得到相同根；重复账户拒绝。
- 编码包含版本标记且字段顺序有固定测试向量；空根确定且非旧零根。
- PoW、PoS 和历史重放使用同一策略；现有测试在默认不激活配置下全部保持通过。
