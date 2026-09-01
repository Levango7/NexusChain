# Python SDK — v2.2.0 补真（2026-09-01）

> **状态变更：`nexus_sdk/` 包已可用**（原骨架缺陷全部修复或替代）
>
> ## 使用 `nexus_sdk` 包（新）
>
> 按 nexus-core JsonRpcController 真实 RPC 契约实现，**零第三方依赖**
> （urllib + 标准库；keccak-256 纯 Python 实现已过双权威向量验证）：
> - 链查询：`get_block_number` / `get_chain_id` / `get_node_status` /
>   `get_block_by_height`（数值型信封——原骨架的 0x hex 解析假设已修正）
> - 钱包查询：`wallet.get_balance`（`{"balance":"<decimal>"}` 信封解包）、
>   `wallet.get_nonce`（`{"count":N}`）、`wallet.get_transactions_by_address`
> - 交易：`transaction.build_transfer`（本地地址校验 + 实时 nonce）、
>   `transaction.get_transaction_by_hash` / `get_latest_transactions`
> - 跨链桥：`bridge.list`（nexus_getCrossChainTransactions）
> - 地址校验：`validate_address`（Base58 + 25 字节 + keccak 双哈希校验尾，
>   对齐 Java KeystoreAction.verifyAddress）
> - 交易提交：`wallet.submit_transfer` 走 **nexus-wallet-service** HTTP
>   （`Client(wallet_service_url=...)`）——core JSON-RPC 无
>   sendRawTransaction 是架构决策（签名/KMS/审计集中在 wallet-service），非缺陷
>
> ## `conpay/` 包（旧）
>
> **Deprecated**：品牌残留（CPAY）+ hex 信封解析假设错误 + 广播走不存在的
> RPC 方法。仅为兼容保留源码，请勿新集成；迁移到 `nexus_sdk` 包。
>
> 运行测试：`cd nexus-sdk/python && python tests/test_nexus_sdk.py`
