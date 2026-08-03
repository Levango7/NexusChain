# NexusChain SDK

统一多语言 SDK，聚合原 `nexus-js-sdk` 和 `nexus-java-sdk` 的能力，为 NexusChain 区块链支付网络提供一致的开发体验。

## 概述

NexusChain SDK 是一个统一的多语言软件开发工具包，为 NexusChain 网络提供全栈访问能力。无论你使用哪种主流编程语言，都能获得一致的 API 设计和完整的功能覆盖。

- **代币符号**: NEX
- **项目名称**: NexusChain

## 支持语言

| 语言 | 状态 | 目录 | 包名 |
|------|------|------|------|
| JavaScript / TypeScript | 迁移自 nexus-js-sdk | `typescript/` | `@nexus/sdk` |
| Java | 迁移自 nexus-java-sdk | `java/` | `org.nexus.sdk` |
| Python | 新增 | `python/` | `nexus` |
| Go | 新增 | `go/` | `nexus` |

## 核心能力

### 钱包管理 (Wallet)
- 创建新钱包（生成密钥对）
- 从私钥/助记词导入钱包
- 查询余额（NEX 及其他代币）
- 地址验证与格式转换

### 交易构造 / 签名 / 广播 (Transaction)
- 构建转账交易
- 离线签名
- 交易序列化 / 反序列化
- 广播交易到网络
- 查询交易状态

### RPC 客户端 (RpcClient)
- 封装 NexusChain 节点 JSON-RPC 接口
- 支持主网 / 测试网切换
- 连接池管理与自动重连
- 批量请求支持

### 支付通道操作 (Payment Channel)
- 开启 / 关闭支付通道
- 链下状态更新
- 通道结算与争议处理

### 稳定币操作 (StableCoin)
- 稳定币发行 / 销毁
- 稳定币转账
- 抵押率查询
- 价格喂价接口

### 跨链操作 (Bridge)
- 跨链资产锁定 / 解锁
- 跨链交易状态跟踪
- 支持多目标链（Ethereum、BSC、Polygon 等）

## 目录结构

```
nexus-sdk/
├── README.md
├── package.json              # npm workspace 根配置
├── java/                     # Java SDK
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/org/nexus/sdk/
│       ├── NexusChainClient.java
│       ├── Wallet.java
│       ├── TransactionBuilder.java
│       ├── RpcClient.java
│       ├── channel/PaymentChannelClient.java
│       ├── stablecoin/StableCoinClient.java
│       └── bridge/BridgeClient.java
├── typescript/               # TypeScript SDK
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── index.ts
│       ├── client.ts
│       ├── wallet.ts
│       ├── transaction.ts
│       ├── rpc.ts
│       └── types.ts
├── python/                   # Python SDK
│   ├── setup.py
│   └── nexus/
│       ├── __init__.py
│       ├── client.py
│       ├── wallet.py
│       └── transaction.py
├── go/                       # Go SDK
│   ├── go.mod
│   └── nexus/
│       ├── client.go
│       ├── wallet.go
│       └── transaction.go
└── common/                   # 跨语言共享
    ├── protobuf/
    │   └── nexus.proto      # 统一 Protobuf 协议定义
    └── docs/                  # API 文档
```

## 快速开始

### TypeScript / JavaScript

```typescript
import { NexusChainClient } from '@nexus/sdk';

const client = new NexusChainClient({
  network: 'mainnet',
  rpcUrl: 'https://rpc.nexus.network',
});

// 创建钱包
const wallet = client.wallet.create();
console.log('Address:', wallet.address);
console.log('PrivateKey:', wallet.privateKey);

// 查询余额
const balance = await client.wallet.getBalance(wallet.address);
console.log('NEX Balance:', balance);

// 构建并发送交易
const tx = client.transaction.buildTransfer({
  from: wallet.address,
  to: '0xRecipientAddress',
  amount: '100',
  token: 'NEX',
});
const signedTx = client.transaction.sign(tx, wallet.privateKey);
const txHash = await client.transaction.broadcast(signedTx);
console.log('Transaction Hash:', txHash);
```

### Java

```java
import org.nexus.sdk.NexusChainClient;
import org.nexus.sdk.Wallet;

NexusChainClient client = new NexusChainClient.Builder()
    .network("mainnet")
    .rpcUrl("https://rpc.nexus.network")
    .build();

// 创建钱包
Wallet wallet = client.wallet().create();
System.out.println("Address: " + wallet.getAddress());

// 查询余额
BigInteger balance = client.wallet().getBalance(wallet.getAddress());
System.out.println("NEX Balance: " + balance);

// 构建并发送交易
Transaction tx = client.transactionBuilder()
    .buildTransfer(wallet.getAddress(), "0xRecipient", BigInteger.valueOf(100), "NEX");
String signedTx = client.transactionBuilder().sign(tx, wallet.getPrivateKey());
String txHash = client.transactionBuilder().broadcast(signedTx);
System.out.println("Transaction Hash: " + txHash);
```

### Python

```python
from nexus import NexusChainClient

client = NexusChainClient(
    network='mainnet',
    rpc_url='https://rpc.nexus.network',
)

# 创建钱包
wallet = client.wallet.create()
print(f'Address: {wallet.address}')

# 查询余额
balance = client.wallet.get_balance(wallet.address)
print(f'NEX Balance: {balance}')

# 构建并发送交易
tx = client.transaction.build_transfer(
    from_addr=wallet.address,
    to='0xRecipientAddress',
    amount='100',
    token='NEX',
)
signed_tx = client.transaction.sign(tx, wallet.private_key)
tx_hash = client.transaction.broadcast(signed_tx)
print(f'Transaction Hash: {tx_hash}')
```

### Go

```go
package main

import (
    "fmt"
    "nexus"
)

func main() {
    client := nexus.NewClient(&nexus.Config{
        Network: "mainnet",
        RPCUrl:  "https://rpc.nexus.network",
    })

    // 创建钱包
    wallet := client.Wallet().Create()
    fmt.Println("Address:", wallet.Address)

    // 查询余额
    balance, _ := client.Wallet().GetBalance(wallet.Address)
    fmt.Println("NEX Balance:", balance)

    // 构建并发送交易
    tx := client.Transaction().BuildTransfer(
        wallet.Address, "0xRecipient", "100", "NEX",
    )
    signedTx := client.Transaction().Sign(tx, wallet.PrivateKey)
    txHash, _ := client.Transaction().Broadcast(signedTx)
    fmt.Println("Transaction Hash:", txHash)
}
```

## 配置

SDK 支持以下配置项：

| 参数 | 类型 | 说明 |
|------|------|------|
| `network` | string | 网络类型：`mainnet` / `testnet` |
| `rpcUrl` | string | NexusChain 节点 RPC 地址 |
| `timeout` | number | 请求超时时间（毫秒），默认 30000 |
| `apiKey` | string | API 密钥（可选，用于付费节点） |

## 协议定义

所有语言共享统一的 Protobuf 协议定义，位于 `common/protobuf/nexus.proto`。各语言 SDK 可基于该定义生成对应的序列化代码，确保跨语言兼容。

## 构建

### TypeScript

```bash
cd typescript
npm install
npm run build
```

### Java

```bash
cd java
./gradlew build
```

### Python

```bash
cd python
pip install -e .
```

### Go

```bash
cd go
go build ./...
```

## 许可证

MIT License
