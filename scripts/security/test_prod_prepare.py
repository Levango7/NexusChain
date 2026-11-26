#!/usr/bin/env python3
from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
BASH = "bash"
sys.path.insert(0, str(ROOT / "scripts"))

import prod_prepare  # noqa: E402


def bash_path(path: Path, repository_relative: bool = True) -> str:
    if repository_relative:
        try:
            path = path.relative_to(ROOT)
        except ValueError:
            pass
    value = path.as_posix()
    if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
        return f"/{value[0].lower()}{value[2:]}"
    return value


class ProdPrepareTests(unittest.TestCase):
    def runtime_env(self) -> contextlib.AbstractContextManager:
        env = dict(os.environ)
        if os.name != "nt":
            uid = str(os.getuid())
            env["NEXUS_MPC_RUNTIME_UID"] = uid
            env["NEXUS_SIGNING_RUNTIME_UID"] = uid
        return mock.patch.dict(os.environ, env, clear=True)

    @classmethod
    def setUpClass(cls) -> None:
        (ROOT / "build").mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="nexus-prod-prepare-", dir=ROOT / "build")
        cls.work = Path(cls.temp.name)
        cls.env_file = cls.work / ".env.prod"
        cls.certs = cls.work / "certs"
        cls.configs = cls.work / "config-prod"
        with contextlib.redirect_stdout(io.StringIO()):
            prod_prepare.prepare_env(ROOT, cls.env_file, 90)
        cert_result = subprocess.run(
            [BASH, "scripts/gen-mpc-certs.sh", "--cert-dir", bash_path(cls.certs)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if cert_result.returncode != 0:
            cls.temp.cleanup()
            raise RuntimeError(f"certificate fixture generation failed: {cert_result.stderr}")
        config_result = subprocess.run(
            [
                BASH,
                "scripts/gen-mpc-engine-configs.sh",
                "--layout",
                "compose-prod",
                "--certs-dir",
                bash_path(cls.certs),
                "-o",
                bash_path(cls.configs),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        if config_result.returncode != 0:
            cls.temp.cleanup()
            raise RuntimeError(f"config fixture generation failed: {config_result.stderr}")
        cls.values = prod_prepare.parse_env(cls.env_file)
        cls.config_stdout = config_result.stdout

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def test_environment_generation_is_complete_and_secret_safe(self) -> None:
        required = prod_prepare.required_keys(ROOT / "docker-compose.prod.yml")
        self.assertEqual(len(required), 22)
        self.assertTrue(all(self.values.get(key) for key in required))
        self.assertRegex(self.values["MPC_STORAGE_KEY"], r"^[0-9a-f]{64}$")
        self.assertNotEqual(int(self.values["MPC_STORAGE_KEY"], 16), 0)
        prod_prepare.validate_env(self.values, required)
        prod_prepare.verify_rpc_token(
            self.values["NEXUS_RPC_TOKEN"],
            self.values["NEXUS_JWT_APP_ID"],
            self.values["NEXUS_JWT_APP_SECRET"],
        )
        if not prod_prepare.windows_mount(self.env_file):
            self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        for secret in (
            self.values["MPC_STORAGE_KEY"],
            self.values["JWT_SECRET"],
            self.values["NEXUS_RPC_TOKEN"],
            self.values["NEXUS_JWT_APP_SECRET"],
        ):
            self.assertNotIn(secret, self.config_stdout)

    def test_environment_generation_never_overwrites(self) -> None:
        before = self.env_file.read_bytes()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(prod_prepare.prepare_env(ROOT, self.env_file, 90), 0)
        self.assertEqual(self.env_file.read_bytes(), before)
        self.assertIn("未修改", output.getvalue())

    def test_invalid_existing_environment_is_not_replaced(self) -> None:
        target = self.work / "invalid.env"
        original = "MPC_AUTH_TOKEN=dev-mpc-engine-token-change-in-prod\n"
        target.write_text(original, encoding="utf-8")
        with self.assertRaises(prod_prepare.ProdError):
            prod_prepare.prepare_env(ROOT, target, 90)
        self.assertEqual(target.read_text(encoding="utf-8"), original)

    def test_placeholder_check_is_case_insensitive(self) -> None:
        values = dict(self.values)
        values["MPC_AUTH_TOKEN"] = "DEV-MPC-ENGINE-TOKEN-CHANGE-IN-PROD"
        with self.assertRaisesRegex(prod_prepare.ProdError, "占位值"):
            prod_prepare.validate_env(values, prod_prepare.required_keys(ROOT / "docker-compose.prod.yml"))

    def test_rpc_token_rejects_wrong_signing_key(self) -> None:
        with self.assertRaisesRegex(prod_prepare.ProdError, "签名"):
            prod_prepare.verify_rpc_token(
                self.values["NEXUS_RPC_TOKEN"],
                self.values["NEXUS_JWT_APP_ID"],
                "0" * 64,
            )

    def test_production_configs_use_env_key_and_exact_topology(self) -> None:
        with self.runtime_env(), contextlib.redirect_stdout(io.StringIO()):
            prod_prepare.validate_configs(self.configs)
        for index, node in enumerate(prod_prepare.NODE_NAMES):
            path = self.configs / f"node{index}.json"
            actual = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(actual, prod_prepare.expected_config(index, node))
            self.assertEqual(actual["storage_key"], "")
            self.assertEqual(actual["storage_key_source"], "env")
            self.assertNotIn(self.values["MPC_STORAGE_KEY"], path.read_text(encoding="utf-8"))
            if not prod_prepare.windows_mount(path):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_config_generation_is_idempotent_and_refuses_drift(self) -> None:
        command = [
            BASH,
            "scripts/gen-mpc-engine-configs.sh",
            "--layout",
            "compose-prod",
            "--certs-dir",
            bash_path(self.certs),
            "-o",
            bash_path(self.configs),
        ]
        env = {**os.environ, "MSYS_NO_PATHCONV": "1"}
        before = {path.name: path.read_bytes() for path in self.configs.glob("node*.json")}
        same = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(same.returncode, 0, same.stderr)
        self.assertEqual({path.name: path.read_bytes() for path in self.configs.glob("node*.json")}, before)

        drifted = self.configs / "node1.json"
        original_bytes = drifted.read_bytes()
        data = json.loads(original_bytes)
        data["party_id"] = "wrong-party"
        drifted.write_text(json.dumps(data), encoding="utf-8")
        drifted_bytes = drifted.read_bytes()
        try:
            different = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, check=False)
            self.assertEqual(different.returncode, 3)
            self.assertEqual(drifted.read_bytes(), drifted_bytes)
        finally:
            drifted.write_bytes(original_bytes)

    def test_certificate_chain_san_eku_and_key_pairs(self) -> None:
        with self.runtime_env(), contextlib.redirect_stdout(io.StringIO()):
            prod_prepare.validate_certificates(self.certs, skip_openssl=False)

    def test_full_read_only_preflight(self) -> None:
        with self.runtime_env(), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(
                prod_prepare.check(ROOT, self.env_file, self.certs, self.configs, skip_openssl=False),
                0,
            )

    def test_runtime_owner_rejects_wrong_uid(self) -> None:
        with (
            mock.patch.dict(os.environ, {"NEXUS_MPC_RUNTIME_UID": "1000"}),
            mock.patch("prod_prepare.windows_mount", return_value=False),
            mock.patch("prod_prepare.Path.stat", return_value=SimpleNamespace(st_uid=1234)),
        ):
            with self.assertRaisesRegex(prod_prepare.ProdError, "UID 1000"):
                prod_prepare.check_runtime_owner(self.work / "key.pem")

    def test_signing_runtime_owner_rejects_wrong_uid(self) -> None:
        with (
            mock.patch.dict(os.environ, {"NEXUS_SIGNING_RUNTIME_UID": "65532"}),
            mock.patch("prod_prepare.windows_mount", return_value=False),
            mock.patch("prod_prepare.Path.stat", return_value=SimpleNamespace(st_uid=1234)),
        ):
            with self.assertRaisesRegex(prod_prepare.ProdError, "UID 65532"):
                prod_prepare.check_runtime_owner(
                    self.work / "key.pem",
                    "NEXUS_SIGNING_RUNTIME_UID",
                    "签名服务非 root",
                )

    @unittest.skipIf(os.name == "nt", "managed shell integration runs in POSIX CI; Windows uses manual Git Bash verification")
    def test_managed_workflow_blocks_start_when_resource_gate_fails(self) -> None:
        managed = self.work / "gate-managed"
        managed.mkdir()
        shutil.copytree(ROOT / "scripts", managed / "scripts")
        shutil.copy2(ROOT / "docker-compose.prod.yml", managed / "docker-compose.prod.yml")
        shutil.copy2(ROOT / ".env.prod.example", managed / ".env.prod.example")
        fake_bin = managed / "bin"
        fake_bin.mkdir()
        marker = managed / "compose-called"
        docker = fake_bin / "docker"
        docker.write_text(
            "#!/usr/bin/env bash\n"
            'if [ "$1" = info ]; then exit 0; fi\n'
            'if [ "$1" = compose ]; then touch compose-called; exit 0; fi\n'
            "exit 2\n",
            encoding="utf-8",
        )
        docker.chmod(0o700)
        bash_path_entries = [bash_path(fake_bin, repository_relative=False)]
        bash_path_entries.extend(bash_path(Path(item), repository_relative=False) for item in os.environ["PATH"].split(os.pathsep))
        env = {
            **os.environ,
            "PATH": ":".join(bash_path_entries),
            "NEX_MIN_AVAILABLE_RAM_MB": "999999999",
            "NEX_ENV_FILE": str(managed / ".env.prod"),
        }
        blocked = subprocess.run(
            [BASH, "scripts/ops/nc-task.sh", "compose-up"],
            cwd=managed,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(blocked.returncode, 2, blocked.stdout + blocked.stderr)
        receipt = json.loads(blocked.stdout.strip().splitlines()[-1])
        self.assertEqual(receipt["action"], "compose-up")
        self.assertFalse(receipt["ok"])
        self.assertIn("资源门禁未满足", receipt["reason"])
        self.assertFalse(marker.exists())

    @unittest.skipIf(os.name == "nt", "managed shell integration runs in POSIX CI; Windows uses manual Git Bash verification")
    def test_managed_workflow_generates_and_fails_closed(self) -> None:
        managed = self.work / "managed"
        managed.mkdir()
        shutil.copytree(ROOT / "scripts", managed / "scripts")
        shutil.copy2(ROOT / "docker-compose.prod.yml", managed / "docker-compose.prod.yml")
        shutil.copy2(ROOT / ".env.prod.example", managed / ".env.prod.example")
        fake_bin = managed / "bin"
        fake_bin.mkdir()
        marker = managed / "compose-called"
        docker = fake_bin / "docker"
        docker.write_text(
            "#!/usr/bin/env bash\n"
            'if [ "$1" = info ]; then exit 0; fi\n'
            'if [ "$1" = compose ]; then touch compose-called; exit 0; fi\n'
            "exit 2\n",
            encoding="utf-8",
        )
        docker.chmod(0o700)
        bash_path_entries = [bash_path(fake_bin, repository_relative=False)]
        bash_path_entries.extend(bash_path(Path(item), repository_relative=False) for item in os.environ["PATH"].split(os.pathsep))
        env = {
            **os.environ,
            "PATH": ":".join(bash_path_entries),
            "NEXUS_MPC_RUNTIME_UID": str(os.getuid()),
            "NEXUS_SIGNING_RUNTIME_UID": str(os.getuid()),
            "NEX_MIN_AVAILABLE_RAM_MB": "1",
        }
        command = [BASH, "scripts/ops/nc-task.sh"]

        prepared = subprocess.run(command + ["prod-prepare"], cwd=managed, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(prepared.returncode, 0, prepared.stdout + prepared.stderr)
        receipt = json.loads(prepared.stdout.strip().splitlines()[-1])
        self.assertEqual(receipt["action"], "prod-prepare")
        self.assertTrue(receipt["ok"])
        values = prod_prepare.parse_env(managed / ".env.prod")
        for key in ("MPC_STORAGE_KEY", "JWT_SECRET", "NEXUS_RPC_TOKEN", "NEXUS_JWT_APP_SECRET"):
            self.assertNotIn(values[key], prepared.stdout + prepared.stderr)

        checked = subprocess.run(command + ["prod-check"], cwd=managed, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
        self.assertTrue(json.loads(checked.stdout.strip().splitlines()[-1])["ok"])
        self.assertTrue(marker.is_file())
        marker.unlink()

        env_file = managed / ".env.prod"
        env_file.write_text(
            env_file.read_text(encoding="utf-8").replace(
                f"MPC_AUTH_TOKEN={values['MPC_AUTH_TOKEN']}",
                "MPC_AUTH_TOKEN=DEV-MPC-ENGINE-TOKEN-CHANGE-IN-PROD",
            ),
            encoding="utf-8",
        )
        blocked = subprocess.run(command + ["compose-up"], cwd=managed, env=env, capture_output=True, text=True, check=False)
        self.assertEqual(blocked.returncode, 1, blocked.stdout + blocked.stderr)
        receipt = json.loads(blocked.stdout.strip().splitlines()[-1])
        self.assertEqual(receipt["action"], "compose-up")
        self.assertFalse(receipt["ok"])
        self.assertIn("拒绝启动", receipt["reason"])
        self.assertFalse(marker.exists())

    def test_compose_has_minimal_mounts_and_no_ca_key(self) -> None:
        with contextlib.redirect_stdout(io.StringIO()):
            prod_prepare.validate_compose(ROOT)


if __name__ == "__main__":
    unittest.main(verbosity=2)
