# NexusChain Avalanche C-Chain 跨链桥指南

## 第1章 概述

### 1.1 文档目的

本文档说明 NexusChain 跨链桥在 Avalanche 网络上的设计与实现，
重点覆盖 Avalanche 三链架构（X-Chain / P-Chain / C-Chain）、
C-Chain 上桥合约的交互方式，以及与 X/P Chain 的跨链协作流程。

### 1.2 适用范围

- `nexus-bridge` 模块的 `AvalancheAdapter` 与 `AvalancheBridgeHandler` 实现者
- 跨链 relayer 与验证者运维人员
- 希望理解 Avalanche 跨链资产流转路径的集成方

### 1.3 术语约定

| 术语 | 含义 |
|------|------|
| X-Chain | Exchange Chain，Avalanche 资产链，负责 AVAX 与原生资产转账 |
| P-Chain | Platform Chain，Avalanche 平台链，管理验证者与子网（Subnet） |
| C-Chain | Contract Chain，Avalanche 合约链，EVM 兼容 |
| C-Chain RPC | C-Chain 的 JSON-RPC 端点，命名空间为 `eth_*` |
| AVAX | Avalanche 原生代币 |
| NEX | NexusChain 跨链代币 |

## 第2章 Avalanche 三链架构

### 2.1 架构总览

图：Avalanche 三链架构示意图

```
┌─────────────────────────────────────────────────────────┐
│                    Avalanche Primary Network              │
│                                                            │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   │
│  │   X-Chain    │   │   P-Chain    │   │   C-Chain    │   │
│  │  (Exchange)  │   │  (Platform)  │   │  (Contract)  │   │
│  │              │   │              │   │              │   │
│  │  AVAX 转账   │   │  验证者管理   │   │  EVM 兼容    │   │
│  │  原生资产     │   │  子网创建     │   │  智能合约    │   │
│  │  UTXO 模型   │   │  PoS 共识     │   │  Snowman     │   │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘   │
│         │                  │                  │            │
│         └──────────────────┴──────────────────┘            │
│                           │                                │
│                   AVAX 跨链转移                            │
│              (Export/Import 原语)                          │
└─────────────────────────────────────────────────────────┘
```

### 2.2 X-Chain（资产链）

- **职责**：AVAX 与原生资产的创建、转账、交易
- **数据模型**：UTXO 模型，类似 Bitcoin
- **共识**：Avalanche 共识（DAG-based）
- **RPC 命名空间**：`avm.*`
- **典型操作**：`avm.send`, `avm.getBalance`, `avm.createAsset`
- **与桥的关系**：若跨链涉及 AVAX 原生资产，需通过 X-Chain 的 `exportAVAX` / `importAVAX` 将资产在 X-Chain 与 C-Chain 之间转移

### 2.3 P-Chain（平台链）

- **职责**：验证者注册、子网（Subnet）创建与管理、质押
- **数据模型**：账户模型
- **共识**：Snowman 共识（线性链）
- **RPC 命名空间**：`platform.*`
- **典型操作**：`platform.addValidator`, `platform.createSubnet`, `platform.delegateAVAX`
- **与桥的关系**：若 NexusChain 部署在自定义子网（Subnet）上，P-Chain 管理该子网的验证者集合；桥的验证者多签可映射为子网验证者白名单

### 2.4 C-Chain（合约链）

- **职责**：智能合约执行，EVM 兼容
- **数据模型**：账户模型（与以太坊一致）
- **共识**：Snowman 共识（线性链，出块约 2 秒）
- **RPC 命名空间**：`eth_*`（与以太坊完全兼容）、`net_*`、`web3_*`
- **Chain ID**：主网 `43114`（0xA86A），Fuji 测试网 `43113`（0xA869）
- **与桥的关系**：**NexusChain 桥合约部署在 C-Chain**，所有 lock/mint/burn/unlock 操作均在 C-Chain 上执行

### 2.5 三链资产转移原语

Avalanche 原生提供跨链转移原语（非 NexusChain 桥，而是 Avalanche 内置）：

| 原语 | 方向 | 说明 |
|------|------|------|
| `avax.exportAVAX` | X-Chain → C-Chain | 将 AVAX 从 X-Chain 导出至 C-Chain |
| `avax.importAVAX` | C-Chain → X-Chain | 将 AVAX 从 C-Chain 导入回 X-Chain |
| `platform.exportAVAX` | X-Chain → P-Chain | 将 AVAX 从 X-Chain 导出至 P-Chain（用于质押） |
| `platform.importAVAX` | P-Chain → X-Chain | 将 AVAX 从 P-Chain 导入回 X-Chain |

> **注意**：上述原语用于 AVAX 在 Avalanche 三链之间流转，与 NexusChain 跨链桥（跨不同区块链网络）是不同层次的概念。

## 第3章 NexusChain 桥在 C-Chain 上的实现

### 3.1 部署架构

图：NexusChain 桥在 Avalanche C-Chain 上的部署架构图

```
┌─────────────────────────────────────────────────────────┐
│                  NexusChain 跨链桥                        │
│                                                            │
│  ┌─────────────┐  lock/mint   ┌─────────────────────┐     │
│  │  源链        │ ───────────► │  Avalanche C-Chain  │     │
│  │ (ETH/BSC/   │               │                      │     │
│  │  Polygon/   │  burn/unlock  │  ┌──────────────┐   │     │
│  │  Solana)    │ ◄─────────── │  │ BridgeContract│   │     │
│  └─────────────┘               │  │  (Solidity)   │   │     │
│                                 │  └──────────────┘   │     │
│                                 │  ┌──────────────┐   │     │
│                                 │  │ NexToken     │   │     │
│                                 │  │  (ERC-20)    │   │     │
│                                 │  └──────────────┘   │     │
│                                 └─────────────────────┘     │
└─────────────────────────────────────────────────────────┘
```

### 3.2 合约接口

桥合约部署在 C-Chain 上，接口与 Ethereum / BSC / Polygon 上的桥合约完全一致（C-Chain EVM 兼容）：

```solidity
interface IBridge {
    function lock(address user, address target, uint256 amount) external returns (bool);
    function mint(bytes32 lockTxId, address user, uint256 amount, address target) external returns (bool);
    function burn(address user, address target, uint256 amount) external returns (bool);
    function unlock(bytes32 burnTxId, address user, uint256 amount, address target) external returns (bool);
}
```

### 3.3 适配器与处理器映射

| NexusChain 组件 | Avalanche 对应实现 | 说明 |
|------------------|---------------------|------|
| `ChainAdapter` | `AvalancheAdapter` | 复用 `AbstractEvmChainAdapter`，C-Chain EVM 兼容 |
| `BridgeHandler` | `AvalancheBridgeHandler` | 复用 `AbstractBridgeHandler` 模板方法 |
| RPC 端点 | `https://api.avax.network/ext/bc/C/rpc` | C-Chain JSON-RPC |
| Chain ID | `0xA86A`（43114） | 主网；Fuji 测试网为 `0xA869`（43113） |
| 推荐确认数 | 20 | C-Chain 出块约 2 秒，Snowman 最终性快 |
| Gas 代币 | AVAX | C-Chain Gas 以 AVAX 计价 |

### 3.4 桥操作状态机

#### 3.4.1 正向跨链（lock → mint）

图：正向跨链状态流转图

```
源链                      Avalanche C-Chain
────                      ─────────────────
LOCK_PENDING ──► LOCKED ──► MINT_PENDING ──► MINTED
  (用户锁定)    (确认)      (验证者铸造)     (完成)
```

1. 用户在源链调用 `lock(user, target, amount)`，资产锁定到源链桥托管地址
2. 源链交易达到确认数后，状态转为 `LOCKED`
3. 验证者收集足够签名（≥ 阈值），在 C-Chain 调用 `mint(lockTxId, user, amount, target)`
4. C-Chain 桥合约铸造等量 NEX 给用户，状态转为 `MINTED`

#### 3.4.2 反向跨链（burn → unlock）

图：反向跨链状态流转图

```
Avalanche C-Chain                      源链
─────────────────                      ────
BURN_PENDING ──► BURNED ──► UNLOCK_PENDING ──► UNLOCKED
  (用户销毁)     (确认)      (验证者解锁)       (完成)
```

1. 用户在 C-Chain 调用 `burn(user, target, amount)`，销毁 NEX 包装代币
2. C-Chain 交易达到确认数后，状态转为 `BURNED`
3. 验证者收集足够签名，在源链调用 `unlock(burnTxId, user, amount, target)`
4. 源链桥合约释放等量原始资产给用户，状态转为 `UNLOCKED`

### 3.5 异常终态

| 终态 | 触发条件 |
|------|----------|
| `FAILED` | 合约调用 revert、签名验证失败、金额超限 |
| `CANCELLED` | 治理层主动取消（如安全事件暂停） |
| `TIMEOUT` | 超过时间锁周期未完成确认 |

## 第4章 C-Chain 与 X/P Chain 的交互

### 4.1 交互场景分类

NexusChain 桥的核心操作（lock/mint/burn/unlock）**仅在 C-Chain 上执行**，
不直接涉及 X/P Chain。但在以下场景需要与 X/P Chain 交互：

| 场景 | 涉及链 | 说明 |
|------|--------|------|
| Gas 费用补充 | X-Chain → C-Chain | 验证者需用 AVAX 支付 C-Chain Gas，通过 `avax.exportAVAX` 从 X-Chain 转入 |
| AVAX 原生跨链 | X-Chain ↔ C-Chain | 若桥支持 AVAX 原生资产跨链，需先在 X-Chain 与 C-Chain 间转移 |
| 子网部署 | P-Chain | 若桥合约部署在自定义子网，P-Chain 管理子网验证者 |
| 验证者质押 | P-Chain | 桥验证者若同时为 Avalanche 主网验证者，需在 P-Chain 质押 AVAX |

### 4.2 Gas 费用流转

图：验证者 Gas 补充流程图

```
验证者账户（X-Chain）          验证者账户（C-Chain）
       │                              │
       │  avax.exportAVAX             │
       │  (X → C, UTXO 消费)          │
       └─────────────────────────────►│
                                      │  eth_sendRawTransaction
                                      │  (桥合约调用, 以 AVAX 付 Gas)
                                      ▼
                                 C-Chain 桥合约
```

**操作步骤**：

1. 验证者在 X-Chain 发起 `avax.exportAVAX`，指定 C-Chain 目标地址与金额
2. 等待 X-Chain 交易确认（Avalanche 共识最终性约 1 秒）
3. 在 C-Chain 发起 `avax.importAVAX`，完成 AVAX 从 X-Chain 到 C-Chain 的转移
4. C-Chain 账户获得 AVAX 余额，可用于支付后续桥合约调用的 Gas

> **注意**：此流程由验证者运维脚本（AvalancheJS SDK）完成，不在 `AvalancheBridgeHandler` 范围内。

### 4.3 子网（Subnet）部署场景

若 NexusChain 桥合约部署在 Avalanche 自定义子网（而非主网 C-Chain）：

1. **子网创建**：在 P-Chain 调用 `platform.createSubnet`，指定验证者集合与阈值
2. **验证者加入**：验证者调用 `platform.addValidator` 加入子网
3. **子网 Chain ID**：子网拥有独立的 Chain ID，C-Chain RPC 端点需替换为子网端点
4. **桥配置**：`AvalancheAdapter` 的 `rpc-endpoint` 与 `chain-id` 需指向子网

### 4.4 与主网 C-Chain 的差异

| 维度 | 主网 C-Chain | 自定义子网 C-Chain |
|------|--------------|---------------------|
| Chain ID | 43114（0xA86A） | 子网自定义 |
| RPC 端点 | `api.avax.network/ext/bc/C/rpc` | 子网独立端点 |
| 验证者集合 | Avalanche 主网验证者 | 子网指定验证者 |
| 最终性 | Snowman 共识（约 1-2 秒） | 取决于子网配置 |
| Gas 代币 | AVAX | 子网自定义（可为 AVAX 或其他） |

## 第5章 配置与部署

### 5.1 application.yml 配置

配置示例：Avalanche C-Chain RPC 配置

```yaml
nexus:
  bridge:
    avalanche:
      rpc-endpoint: https://api.avax.network/ext/bc/C/rpc
      chain-id: "0xA86A"
```

### 5.2 Fuji 测试网配置

配置示例：Fuji 测试网配置

```yaml
nexus:
  bridge:
    avalanche:
      rpc-endpoint: https://api.avax-test.network/ext/bc/C/rpc
      chain-id: "0xA869"
```

### 5.3 桥合约部署参数

表：Avalanche C-Chain 桥合约部署参数说明表

| 参数 | 主网值 | Fuji 测试网值 |
|------|--------|---------------|
| Chain ID | 43114 | 43113 |
| RPC 端点 | `https://api.avax.network/ext/bc/C/rpc` | `https://api.avax-test.network/ext/bc/C/rpc` |
| 区块浏览器 | `https://snowtrace.io` | `https://testnet.snowtrace.io` |
| 推荐确认数 | 20 | 20 |
| 出块时间 | ~2 秒 | ~2 秒 |
| Gas 代币 | AVAX | AVAX（测试代币） |

## 第6章 安全注意事项

### 6.1 私钥管理

- 桥合约调用的私钥由 `nexus-signing-service` 托管，不落地存储
- `AvalancheBridgeHandler` 通过合成交易哈希（SHA-256）标识已提交的调用，
  实际生产中应由签名服务构造已签名交易后通过 `eth_sendRawTransaction` 提交

### 6.2 确认数选择

- C-Chain 采用 Snowman 共识，最终性约 1-2 秒
- 推荐确认数 20（约 40 秒），保守取值以应对网络抖动与重组风险
- 大额跨链可适当提高确认数至 30-50

### 6.3 重放保护

- 每笔 lock/mint/burn/unlock 携带唯一 `tx_id`（32 字节），桥合约拒绝重复 `tx_id`
- C-Chain 的 Chain ID（43114）与源链 Chain ID 不同，天然防止跨链重放

### 6.4 AVAX Gas 余额监控

- 验证者 C-Chain 账户需保持足够 AVAX 余额以支付 Gas
- 建议设置监控告警，当余额低于阈值时通过 X-Chain → C-Chain 转账补充
- Gas 价格（Gas Price）应参考 C-Chain 当前 `eth_gasPrice`，避免交易长期未打包

## 第7章 参考资料

- [Avalanche 官方文档](https://docs.avax.network/)
- [Avalanche C-Chain RPC 文档](https://docs.avax.network/apis/avalanchego/apis/c-chain)
- [Avalanche 跨链转移原语](https://docs.avax.network/learn/cross-chain-transactions/)
- [Snowman 共识白皮书](https://docs.avax.network/learn/avalanche/avalanche-consensus/)
- NexusChain 桥设计文档：`docs/solana-bridge-program-idl.md`（Solana 桥 IDL，C-Chain 接口与之对齐）