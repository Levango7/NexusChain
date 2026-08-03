"""
ConPay Python SDK

统一多语言 SDK 的 Python 实现，为 ConPay 区块链支付网络提供全栈访问能力。

代币符号：CPAY

Usage:
    from conpay import ConPayClient

    client = ConPayClient(
        network='mainnet',
        rpc_url='https://rpc.conpay.network',
    )
    wallet = client.wallet.create()
    balance = client.wallet.get_balance(wallet.address)
"""

from .client import ConPayClient
from .wallet import Wallet
from .transaction import TransactionManager

__version__ = "1.0.0"
__all__ = ["ConPayClient", "Wallet", "TransactionManager"]
