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
        rpc_url: NexusChain 节点 RPC 地址
        timeout: 请求超时时间（秒），默认 30
        api_key: API 密钥（可选，用于付费节点认证）

    Example:
        >>> client = ConPayClient(
        ...     network='mainnet',
        ...     rpc_url='https://rpc.nexus.network',
        ... )
        >>> wallet = client.wallet.create()
        >>> balance = client.wallet.get_balance(wallet.address)
    """

    def __init__(
        self,
        network: str = "mainnet",
        rpc_url: str = "https://rpc.nexus.network",
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
        """查询当前区块高度。

        兼容实现：nexus-core 未提供 nexus_blockNumber，改为调用
        nexus_getLatestBlocks 取最新区块列表中的第一个区块高度。
        """
        result = self.rpc_call("nexus_getLatestBlocks", [1])
        if isinstance(result, list):
            if not result:
                return 0
            first = result[0]
            if isinstance(first, dict):
                height = first.get("height", first.get("number", 0))
                return int(height, 16) if isinstance(height, str) else int(height)
            return int(first, 16) if isinstance(first, str) else int(first)
        return int(result, 16) if isinstance(result, str) else int(result)

    def get_chain_id(self) -> int:
        """获取网络链 ID。

        兼容实现：nexus-core 未提供 nexus_chainId，改为调用
        nexus_getNodeStatus 从节点状态中获取 chainId 字段。
        """
        result = self.rpc_call("nexus_getNodeStatus", [])
        if isinstance(result, dict):
            cid = result.get("chainId", result.get("chain_id"))
            if cid is not None:
                return int(cid, 16) if isinstance(cid, str) else int(cid)
        return int(result, 16) if isinstance(result, str) else int(result)

    def get_block_by_hash(self, block_hash: str) -> Optional[Dict[str, Any]]:
        """根据 hash 获取区块信息。

        注意：nexus-core 当前未提供 nexus_getBlockByHash，保留接口以兼容旧 SDK 用户。
        实际应通过 nexus_getBlockByHeight 配合索引服务使用。
        """
        raise NotImplementedError(
            "nexus_getBlockByHash not supported by nexus-core; "
            "use get_block_by_number instead"
        )

    def get_block_by_number(self, block_number: int) -> Optional[Dict[str, Any]]:
        """根据区块号获取区块信息。

        对齐 nexus-core：nexus_getBlockByNumber → nexus_getBlockByHeight。
        """
        return self.rpc_call("nexus_getBlockByHeight", [block_number, True])
