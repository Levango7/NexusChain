#!/usr/bin/env python3
"""Deterministic read-only chain RPC stub for isolated performance drills."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass

    def reply(self, status, payload):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/height":
            self.reply(200, {"code": 0, "data": 1})
            return
        if path == "/transactionConfirmed":
            self.reply(200, {"code": 0, "data": 2000})
            return
        if path.startswith("/rpc/v1/transaction/") and path.endswith("/status"):
            self.reply(200, {"code": 0, "data": {
                "status": "CONFIRMED", "confirmations": 12, "block_height": 1,
            }})
            return
        if path.startswith("/rpc/v1/finality/epoch/"):
            self.reply(200, {"code": 0, "data": {"finality_status": "NOT_ACTIVE"}})
            return
        if path.startswith("/rpc/v1/transaction/"):
            self.reply(200, {"code": 0, "data": {
                "status": "CONFIRMED", "confirmed": True, "confirmations": 12, "block_height": 1,
            }})
            return
        self.reply(404, {"code": 404, "message": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/sendNonce":
            self.reply(200, {"code": 0, "data": 0})
            return
        if path == "/sendTransaction":
            self.reply(200, {"code": 2000, "data": True})
            return
        self.reply(404, {"code": 404, "message": "not found"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 19585), Handler).serve_forever()
