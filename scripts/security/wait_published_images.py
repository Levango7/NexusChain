#!/usr/bin/env python3
"""Wait until the CI workflow has published every required image for this SHA."""

import argparse
import base64
import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request


CI_WORKFLOW_NAME = "NexusChain CI/CD Pipeline"
MODULES = (
    "nexus-core", "nexus-gateway", "nexus-bridge", "nexus-signing-service",
    "nexus-wallet-service", "nexus-api-gateway", "nexus-settlement",
    "nexus-compliance", "nexus-analytics", "nexus-oracle", "mpc-engine",
    "zk-groth16-service", "nexus-explorer",
)
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
SHA = re.compile(r"[0-9a-f]{40}")


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("redirects are not allowed for CI readiness checks")


def validate_public_github_url(url):
    parts = urllib.parse.urlsplit(url)
    if (parts.scheme != "https" or parts.hostname != "api.github.com"
            or parts.port not in (None, 443) or parts.username or parts.password
            or not parts.path.startswith("/repos/")):
        raise ValueError("only the public GitHub API over HTTPS is allowed")
    for entry in socket.getaddrinfo(parts.hostname, 443, type=socket.SOCK_STREAM):
        address = ipaddress.ip_address(entry[4][0])
        if (address.is_private or address.is_loopback or address.is_link_local
                or address.is_reserved or address.is_multicast or address.is_unspecified):
            raise ValueError("private, loopback, link-local, or reserved API addresses are forbidden")


def request_headers():
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "nexuschain-scan-ready"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        result = subprocess.run(
            ["git", "config", "--get", "http.https://github.com/.extraheader"],
            shell=False, capture_output=True, text=True, timeout=10, check=False,
        )
        if result.returncode == 0:
            try:
                basic = result.stdout.strip().split()[-1]
                decoded = base64.b64decode(basic).decode("utf-8")
                if decoded.startswith("x-access-token:"):
                    token = decoded.split(":", 1)[1]
            except (ValueError, UnicodeDecodeError):
                pass
    if token:
        headers["Authorization"] = "Bearer " + token
    return headers


def fetch_json(url):
    validate_public_github_url(url)
    request = urllib.request.Request(url, headers=request_headers())
    with urllib.request.build_opener(RejectRedirects()).open(request, timeout=30) as response:
        return json.load(response)


def wait_for_images(repository, sha, fetch=fetch_json, sleeper=time.sleep,
                    max_attempts=180, wait_seconds=30):
    if not REPOSITORY.fullmatch(repository):
        raise ValueError("invalid owner/repository")
    if not SHA.fullmatch(sha):
        raise ValueError("a full lowercase commit SHA is required")
    if max_attempts < 1 or max_attempts > 180 or not 0 <= wait_seconds <= 60:
        raise ValueError("attempts must be 1..180 and wait seconds must be 0..60")

    run_url = f"https://api.github.com/repos/{repository}/actions/runs?head_sha={sha}&event=push&per_page=100"
    for attempt in range(1, max_attempts + 1):
        runs = fetch(run_url).get("workflow_runs") or []
        ci_run = next((run for run in runs if run.get("name") == CI_WORKFLOW_NAME), None)
        if ci_run is not None:
            run_id = ci_run["id"]
            jobs_url = f"https://api.github.com/repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"
            jobs = fetch(jobs_url).get("jobs") or []
            by_name = {job.get("name"): job for job in jobs}
            expected = {f"Docker Build & Push ({module})" for module in MODULES}
            for name in sorted(expected):
                job = by_name.get(name)
                conclusion = str((job or {}).get("conclusion") or "").lower()
                if conclusion in {"failure", "cancelled", "timed_out", "action_required"}:
                    raise RuntimeError(f"publication job failed: {name} ({conclusion})")
            if all(str((by_name.get(name) or {}).get("conclusion") or "").lower() == "success"
                   for name in expected):
                print(f"All {len(expected)} images are published for {sha}", flush=True)
                return 0
            if str(ci_run.get("conclusion") or "").lower() in {"failure", "cancelled", "timed_out"}:
                raise RuntimeError(f"CI workflow ended before publication: {ci_run.get('conclusion')}")
        print(f"publication not complete ({attempt}/{max_attempts}); waiting {wait_seconds}s", flush=True)
        if attempt < max_attempts:
            sleeper(wait_seconds)
    raise TimeoutError("timed out waiting for all published images")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--attempts", type=int, default=180)
    parser.add_argument("--wait-seconds", type=int, default=30)
    args = parser.parse_args()
    try:
        return wait_for_images(args.repository, args.sha, max_attempts=args.attempts,
                               wait_seconds=args.wait_seconds)
    except (RuntimeError, TimeoutError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
