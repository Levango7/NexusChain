# TypeScript SDK — v2.2.0 补真（2026-09-01）

> **状态变更：本包已可用**（原骨架缺陷全部修复或替代）
>
> ## 能力面（全部对齐 nexus-core 真实 RPC 契约）
>
> - 链查询：`getBlockNumber` / `getChainId` / `getNodeStatus` /
>   `getBlockByNumber`（数值型信封——原骨架的 0x hex 解析假设已修正）
> - 钱包查询：`wallet.getBalance`（`{"balance":"<decimal>"}` 信封解包）、
>   `wallet.getNonce`（`{"count":N}`）、`wallet.getTransactionsByAddress`
> - 交易：`transaction.buildTransfer`（本地地址校验 + 实时 nonce）、
>   `transaction.getTransactionByHash` / `getLatestTransactions`
> - 跨链桥：`bridge.list`（nexus_getCrossChainTransactions）
> - 地址校验：`validateAddress`（Base58 + 25 字节 + keccak 双哈希校验尾，
>   纯 TS 实现，keccak-256 已过权威向量；对齐 Java
>   KeystoreAction.verifyAddress）
> - 交易提交：`wallet.submitTransfer` 走 **nexus-wallet-service** HTTP
>   （`NexusChainConfig.walletServiceUrl`）——core JSON-RPC 无
>   sendRawTransaction 是架构决策（签名/KMS/审计集中在 wallet-service），
>   非缺陷。原骨架的 `broadcast(nexus_sendRawTransaction)` 已移除。
>
> 钱包密钥生成（`create`）仍是 wallet-service 的职责（KMS/轮换/审计集中
> 管控）——这是三语言一致的架构决策，方法保留明确指向 wallet-service 端点。
>
> 运行测试：`cd nexus-sdk/typescript && npm test`（Node >= 18 内置 test
> runner；依赖安装 `npm install --registry=https://registry.npmmirror.com`）
