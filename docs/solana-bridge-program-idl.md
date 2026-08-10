# NexusChain Solana Bridge Program IDL 设计

## 第1章 概述

### 1.1 文档目的

本文档定义 NexusChain 跨链桥在 Solana 链上的 Bridge Program 接口定义语言（IDL），
覆盖指令（Instructions）、账户结构（Accounts）、事件（Events）三个维度，
作为 `nexus-bridge` 模块 `SolanaBridgeHandler` 与链上 Program 交互的契约。

### 1.2 设计目标

- **资产安全**：锁定资产由 Bridge Program 托管，仅验证者多签可释放
- **跨链兼容**：支持 Solana ↔ EVM（Ethereum / BSC / Polygon）双向跨链
- **SPL Token 标准**：基于 Solana 官方 Token Program，不引入 Token-2022 复杂特性
- **可审计**：所有操作 emit 链上事件，便于 relayer 与监控追踪
- **幂等性**：每笔 lock/mint/burn/unlock 携带唯一 tx_id，防止重放

### 1.3 部署信息

| 项目 | 值 |
|------|-----|
| Program Name | `nexus_bridge` |
| Program ID（devnet） | `BriDGe11111111111111111111111111111111111111`（占位，部署后替换） |
| Program ID（mainnet） | 待部署 |
| SPL Token Program | `TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN`（官方 Token Program） |
| NEX Mint（devnet） | `NEXMint111111111111111111111111111111111111`（占位） |
| 验证者多签阈值 | 2-of-3（可由治理调整） |

## 第2章 账户结构

### 2.1 BridgeState（桥全局状态）

桥的全局配置与统计信息，单例账户（PDA with seed `["bridge-state"]`）。

```rust
#[account]
pub struct BridgeState {
    /// 桥管理员多签地址（Multisig PDA）
    pub admin_multisig: Pubkey,
    /// NEX 代币 mint
    pub nexus_mint: Pubkey,
    /// 当前桥暂停状态（0=运行，1=暂停）
    pub paused: u8,
    /// 单笔跨链上限（最小单位）
    pub max_amount_per_tx: u64,
    /// 24 小时累计流出上限
    pub daily_limit: u64,
    /// 大额阈值（超过此值需时间锁）
    pub large_amount_threshold: u64,
    /// 时间锁周期（秒）
    pub timelock_period: u64,
    /// 验证者公钥列表（最多 32 个）
    pub validators: [Pubkey; 32],
    /// 实际验证者数量
    pub validator_count: u8,
    /// 签名阈值（N-of-M 中的 N）
    pub signature_threshold: u8,
    /// 累计锁定总量
    pub total_locked: u64,
    /// 累计铸造总量
    pub total_minted: u64,
    /// 最后更新 slot
    pub last_updated_slot: u64,
    /// 保留字段
    pub reserved: [u8; 64],
}
```

**表：BridgeState 字段说明表**

| 字段 | 类型 | 说明 |
|------|------|------|
| admin_multisig | Pubkey | 桥管理员多签地址 |
| nexus_mint | Pubkey | NEX 代币 mint |
| paused | u8 | 桥暂停状态 |
| max_amount_per_tx | u64 | 单笔跨链上限 |
| daily_limit | u64 | 24 小时累计流出上限 |
| large_amount_threshold | u64 | 大额阈值 |
| timelock_period | u64 | 时间锁周期 |
| validators | [Pubkey; 32] | 验证者公钥列表 |
| validator_count | u8 | 实际验证者数量 |
| signature_threshold | u8 | 签名阈值 |
| total_locked | u64 | 累计锁定总量 |
| total_minted | u64 | 累计铸造总量 |
| last_updated_slot | u64 | 最后更新 slot |
| reserved | [u8; 64] | 保留字段 |

### 2.2 LockRecord（锁定记录）

每次 lock 操作创建一条 LockRecord（PDA with seed `["lock", lock_tx_id]`）。

```rust
#[account]
pub struct LockRecord {
    /// 锁定交易 ID（32 字节，全局唯一）
    pub lock_tx_id: [u8; 32],
    /// 用户源链地址（Solana pubkey）
    pub user_pubkey: Pubkey,
    /// 目标链地址（32 字节，EVM 地址左补零）
    pub target_address: [u8; 32],
    /// 目标链 ID（如 "ethereum"、"bsc"）
    pub target_chain_id: [u8; 16],
    /// 锁定金额（最小单位）
    pub amount: u64,
    /// 锁定时的 slot
    pub locked_slot: u64,
    /// 状态：0=LOCKED, 1=MINTED, 2=FAILED
    pub status: u8,
    /// 关联的 mint 交易哈希（铸造完成后填入）
    pub mint_tx_hash: [u8; 64],
    /// 保留字段
    pub reserved: [u8; 32],
}
```

### 2.3 MintRecord（铸造记录）

每次 mint 操作创建一条 MintRecord（PDA with seed `["mint", lock_tx_id]`）。

```rust
#[account]
pub struct MintRecord {
    /// 关联的锁定交易 ID
    pub lock_tx_id: [u8; 32],
    /// 铸造接收者地址（Solana pubkey）
    pub recipient: Pubkey,
    /// 铸造金额
    pub amount: u64,
    /// 铸造时的 slot
    pub minted_slot: u64,
    /// 参与签名的验证者位图
    pub signer_bitmap: [u8; 4],
    /// 保留字段
    pub reserved: [u8; 32],
}
```

### 2.4 BurnRecord / UnlockRecord

结构与 LockRecord / MintRecord 对称，分别用于反向跨链的销毁与解锁。

```rust
#[account]
pub struct BurnRecord {
    pub burn_tx_id: [u8; 32],
    pub user_pubkey: Pubkey,
    pub target_address: [u8; 32],
    pub target_chain_id: [u8; 16],
    pub amount: u64,
    pub burned_slot: u64,
    pub status: u8,           // 0=BURNED, 1=UNLOCKED, 2=FAILED
    pub unlock_tx_hash: [u8; 64],
    pub reserved: [u8; 32],
}

#[account]
pub struct UnlockRecord {
    pub burn_tx_id: [u8; 32],
    pub recipient: Pubkey,
    pub amount: u64,
    pub unlocked_slot: u64,
    pub signer_bitmap: [u8; 4],
    pub reserved: [u8; 32],
}
```

## 第3章 指令

### 3.1 指令枚举

```rust
#[derive(AnchorSerialize, AnchorDeserialize)]
pub enum BridgeInstruction {
    /// 初始化桥（仅一次）
    Initialize { admin_multisig: Pubkey, nexus_mint: Pubkey },
    /// 锁定源链资产
    Lock { target_address: [u8; 32], target_chain_id: [u8; 16], amount: u64 },
    /// 在目标链铸造包装资产
    Mint { lock_tx_id: [u8; 32], user_pubkey: Pubkey, amount: u64, target_address: [u8; 32] },
    /// 销毁包装资产
    Burn { target_address: [u8; 32], target_chain_id: [u8; 16], amount: u64 },
    /// 释放锁定资产
    Unlock { burn_tx_id: [u8; 32], user_pubkey: Pubkey, amount: u64, target_address: [u8; 32] },
    /// 暂停/恢复桥
    SetPaused { paused: bool },
    /// 更新验证者集合
    UpdateValidators { validators: Vec<Pubkey>, threshold: u8 },
}
```

**表：指令标识符表**

| 指令 | opcode（u8） | 调用方 | 说明 |
|------|------|--------|------|
| Initialize | 0x00 | admin | 初始化桥全局状态 |
| Lock | 0x01 | user | 用户锁定 NEX 到桥托管 |
| Mint | 0x02 | validator | 验证者在目标链铸造 NEX |
| Burn | 0x03 | user | 用户销毁包装 NEX |
| Unlock | 0x04 | validator | 验证者在原链释放锁定 NEX |
| SetPaused | 0x05 | admin | 紧急暂停 |
| UpdateValidators | 0x06 | admin | 更新验证者集合 |

### 3.2 Lock 指令

**图：Lock 指令流程图**

```
用户 ──► Lock(user, target, amount, target_chain)
              │
              ├─► 校验 BridgeState.paused == 0
              ├─► 校验 amount <= max_amount_per_tx
              ├─► 校验 SPL Token transfer(user → bridge_vault, amount)
              ├─► 创建 LockRecord（PDA）
              ├─► BridgeState.total_locked += amount
              └─► emit LockEvent(lock_tx_id, user, amount, target, target_chain)
```

**所需账户**：

```rust
#[derive(Accounts)]
pub struct Lock<'info> {
    #[account(mut, seeds = [b"bridge-state"], bump)]
    pub bridge_state: Account<'info, BridgeState>,

    #[account(mut)]
    pub user: Signer<'info>,

    #[account(mut, constraint = user_token_account.owner == user.key())]
    pub user_token_account: Account<'info, TokenAccount>,

    #[account(mut, seeds = [b"vault", bridge_state.nexus_mint.as_ref()], bump)]
    pub bridge_vault: Account<'info, TokenAccount>,

    #[account(
        init,
        payer = user,
        space = 8 + LockRecord::LEN,
        seeds = [b"lock", lock_tx_id.as_ref()],
        bump
    )]
    pub lock_record: Account<'info, LockRecord>,

    pub nexus_mint: Account<'info, Mint>,

    #[account(address = token::ID)]
    pub token_program: Program<'info, Token>,

    #[account(address = system_program::ID)]
    pub system_program: Program<'info, System>,

    pub clock: Sysvar<'info, Clock>,
}
```

### 3.3 Mint 指令

**图：Mint 指令流程图**

```
验证者多签 ──► Mint(lock_tx_id, user, amount, target)
                  │
                  ├─► 校验 LockRecord.status == LOCKED
                  ├─► 校验签名者 >= signature_threshold
                  ├─► 校验签名者均在 validators 列表中
                  ├─► 校验未重复签名（signer_bitmap）
                  ├─► SPL Token mint_to(recipient, amount)
                  ├─► LockRecord.status = MINTED
                  ├─► BridgeState.total_minted += amount
                  └─► emit MintEvent(lock_tx_id, recipient, amount)
```

### 3.4 Burn / Unlock 指令

Burn 与 Lock 对称（用户销毁包装资产），Unlock 与 Mint 对称（验证者多签释放锁定资产）。
指令签名与所需账户结构参照 §3.2 / §3.3，此处省略。

## 第4章 事件

### 4.1 事件定义

```rust
#[event]
pub struct LockEvent {
    pub lock_tx_id: [u8; 32],
    pub user: Pubkey,
    pub amount: u64,
    pub target_address: [u8; 32],
    pub target_chain_id: String,
    pub slot: u64,
}

#[event]
pub struct MintEvent {
    pub lock_tx_id: [u8; 32],
    pub recipient: Pubkey,
    pub amount: u64,
    pub slot: u64,
}

#[event]
pub struct BurnEvent {
    pub burn_tx_id: [u8; 32],
    pub user: Pubkey,
    pub amount: u64,
    pub target_address: [u8; 32],
    pub target_chain_id: String,
    pub slot: u64,
}

#[event]
pub struct UnlockEvent {
    pub burn_tx_id: [u8; 32],
    pub recipient: Pubkey,
    pub amount: u64,
    pub slot: u64,
}

#[event]
pub struct BridgePausedEvent {
    pub paused: bool,
    pub admin: Pubkey,
    pub slot: u64,
}
```

### 4.2 事件日志格式

Solana Program 通过 `sol_log_data` 输出事件，格式为 borsh 序列化的字节。
relayer 通过 `getTransaction` 解析 `meta.logData` 提取事件。

**表：事件日志格式表**

| 事件 | program_log 前缀 | data 字段 |
|------|------------------|-----------|
| LockEvent | `Program log: Lock` | borsh(LockEvent) |
| MintEvent | `Program log: Mint` | borsh(MintEvent) |
| BurnEvent | `Program log: Burn` | borsh(BurnEvent) |
| UnlockEvent | `Program log: Unlock` | borsh(UnlockEvent) |
| BridgePausedEvent | `Program log: Paused` | borsh(BridgePausedEvent) |

## 第5章 状态机

### 5.1 正向跨链（Solana → EVM）

**图：正向跨链状态流转图**

```
LOCK_PENDING ──► LOCKED ──► MINT_PENDING ──► MINTED
     │              │              │
     └──────────────┴──────────────┴──► FAILED / TIMEOUT
```

1. 用户在 Solana 调用 `Lock`，桥托管 NEX，emit `LockEvent`
2. relayer 监听 `LockEvent`，等待 Solana finality（32 slot）
3. relayer 在 EVM 链调用 `mint(lockTxId, user, amount, target)`
4. EVM 桥合约验证多签，铸造包装 NEX，emit `MintEvent`
5. relayer 回填 `MintEvent` 到 Solana `LockRecord.mint_tx_hash`

### 5.2 反向跨链（EVM → Solana）

**图：反向跨链状态流转图**

```
BURN_PENDING ──► BURNED ──► UNLOCK_PENDING ──► UNLOCKED
     │               │              │
     └───────────────┴──────────────┴──► FAILED / TIMEOUT
```

1. 用户在 EVM 链调用 `burn`，销毁包装 NEX，emit `BurnEvent`
2. relayer 监听 `BurnEvent`，等待 EVM finality（12 区块）
3. relayer 在 Solana 调用 `Unlock(burnTxId, user, amount, target)`
4. Bridge Program 验证多签，释放锁定 NEX，emit `UnlockEvent`

### 5.3 异常处理

| 异常场景 | 处理策略 |
|---------|---------|
| Lock 后 Mint 失败 | relayer 重试；连续失败 3 次进入 FAILED，触发退款流程 |
| Burn 后 Unlock 失败 | 同上 |
| 签名不足 | 等待更多验证者签名，超时后标记 TIMEOUT |
| 桥暂停 | 所有 Lock/Burn 拒绝；Mint/Unlock 仍可执行（释放存量） |
| 金额超限 | 指令直接 reject，不创建 Record |

## 第6章 与 Java 实现的映射

### 6.1 类对应关系

**表：IDL 与 Java 实现对照表**

| IDL 元素 | Java 类 | 说明 |
|---------|---------|------|
| BridgeInstruction | `SolanaBridgeHandler` 的 `encodeXxxInstruction` | 指令编码为字节 |
| BridgeState | （未实现，由治理层管理） | 链上单例 |
| LockRecord | `BridgeTransaction`（status=LOCK_PENDING/LOCKED） | 桥交易记录 |
| MintRecord | `BridgeTransaction`（status=MINT_PENDING/MINTED） | 桥交易记录 |
| LockEvent | `BridgeEvent`（type=BRIDGE_LOCK） | 桥事件 |
| MintEvent | `BridgeEvent`（type=BRIDGE_MINT） | 桥事件 |

### 6.2 指令编码格式

`SolanaBridgeHandler` 采用简化 borsh 编码：

**表：指令字节布局表**

| 指令 | 字节布局 |
|------|---------|
| Lock | `[0x01][user:32B][target:32B][amount:8B LE]` |
| Mint | `[0x02][lock_tx_id:32B][user:32B][amount:8B LE][target:32B]` |
| Burn | `[0x03][user:32B][target:32B][amount:8B LE]` |
| Unlock | `[0x04][burn_tx_id:32B][user:32B][amount:8B LE][target:32B]` |

### 6.3 地址编码

- **Solana 地址**：base58 编码的 32 字节 Ed25519 公钥
- **EVM 地址**：hex 编码的 20 字节，跨链时左补零到 32 字节
- **交易 ID**：Solana 为 base58 的 64 字节签名；EVM 为 hex 的 32 字节哈希

## 第7章 安全考量

### 7.1 多签验证

- 验证者签名采用 Ed25519，逐签验签（参考 `BridgeValidator`）
- 签名阈值默认 2-of-3，可由治理调整
- 防重放：每笔 tx_id 全局唯一，`LockRecord` / `BurnRecord` PDA 已绑定

### 7.2 资金安全

- 锁定资产存入 `bridge_vault`（PDA），仅 Bridge Program 可操作
- `mint_to` / `transfer` 均通过官方 Token Program，无自定义代币逻辑
- 紧急暂停通过 `SetPaused` 指令，仅 admin 多签可调用

### 7.3 跨链最终性

- Solana finality：32 slot（约 13 秒）
- EVM finality：12 区块（约 3 分钟）
- relayer 必须等待源链 finality 后才能在目标链执行对应操作

## 第8章 Devnet 部署清单

### 8.1 部署步骤

1. 使用 `anchor build` 编译 Program
2. `anchor deploy --provider.cluster devnet` 部署到 Devnet
3. 调用 `Initialize` 指令初始化 BridgeState
4. 创建 NEX SPL Token Mint（`spl-token create-token`）
5. 创建 bridge_vault Token Account（`spl-token create-account`）
6. 配置 `nexus-bridge` 的 `application.yml`：
   - `nexus.bridge.solana.rpc-endpoint=https://api.devnet.solana.com`
   - `nexus.bridge.solana.bridge-program-id=<部署后 Program ID>`
   - `nexus.bridge.solana.nexus-token-mint=<NEX Mint>`

### 8.2 验证步骤

1. 在 Solana Devnet 调用 `Lock`，确认 `LockRecord` 创建
2. 在 EVM 测试网调用 `mint`，确认包装 NEX 铸造
3. 反向跨链：EVM `burn` → Solana `unlock`
4. 全流程事件日志可通过 `solana confirm <tx>` 查看

## 第9章 参考实现

- **Java 适配器**：`org.nexus.bridge.adapter.SolanaAdapter`
- **Java 处理器**：`org.nexus.bridge.handler.SolanaBridgeHandler`
- **RPC 客户端**：`org.nexus.bridge.solana.SolanaRpcClient`
- **Base58 工具**：`org.nexus.bridge.solana.Base58`
- **IDL 设计**：本文档

## 第10章 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.0 | 2026-08-09 | 初始版本，P4-T1 Solana 链适配器交付 |