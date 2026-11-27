#!/usr/bin/env python3
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ops" / "nc-task.sh"
BASH = shutil.which("bash")
sys.path.insert(0, str(ROOT / "scripts"))

import prod_prepare  # noqa: E402


def posix_arg(path: Path) -> str:
    # 生成器内部 cd 到仓库根；传仓库相对路径可避免 MSYS 绝对路径转换
    # 与 MSYS_NO_PATHCONV 下 openssl 收到 /f/... 的双重问题。
    try:
        return path.resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


class PreparedEnvironment:
    """合法生产环境夹具：env + MPC 证书 + 三方拓扑配置。"""

    def __init__(self) -> None:
        (ROOT / "build").mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="nexus-nc-task-", dir=ROOT / "build")
        self.work = Path(self.temp.name)
        self.env_file = self.work / ".env.prod"
        self.certs = self.work / "certs"
        self.configs = self.work / "config-prod"

    def build(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            prod_prepare.prepare_env(ROOT, self.env_file, 90)
        for command in (
            [BASH, "scripts/gen-mpc-certs.sh", "--cert-dir", posix_arg(self.certs)],
            [
                BASH,
                "scripts/gen-mpc-engine-configs.sh",
                "--layout",
                "compose-prod",
                "--certs-dir",
                posix_arg(self.certs),
                "-o",
                posix_arg(self.configs),
            ],
        ):
            result = subprocess.run(
                command, cwd=ROOT, capture_output=True, text=True, check=False
            )
            if result.returncode != 0:
                raise RuntimeError(result.stdout + result.stderr)

    @property
    def env(self) -> dict[str, str]:
        env = {
            "NEX_ENV_FILE": str(self.env_file),
            "NEX_CERTS_DIR": posix_arg(self.certs),
            "NEX_CONFIG_DIR": posix_arg(self.configs),
            "NEX_MIN_AVAILABLE_RAM_MB": "1",
        }
        if os.name != "nt":
            # CI runner 的属主 UID 未必是 1000；显式对齐当前用户，
            # 生产默认 1000 仍由 test_prod_prepare 守护。
            env["NEXUS_MPC_RUNTIME_UID"] = str(os.getuid())
            env["NEXUS_SIGNING_RUNTIME_UID"] = str(os.getuid())
        return env

    def cleanup(self) -> None:
        self.temp.cleanup()


class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(self.server.health_status)
        self.end_headers()

    def log_message(self, format, *args):
        pass


class NcTaskTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prepared = PreparedEnvironment()
        cls.prepared.build()

    @classmethod
    def tearDownClass(cls):
        cls.prepared.cleanup()

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

    def run_task(self, action, extra_env=None):
        if BASH is None:
            self.fail("bash is required to exercise nc-task.sh")
        env = os.environ.copy()
        env["PATH"] = os.pathsep.join([str(self.fake_bin), env.get("PATH", "")])
        env["FAKE_DOCKER_LOG"] = str(self.docker_log)
        env["NO_PROXY"] = "127.0.0.1,localhost"
        env["no_proxy"] = "127.0.0.1,localhost"
        env.update(self.prepared.env)
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
            # compose-up 预检含资源门禁与 openssl 证书链验证，
            # 单次可达数十秒；超时仅给足余量，不放宽判定。
            timeout=90,
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
        # nc-task 先确认 docker 可用
        self.assertEqual(calls[0], ["info"])
        # prod-check 预检内再次确认 docker，再渲染 compose config
        self.assertEqual(calls[1], ["info"])
        self.assertEqual(calls[2][0:2], ["compose", "-f"])
        self.assertEqual(calls[2][5:], ["config", "--quiet"])
        # 真正的起栈调用
        self.assertEqual(calls[3][0:2], ["compose", "-f"])
        self.assertTrue(calls[3][2].endswith("docker-compose.prod.yml"))
        self.assertEqual(calls[3][3], "--env-file")
        self.assertTrue(calls[3][4].endswith(".env.prod"))
        self.assertEqual(calls[3][5:], ["up", "-d"])

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
