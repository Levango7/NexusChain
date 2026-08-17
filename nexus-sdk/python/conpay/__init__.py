"""
ConPay Python SDK (deprecated 别名，新代码请使用 nexus 包)

统一多语言 SDK 的 Python 实现，为 NexusChain 区块链支付网络提供全栈访问能力。

代币符号：NEX

Usage:
    from conpay import ConPayClient

    client = ConPayClient(
        network='mainnet',
        rpc_url='https://rpc.nexus.network',
    )
    wallet = client.wallet.create()
    balance = client.wallet.get_balance(wallet.address)
"""

from .client import ConPayClient
from .wallet import Wallet
from .transaction import TransactionManager

# 兼容别名：NexusClient = ConPayClient（待发布 nexus 包后正式切换）
NexusClient = ConPayClient

__version__ = "1.1.0"
__all__ = ["ConPayClient", "NexusClient", "Wallet", "TransactionManager"]
