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

> **Not Implemented** 标注的方法在 SDK 中仅保留接口签名，调用时会返回明确的 error / NotImplementedError。
> 这些方法涉及密钥材料生成/导入，必须由 **nexus-wallet-service**（独立微服务）执行，
> 该服务负责 KMS 集成、密钥轮换与审计策略。SDK 不应直接处理密钥。
> 已实现的方法（getBalance）通过 RPC 与节点交互，不接触密钥材料。

| 方法 | 参数 | 返回值 | 状态 | 说明 |
|------|------|--------|------|------|
| create() | - | WalletInfo | **Not Implemented** | 创建新钱包，请改用 wallet-service API |
| fromPrivateKey(pk) | privateKey: string | WalletInfo | **Not Implemented** | 从私钥导入，请改用 wallet-service API |
| fromMnemonic(mnemonic, path) | mnemonic, path | WalletInfo | **Not Implemented** | 从助记词导入，请改用 wallet-service API |
| getBalance(address) | address: string | string/int | Implemented | 查询 NEX 余额（通过 nexus_getBalance RPC） |
| getTokenBalance(addr, contract) | address, tokenContract | string/int | **Not Implemented** | 查询代币余额，请改用 wallet-service API 或直接调用合约 |
| validateAddress(address) | address: string | boolean | **Not Implemented** | 验证地址，请改用 wallet-service API 或本地校验 |

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

| 方法 | 参数 | 返回值 | 状态 | 说明 |
|------|------|--------|------|------|
| call(method, params) | method, params | any | Implemented | 通用 RPC 调用 |
| batchCall(requests) | RpcRequest[] | RpcResponse[] | Implemented | 批量请求 |
| getBlockNumber() | - | number | Implemented | 当前区块高度（内部调用 nexus_getLatestBlocks） |
| getBlockByHash(hash) | string | Block | **Not Implemented** (nexus-core) | 按哈希查区块，SDK 保留接口但抛错，建议改用 getBlockByNumber |
| getBlockByNumber(num) | number | Block | Implemented | 按高度查区块（内部调用 nexus_getBlockByHeight） |
| getChainId() | - | number | Implemented | 链 ID（内部调用 nexus_getNodeStatus 解析 chainId 字段） |

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

NexusChain 节点支持的 JSON-RPC 方法（nexus_ 前缀）。

> **注意**：早期 SDK 文档中出现的 `nexus_blockNumber` / `nexus_getBlockByNumber` /
> `nexus_chainId` / `nexus_gasPrice` 在 nexus-core 当前版本中**未实现**。
> SDK 已通过下表中的兼容方法兜底，详见各语言 RpcClient 实现。

| 方法 | 状态 | 说明 | SDK 兼容策略 |
|------|------|------|--------------|
| nexus_getLatestBlocks | Implemented | 获取最新区块列表（取首个区块高度即 blockNumber） | SDK getBlockNumber() 调用此方法取 list[0].number |
| nexus_getBlockByHeight | Implemented | 按高度获取区块 | SDK getBlockByNumber() 调用此方法 |
| nexus_getBlockByHash | **Not Implemented** (nexus-core) | 按哈希获取区块 | SDK 保留接口但抛错，建议改用 getBlockByHeight |
| nexus_getNodeStatus | Implemented | 获取节点状态（含 chainId、gasPrice 等） | SDK getChainId()/getGasPrice() 从此方法解析对应字段 |
| nexus_getBalance | Implemented | 获取账户余额 | 直接调用 |
| nexus_getTransactionCount | Implemented | 获取交易计数（nonce） | 直接调用 |
| nexus_estimateGas | Implemented | 估算交易 Gas | 直接调用 |
| nexus_sendRawTransaction | Implemented | 广播已签名交易 | 直接调用 |
| nexus_getTransactionReceipt | Implemented | 获取交易回执 | 直接调用 |

### 已弃用 / 未实现的 RPC 方法

| 旧方法名 | 替代方法 | 说明 |
|----------|----------|------|
| nexus_blockNumber | nexus_getLatestBlocks | nexus-core 未提供，SDK 已切换 |
| nexus_getBlockByNumber | nexus_getBlockByHeight | nexus-core 未提供，SDK 已切换 |
| nexus_chainId | nexus_getNodeStatus | nexus-core 未提供，SDK 从节点状态解析 |
| nexus_gasPrice | nexus_getNodeStatus | nexus-core 未提供，SDK 从节点状态解析 gasPrice 字段，缺省返回 1 gwei |

## Protobuf 协议

跨语言共享的 Protobuf 定义位于 `common/protobuf/nexus.proto`，包含：

- 基础类型：WalletInfo, Transaction, TransactionReceipt, Block
- 支付通道：BalanceProof, ChannelInfo
- 稳定币：MintRequest, BurnRequest, StableCoinInfo
- 跨链桥：BridgeLockRequest, BridgeUnlockRequest, BridgeStatus
- 服务定义：WalletService, TransactionService, PaymentChannelService, StableCoinService, BridgeService

各语言可基于此定义生成序列化代码，确保跨语言兼容。
