#!/usr/bin/env python3
"""Seeded k6 business smoke with an isolated gateway and ephemeral credentials."""

import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = ROOT / "docker-compose.yml"
PROJECT = f"nc-perf-{os.getpid()}"
JWT_SECRET = secrets.token_urlsafe(48)
REQUEST_SIGNING_SECRET = secrets.token_urlsafe(48)
API_KEY = ""
SIGNING_SECRET = ""


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def sanitized(text):
    for secret in (JWT_SECRET, REQUEST_SIGNING_SECRET, API_KEY, SIGNING_SECRET):
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


def loopback_request(port, path, timeout=5):
    if not 1 <= port <= 65535 or not path.startswith("/") or path.startswith("//"):
        raise ValueError("invalid loopback endpoint")
    if ".." in path or "://" in path:
        raise ValueError("invalid loopback path")
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        return response.status, response.read().decode("utf-8", errors="replace")
    finally:
        connection.close()


def wait_gateway(port, deadline_seconds=300):
    deadline = time.time() + deadline_seconds
    while time.time() < deadline:
        try:
            status, body = loopback_request(port, "/actuator/health")
            if status == 200 and json.loads(body).get("status") == "UP":
                return
        except (OSError, http.client.HTTPException, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise RuntimeError("gateway did not become UP")


def assert_no_log_mounts(env):
    output = run(["docker", "inspect", f"{PROJECT}-gateway"], env, timeout=30)
    mounts = json.loads(output)[0].get("Mounts") or []
    destinations = {mount.get("Destination") for mount in mounts}
    forbidden = destinations & {"/app/logs", "/var/log/nexus"}
    if forbidden:
        raise RuntimeError(f"unexpected log mounts in isolated drill: {sorted(forbidden)}")


def seed_credentials(env):
    global API_KEY, SIGNING_SECRET
    output = run([
        "docker", "run", "--rm", "--network", f"{PROJECT}_default",
        "-e", f"JWT_SECRET={JWT_SECRET}", "-e", f"NEXUS_REQUEST_SIGNING_SECRET={REQUEST_SIGNING_SECRET}",
        "-v", f"{ROOT}:/workspace", "-w", "/workspace",
        "python:3.14-alpine", "sh", "-c",
        "apk add --no-cache bash curl openssl >/dev/null && "
        "bash perf/k6/seed-perf-credentials.sh http://nexus-gateway:8080 \"$JWT_SECRET\"",
    ], env, timeout=180)
    key_match = re.search(r"^API_KEY\s*:\s*(\S+)\s*$", output, re.MULTILINE)
    secret_match = re.search(r"^SIGNING_SECRET\s*:\s*(\S+)\s*$", output, re.MULTILINE)
    if not key_match or not secret_match:
        raise RuntimeError("could not parse seeded credentials")
    API_KEY = key_match.group(1)
    SIGNING_SECRET = secret_match.group(1)


def run_k6(env, stages, warmup=False):
    args = [
        "docker", "run", "--rm", "--network", f"{PROJECT}_default",
        "-e", f"API_KEY={API_KEY}", "-e", f"SIGNING_SECRET={SIGNING_SECRET}",
        "-e", "BASE_URL_GATEWAY=http://nexus-gateway:8080",
        "-v", f"{ROOT}:/workspace", "-w", "/workspace",
        "grafana/k6:0.54.0", "run",
    ]
    for stage in stages:
        args.extend(("--stage", stage))
    args.append("perf/k6/payment-create.js")
    result = subprocess.run(
        args, cwd=ROOT, env=env, text=True, encoding="utf-8", errors="replace",
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180, check=False,
    )
    output = sanitized(result.stdout)
    failure_rate = re.search(r"http_req_failed.*?:\s+([0-9.]+)%", output)
    if not failure_rate or float(failure_rate.group(1)) != 0.0:
        raise RuntimeError(f"k6 had HTTP failures:\n{output[-10000:]}")
    if result.returncode != 0 and not warmup:
        raise RuntimeError(f"k6 thresholds failed:\n{output[-10000:]}")
    return result.returncode, output


def verify_powershell_seed(env, gateway_port):
    executable = shutil.which("powershell.exe") or shutil.which("pwsh")
    if not executable:
        print("SKIP: PowerShell is unavailable; bash seed was already verified", flush=True)
        return
    output = run([
        executable, "-NoProfile", "-File", "perf/k6/seed-perf-credentials.ps1",
        "-GatewayBase", f"http://127.0.0.1:{gateway_port}",
        "-JwtSecret", JWT_SECRET, "-RequestSigningSecret", REQUEST_SIGNING_SECRET,
    ], env, timeout=120)
    match = re.search(r"^SIGNING_SECRET\s*:\s*(\S+)\s*$", output, re.MULTILINE)
    if not match or match.group(1) != REQUEST_SIGNING_SECRET:
        raise RuntimeError("PowerShell seed did not emit the configured global signing secret")
    if not re.search(r"^API_KEY\s*:\s*\S+\s*$", output, re.MULTILINE):
        raise RuntimeError("PowerShell seed did not emit an API key")
    print("PASS: PowerShell seed emits the same global signing contract", flush=True)


def main():
    global API_KEY, SIGNING_SECRET
    gateway_port = free_port()
    env = os.environ.copy()
    env.update({
        "COMPOSE_PROJECT_NAME": PROJECT,
        "NEX_CONTAINER_PREFIX": PROJECT,
        "NEX_DEV_NACOS_ENABLED": "false",
        "NEX_DEV_JWT_SECRET": JWT_SECRET,
        "NEXUS_REQUEST_SIGNING_SECRET": REQUEST_SIGNING_SECRET,
    })
    try:
        run(["docker", "version", "--format", "{{.Server.Version}}"], env, timeout=30)
        compose(env, "build", "nexus-gateway", timeout=2400)
        run(["docker", "network", "create", f"{PROJECT}_default"], env, timeout=30)
        run([
            "docker", "run", "-d", "--name", f"{PROJECT}-core", "--network", f"{PROJECT}_default",
            "--network-alias", "nexus-core", "-v", f"{ROOT}:/workspace", "-w", "/workspace",
            "python:3.14-alpine", "python", "-I", "scripts/drill/fake_chain_node.py",
        ], env, timeout=30)
        compose(
            env, "run", "-d", "--name", f"{PROJECT}-gateway", "--no-deps", "--use-aliases",
            "-p", f"127.0.0.1:{gateway_port}:8080",
            "-e", "NEX_DEV_NACOS_ENABLED=false", "-e", f"JWT_SECRET={JWT_SECRET}",
            "-e", f"NEXUS_REQUEST_SIGNING_SECRET={REQUEST_SIGNING_SECRET}", "nexus-gateway",
        )
        assert_no_log_mounts(env)
        wait_gateway(gateway_port)
        print("PASS: gateway is UP without /app/logs or /var/log/nexus mounts", flush=True)
        seed_credentials(env)
        print("PASS: register -> ADMIN verify -> api-keys seed chain", flush=True)
        warmup_rc, _ = run_k6(env, ("10s:1",), warmup=True)
        print(f"PASS: warmup completed with zero HTTP failures (rc={warmup_rc}; cold-start latency is not acceptance)", flush=True)
        _, output = run_k6(env, ("5s:1", "20s:1", "5s:0"))
        print(sanitized(output), flush=True)
        print("PASS: seeded k6 payment-create business smoke met all thresholds", flush=True)
        verify_powershell_seed(env, gateway_port)
    except Exception:
        try:
            logs = run(["docker", "logs", "--tail", "160", f"{PROJECT}-gateway"], env, timeout=30)
            print(sanitized(logs), file=sys.stderr)
        except Exception as diagnostic_error:
            print(f"WARNING: could not read gateway logs: {diagnostic_error}", file=sys.stderr)
        raise
    finally:
        subprocess.run(["docker", "rm", "-f", f"{PROJECT}-core"], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        try:
            compose(env, "down", "-v", "--remove-orphans", timeout=180)
        except Exception as cleanup_error:
            print(f"WARNING: cleanup failed: {sanitized(str(cleanup_error))}", file=sys.stderr)
        subprocess.run(["docker", "network", "rm", f"{PROJECT}_default"], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
