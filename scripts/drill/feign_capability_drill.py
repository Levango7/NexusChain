#!/usr/bin/env python3
"""Isolated Nacos/Feign capability drill with ephemeral credentials.

The default mode requires the gateway's signingServiceHealth component to be UP.
--expect legacy-down is only for reproducing the historical missing-READ default.
This is a real Docker service-discovery drill, not a Kubernetes or production claim.
"""

import argparse
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
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = ROOT / "docker-compose.yml"
PROJECT = f"nc-feign-{os.getpid()}"
JWT_SECRET = secrets.token_urlsafe(48)
NACOS_AUTH_TOKEN = base64.b64encode(secrets.token_bytes(48)).decode("ascii")
NACOS_IDENTITY_VALUE = secrets.token_urlsafe(32)
NACOS_ADMIN_PASSWORD = os.environ.get("NACOS_ADMIN_PASSWORD") or ("Nc" + secrets.token_hex(8) + "Aa")
MPC_KEK = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
NACOS_IDENTITY_KEY = "nexus-identity"


def b64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def service_jwt(roles):
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({
        "sub": "nexus-gateway", "iat": now, "exp": now + 300, "roles": roles,
    }, separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")
    signature = b64url(hmac.new(JWT_SECRET.encode("utf-8"), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{signature}"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def sanitized(text):
    for secret in (JWT_SECRET, NACOS_AUTH_TOKEN, NACOS_IDENTITY_VALUE, NACOS_ADMIN_PASSWORD, MPC_KEK):
        if secret:
            text = text.replace(secret, "***")
    return text


def run(args, env, timeout=1800):
    result = subprocess.run(
        args, cwd=ROOT, env=env, text=True, encoding="utf-8", errors="replace",
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): {' '.join(args[:5])}\n"
            + sanitized(result.stdout[-10000:])
        )
    return result.stdout


def compose(env, *args, timeout=1800):
    return run(["docker", "compose", "-f", str(COMPOSE_FILE), "-p", PROJECT, *args], env, timeout)


def loopback_request(port, path, method="GET", headers=None, body=None, timeout=5):
    """Only a fixed loopback host and an internally constructed absolute path."""
    if not 1 <= port <= 65535:
        raise ValueError("invalid loopback port")
    if not path.startswith("/") or path.startswith("//") or ".." in path or "://" in path:
        raise ValueError("invalid loopback path")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.read().decode("utf-8", errors="replace")
    finally:
        connection.close()


def identity_headers():
    return {NACOS_IDENTITY_KEY: NACOS_IDENTITY_VALUE}


def wait_nacos(port, deadline_seconds=180):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        try:
            status, _ = loopback_request(port, "/nacos/v3/admin/core/state", headers=identity_headers())
            if status == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("Nacos did not become ready")


def wait_capability(port, deadline_seconds=300):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        try:
            status, _ = loopback_request(port, "/api/v1/transfers/capability")
            if status in (401, 403):
                return status
            if status == 200:
                raise RuntimeError("anonymous capability request was accepted")
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    raise RuntimeError("signing-service did not reach its protected readiness state")


def nacos_instances(port, service_name):
    query = urllib.parse.urlencode({
        "serviceName": service_name,
        "namespaceId": "dev",
        "groupName": "NEXUS_GROUP",
    })
    status, body = loopback_request(
        port, f"/nacos/v1/ns/instance/list?{query}", headers=identity_headers(),
    )
    if status != 200:
        raise RuntimeError(f"Nacos instance query returned HTTP {status}: {body[:500]}")
    payload = json.loads(body)
    hosts = payload.get("hosts")
    if not isinstance(hosts, list):
        raise RuntimeError(f"Nacos instance response has no hosts array: {body[:500]}")
    return hosts


def wait_registry(port, service_name, deadline_seconds=180):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        try:
            hosts = nacos_instances(port, service_name)
            if hosts:
                return hosts
        except (RuntimeError, OSError, http.client.HTTPException, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise RuntimeError(f"Nacos has no healthy instance for {service_name}")


def gateway_component(port):
    return loopback_request(port, "/actuator/health")


def wait_gateway_component(port, expected, deadline_seconds=300):
    deadline = time.time() + deadline_seconds
    last = None
    while time.time() < deadline:
        try:
            http_status, body = gateway_component(port)
            payload = json.loads(body)
            components = payload.get("components") or {}
            component = components.get("signingService") or components.get("signingServiceHealth") or {}
            component_status = component.get("status")
            last = (http_status, component_status, body)
            wanted = "DOWN" if expected == "legacy-down" else "UP"
            if component_status == wanted:
                return component_status, body
        except (OSError, http.client.HTTPException, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise RuntimeError(f"gateway signingServiceHealth did not become {expected}: {last}")


def assert_mounts_absent(env, container, forbidden):
    output = run(["docker", "inspect", container], env, timeout=30)
    mounts = json.loads(output)[0].get("Mounts") or []
    destinations = {mount.get("Destination") for mount in mounts}
    present = destinations & set(forbidden)
    if present:
        raise RuntimeError(f"container {container} has forbidden mounts: {sorted(present)}")


def assert_container_env(env, container, expected):
    output = run(["docker", "inspect", "--format", "{{json .Config.Env}}", container], env, timeout=30)
    actual = {}
    for item in json.loads(output):
        key, separator, value = item.partition("=")
        if separator:
            actual[key] = value
    for key, expected_value in expected.items():
        if actual.get(key) != expected_value:
            raise RuntimeError(f"container {container} has an unexpected value for {key}")


def nacos_init(env):
    return run([
        "docker", "exec",
        "-e", f"NACOS_ADMIN_PASSWORD={NACOS_ADMIN_PASSWORD}",
        "-e", f"NACOS_IDENTITY_KEY={NACOS_IDENTITY_KEY}",
        "-e", f"NACOS_IDENTITY_VALUE={NACOS_IDENTITY_VALUE}",
        f"{PROJECT}-nacos", "bash", "/tmp/nacos-config/init.sh", "127.0.0.1:8848",
    ], env, timeout=120)


def print_diagnostics(env):
    for name in ("nacos", "signing", "gateway"):
        try:
            logs = run(["docker", "logs", "--tail", "120", f"{PROJECT}-{name}"], env, timeout=30)
            print(f"--- {name} logs ---\n{sanitized(logs)}", file=sys.stderr)
        except Exception as error:
            print(f"WARNING: could not read {name} logs: {error}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expect", choices=("up", "legacy-down"), default="up")
    args = parser.parse_args()
    nacos_port = free_port()
    signing_port = free_port()
    gateway_port = free_port()
    env = os.environ.copy()
    env.pop("JWT_SERVICE_ROLES", None)
    env.update({
        "COMPOSE_PROJECT_NAME": PROJECT,
        "NEX_CONTAINER_PREFIX": PROJECT,
        "NACOS_AUTH_TOKEN": NACOS_AUTH_TOKEN,
        "NACOS_IDENTITY_KEY": NACOS_IDENTITY_KEY,
        "NACOS_IDENTITY_VALUE": NACOS_IDENTITY_VALUE,
        "NACOS_ADMIN_USER": "nacos",
        "NACOS_ADMIN_PASSWORD": NACOS_ADMIN_PASSWORD,
        "NEX_DEV_JWT_SECRET": JWT_SECRET,
        "NEXUS_MPC_KEK": MPC_KEK,
        "NEX_DEV_NACOS_ENABLED": "true",
    })
    try:
        run(["docker", "version", "--format", "{{.Server.Version}}"], env, timeout=30)
        compose(
            env, "run", "-d", "--name", f"{PROJECT}-nacos", "--no-deps", "--use-aliases",
            "-p", f"127.0.0.1:{nacos_port}:8848", "-e", f"NACOS_AUTH_IDENTITY_VALUE={NACOS_IDENTITY_VALUE}", "nacos",
        )
        wait_nacos(nacos_port)
        _, state_body = loopback_request(nacos_port, "/nacos/v3/admin/core/state", headers=identity_headers())
        print(f"NACOS_STATE: {sanitized(state_body)}", flush=True)
        run(["docker", "cp", str(ROOT / "nacos-config"), f"{PROJECT}-nacos:/tmp/"], env, timeout=60)
        init_output = nacos_init(env)
        print(sanitized(init_output), flush=True)
        print("PASS: Nacos v3 initialization and read-back checks", flush=True)
        run(["docker", "restart", f"{PROJECT}-nacos"], env, timeout=120)
        wait_nacos(nacos_port)
        second_init = nacos_init(env)
        print(sanitized(second_init), flush=True)
        print("PASS: Nacos restart preserves admin and idempotent initialization", flush=True)

        compose(env, "build", "nexus-signing-service", "nexus-gateway", timeout=2400)
        compose(
            env, "run", "-d", "--name", f"{PROJECT}-signing", "--no-deps", "--use-aliases",
            "-p", f"127.0.0.1:{signing_port}:8082",
            "-e", "NEX_NACOS_SERVER=nacos:8848", "-e", "NEX_NACOS_USERNAME=nacos",
            "-e", f"NEX_NACOS_PASSWORD={NACOS_ADMIN_PASSWORD}", "-e", f"JWT_SECRET={JWT_SECRET}",
            "-e", f"NEXUS_MPC_KEK={MPC_KEK}", "-e", "SPRING_CLOUD_SENTINEL_ENABLED=false",
            "-e", "NEX_MPC_ENGINE_DISTRIBUTED=false", "-e", "NEX_MPC_ENGINE_CGGMP_ENABLED=false",
            "nexus-signing-service",
        )
        assert_mounts_absent(env, f"{PROJECT}-signing", ("/app/logs", "/var/log/nexus", "/app/leveldb"))
        assert_container_env(env, f"{PROJECT}-signing", {
            "NEX_NACOS_USERNAME": "nacos",
            "NEX_NACOS_PASSWORD": NACOS_ADMIN_PASSWORD,
            "JWT_SECRET": JWT_SECRET,
        })
        print("PASS: signing container received the exact generated credentials", flush=True)
        time.sleep(5)
        anonymous_status = wait_capability(signing_port)
        print(f"PASS: anonymous capability request is rejected (HTTP {anonymous_status})")
        wait_registry(nacos_port, "nexus-signing-service")
        print("PASS: signing-service has a registered Nacos instance")

        legacy_status, legacy_body = loopback_request(
            signing_port, "/api/v1/transfers/capability",
            headers={"Authorization": f"Bearer {service_jwt('SIGNING_SERVICE')}"},
        )
        if legacy_status != 403:
            raise RuntimeError(f"legacy-only token expected HTTP 403, got {legacy_status}: {legacy_body[:500]}")
        print("PASS: legacy SIGNING_SERVICE-only token cannot read capability (HTTP 403)")

        read_status, read_body = loopback_request(
            signing_port, "/api/v1/transfers/capability",
            headers={"Authorization": f"Bearer {service_jwt('SIGNING_SERVICE,READ')}"},
        )
        if read_status != 200 or json.loads(read_body).get("statusCode") != 2000:
            raise RuntimeError(f"READ token capability failed: HTTP {read_status}, {read_body[:500]}")
        print("PASS: adding READ makes the same capability request succeed")

        compose(
            env, "run", "-d", "--name", f"{PROJECT}-gateway", "--no-deps", "--use-aliases",
            "-p", f"127.0.0.1:{gateway_port}:8080",
            "-e", "NEX_NACOS_SERVER=nacos:8848", "-e", "NEX_NACOS_USERNAME=nacos",
            "-e", f"NEX_NACOS_PASSWORD={NACOS_ADMIN_PASSWORD}", "-e", f"JWT_SECRET={JWT_SECRET}",
            "-e", "NEX_NACOS_ENABLED=true", "-e", "NEXUS_HEALTH_DOWNSTREAM_INDICATORS_ENABLED=true",
            "nexus-gateway",
        )
        assert_mounts_absent(env, f"{PROJECT}-gateway", ("/app/logs", "/var/log/nexus", "/app/leveldb"))
        print("PASS: gateway/signing start without log or LevelDB mounts", flush=True)
        assert_container_env(env, f"{PROJECT}-gateway", {
            "NEX_NACOS_USERNAME": "nacos",
            "NEX_NACOS_PASSWORD": NACOS_ADMIN_PASSWORD,
            "JWT_SECRET": JWT_SECRET,
            "NEXUS_HEALTH_DOWNSTREAM_INDICATORS_ENABLED": "true",
        })
        print("PASS: gateway container received the exact generated credentials", flush=True)
        wait_registry(nacos_port, "nexus-gateway")
        print("PASS: gateway has a registered Nacos instance")
        status, body = wait_gateway_component(gateway_port, args.expect)
        if args.expect == "legacy-down":
            print(f"PASS: reproduced legacy default failure (signingService={status})")
        else:
            print(f"PASS: gateway discovers and calls signing capability through Feign (signingService={status})")
        print(f"EVIDENCE: {body[:800]}")
    except Exception:
        print_diagnostics(env)
        raise
    finally:
        try:
            compose(env, "down", "-v", "--remove-orphans", timeout=180)
        except Exception as cleanup_error:
            print(f"WARNING: cleanup failed: {sanitized(str(cleanup_error))}", file=sys.stderr)


if __name__ == "__main__":
    main()
