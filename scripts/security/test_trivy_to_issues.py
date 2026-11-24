#!/usr/bin/env python3
"""Regression tests for Trivy issue creation and in-run deduplication."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).with_name("trivy-to-issues.py")
SPEC = importlib.util.spec_from_file_location("trivy_to_issues", MODULE_PATH)
triage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(triage)


class TriageIssueCreationTest(unittest.TestCase):
    def test_created_issue_is_appended_and_deduplicates_same_cve(self):
        report = {
            "Results": [
                {
                    "Target": "image-one",
                    "Class": "os-pkgs",
                    "Vulnerabilities": [{
                        "VulnerabilityID": "CVE-2099-0001",
                        "PkgName": "libone",
                        "InstalledVersion": "1",
                        "Severity": "HIGH",
                    }],
                },
                {
                    "Target": "image-two",
                    "Class": "os-pkgs",
                    "Vulnerabilities": [{
                        "VulnerabilityID": "CVE-2099-0001",
                        "PkgName": "libtwo",
                        "InstalledVersion": "1",
                        "Severity": "HIGH",
                    }],
                },
            ]
        }
        existing = []
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "report.json"
            report_path.write_text(json.dumps(report), encoding="utf-8")
            argv = [
                "trivy-to-issues.py",
                "--report", str(report_path),
                "--repo", "example/repository",
            ]
            with (
                mock.patch.object(sys, "argv", argv),
                mock.patch.object(triage, "list_existing_issues", return_value=existing),
                mock.patch.object(triage, "ensure_labels"),
                mock.patch.object(triage, "create_issue", return_value=9001) as create_issue,
                contextlib.redirect_stdout(io.StringIO()),
            ):
                result = triage.main()

        self.assertEqual(result, 0)
        self.assertEqual(create_issue.call_count, 1)
        self.assertEqual(len(existing), 1)
        self.assertEqual(existing[0]["number"], 9001)
        self.assertEqual(
            existing[0]["title"],
            "[Security/HIGH] CVE-2099-0001 — libone",
        )
        self.assertIn("CVE-2099-0001", existing[0]["body"])


if __name__ == "__main__":
    unittest.main()
