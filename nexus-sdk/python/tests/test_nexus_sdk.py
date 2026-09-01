"""nexus_sdk 单测（真实信封契约 + keccak 权威向量）。

用 http.server 起本地 JSON-RPC mock（回 JsonRpcController 真实信封形状），
不依赖任何第三方框架（无 pytest/requests——SDK 本身零依赖，测试同样）。
"""

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from nexus_sdk import Client, validate_address
from nexus_sdk.address import base58_decode, keccak256
from nexus_sdk.client import RPCError


class FakeCore(BaseHTTPRequestHandler):
    """模拟 nexus-core JSON-RPC——真实信封形状（数值/十进制字符串）。"""

    requests = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeCore.requests.append(body)
        resp = {"jsonrpc": "2.0", "id": body.get("id")}

        method = body.get("method")
        if method == "nexus_getBalance":
            resp["result"] = {"balance": "123456789"}
        elif method == "nexus_getTransactionCount":
            resp["result"] = {"count": 7}
        elif method == "nexus_getNodeStatus":
            resp["result"] = {
                "chainId": 31337, "latestHeight": 100, "latestHash": "ab12",
                "syncing": False, "peers": 0, "version": "v2-rpc-bridge",
            }
        elif method == "nexus_getTransactionsByAddress":
            resp["result"] = [
                {"txHash": "aa", "from": "f1", "to": "t1", "amount": "5",
                 "status": "success"},
            ]
        elif method == "nexus_getTransactionByHash":
            resp["result"] = {
                "txHash": "aa", "from": "f1", "to": "t1", "amount": "5",
                "status": "success",
            }
        elif method == "nexus_getLatestTransactions":
            resp["result"] = [{"txHash": "bb"}]
        elif method == "nexus_getCrossChainTransactions":
            resp["result"] = [
                {"bridgeTxId": "cc", "sourceChain": "eth",
                 "status": "confirmed"},
            ]
        elif method == "nexus_getLatestBlocks":
            resp["result"] = [{"height": 99, "hash": "hh"}]
        else:
            resp["error"] = {"code": -32601, "message": f"method not found: {method}"}

        out = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):  # 静默
        pass


class TestEnvelopes(unittest.TestCase):
    """对 mock core 的信封解码断言。"""

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeCore)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.client = Client(rpc_url=f"http://127.0.0.1:{cls.port}/rpc")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_balance_decimal_string_envelope(self):
        self.assertEqual(self.client.wallet.get_balance("any"), 123456789)

    def test_nonce_count_envelope(self):
        self.assertEqual(self.client.wallet.get_nonce("any"), 7)

    def test_node_status_numeric(self):
        self.assertEqual(self.client.get_block_number(), 100)
        self.assertEqual(self.client.get_chain_id(), 31337)

    def test_transactions(self):
        txs = self.client.wallet.get_transactions_by_address("f1", 10)
        self.assertEqual(len(txs), 1)
        self.assertEqual(txs[0]["txHash"], "aa")
        tx = self.client.transaction.get_transaction_by_hash("aa")
        self.assertEqual(tx["status"], "success")
        latest = self.client.transaction.get_latest_transactions(5)
        self.assertEqual(len(latest), 1)

    def test_bridge_list(self):
        cc = self.client.bridge.list(5)
        self.assertEqual(cc[0]["bridgeTxId"], "cc")

    def test_rpc_error_surfaces(self):
        with self.assertRaises(RPCError):
            self.client.rpc_call("nexus_nonexistent")

    def test_build_transfer_validates_address(self):
        with self.assertRaises(ValueError):
            self.client.transaction.build_transfer("bad!", "bad!", 1)

    def test_submit_requires_wallet_service_url(self):
        with self.assertRaises(ValueError):
            self.client.wallet.submit_transfer(
                "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk",
                "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk", 1)


class TestKeccak(unittest.TestCase):
    """keccak256 权威向量（Keccak-256，非 NIST SHA3-256）。"""

    def test_empty_vector(self):
        # Keccak-256("") = c5d246...a470（以太坊生态标准向量）
        self.assertEqual(
            keccak256(b"").hex(),
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
        )

    def test_abc_vector(self):
        # Keccak-256("abc") = 4e03657aea45a94f...（公开向量）
        self.assertEqual(
            keccak256(b"abc").hex()[:16],
            "4e03657aea45a94f",
        )

    def test_differs_from_nist_sha3(self):
        # NIST SHA3-256("") = a7ffc6f8bf1ed766...——必须不同（padding 01 vs 06）
        self.assertNotEqual(
            keccak256(b"").hex()[:8],
            "a7ffc6f8",
        )


class TestAddress(unittest.TestCase):
    """地址本地校验（对齐 Java KeystoreAction.verifyAddress 语义）。"""

    def test_golden_address_decodes_25_bytes(self):
        # Java KeystoreAction.main() 使用的真实地址——25 字节布局黄金证据
        decoded = base58_decode("1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk")
        self.assertEqual(len(decoded), 25)

    def test_invalid_base58_rejected(self):
        with self.assertRaises(ValueError):
            base58_decode("0OIl")  # 0/O/I 不在字母表

    def test_malformed_addresses(self):
        for bad in ["", "abc", "0x1234"]:
            self.assertFalse(validate_address(bad), bad)

    def test_self_consistent_address_round_trip(self):
        # 构造自洽地址：version 0x00 + 20 字节 hash + keccak² 前 4 字节
        pubkey_hash = bytes(range(1, 21))
        checksum = keccak256(keccak256(pubkey_hash))[:4]
        full = b"\x00" + pubkey_hash + checksum
        # base58 编码（与解码互逆）
        zeros = 0
        for b in full:
            if b != 0:
                break
            zeros += 1
        alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        n = int.from_bytes(full, "big")
        digits = ""
        while n > 0:
            n, r = divmod(n, 58)
            digits = alphabet[r] + digits
        addr = "1" * zeros + digits
        self.assertTrue(validate_address(addr), addr)
        # 篡改首字符后校验失败
        tampered = ("3" if addr[0] == "2" else "2") + addr[1:]
        self.assertFalse(validate_address(tampered))


if __name__ == "__main__":
    unittest.main()
