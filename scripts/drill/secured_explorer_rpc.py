#!/usr/bin/env python3
"""Loopback-only secured Explorer RPC drill with ephemeral credentials."""

import base64
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = ROOT / "docker-compose.yml"
PROJECT = f"nc-secured-rpc-{os.getpid()}"
APP_ID = secrets.token_hex(16)
APP_SECRET = secrets.token_hex(32)
RPC_TOKEN = ""


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def make_jwt():
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({
        "jti": "1", "iat": now, "sub": "JWTToken", "iss": "admin", "exp": now + 3600,
    }, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    key = base64.b64decode((APP_ID + APP_SECRET).encode("ascii"))
    signature = b64url(hmac.new(key, signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def sanitized(text):
    for secret in (APP_ID, APP_SECRET, RPC_TOKEN):
        if secret:
            text = text.replace(secret, "***")
    return text


def run(args, env, timeout=1800):
    result = subprocess.run(args, cwd=ROOT, env=env, text=True, encoding="utf-8",
                            errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(args[:5])}\n"
                           + sanitized(result.stdout[-8000:]))
    return result.stdout


def compose(env, *args, timeout=1800):
    return run(["docker", "compose", "-f", str(COMPOSE_FILE), "-p", PROJECT, *args], env, timeout)


def wait_healthy(env, container, deadline_seconds=180):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        result = subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
                                env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                check=False)
        if result.stdout.strip() == "healthy":
            return
        time.sleep(2)
    raise RuntimeError(f"container did not become healthy: {container}")


def loopback_request(port, path, method="GET", payload=None, token=None, timeout=5):
    """Only a fixed loopback host and an absolute path; no URL parsing or redirects."""
    if not 1 <= port <= 65535:
        raise ValueError("invalid loopback port")
    if not path.startswith("/") or path.startswith("//") or ".." in path or "://" in path:
        raise ValueError("invalid loopback path")
    headers = {}
    body = None
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["token"] = token
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        return response.status, response.read().decode("utf-8", errors="replace")
    finally:
        connection.close()


def wait_core(port, deadline_seconds=300):
    deadline = time.time() + deadline_seconds
    payload = {"jsonrpc": "2.0", "id": 1, "method": "nexus_getNodeStatus", "params": []}
    while time.time() < deadline:
        try:
            status, body = loopback_request(port, "/rpc", "POST", payload)
            if status == 401:
                return body
            if status == 200:
                raise RuntimeError("core accepted an unauthenticated RPC request while LOCALONLY=true")
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("secured core did not reach its expected 401 readiness state")


def wait_bff(port, deadline_seconds=180):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        try:
            status, _ = loopback_request(port, "/health")
            if status == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("BFF did not become healthy")


def main():
    global RPC_TOKEN
    RPC_TOKEN = make_jwt()
    pg_port = free_port()
    core_port = free_port()
    bff_port = free_port()
    env = os.environ.copy()
    env.update({
        "COMPOSE_PROJECT_NAME": PROJECT,
        "NEX_CONTAINER_PREFIX": PROJECT,
        "NEXUS_JWT_APP_ID": APP_ID,
        "NEXUS_JWT_APP_SECRET": APP_SECRET,
        "NEXUS_RPC_TOKEN": RPC_TOKEN,
    })
    try:
        run(["docker", "version", "--format", "{{.Server.Version}}"], env, timeout=30)
        compose(env, "run", "-d", "--name", f"{PROJECT}-pgsql", "--no-deps",
                "--use-aliases",
                "-p", f"127.0.0.1:{pg_port}:5432", "nexus-pgsql")
        wait_healthy(env, f"{PROJECT}-pgsql")
        compose(env, "build", "nexus-core", "nexus-explorer")
        compose(env, "run", "-d", "--name", f"{PROJECT}-core", "--no-deps",
                "--use-aliases",
                "-p", f"127.0.0.1:{core_port}:19585",
                "-e", "LOCALONLY=true", "-e", "NEXUS_JWT_APP_ID", "-e", "NEXUS_JWT_APP_SECRET",
                "nexus-core")
        wait_core(core_port)
        print("PASS: direct core RPC without token returns 401")
        compose(env, "run", "-d", "--name", f"{PROJECT}-bff", "--no-deps",
                "-p", f"127.0.0.1:{bff_port}:3000", "-e", "NEXUS_RPC_TOKEN", "nexus-explorer")
        wait_bff(bff_port)
        status, body = loopback_request(bff_port, "/api/node/status")
        if status != 200:
            raise RuntimeError(f"BFF secured RPC failed: HTTP {status}, body={body[:500]}")
        node = json.loads(body)
        if not isinstance(node.get("latestHeight"), int) or not isinstance(node.get("latestHash"), str):
            raise RuntimeError(f"unexpected node status shape: {body[:500]}")
        print(f"PASS: BFF to secured core RPC returns 200 (latestHeight={node['latestHeight']})")
        print("PASS: isolated compose project and ephemeral credentials will be removed")
    except Exception:
        try:
            diagnostics = run(["docker", "logs", "--tail", "120", f"{PROJECT}-core"], env, timeout=30)
            print(sanitized(diagnostics), file=sys.stderr)
        except Exception as diagnostic_error:
            print(f"WARNING: could not read core logs: {diagnostic_error}", file=sys.stderr)
        raise
    finally:
        try:
            compose(env, "down", "-v", "--remove-orphans", timeout=180)
        except Exception as cleanup_error:
            print(f"WARNING: cleanup failed: {sanitized(str(cleanup_error))}", file=sys.stderr)


if __name__ == "__main__":
    main()
