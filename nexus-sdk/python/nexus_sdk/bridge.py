"""NexusChain SDK 跨链桥模块。

nexus_getCrossChainTransactions：core 遍历近 200 区块体筛选 BRIDGE_* 交易
并解析 payload 还原跨链字段（bridgeTxId/sourceChain/targetChain/amount/
status/timestamp）。
"""

from typing import Any, Dict, List, Optional


class BridgeManager:
    """跨链桥查询。"""

    def __init__(self, client):
        self._client = client

    def list(self, limit: int = 20, status_filter: Optional[str] = None) -> List[Dict[str, Any]]:
        """跨链交易列表。

        Args:
            limit: 数量上限（服务端夹逼 1..100）
            status_filter: 可选状态过滤（"pending"/"confirmed"/...，
                服务端按 payload 还原的 status 字段匹配）
        """
        params: List[Any] = [limit]
        if status_filter:
            params.append(status_filter)
        result = self._client.rpc_call("nexus_getCrossChainTransactions", params)
        if not isinstance(result, list):
            raise ValueError(f"unexpected cross-chain envelope: {type(result)}")
        return [tx for tx in result if isinstance(tx, dict)]
