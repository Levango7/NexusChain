#!/usr/bin/env python3
"""Synthetic Nacos auth contract for init.sh; no Docker or real credentials."""

import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]


class FakeNacos:
    def __init__(self, reject_login=False):
        self.reject_login = reject_login
        self.password = secrets.token_hex(12)
        self.identity = secrets.token_hex(12)
        self.token = secrets.token_hex(24)
        self.admin_initialized = False
        self.namespaces = {"public": "Default Namespace"}
        self.configs = {}
        self.events = []
        self.authenticated_requests = 0
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format, *_args):
                pass

            def json_response(self, status, payload):
                body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def fields(self):
                length = int(self.headers.get("Content-Length", "0"))
                return parse_qs(self.rfile.read(length).decode("utf-8"), keep_blank_values=True)

            def authorized(self):
                if self.headers.get("Authorization") != f"Bearer {outer.token}":
                    self.json_response(403, {"status": 403, "message": "User not found"})
                    return False
                outer.authenticated_requests += 1
                return True

            def do_GET(self):
                parsed = urlparse(self.path)
                if parsed.path == "/nacos/v3/admin/core/state":
                    self.json_response(200, {"code": 0, "data": {
                        "auth_enabled": "true",
                        "auth_admin_request": "false" if outer.admin_initialized else "true",
                    }})
                    return
                if parsed.path == "/nacos/v3/admin/core/namespace/list":
                    if not self.authorized():
                        return
                    self.json_response(200, {"code": 0, "data": [
                        {"namespace": name, "namespaceShowName": description}
                        for name, description in outer.namespaces.items()
                    ]})
                    return
                if parsed.path == "/nacos/v3/admin/cs/config":
                    if not self.authorized():
                        return
                    query = parse_qs(parsed.query)
                    key = tuple(query.get(name, [""])[0] for name in ("dataId", "groupName", "namespaceId"))
                    if key not in outer.configs:
                        self.json_response(404, {"code": 404, "message": "config not found"})
                        return
                    self.json_response(200, {"code": 0, "data": outer.configs[key]})
                    return
                self.json_response(404, {"code": 404})

            def do_POST(self):
                parsed = urlparse(self.path)
                fields = self.fields()
                if parsed.path == "/nacos/v3/auth/user/admin":
                    outer.events.append("admin")
                    outer.admin_initialized = True
                    self.json_response(200, {"code": 0, "data": {
                        "username": "nacos", "password": outer.password,
                    }})
                    return
                if parsed.path == "/nacos/v3/auth/user/login":
                    outer.events.append("login")
                    valid = (fields.get("username") == ["nacos"]
                             and fields.get("password") == [outer.password]
                             and not outer.reject_login)
                    if not valid:
                        self.json_response(401, {"message": "bad credentials"})
                        return
                    self.json_response(200, {
                        "accessToken": outer.token, "tokenTtl": 18000, "globalAdmin": True,
                    })
                    return
                if parsed.path == "/nacos/v3/admin/core/namespace":
                    outer.events.append("namespace")
                    if not self.authorized():
                        return
                    name = fields.get("namespaceId", [""])[0]
                    if name in outer.namespaces:
                        self.json_response(400, {"code": 22002, "message": "namespace already exist"})
                        return
                    outer.namespaces[name] = fields.get("namespaceName", [""])[0]
                    self.json_response(200, {"code": 0, "data": True})
                    return
                if parsed.path == "/nacos/v3/admin/cs/config":
                    outer.events.append("config")
                    if not self.authorized():
                        return
                    key = tuple(fields.get(name, [""])[0] for name in ("dataId", "groupName", "namespaceId"))
                    outer.configs[key] = fields.get("content", [""])[0]
                    self.json_response(200, {"code": 0, "data": True})
                    return
                self.json_response(404, {"code": 404})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def run_init(self):
        env = os.environ.copy()
        env.update({
            "NACOS_ADMIN_PASSWORD": self.password,
            "NACOS_IDENTITY_KEY": "nexus-identity",
            "NACOS_IDENTITY_VALUE": self.identity,
        })
        try:
            return subprocess.run(
                ["bash", "nacos-config/init.sh", f"127.0.0.1:{self.server.server_port}"],
                cwd=ROOT, env=env, text=True, encoding="utf-8", errors="replace",
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60, check=False,
            )
        except subprocess.TimeoutExpired as error:
            raise AssertionError(f"init.sh timeout; events={self.events}; output={error.stdout}") from error


def main():
    with FakeNacos() as fake:
        first = fake.run_init()
        assert first.returncode == 0, first.stdout
        assert fake.events[0] == "admin", fake.events
        assert fake.events[1] == "login", fake.events
        assert fake.events[2] == "namespace", fake.events
        assert fake.authenticated_requests > 0
        second = fake.run_init()
        assert second.returncode == 0, second.stdout
        assert fake.events.count("admin") == 1, fake.events
        assert fake.events.count("login") == 2, fake.events
        print("PASS: admin bootstrap precedes login and all namespace/config requests; rerun is idempotent")

    with FakeNacos(reject_login=True) as fake:
        rejected = fake.run_init()
        assert rejected.returncode != 0, rejected.stdout
        assert "namespace" not in fake.events, fake.events
        assert "config" not in fake.events, fake.events
        assert fake.authenticated_requests == 0
        print("PASS: failed login aborts before any namespace/config write")


if __name__ == "__main__":
    main()
