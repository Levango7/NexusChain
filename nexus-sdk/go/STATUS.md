# Go SDK — v2.2.0 补真（2026-09-01）

> **状态变更：`nexus/` 包已可用**（原骨架缺陷全部修复或移除）
>
> ## 使用 `nexus` 包（新）
>
> 按 nexus-core JsonRpcController 真实 RPC 契约实现（15 个已核实方法）：
> - 链查询：`GetBlockNumber` / `GetChainID` / `GetNodeStatus` / `GetBlockByHeight`
>   （数值型信封——原骨架的 0x hex 解析假设与真实信封不符，已修正）
> - 钱包查询：`Wallet.GetBalance`（`{"balance":"<decimal>"}` 信封解包）、
>   `Wallet.GetNonce`（`{"count":N}`）、`Wallet.GetTransactionsByAddress`
> - 交易：`Transaction.BuildTransfer`（本地地址校验 + 实时 nonce）、
>   `Transaction.GetTransactionByHash` / `GetLatestTransactions`
> - 跨链桥：`Bridge.List`（nexus_getCrossChainTransactions）
> - 地址校验：`ValidateAddress`（Base58 + 25 字节 + keccak 双哈希校验尾，
>   对齐 Java KeystoreAction.verifyAddress——黄金地址跨语言一致性有测试钉死）
> - 交易提交：`Wallet.SubmitTransfer` 走 **nexus-wallet-service** HTTP
>   （`Config.WalletServiceURL`）——core JSON-RPC 无 sendRawTransaction
>   是架构决策（签名/KMS/审计集中在 wallet-service），非缺陷
>
> ## `conpay/` 包（旧）
>
> **Deprecated**：品牌残留（CPAY）+ Broadcast 调用不存在的 RPC 方法 +
> hex 信封解析假设错误。仅为兼容保留源码，请勿新集成；迁移到 `nexus` 包。
>
> 运行测试：`cd nexus-sdk/go && go test ./nexus/`
