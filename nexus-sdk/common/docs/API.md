# NexusChain SDK API 文档

本文档描述 NexusChain SDK 各语言版本的公共 API 接口。

## 版本

- SDK 版本：1.0.0
- 代币符号：NEX

## 通用配置

所有语言 SDK 共享以下配置参数：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| network | string | 是 | mainnet | 网络类型：mainnet / testnet |
| rpcUrl | string | 是 | - | NexusChain 节点 RPC 地址 |
| timeout | number | 否 | 30000 | 请求超时（毫秒） |
| apiKey | string | 否 | - | API 密钥（付费节点认证） |

## 核心接口

### 1. 钱包管理 (Wallet)

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| create() | - | WalletInfo | 创建新钱包 |
| fromPrivateKey(pk) | privateKey: string | WalletInfo | 从私钥导入 |
| fromMnemonic(mnemonic, path) | mnemonic, path | WalletInfo | 从助记词导入 |
| getBalance(address) | address: string | string/int | 查询 NEX 余额 |
| getTokenBalance(addr, contract) | address, tokenContract | string/int | 查询代币余额 |
| validateAddress(address) | address: string | boolean | 验证地址 |

### 2. 交易管理 (Transaction)

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| buildTransfer(params) | TransferParams | Transaction | 构建转账交易 |
| buildContractCall(from, addr, data, value) | - | Transaction | 构建合约调用 |
| sign(tx, privateKey) | Transaction, string | string | 签名交易 |
| broadcast(signedTx) | string | string | 广播交易 |
| getTransactionReceipt(txHash) | string | Receipt | 查询回执 |
| estimateGas(tx) | Transaction | string/int | 估算 Gas |
| getGasPrice() | - | string/int | 当前 Gas 价格 |

### 3. RPC 客户端

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| call(method, params) | method, params | any | 通用 RPC 调用 |
| batchCall(requests) | RpcRequest[] | RpcResponse[] | 批量请求 |
| getBlockNumber() | - | number | 当前区块高度 |
| getBlockByHash(hash) | string | Block | 按哈希查区块 |
| getBlockByNumber(num) | number | Block | 按高度查区块 |
| getChainId() | - | number | 链 ID |

### 4. 支付通道 (PaymentChannel)

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| openChannel(sender, recipient, deposit) | - | channelId | 开启通道 |
| closeChannel(channelId) | string | txHash | 关闭通道 |
| updateChannelState(channelId, proof) | - | bool | 更新状态 |
| getChannelInfo(channelId) | string | ChannelInfo | 查询通道 |
| challengeChannel(channelId) | string | txHash | 发起争议 |

### 5. 稳定币 (StableCoin)

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| mint(minter, amount, collateral) | - | txHash | 铸造稳定币 |
| burn(burner, amount) | - | txHash | 销毁稳定币 |
| transfer(from, to, amount) | - | txHash | 稳定币转账 |
| getCollateralRatio(address) | string | ratio | 查询抵押率 |
| getPrice() | - | price | 查询价格 |
| getTotalSupply() | - | supply | 查询总供应量 |

### 6. 跨链桥 (Bridge)

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| lock(from, token, amount, chain, addr) | - | txHash | 锁定资产 |
| unlock(to, token, amount, chain, proof) | - | txHash | 解锁资产 |
| getBridgeStatus(txHash) | string | BridgeStatus | 跨链状态 |
| getSupportedChains() | - | string[] | 支持的链 |
| getBridgeFee(token, chain) | - | fee | 跨链手续费 |

## RPC 方法列表

NexusChain 节点支持的 JSON-RPC 方法：

| 方法 | 说明 |
|------|------|
| nexus_blockNumber | 获取当前区块高度 |
| nexus_getBlockByHash | 按哈希获取区块 |
| nexus_getBlockByNumber | 按高度获取区块 |
| nexus_chainId | 获取链 ID |
| nexus_getBalance | 获取账户余额 |
| nexus_getTransactionCount | 获取交易计数（nonce） |
| nexus_gasPrice | 获取当前 Gas 价格 |
| nexus_estimateGas | 估算交易 Gas |
| nexus_sendRawTransaction | 广播已签名交易 |
| nexus_getTransactionReceipt | 获取交易回执 |

## Protobuf 协议

跨语言共享的 Protobuf 定义位于 `common/protobuf/nexus.proto`，包含：

- 基础类型：WalletInfo, Transaction, TransactionReceipt, Block
- 支付通道：BalanceProof, ChannelInfo
- 稳定币：MintRequest, BurnRequest, StableCoinInfo
- 跨链桥：BridgeLockRequest, BridgeUnlockRequest, BridgeStatus
- 服务定义：WalletService, TransactionService, PaymentChannelService, StableCoinService, BridgeService

各语言可基于此定义生成序列化代码，确保跨语言兼容。
