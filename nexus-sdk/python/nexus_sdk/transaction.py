"""NexusChain SDK 交易模块（真实信封契约）。

构建转账（nonce 取自 nexus_getTransactionCount 真实信封）、查询确认交易。
签名/广播不是本模块职责——走 wallet-service（见 WalletManager.submit_transfer）。
"""

from typing import Any, Dict, List, Optional

from .address import validate_address


class TransferRequest:
    """未签名的 NEX 转账（字段来自真实链上查询）。"""

    def __init__(self, from_addr: str, to: str, amount: int, nonce: int):
        self.from_addr = from_addr
        self.to = to
        self.amount = amount
        self.nonce = nonce

    def __repr__(self) -> str:  # pragma: no cover — debug 辅助
        return (f"TransferRequest(from={self.from_addr!r}, to={self.to!r}, "
                f"amount={self.amount}, nonce={self.nonce})")


class TransactionManager:
    """交易构建与查询。"""

    def __init__(self, client):
        self._client = client

    def build_transfer(self, from_addr: str, to: str, amount: int) -> TransferRequest:
        """构建转账：nonce 实时取链（{"count":N} 信封）。

        本地先校验地址格式（Base58 + 25 字节 + keccak 校验尾）。
        """
        if not validate_address(from_addr):
            raise ValueError(f"invalid from address: {from_addr!r}")
        if not validate_address(to):
            raise ValueError(f"invalid to address: {to!r}")
        nonce = self._client.wallet.get_nonce(from_addr)
        return TransferRequest(from_addr, to, amount, nonce)

    def get_transaction_by_hash(self, tx_hash: str) -> Optional[Dict[str, Any]]:
        """查确认交易（nexus_getTransactionByHash）。

        注意这不是回执：core RPC 只返回已上链交易（status 恒 "success"）。
        """
        result = self._client.rpc_call("nexus_getTransactionByHash", [tx_hash])
        if isinstance(result, dict):
            return result
        return None

    def get_latest_transactions(self, limit: int = 20) -> List[Dict[str, Any]]:
        """最新交易列表（nexus_getLatestTransactions，服务端夹逼 1..100）。"""
        result = self._client.rpc_call("nexus_getLatestTransactions", [limit])
        if not isinstance(result, list):
            raise ValueError(f"unexpected list envelope: {type(result)}")
        return [tx for tx in result if isinstance(tx, dict)]
