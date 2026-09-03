# NexusChain SDK

统一多语言 SDK，为 NexusChain 区块链支付网络提供一致的开发体验。

> **状态声明（2026-09-01 v2.2.0 三语言补真）**
>
> | 语言 | 状态 | 包名 | 测试 |
> |------|------|------|------|
> | **Java** | ✅ 生产可用（全能力：RPC/钱包/支付编排/跨链/v2 客户端） | `org.nexus.sdk` | gradle 全绿 |
> | **TypeScript** | ✅ 可用（真实 RPC 契约：链查询/钱包查询/交易构建/跨链桥/地址校验/提交走 wallet-service） | `@nexus/sdk` | 8 用例（含 keccak 权威向量） |
> | **Python** | ✅ 可用（同上能力面，零第三方依赖） | `nexus_sdk` | 15 用例（keccak 双权威向量） |
> | **Go** | ✅ 可用（同上能力面） | `nexus` | 13 用例（含黄金地址跨语言一致性） |
>
> 三语言共享的架构决策：**交易签名与密钥管控集中在 nexus-wallet-service**
> （KMS/轮换/审计），SDK 不持私钥——`create/submit` 方法指向 wallet-service
> 端点。查询能力直连 nexus-core JSON-RPC（15 个已核实方法，数值信封）。
> 各语言详情见对应目录 `STATUS.md`。
>
> 历史：2026-08-31 前三门语言为骨架占位（含 Broadcast 调用不存在方法等缺陷）；
> 旧 `conpay` 包（Go/Python）保留源码但已弃用，迁移到新 `nexus`/`nexus_sdk` 包。

## 概述

- **代币符号**: NEX
- **项目名称**: NexusChain

## 核心能力（三语言一致）

### 链查询
- 区块高度 / 链 ID / 节点状态（`nexus_getNodeStatus`，数值信封）
- 按高度取块（`nexus_getBlockByHeight`）

### 钱包查询
- 余额（`nexus_getBalance` → `{"balance":"<decimal>"}` 信封解包）
- nonce（`nexus_getTransactionCount` → `{"count":N}`）
- 按地址查交易（`nexus_getTransactionsByAddress`）

### 交易
- 构建转账（本地地址校验 + 实时 nonce）
- 查询确认交易 / 最新交易列表
- **提交**走 nexus-wallet-service HTTP（`/api/v1/transfers`）

### 跨链桥
- 跨链交易列表（`nexus_getCrossChainTransactions`，近 200 区块 BRIDGE_* 推导，支持状态过滤）

### 地址校验（纯本地，零依赖）
- Base58 解码 + 25 字节布局（1 版本 + 20 哈希 + 4 校验尾）
- keccak256(keccak256(pubkeyHash)) 校验尾验证
- 语义对齐 Java `KeystoreAction.verifyAddress`，黄金地址跨语言一致

## 快速开始

### TypeScript / JavaScript

```typescript
import { NexusChainClient, validateAddress } from '@nexus/sdk';

const client = new NexusChainClient({
  network: 'mainnet',
  rpcUrl: 'http://127.0.0.1:19585/rpc',
  walletServiceUrl: 'http://127.0.0.1:8083', // 交易提交需要
});

const height = await client.getBlockNumber();           // 数值信封
const balance = await client.wallet.getBalance(addr);   // 十进制字符串
const nonce = await client.wallet.getNonce(addr);       // count 信封
const ok = validateAddress(addr);                       // 本地校验

// 提交转账（wallet-service 签名与提交）
const txHash = await client.wallet.submitTransfer(from, to, '100');
```

### Python

```python
from nexus_sdk import Client, validate_address

client = Client(
    rpc_url="http://127.0.0.1:19585/rpc",
    wallet_service_url="http://127.0.0.1:8083",  # 交易提交需要
)

height = client.get_block_number()
balance = client.wallet.get_balance(addr)    # 十进制字符串信封 → int
nonce = client.wallet.get_nonce(addr)
ok = validate_address(addr)                  # 本地校验

# 提交转账（wallet-service 签名与提交）
tx_hash = client.wallet.submit_transfer(from_addr, to, 100)
```

### Go

```go
import "github.com/levango7/nexuschain/sdk/go/nexus"

client, err := nexus.NewClient(&nexus.Config{
    RPCUrl:           "http://127.0.0.1:19585/rpc",
    WalletServiceURL: "http://127.0.0.1:8083", // 交易提交需要
})

height, _ := client.GetBlockNumber()          // 数值信封
balance, _ := client.Wallet.GetBalance(addr)   // *big.Int（十进制信封）
nonce, _ := client.Wallet.GetNonce(addr)
ok := nexus.ValidateAddress(addr)              // 本地校验

// 提交转账（wallet-service 签名与提交）
txHash, err := client.Wallet.SubmitTransfer(nexus.SubmitTransferRequest{
    From: from, To: to, Amount: big.NewInt(100),
})
```

### Java（全能力，见 `java/`）

支付编排、支付通道、稳定币、跨链、v2 分页/订阅/租户客户端等完整能力
仅在 Java SDK 提供（Feign 客户端 + 服务发现）；三轻语言覆盖上表查询
与提交能力面。

## 配置

| 参数 | 类型 | 说明 |
|------|------|------|
| `rpcUrl` / `rpc_url` / `RPCUrl` | string | nexus-core JSON-RPC 端点（如 `http://127.0.0.1:19585/rpc`） |
| `walletServiceUrl` | string | nexus-wallet-service 基址（交易提交需要；仅查询可不填） |
| `timeout` | number | 请求超时（TS/Go 毫秒 / Python 秒），默认 30000 / 30 |
| `apiKey` | string | 可选 Bearer token |

## 构建与测试

```bash
# TypeScript（Node >= 18）
cd typescript && npm install && npm test

# Python（>= 3.10，零依赖）
cd python && python tests/test_nexus_sdk.py

# Go（>= 1.25；x/crypto 0.55 要求，go.mod 已声明）
cd go && go test ./nexus/

# Java
cd java && ./gradlew build
```

## 许可证

MIT License
