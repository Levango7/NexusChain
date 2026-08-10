"""
ConPay SDK 交易模块。

提供交易构建、签名、序列化和广播能力。
支持 CPAY 原生转账及合约调用。
"""

from typing import Optional, Dict, Any
from dataclasses import dataclass, field


@dataclass
class Transaction:
    """交易对象。"""

    from_addr: str
    to: str
    value: str
    token: str = "CPAY"
    gas_limit: Optional[str] = None
    gas_price: Optional[str] = None
    nonce: Optional[str] = None
    data: Optional[str] = None


@dataclass
class TransactionReceipt:
    """交易回执。"""

    transaction_hash: str
    block_hash: str
    block_number: int
    status: str  # "success" 或 "failed"
    gas_used: str
    logs: list = field(default_factory=list)


class TransactionManager:
    """交易管理器。

    Args:
        client: ConPayClient 实例
    """

    def __init__(self, client):
        self._client = client

    def build_transfer(
        self,
        from_addr: str,
        to: str,
        amount: str,
        token: str = "CPAY",
    ) -> Transaction:
        """
        构建 CPAY 原生转账交易。

        Args:
            from_addr: 发送方地址
            to: 接收方地址
            amount: 转账金额（最小单位）
            token: 代币符号

        Returns:
            未签名的 Transaction 对象
        """
        # TODO: 查询 nonce 和 gas price
        nonce = self._client.rpc_call("conpay_getTransactionCount", [from_addr, "latest"])
        gas_price = self._client.rpc_call("conpay_gasPrice", [])

        return Transaction(
            from_addr=from_addr,
            to=to,
            value=amount,
            token=token,
            nonce=nonce,
            gas_price=gas_price,
        )

    def build_contract_call(
        self,
        from_addr: str,
        contract_address: str,
        data: str,
        value: Optional[str] = None,
    ) -> Transaction:
        """
        构建合约调用交易。

        Args:
            from_addr: 发送方地址
            contract_address: 合约地址
            data: 调用数据（ABI 编码）
            value: 附带的 CPAY 金额

        Returns:
            未签名的 Transaction 对象
        """
        # TODO: 构建合约调用交易
        raise NotImplementedError("Not yet implemented")

    def sign(self, tx: Transaction, private_key: str) -> str:
        """
        对交易进行签名。

        Args:
            tx: 交易对象
            private_key: 签名私钥（十六进制）

        Returns:
            已签名的交易序列化字符串
        """
        # TODO: 使用私钥签名交易
        raise NotImplementedError("Not yet implemented")

    def broadcast(self, signed_tx: str) -> str:
        """
        广播已签名的交易到网络。

        Args:
            signed_tx: 已签名的交易序列化字符串

        Returns:
            交易哈希
        """
        return self._client.rpc_call("conpay_sendRawTransaction", [signed_tx])

    def get_transaction_receipt(self, tx_hash: str) -> Optional[Dict[str, Any]]:
        """
        查询交易状态。

        Args:
            tx_hash: 交易哈希

        Returns:
            交易回执字典，或 None（如果交易未确认）
        """
        return self._client.rpc_call("conpay_getTransactionReceipt", [tx_hash])

    def estimate_gas(self, tx: Transaction) -> str:
        """
        估算交易所需的 Gas。

        Args:
            tx: 交易对象

        Returns:
            Gas 估算值（十六进制）
        """
        # TODO: 构建估算请求
        raise NotImplementedError("Not yet implemented")

    def get_gas_price(self) -> str:
        """
        获取当前 Gas 价格。

        Returns:
            Gas 价格（wei，十六进制）
        """
        return self._client.rpc_call("conpay_gasPrice", [])
