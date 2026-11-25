#!/usr/bin/env python3
"""Tests for the explicit CI publication readiness gate."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("wait_published_images", Path(__file__).with_name("wait_published_images.py"))
wait_published_images = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(wait_published_images)
SHA = "b" * 40


def run_payload(run):
    return {"workflow_runs": [] if run is None else [run]}


def jobs_payload(conclusions):
    return {"jobs": [
        {"name": f"Docker Build & Push ({module})", "conclusion": conclusion}
        for module, conclusion in conclusions.items()
    ]}


class WaitPublishedImagesTest(unittest.TestCase):
    def wait(self, payloads):
        calls = []
        sleeps = []

        def fetch(url):
            calls.append(url)
            return payloads.pop(0)

        result = wait_published_images.wait_for_images(
            "owner/repository", SHA, fetch=fetch, sleeper=sleeps.append,
            max_attempts=5, wait_seconds=0,
        )
        return result, calls, sleeps

    def test_waits_for_run_registration_and_all_13_images(self):
        ci_run = {"id": 42, "name": wait_published_images.CI_WORKFLOW_NAME, "conclusion": "in_progress"}
        partial = {module: "success" for module in wait_published_images.MODULES[:-1]}
        partial[wait_published_images.MODULES[-1]] = "in_progress"
        complete = {module: "success" for module in wait_published_images.MODULES}

        result, calls, sleeps = self.wait([
            run_payload(None),
            run_payload(ci_run), jobs_payload(partial),
            run_payload(ci_run), jobs_payload(complete),
        ])

        self.assertEqual(result, 0)
        self.assertEqual(len(sleeps), 2)
        self.assertTrue(any("runs/42/jobs" in call for call in calls))

    def test_publication_failure_fails_immediately(self):
        conclusions = {module: "success" for module in wait_published_images.MODULES}
        conclusions["nexus-core"] = "cancelled"
        with self.assertRaisesRegex(RuntimeError, "nexus-core"):
            self.wait([
                run_payload({"id": 42, "name": wait_published_images.CI_WORKFLOW_NAME,
                             "conclusion": "cancelled"}),
                jobs_payload(conclusions),
            ])

    def test_timeout_when_ci_never_registers(self):
        with self.assertRaises(TimeoutError):
            wait_published_images.wait_for_images(
                "owner/repository", SHA, fetch=lambda _: run_payload(None),
                sleeper=lambda _: None, max_attempts=2, wait_seconds=0,
            )

    def test_ignores_unrelated_workflow_with_same_sha(self):
        with self.assertRaises(TimeoutError):
            wait_published_images.wait_for_images(
                "owner/repository", SHA,
                fetch=lambda _: run_payload({"id": 9, "name": "NexusChain Security Scan"}),
                sleeper=lambda _: None, max_attempts=2, wait_seconds=0,
            )

    def test_invalid_inputs_do_not_fetch(self):
        for repository, sha in (("bad", SHA), ("owner/repository", "abc")):
            with self.assertRaises(ValueError):
                wait_published_images.wait_for_images(repository, sha, fetch=lambda _: (_ for _ in ()).throw(AssertionError))


if __name__ == "__main__":
    unittest.main()
