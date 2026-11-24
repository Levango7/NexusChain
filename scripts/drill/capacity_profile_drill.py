#!/usr/bin/env python3
"""Bounded multi-key capacity profile against an isolated local gateway."""

from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("api_attack", ROOT / "scripts" / "drill" / "api_attack_drill.py")
attack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(attack)
base = attack.base
base.PROJECT = f"nc-capacity-{os.getpid()}"
EVIDENCE = Path(os.environ.get(
    "CAP_EVIDENCE_DIR", f"F:/Agent/opencode/workspace/evidence/capacity-{os.getpid()}"
))
RESULTS = EVIDENCE / "profile.json"


def write_results(levels, recovery=None):
    document = {
        "scope": {
            "local_only": True,
            "production_sla": False,
            "real_chain": False,
            "connector": "mock",
            "database": "isolated H2",
            "generated_at": datetime.now(timezone.utc).isoformat(),
        },
        "levels": levels,
        "recovery": recovery,
    }
    temporary = RESULTS.with_suffix(".tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, RESULTS)


def metric(summary, name, field, default=0):
    entry = summary.get("metrics", {}).get(name, {})
    # k6 --summary-export 是扁平 values；部分内部/旧格式会再包一层 values。
    values = entry.get("values", entry)
    return values.get(field, default)


def run_level(env, api_keys, rate, duration_seconds):
    summary_path = EVIDENCE / f"rps-{rate}.json"
    log_path = EVIDENCE / f"rps-{rate}.log"
    run_env = env.copy()
    run_env.update({
        "API_KEY": base.API_KEY,
        "API_KEYS": api_keys,
        "SIGNING_SECRET": base.SIGNING_SECRET,
    })
    args = [
        "docker", "run", "--rm", "--network", f"{base.PROJECT}_default",
        "-e", "API_KEY", "-e", "API_KEYS", "-e", "SIGNING_SECRET",
        "-e", "BASE_URL_GATEWAY=http://nexus-gateway:8080",
        "-e", "CAP_SINGLE_LEVEL=true", "-e", f"CAP_START_RPS={rate}",
        "-e", f"CAP_STEP_DURATION={duration_seconds}s",
        "-e", "CAP_PRE_ALLOC_VUS=100", "-e", "CAP_MAX_VUS=400",
        "-v", f"{ROOT}:/workspace", "-w", "/workspace",
        "-v", f"{EVIDENCE}:/evidence",
        "grafana/k6:0.54.0", "run", "--summary-export=/evidence/" + summary_path.name,
        "perf/k6/capacity.js",
    ]
    result = subprocess.run(
        args, cwd=ROOT, env=run_env, text=True, encoding="utf-8", errors="replace",
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=duration_seconds + 180, check=False,
    )
    log_path.write_text(result.stdout, encoding="utf-8")
    if not summary_path.is_file():
        raise RuntimeError(f"k6 produced no summary at {rate} RPS; see {log_path}")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    requests = metric(summary, "http_reqs", "count")
    status_5xx = metric(summary, "capacity_status_5xx", "count")
    status_other = metric(summary, "capacity_status_other", "count")
    if not requests:
        raise RuntimeError(f"k6 issued no requests at {rate} RPS")
    if status_5xx:
        raise RuntimeError(f"capacity level {rate} RPS produced {status_5xx} HTTP 5xx responses")
    if status_other:
        raise RuntimeError(f"capacity level {rate} RPS produced {status_other} unexpected statuses")
    row = {
        "target_rps": rate,
        "duration_seconds": duration_seconds,
        "requests": requests,
        "achieved_rps": round(requests / duration_seconds, 3),
        "http_2xx_3xx": metric(summary, "capacity_status_2xx", "count"),
        "http_429": metric(summary, "capacity_status_429", "count"),
        "http_5xx": status_5xx,
        "http_other": status_other,
        "http_failure_rate": metric(summary, "http_req_failed", "value"),
        "business_success_rate": metric(summary, "biz_success_rate", "value"),
        "p50_ms": metric(summary, "http_req_duration", "med"),
        "p95_ms": metric(summary, "http_req_duration", "p(95)"),
        "p99_ms": metric(summary, "http_req_duration", "p(99)"),
        "max_ms": metric(summary, "http_req_duration", "max"),
        "k6_exit_code": result.returncode,
        "note": "429 is capacity evidence; any 5xx or unexpected status fails the drill",
    }
    print("LEVEL " + json.dumps(row, ensure_ascii=False), flush=True)
    return row


def main():
    levels = [int(value) for value in os.environ.get("CAP_LEVELS", "10,50,100,200,250").split(",")]
    duration_seconds = int(os.environ.get("CAP_LEVEL_SECONDS", "30"))
    key_count = int(os.environ.get("CAP_KEY_COUNT", "40"))
    if not levels or len(levels) > 8 or any(rate < 1 or rate > 500 for rate in levels):
        raise ValueError("CAP_LEVELS must contain 1-8 rates between 1 and 500 RPS")
    if not 10 <= duration_seconds <= 120 or not 2 <= key_count <= 100:
        raise ValueError("duration or key count exceeds the bounded local profile")
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    gateway_port = base.free_port()
    env = os.environ.copy()
    env.update({
        "COMPOSE_PROJECT_NAME": base.PROJECT,
        "NEX_CONTAINER_PREFIX": base.PROJECT,
        "NEX_DEV_NACOS_ENABLED": "false",
        "NEX_DEV_JWT_SECRET": base.JWT_SECRET,
        "NEXUS_REQUEST_SIGNING_SECRET": base.REQUEST_SIGNING_SECRET,
    })
    rows = []
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
        attack.API_KEY = base.API_KEY
        attack.SIGNING_SECRET = base.SIGNING_SECRET
        base.run_k6(env, ("10s:1",), warmup=True)
        print("PASS: isolated gateway, credentials, and zero-failure warmup", flush=True)

        executable = shutil.which("powershell.exe") or shutil.which("pwsh")
        if not executable:
            raise RuntimeError("PowerShell is required for the repository multi-key seeder")
        seed_output = base.run([
            executable, "-NoProfile", "-File", "perf/k6/seed-multi-keys.ps1",
            "-GatewayBase", f"http://127.0.0.1:{gateway_port}",
            "-JwtSecret", base.JWT_SECRET, "-Count", str(key_count),
        ], env, timeout=240)
        match = re.search(r"^API_KEYS=(\S+)\s*$", seed_output, re.MULTILINE)
        if not match:
            raise RuntimeError("could not parse multi-key seed output")
        api_keys = match.group(1)
        if len(api_keys.split(",")) != key_count:
            raise RuntimeError("multi-key seeder returned an unexpected key count")
        print(f"PASS: seeded {key_count} ephemeral API keys", flush=True)
        write_results(rows)

        for rate in levels:
            rows.append(run_level(env, api_keys, rate, duration_seconds))
            write_results(rows)
            time.sleep(61)

        recovery_status, recovery_body = attack.post(
            gateway_port, "/api/v1/payments",
            attack.payment_body("capacity-recovery", f"capacity-recovery-{uuid.uuid4().hex}"),
        )
        if recovery_status != 201:
            raise RuntimeError(f"capacity recovery failed: {recovery_status} {recovery_body[:300]}")
        write_results(rows, {"status": recovery_status, "after_seconds": 61})
        print("PASS: signed payment returns 201 after the final rate-limit window", flush=True)
        print(f"PROFILE: {RESULTS}", flush=True)
    except Exception:
        try:
            logs = base.run(["docker", "logs", "--tail", "120", f"{base.PROJECT}-gateway"], env, timeout=30)
            print(base.sanitized(logs), file=sys.stderr)
        except Exception as diagnostic_error:
            print(f"WARNING: could not read gateway logs: {diagnostic_error}", file=sys.stderr)
        raise
    finally:
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
