#!/usr/bin/env python3
"""Exercise the shell scan-image helper with a fake, logged docker binary."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SHA = "a" * 40
SCRIPT = Path(__file__).with_name("prepare_scan_image.sh")
if os.name == "nt":
    BASH = next(
        path for path in subprocess.check_output(["where.exe", "bash"], text=True).splitlines()
        if "\\Git\\" in path
    )
else:
    BASH = "bash"
FAKE_DOCKER = r'''#!/usr/bin/env bash
set -euo pipefail
index=$(cat "$COUNT_FILE")
index=$((index + 1))
printf '%s' "$index" > "$COUNT_FILE"
printf '%s\n' "$*" >> "$CALLS"
IFS=',' read -ra codes <<< "$OUTCOMES"
code="${codes[$((index - 1))]:-0}"
exit "$code"
'''


class PrepareScanImageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.calls = root / "calls"
        self.count = root / "count"
        self.calls.write_text("", encoding="utf-8")
        self.count.write_text("0", encoding="utf-8")
        self.docker = root / "docker"
        self.docker.write_text(FAKE_DOCKER, encoding="utf-8")
        self.docker.chmod(0o755)

    def tearDown(self):
        self.temp.cleanup()

    def run_helper(self, event="push", outcomes="0", sha=SHA, module="nexus-bridge", attempts="3"):
        env = os.environ.copy()
        env.update({
            "GITHUB_REPOSITORY": "owner/repository",
            "GITHUB_EVENT_NAME": event,
            "GITHUB_SHA": sha,
            "DOCKER_BIN": str(self.docker),
            "CALLS": str(self.calls),
            "COUNT_FILE": str(self.count),
            "OUTCOMES": outcomes,
            "SCAN_PULL_ATTEMPTS": attempts,
            "SCAN_WAIT_SECONDS": "0",
            "SCAN_BUILD_WAIT_SECONDS": "0",
        })
        result = subprocess.run(
            [BASH, SCRIPT.as_posix(), module], shell=False, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", timeout=10, check=False,
        )
        calls = self.calls.read_text(encoding="utf-8").splitlines()
        return result.returncode, calls, result.stdout

    def test_push_uses_exact_published_sha_and_never_builds(self):
        result, calls, _ = self.run_helper(outcomes="0,0,0")

        self.assertEqual(result, 0)
        self.assertEqual(calls[0], f"pull ghcr.io/owner/repository/nexus-bridge:{SHA}")
        self.assertTrue(calls[1].startswith("tag ghcr.io/"))
        self.assertEqual(calls[2], "image inspect nexus-bridge:scan")
        self.assertFalse(any(call.startswith("build ") for call in calls))

    def test_push_waits_without_falling_back_to_build(self):
        result, calls, _ = self.run_helper(outcomes="1,1,0,0,0")

        self.assertEqual(result, 0)
        self.assertEqual(sum(call.startswith("pull ") for call in calls), 3)
        self.assertFalse(any(call.startswith("build ") for call in calls))

    def test_push_fails_closed_when_artifact_never_appears(self):
        result, calls, output = self.run_helper(outcomes="1,1", attempts="2")

        self.assertEqual(result, 1)
        self.assertEqual(len(calls), 2)
        self.assertIn("rather than rebuilding a different artifact", output)

    def test_pr_keeps_bounded_local_build_retry(self):
        result, calls, _ = self.run_helper(event="pull_request", outcomes="1,0")

        self.assertEqual(result, 0)
        self.assertEqual(len(calls), 2)
        self.assertTrue(all(call.startswith("buildx build ") for call in calls))
        self.assertTrue(all("type=gha" in call for call in calls))

    def test_pr_does_not_pull_published_image(self):
        result, calls, _ = self.run_helper(event="pull_request")

        self.assertEqual(result, 0)
        self.assertTrue(calls[0].startswith("buildx build --load -t nexus-bridge:scan"))
        self.assertIn("type=gha", calls[0])

    def test_invalid_inputs_do_not_invoke_docker(self):
        for kwargs in (
            {"module": "../bridge"},
            {"sha": "abc"},
            {"attempts": "0"},
        ):
            result, calls, _ = self.run_helper(**kwargs)
            self.assertEqual(result, 2)
            self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
