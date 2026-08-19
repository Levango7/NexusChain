#!/usr/bin/env python3
"""k6 压测 mock 服务器 — 模拟 nexus-gateway API 端点"""
import json
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading
import time
import uuid

class MockHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/actuator/health":
            self._respond(200, {"status": "UP"})
        elif self.path.startswith("/api/v1/payments/"):
            pay_id = self.path.split("/")[-1]
            self._respond(200, {"id": pay_id, "status": "SUCCEEDED"})
        else:
            self._respond(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/api/v1/payments":
            pay_id = "pay_" + uuid.uuid4().hex[:16]
            self._respond(201, {"id": pay_id, "status": "PENDING"})
        elif self.path == "/api/v1/bridge/lock":
            self._respond(201, {"lockId": "lock_" + uuid.uuid4().hex[:16], "status": "LOCKED"})
        elif "/confirm" in self.path:
            self._respond(200, {"status": "CONFIRMED"})
        else:
            self._respond(404, {"error": "not found"})

    def _respond(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        pass  # 静默日志

def run_server(port=8080):
    server = HTTPServer(("127.0.0.1", port), MockHandler)
    print(f"Mock server on :{port}")
    server.serve_forever()

if __name__ == "__main__":
    run_server()