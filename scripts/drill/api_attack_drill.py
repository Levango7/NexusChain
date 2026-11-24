#!/usr/bin/env python3
"""Bounded real-HTTP attack battery against an isolated gateway."""

import base64
import concurrent.futures
import hashlib
import hmac
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("perf_business", ROOT / "scripts" / "drill" / "perf_business_drill.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.PROJECT = f"nc-attack-{os.getpid()}"
base.JWT_SECRET = secrets.token_urlsafe(48)
base.REQUEST_SIGNING_SECRET = secrets.token_urlsafe(48)
base.API_KEY = ""
base.SIGNING_SECRET = ""
API_KEY = ""
SIGNING_SECRET = ""


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def auth_headers(method, path, body, nonce=None):
    timestamp = str(int(time.time() * 1000))
    nonce = nonce or f"attack-{uuid.uuid4().hex}"
    field = lambda value: f"{len(value.encode('utf-8'))}:{value}|"
    payload = "NXC2|" + field(timestamp) + field(nonce) + field(method) + field(path) + field(body)
    signature = "v2:" + hmac.new(SIGNING_SECRET.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "X-NexusChain-ApiKey": API_KEY,
        "X-NexusChain-Timestamp": timestamp,
        "X-NexusChain-Nonce": nonce,
        "X-NexusChain-Signature": signature,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }


def post(port, path, body, nonce=None, raw=False):
    if not path.startswith("/") or path.startswith("//") or ".." in path or "://" in path:
        raise ValueError("invalid loopback path")
    payload = body if raw else json.dumps(body, separators=(",", ":"), ensure_ascii=False)
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try:
        connection.request("POST", path, body=payload, headers=auth_headers("POST", path, payload, nonce))
        response = connection.getresponse()
        text = response.read().decode("utf-8", errors="replace")
        lowered = text.lower()
        if any(marker in lowered for marker in ("caused by", "stacktrace", "at org.nexus", "sqlstate", "jdbc")):
            raise RuntimeError(f"response leaks implementation details: {text[:500]}")
        if response.status >= 500:
            raise RuntimeError(f"attack payload produced {response.status}: {text[:500]}")
        return response.status, text
    finally:
        connection.close()


def payment_body(description, request_id):
    return {
        "merchant_id": 1,
        "amount": 1,
        "currency": "NEX",
        "description": description,
        "notify_url": "https://perf.example.com/webhooks/nexus",
        "metadata": "{\"source\":\"attack-drill\"}",
        "request_id": request_id,
        "routing": {"preferred_connector": "mock"},
    }


def main():
    global API_KEY, SIGNING_SECRET
    gateway_port = base.free_port()
    env = os.environ.copy()
    env.update({
        "COMPOSE_PROJECT_NAME": base.PROJECT,
        "NEX_CONTAINER_PREFIX": base.PROJECT,
        "NEX_DEV_NACOS_ENABLED": "false",
        "NEX_DEV_JWT_SECRET": base.JWT_SECRET,
        "NEXUS_REQUEST_SIGNING_SECRET": base.REQUEST_SIGNING_SECRET,
    })
    try:
        base.run(["docker", "version", "--format", "{{.Server.Version}}"], env, timeout=30)
        base.compose(env, "build", "nexus-gateway", timeout=2400)
        base.run(["docker", "network", "create", f"{base.PROJECT}_default"], env, timeout=30)
        base.run([
            "docker", "run", "-d", "--name", f"{base.PROJECT}-core", "--network", f"{base.PROJECT}_default",
            "--network-alias", "nexus-core", "-v", f"{ROOT}:/workspace", "-w", "/workspace",
            "python:3.14-alpine", "python", "-I", "scripts/drill/fake_chain_node.py",
        ], env, timeout=30)
        base.compose(
            env, "run", "-d", "--name", f"{base.PROJECT}-gateway", "--no-deps", "--use-aliases",
            "-p", f"127.0.0.1:{gateway_port}:8080",
            "-e", "NEX_DEV_NACOS_ENABLED=false", "-e", f"JWT_SECRET={base.JWT_SECRET}",
            "-e", f"NEXUS_REQUEST_SIGNING_SECRET={base.REQUEST_SIGNING_SECRET}", "nexus-gateway",
        )
        base.assert_no_log_mounts(env)
        base.wait_gateway(gateway_port)
        base.seed_credentials(env)
        API_KEY = base.API_KEY
        SIGNING_SECRET = base.SIGNING_SECRET
        print("PASS: isolated gateway and seeded API credentials", flush=True)

        baseline = payment_body("baseline", f"baseline-{uuid.uuid4().hex}")
        status, body = post(gateway_port, "/api/v1/payments", baseline)
        if status != 201:
            raise RuntimeError(f"baseline payment failed: {status} {body[:500]}")
        print("PASS: valid signed baseline payment returns 201", flush=True)

        same = payment_body("concurrent-idempotency", f"race-{uuid.uuid4().hex}")
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: post(gateway_port, "/api/v1/payments", same), range(16)))
        payment_ids = set()
        for status, body in results:
            if status == 201:
                payload = json.loads(body)
                if payload.get("id"):
                    payment_ids.add(payload["id"])
            elif status != 409:
                raise RuntimeError(f"unexpected concurrent status {status}: {body[:500]}")
        if len(payment_ids) != 1:
            raise RuntimeError(f"expected one payment id, got {payment_ids}")
        print(f"PASS: 16 concurrent same-request_id calls collapse to one payment ({payment_ids})", flush=True)

        replay_body = payment_body("replay", f"replay-{uuid.uuid4().hex}")
        raw = json.dumps(replay_body, separators=(",", ":"), ensure_ascii=False)
        headers = auth_headers("POST", "/api/v1/payments", raw)
        nonce = headers["X-NexusChain-Nonce"]
        post(gateway_port, "/api/v1/payments", replay_body, nonce=nonce)
        replay_status, replay_text = post(gateway_port, "/api/v1/payments", replay_body, nonce=nonce)
        if replay_status != 401 or "40106" not in replay_text:
            raise RuntimeError(f"valid-signature replay was not rejected as 40106: {replay_status} {replay_text[:500]}")
        print("PASS: exact signature+nonce replay is rejected as 40106", flush=True)

        attack_texts = [
            "' OR 1=1--",
            "<img src=x onerror=\"window.__xssExecuted=true\">",
            "line1\r\n forged-alert",
        ]
        for text in attack_texts:
            status, body = post(gateway_port, "/api/v1/payments", payment_body(text, f"text-{uuid.uuid4().hex}"))
            if status != 201:
                raise RuntimeError(f"text payload was not safely contained: {status} {body[:500]}")
        print("PASS: SQLi/XSS/CRLF text cannot escape API contracts or leak stack traces", flush=True)

        malformed_status, _ = post(gateway_port, "/api/v1/payments", "{not-json", raw=True)
        if malformed_status != 400:
            raise RuntimeError(f"malformed JSON expected 400, got {malformed_status}")
        print("PASS: malformed JSON is rejected as 400, not 500", flush=True)

        oversized_status, _ = post(gateway_port, "/api/v1/payments", payment_body("x" * 200_000, f"large-{uuid.uuid4().hex}"))
        if oversized_status not in (201, 400, 413):
            raise RuntimeError(f"oversized payload produced unexpected status {oversized_status}")
        print(f"PASS: oversized payload is bounded by the API contract (HTTP {oversized_status})", flush=True)

        at_limit_status, at_limit_body = post(
            gateway_port, "/api/v1/payments", payment_body("x" * 512, f"limit-{uuid.uuid4().hex}"))
        if at_limit_status != 201:
            raise RuntimeError(f"512-character description was rejected: {at_limit_status} {at_limit_body[:300]}")
        field_limits = {
            "currency": 16, "description": 512, "notify_url": 512, "metadata": 1024,
            "request_id": 128, "payer_address": 128, "payee_address": 128,
        }
        for field, limit in field_limits.items():
            payload = payment_body("boundary", f"boundary-{field}-{uuid.uuid4().hex}")
            payload[field] = "x" * (limit + 1)
            status, body = post(gateway_port, "/api/v1/payments", payload)
            expected = f"{field} must not exceed {limit} characters"
            if status != 400 or expected not in body:
                raise RuntimeError(f"{field} boundary was not enforced: {status} {body[:300]}")
        print("PASS: all seven persisted text fields enforce exact database boundaries", flush=True)

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            flood_results = list(pool.map(
                lambda index: post(gateway_port, "/api/v1/payments", payment_body("flood", f"flood-{index}-{uuid.uuid4().hex}")),
                range(320),
            ))
        statuses = [status for status, _ in flood_results]
        if 429 not in statuses:
            raise RuntimeError(f"expected rate limiting, got {sorted(set(statuses))}")
        print(f"PASS: bounded flood triggers 429 without 5xx (counts={dict((s, statuses.count(s)) for s in sorted(set(statuses)))})", flush=True)

        time.sleep(61)
        recovery_status, recovery_body = post(gateway_port, "/api/v1/payments", payment_body("recovery", f"recovery-{uuid.uuid4().hex}"))
        if recovery_status != 201:
            raise RuntimeError(f"service did not recover after rate-limit window: {recovery_status} {recovery_body[:500]}")
        print("PASS: service accepts new payments after the rate-limit window", flush=True)
    except Exception:
        try:
            logs = base.run(["docker", "logs", "--tail", "120", f"{base.PROJECT}-gateway"], env, timeout=30)
            print(base.sanitized(logs), file=sys.stderr)
        except Exception as diagnostic_error:
            print(f"WARNING: could not read gateway logs: {diagnostic_error}", file=sys.stderr)
        raise
    finally:
        # compose run 创建的单次容器不一定被 down 收编；先解绑端点，才能删除项目网络。
        for suffix in ("core", "gateway"):
            subprocess.run(["docker", "rm", "-f", f"{base.PROJECT}-{suffix}"], env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        try:
            base.compose(env, "down", "-v", "--remove-orphans", timeout=180)
        except Exception as cleanup_error:
            print(f"WARNING: cleanup failed: {base.sanitized(str(cleanup_error))}", file=sys.stderr)
        for suffix in ("default", "nexus-net"):
            subprocess.run(["docker", "network", "rm", f"{base.PROJECT}_{suffix}"], env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
