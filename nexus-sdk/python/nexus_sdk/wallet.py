"""NexusChain SDK 钱包模块（真实信封契约）。

余额/nonce/交易历史查询走 nexus-core JSON-RPC；交易提交走
nexus-wallet-service HTTP（core 无 nexus_sendRawTransaction——签名与
密钥管控是 wallet-service 的架构职责，SDK 不持私钥）。
"""

import json
import urllib.request
from typing import Any, Dict, List, Optional

from .address import validate_address


class WalletManager:
    """钱包查询与交易提交管理器。"""

    def __init__(self, client):
        self._client = client

    # ------------------------------------------------------------------
    # 查询（nexus-core JSON-RPC）
    # ------------------------------------------------------------------

    def get_balance(self, address: str) -> int:
        """NEX 余额（最小单位）。

        Envelope: nexus_getBalance → {"balance": "<decimal string>"}
        """
        result = self._client.rpc_call("nexus_getBalance", [address])
        if not isinstance(result, dict) or "balance" not in result:
            raise ValueError(f"unexpected balance envelope: {result!r}")
        return int(result["balance"])

    def get_nonce(self, address: str) -> int:
        """下一 nonce。

        Envelope: nexus_getTransactionCount → {"count": <int>}
        """
        result = self._client.rpc_call("nexus_getTransactionCount", [address])
        if not isinstance(result, dict) or "count" not in result:
            raise ValueError(f"unexpected count envelope: {result!r}")
        return int(result["count"])

    def get_transactions_by_address(self, address: str, limit: int = 20) -> List[Dict[str, Any]]:
        """按地址查交易（nexus_getTransactionsByAddress → dict[]）。"""
        result = self._client.rpc_call("nexus_getTransactionsByAddress", [address, limit])
        if not isinstance(result, list):
            raise ValueError(f"unexpected transaction list envelope: {type(result)}")
        return [tx for tx in result if isinstance(tx, dict)]

    # ------------------------------------------------------------------
    # 提交（nexus-wallet-service HTTP）
    # ------------------------------------------------------------------

    def submit_transfer(self, from_addr: str, to: str, amount: int) -> str:
        """通过 wallet-service 签名并提交转账，返回交易哈希。

        Requires Client(wallet_service_url=...)；本地先做地址格式校验
        （Base58 + 25 字节 + keccak 双哈希校验尾）。
        """
        if not self._client.wallet_service_url:
            raise ValueError(
                "wallet_service_url is required for submit_transfer "
                "(submission goes through nexus-wallet-service, not core JSON-RPC)"
            )
        if not validate_address(from_addr):
            raise ValueError(f"invalid from address: {from_addr!r}")
        if not validate_address(to):
            raise ValueError(f"invalid to address: {to!r}")

        payload = json.dumps({
            "from": from_addr, "to": to, "amount": str(amount), "token": "NEX",
        }).encode("utf-8")
        req = urllib.request.Request(
            self._client.wallet_service_url.rstrip("/") + "/api/v1/transfers",
            data=payload, method="POST",
            headers={"Content-Type": "application/json"},
        )
        if self._client.api_key:
            req.add_header("Authorization", f"Bearer {self._client.api_key}")

        with urllib.request.urlopen(req, timeout=self._client.timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))

        for key_path in (("txHash",), ("hash",), ("data", "txHash")):
            node: Any = body
            for k in key_path:
                if isinstance(node, dict) and k in node:
                    node = node[k]
                else:
                    node = None
                    break
            if isinstance(node, str) and node:
                return node
        raise ValueError(f"wallet-service response missing tx hash: {body!r}")
