"""
ConPay SDK 主客户端。

统一入口，聚合钱包管理、交易构造/签名/广播等全部能力。
代币符号：CPAY。
"""

import json
from typing import Optional, Any, Dict, List

import requests

from .wallet import Wallet
from .transaction import TransactionManager


class ConPayClient:
    """
    ConPay SDK 主客户端类。

    Args:
        network: 网络类型 ('mainnet' 或 'testnet')
        rpc_url: ConPay 节点 RPC 地址
        timeout: 请求超时时间（秒），默认 30
        api_key: API 密钥（可选，用于付费节点认证）

    Example:
        >>> client = ConPayClient(
        ...     network='mainnet',
        ...     rpc_url='https://rpc.conpay.network',
        ... )
        >>> wallet = client.wallet.create()
        >>> balance = client.wallet.get_balance(wallet.address)
    """

    def __init__(
        self,
        network: str = "mainnet",
        rpc_url: str = "https://rpc.conpay.network",
        timeout: int = 30,
        api_key: Optional[str] = None,
    ):
        self.network = network
        self.rpc_url = rpc_url
        self.timeout = timeout
        self.api_key = api_key
        self._request_id = 0

        # 初始化子模块
        self.wallet = Wallet(self)
        self.transaction = TransactionManager(self)

    def rpc_call(self, method: str, params: Optional[List[Any]] = None) -> Any:
        """
        发送 JSON-RPC 请求。

        Args:
            method: RPC 方法名
            params: 参数列表

        Returns:
            RPC 响应结果

        Raises:
            Exception: RPC 调用失败时抛出异常
        """
        self._request_id += 1
        payload = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params or [],
            "id": self._request_id,
        }

        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        response = requests.post(
            self.rpc_url,
            data=json.dumps(payload),
            headers=headers,
            timeout=self.timeout,
        )
        response.raise_for_status()

        data = response.json()
        if "error" in data and data["error"]:
            raise Exception(
                f"RPC Error {data['error'].get('code')}: {data['error'].get('message')}"
            )

        return data.get("result")

    def get_block_number(self) -> int:
        """查询当前区块高度。"""
        result = self.rpc_call("conpay_blockNumber")
        return int(result, 16) if isinstance(result, str) else int(result)

    def get_chain_id(self) -> int:
        """获取网络链 ID。"""
        result = self.rpc_call("conpay_chainId")
        return int(result, 16) if isinstance(result, str) else int(result)

    def get_block_by_hash(self, block_hash: str) -> Optional[Dict[str, Any]]:
        """根据 hash 获取区块信息。"""
        return self.rpc_call("conpay_getBlockByHash", [block_hash, True])

    def get_block_by_number(self, block_number: int) -> Optional[Dict[str, Any]]:
        """根据区块号获取区块信息。"""
        return self.rpc_call("conpay_getBlockByNumber", [hex(block_number), True])
