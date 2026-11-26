#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASH = "bash"
sys.path.insert(0, str(ROOT / "scripts" / "ops"))

import j4_summary  # noqa: E402


def abs_posix_path(path: Path) -> str:
    value = path.resolve().as_posix()
    if os.name == "nt" and len(value) >= 3 and value[1:3] == ":/":
        return f"/{value[0].lower()}{value[2:]}"
    return value


class J4EvidenceTests(unittest.TestCase):
    def setUp(self) -> None:
        (ROOT / "build").mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="nexus-j4-ev-", dir=ROOT / "build")
        self.dir = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def receipt(self, name: str, ok: bool, exit_code: int, reason: str) -> None:
        (self.dir / f"{name}.json").write_text(
            json.dumps(
                {
                    "action": name,
                    "ok": ok,
                    "exitCode": exit_code,
                    "reason": reason,
                    "ts": "2026-11-26T00:00:00Z",
                    "elapsedMs": 0,
                    "detail": {},
                }
            ),
            encoding="utf-8",
        )

    def test_summary_passes_when_all_required_ok(self) -> None:
        for name in j4_summary.REQUIRED_NO_START:
            self.receipt(name, True, 0, "PASS")
        rc = j4_summary.main([str(self.dir), "--no-start"])
        self.assertEqual(rc, 0)
        summary = json.loads((self.dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["verdict"], "PASS")
        self.assertEqual(summary["skip"], [])
        self.assertEqual(summary["fail"], [])

    def test_summary_fails_when_required_missing(self) -> None:
        self.receipt("02-resource-gate", True, 0, "PASS")
        rc = j4_summary.main([str(self.dir), "--no-start"])
        self.assertEqual(rc, 1)
        summary = json.loads((self.dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["verdict"], "FAIL")
        self.assertIn("03-prod-check", summary["fail"])

    def test_summary_fails_when_skip_present(self) -> None:
        for name in j4_summary.REQUIRED_FULL:
            if name == "06-acceptance":
                self.receipt(name, False, 2, "SKIP: NEX_API_KEY 未设置")
            else:
                self.receipt(name, True, 0, "PASS")
        rc = j4_summary.main([str(self.dir)])
        self.assertEqual(rc, 1)
        summary = json.loads((self.dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["verdict"], "FAIL")
        self.assertIn("06-acceptance", summary["skip"])
        self.assertNotIn("06-acceptance", summary["pass"])

    def test_no_start_mode_does_not_start_services(self) -> None:
        managed = self.dir / "managed"
        managed.mkdir()
        shutil.copytree(ROOT / "scripts", managed / "scripts")
        shutil.copy2(ROOT / "docker-compose.prod.yml", managed / "docker-compose.prod.yml")
        shutil.copy2(ROOT / ".env.prod.example", managed / ".env.prod.example")
        prepare = subprocess.run(
            [BASH, "scripts/ops/nc-task.sh", "prod-prepare"],
            cwd=managed,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(prepare.returncode, 0, prepare.stdout + prepare.stderr)
        result = subprocess.run(
            [
                BASH,
                "scripts/ops/j4-evidence.sh",
                "evidence",
                "--no-start",
                "--min-ram-mb",
                "1",
            ],
            cwd=managed,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((managed / "evidence" / "no-start.flag").is_file())
        self.assertFalse((managed / "evidence" / "04-compose-up.out").exists())
        summary = json.loads(
            (managed / "evidence" / "summary.json").read_text(encoding="utf-8")
        )
        self.assertEqual(summary["verdict"], "PASS")
        self.assertTrue(summary["noStart"])
        self.assertEqual(summary["skip"], [])

    def test_no_start_mode_blocks_on_resource_gate(self) -> None:
        result = subprocess.run(
            [
                "bash",
                "scripts/ops/j4-evidence.sh",
                abs_posix_path(self.dir / "evidence-blocked"),
                "--no-start",
                "--min-ram-mb",
                "999999999",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("资源门禁未满足", result.stdout + result.stderr)
        self.assertFalse((self.dir / "04-compose-up.out").exists())

    def test_usage_requires_evidence_dir(self) -> None:
        result = subprocess.run(
            ["bash", "scripts/ops/j4-evidence.sh"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
