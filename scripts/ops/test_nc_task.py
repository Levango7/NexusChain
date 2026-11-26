#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ops" / "nc-task.sh"
BASH = shutil.which("bash")


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(self.server.health_status)
        self.end_headers()

    def log_message(self, format, *args):
        pass


class NcTaskTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.temp_path = Path(self.temp.name)
        self.fake_bin = self.temp_path / "bin"
        self.fake_bin.mkdir()
        self.docker_log = self.temp_path / "docker.log"
        fake_docker = self.fake_bin / "docker"
        fake_docker.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\0' \"$@\" >> \"$FAKE_DOCKER_LOG\"\n"
            "printf '\\0' >> \"$FAKE_DOCKER_LOG\"\n"
            "exit 0\n",
            encoding="utf-8",
        )
        fake_docker.chmod(0o755)
        self.env_file = self.temp_path / ".env.prod"
        self.env_file.write_text("", encoding="utf-8")

    def run_task(self, action, extra_env=None):
        if BASH is None:
            self.fail("bash is required to exercise nc-task.sh")
        env = os.environ.copy()
        env["PATH"] = os.pathsep.join([str(self.fake_bin), env.get("PATH", "")])
        env["FAKE_DOCKER_LOG"] = str(self.docker_log)
        env["NEX_ENV_FILE"] = str(self.env_file)
        env["NO_PROXY"] = "127.0.0.1,localhost"
        env["no_proxy"] = "127.0.0.1,localhost"
        if extra_env:
            env.update(extra_env)
        if action in {"compose-up", "compose-down"}:
            probe = subprocess.run(
                [BASH, "-c", "command -v docker"],
                env=env,
                capture_output=True,
                text=True,
                encoding="utf-8",
                timeout=5,
            )
            found = probe.stdout.strip().replace("\\", "/").lower()
            self.assertTrue(
                probe.returncode == 0 and found.endswith("/bin/docker"),
                f"refusing to run compose test without fake docker: {found!r} {probe.stderr!r}",
            )
        result = subprocess.run(
            [BASH, str(SCRIPT), action],
            cwd=ROOT,
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=15,
        )
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        self.assertTrue(lines, f"no stdout from nc-task.sh: {result.stderr}")
        receipt = json.loads(lines[-1])
        return result, receipt

    def docker_calls(self):
        raw = self.docker_log.read_bytes()
        return [
            [os.fsdecode(arg) for arg in block.split(b"\0") if arg]
            for block in raw.split(b"\0\0")
            if block
        ]

    def run_status(self, status):
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        server.health_status = status
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return self.run_task(
            "status",
            {"NEX_GATEWAY_URL": f"http://127.0.0.1:{server.server_port}"},
        )

    def test_compose_up_detaches(self):
        result, receipt = self.run_task("compose-up")
        self.assertEqual(result.returncode, 0)
        self.assertTrue(receipt["ok"])
        calls = self.docker_calls()
        self.assertEqual(calls[0], ["info"])
        self.assertEqual(calls[1][0:2], ["compose", "-f"])
        self.assertTrue(calls[1][2].endswith("docker-compose.prod.yml"))
        self.assertEqual(calls[1][3], "--env-file")
        self.assertTrue(calls[1][4].endswith(".env.prod"))
        self.assertEqual(calls[1][5:], ["up", "-d"])

    def test_compose_down_does_not_pass_detach(self):
        result, receipt = self.run_task("compose-down")
        self.assertEqual(result.returncode, 0)
        self.assertTrue(receipt["ok"])
        calls = self.docker_calls()
        self.assertEqual(calls[0], ["info"])
        self.assertEqual(calls[1][0:2], ["compose", "-f"])
        self.assertTrue(calls[1][2].endswith("docker-compose.prod.yml"))
        self.assertEqual(calls[1][3], "--env-file")
        self.assertTrue(calls[1][4].endswith(".env.prod"))
        self.assertEqual(calls[1][5:], ["down"])

    def test_status_200_is_healthy(self):
        result, receipt = self.run_status(200)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(receipt["ok"])
        self.assertEqual(receipt["exitCode"], 0)
        self.assertEqual(receipt["detail"]["healthHttp"], "200")

    def test_status_204_is_healthy(self):
        result, receipt = self.run_status(204)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(receipt["ok"])
        self.assertEqual(receipt["exitCode"], 0)
        self.assertEqual(receipt["detail"]["healthHttp"], "204")

    def test_status_503_is_unhealthy(self):
        result, receipt = self.run_status(503)
        self.assertEqual(result.returncode, 1)
        self.assertFalse(receipt["ok"])
        self.assertEqual(receipt["exitCode"], 1)
        self.assertEqual(receipt["detail"]["healthHttp"], "503")

    def test_status_unreachable_fails(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        result, receipt = self.run_task(
            "status",
            {"NEX_GATEWAY_URL": f"http://127.0.0.1:{port}"},
        )
        self.assertEqual(result.returncode, 1)
        self.assertFalse(receipt["ok"])
        self.assertEqual(receipt["exitCode"], 1)
        self.assertEqual(receipt["detail"]["healthHttp"], "000")


if __name__ == "__main__":
    unittest.main(verbosity=2)
