"""NexusChain Python SDK — nexus 包（v2.2.0 三语言补真，2026-09-01）。

按 nexus-core JsonRpcController 的真实 RPC 契约实现（15 个已核实方法，
信封为十进制字符串/数值而非 0x hex），替代旧 conpay 骨架（其 Broadcast 调用
不存在的 nexus_sendRawTransaction、hex 解析假设与真实信封不符）。

能力面：
  - 链查询：区块高度 / 区块详情 / 节点状态 / 链 ID
  - 钱包查询：余额（nexus_getBalance {"balance":"<decimal>"}）、
    nonce（nexus_getTransactionCount {"count":N}）、交易历史
  - 交易构建与提交：BuildTransfer（真实信封）；Submit 走 wallet-service
    HTTP（core JSON-RPC 无 sendRawTransaction——架构决策：签名/密钥管控
    集中在 wallet-service，SDK 不持私钥）
  - 跨链桥：nexus_getCrossChainTransactions（近 200 区块 BRIDGE_* 推导）
  - 地址校验：Base58 + 25 字节（1 版本 + 20 哈希 + 4 keccak 双哈希校验尾）

零第三方依赖（urllib + 标准库）。Python >= 3.10。

Example:
    >>> from nexus_sdk import Client
    >>> client = Client(rpc_url="http://127.0.0.1:19585/rpc")
    >>> client.wallet.get_balance("1L3zk...")
    123456789
"""

from .client import Client
from .address import validate_address

__all__ = ["Client", "validate_address"]
__version__ = "1.0.0"
