#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import start_gate  # noqa: E402


class StartGateTests(unittest.TestCase):
    def test_available_ram_mb_reads_linux_meminfo(self) -> None:
        with (
            mock.patch("start_gate.platform.system", return_value="Linux"),
            mock.patch(
                "start_gate.Path.read_text",
                return_value="MemTotal: 32768000 kB\nMemAvailable: 16384000 kB\n",
            ),
        ):
            self.assertEqual(start_gate.available_ram_mb(), 16000)

    def test_available_ram_mb_fails_closed_without_memavailable(self) -> None:
        with (
            mock.patch("start_gate.platform.system", return_value="Linux"),
            mock.patch(
                "start_gate.Path.read_text",
                return_value="MemTotal: 32768000 kB\n",
            ),
        ):
            with self.assertRaises(start_gate.GateError):
                start_gate.available_ram_mb()

    def test_available_ram_mb_parses_windows_powershell(self) -> None:
        result = mock.Mock()
        result.returncode = 0
        result.stdout = "12582912\n"
        with (
            mock.patch("start_gate.platform.system", return_value="Windows"),
            mock.patch("start_gate.subprocess.run", return_value=result),
        ):
            self.assertEqual(start_gate.available_ram_mb(), 12288)

    def test_available_ram_mb_fails_closed_when_powershell_missing(self) -> None:
        def raise_os_error(*args, **kwargs):
            raise OSError("no powershell")

        with (
            mock.patch("start_gate.platform.system", return_value="Windows"),
            mock.patch("start_gate.subprocess.run", side_effect=raise_os_error),
        ):
            with self.assertRaises(start_gate.GateError):
                start_gate.available_ram_mb()

    def test_check_start_resources_passes_when_above_threshold(self) -> None:
        with mock.patch("start_gate.available_ram_mb", return_value=16384):
            self.assertEqual(start_gate.check_start_resources(12288), 16384)

    def test_check_start_resources_blocks_when_below_threshold(self) -> None:
        with mock.patch("start_gate.available_ram_mb", return_value=8192):
            with self.assertRaisesRegex(start_gate.GateError, "资源门禁未满足"):
                start_gate.check_start_resources(12288)

    def test_min_ram_mb_env_override(self) -> None:
        with mock.patch.dict(
            __import__("os").environ, {"NEX_MIN_AVAILABLE_RAM_MB": "16384"}
        ):
            self.assertEqual(start_gate.min_ram_mb(), 16384)

    def test_min_ram_mb_rejects_negative(self) -> None:
        with mock.patch.dict(__import__("os").environ, {"NEX_MIN_AVAILABLE_RAM_MB": "-1"}):
            with self.assertRaisesRegex(start_gate.GateError, "非负整数"):
                start_gate.min_ram_mb()

    def test_cli_json_reports_gate_failure(self) -> None:
        result = subprocess.run(
            [sys.executable, "scripts/start_gate.py", "--json", "--min-mb", "999999999"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn('"ok": false', result.stdout)

    def test_cli_json_reports_gate_success(self) -> None:
        result = subprocess.run(
            [sys.executable, "scripts/start_gate.py", "--json", "--min-mb", "1"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('"ok": true', result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
