"""NexusChain SDK 主客户端（真实 RPC 契约实现）。

信封形状对齐 nexus-core JsonRpcController（org.nexus.controller）：
  - nexus_getBalance          → {"balance": "<decimal string>"}
  - nexus_getTransactionCount → {"count": <long>}
  - nexus_getNodeStatus       → {chainId: <int>, latestHeight: <int>, ...}
  - nexus_getTransactionByHash → {txHash, from, to, amount, status, ...}
数值一律十进制（json number 或 decimal 字符串），没有 0x hex 前缀。
"""

import json
import urllib.request
import urllib.error
from typing import Any, Dict, List, Optional

from .wallet import WalletManager
from .transaction import TransactionManager
from .bridge import BridgeManager


class RPCError(Exception):
    """JSON-RPC 层错误（core 返回 error 信封）。"""


class Client:
    """NexusChain SDK 主客户端。

    Args:
        rpc_url: nexus-core JSON-RPC 端点（如 http://127.0.0.1:19585/rpc）
        wallet_service_url: nexus-wallet-service 基址（交易提交需要；
            仅查询可不填）
        timeout: HTTP 超时（秒），默认 30
        api_key: 可选 Bearer token
    """

    def __init__(
        self,
        rpc_url: str = "http://127.0.0.1:19585/rpc",
        wallet_service_url: str = "",
        timeout: int = 30,
        api_key: Optional[str] = None,
    ):
        if not rpc_url:
            raise ValueError("rpc_url is required")
        self.rpc_url = rpc_url
        self.wallet_service_url = wallet_service_url
        self.timeout = timeout
        self.api_key = api_key
        self._request_id = 0

        self.wallet = WalletManager(self)
        self.transaction = TransactionManager(self)
        self.bridge = BridgeManager(self)

    # ------------------------------------------------------------------
    # 底层 JSON-RPC
    # ------------------------------------------------------------------

    def rpc_call(self, method: str, params: Optional[List[Any]] = None) -> Any:
        """发送 JSON-RPC 请求，返回 result 字段。

        Raises:
            RPCError: JSON-RPC 层 error 信封
            urllib.error.HTTPError / URLError: 网络层失败
        """
        self._request_id += 1
        payload = json.dumps({
            "jsonrpc": "2.0",
            "method": method,
            "params": params or [],
            "id": self._request_id,
        }).encode("utf-8")

        req = urllib.request.Request(
            self.rpc_url, data=payload, method="POST",
            headers={"Content-Type": "application/json"},
        )
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")

        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            body = resp.read().decode("utf-8")

        data = json.loads(body)
        if data.get("error"):
            err = data["error"]
            raise RPCError(f"rpc error {err.get('code')}: {err.get('message')}")
        return data.get("result")

    # ------------------------------------------------------------------
    # 链查询
    # ------------------------------------------------------------------

    def get_node_status(self) -> Dict[str, Any]:
        """节点状态（chainId/latestHeight/latestHash/...，数值型）。"""
        result = self.rpc_call("nexus_getNodeStatus", [])
        if not isinstance(result, dict):
            raise RPCError(f"unexpected getNodeStatus envelope: {type(result)}")
        return result

    def get_block_number(self) -> int:
        """当前链高度（getNodeStatus.latestHeight，十进制数值）。"""
        return int(self.get_node_status().get("latestHeight", 0))

    def get_chain_id(self) -> int:
        """链 ID（getNodeStatus.chainId，十进制数值）。"""
        return int(self.get_node_status().get("chainId", 0))

    def get_block_by_height(self, height: int) -> Optional[Dict[str, Any]]:
        """按高度取块（nexus_getBlockByHeight）。"""
        result = self.rpc_call("nexus_getBlockByHeight", [height, True])
        if isinstance(result, dict):
            return result
        return None

    def get_latest_blocks(self, limit: int = 20) -> List[Dict[str, Any]]:
        """最新区块列表（nexus_getLatestBlocks，服务端夹逼 1..100）。"""
        result = self.rpc_call("nexus_getLatestBlocks", [limit])
        return [b for b in (result or []) if isinstance(b, dict)]
