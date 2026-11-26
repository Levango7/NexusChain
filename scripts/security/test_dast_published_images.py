#!/usr/bin/env python3
"""Contract tests for DAST reuse of the exact published commit images."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "security-scan.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
COMPOSE = ROOT / "docker-compose.dast.yml"


class DastPublishedImagesTest(unittest.TestCase):
    @staticmethod
    def assert_contract(workflow: str, compose: str) -> None:
        if "  published-artifact-ready:\n" not in workflow:
            raise AssertionError("publication readiness job is missing")
        ready = workflow.split("  published-artifact-ready:\n", 1)[1].split(
            "\n  trivy-docker-scan:", 1
        )[0]
        if "timeout-minutes: 310" not in ready:
            raise AssertionError("publication readiness lacks its bounded 310-minute budget")
        if "  dast-zap-baseline:\n" not in workflow:
            raise AssertionError("DAST job is missing")
        job = workflow.split("  dast-zap-baseline:\n", 1)[1].split(
            "\n  security-issue-triage:", 1
        )[0]
        required_job_fragments = (
            "needs: [published-artifact-ready]",
            "SCAN_PULL_ATTEMPTS=1 SCAN_WAIT_SECONDS=0 bash scripts/security/prepare_scan_image.sh nexus-core",
            "SCAN_PULL_ATTEMPTS=1 SCAN_WAIT_SECONDS=0 bash scripts/security/prepare_scan_image.sh nexus-gateway",
            "$COMPOSE up -d --no-build nexus-gateway",
        )
        for fragment in required_job_fragments:
            if fragment not in job:
                raise AssertionError(f"missing DAST contract: {fragment}")
        if "image: nexus-core:scan" not in compose:
            raise AssertionError("DAST core image is not pinned to the prepared local tag")
        if "image: nexus-gateway:scan" not in compose:
            raise AssertionError("DAST gateway image is not pinned to the prepared local tag")

    def test_current_workflow_uses_published_images_without_rebuilding(self) -> None:
        self.assert_contract(
            WORKFLOW.read_text(encoding="utf-8"),
            COMPOSE.read_text(encoding="utf-8"),
        )

    def test_mutation_battery_turns_each_missing_guard_red(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        compose = COMPOSE.read_text(encoding="utf-8")
        mutations = (
            ("timeout-minutes: 310", "timeout-minutes: 100", workflow, compose),
            ("needs: [published-artifact-ready]", "needs: []", workflow, compose),
            (
                "prepare_scan_image.sh nexus-core",
                "prepare_scan_image.sh nexus-oracle",
                workflow,
                compose,
            ),
            (
                "$COMPOSE up -d --no-build nexus-gateway",
                "$COMPOSE up -d nexus-gateway",
                workflow,
                compose,
            ),
            (
                "image: nexus-core:scan",
                "image: nexus-core:rebuilt",
                workflow,
                compose,
            ),
            (
                "image: nexus-gateway:scan",
                "image: nexus-gateway:rebuilt",
                workflow,
                compose,
            ),
        )
        for old, new, source_workflow, source_compose in mutations:
            with self.subTest(mutation=old):
                self.assertTrue(
                    old in source_workflow + source_compose,
                    f"mutation anchor is absent: {old}",
                )
                mutated_workflow = source_workflow.replace(old, new)
                mutated_compose = source_compose.replace(old, new)
                with self.assertRaises(AssertionError):
                    self.assert_contract(mutated_workflow, mutated_compose)


    @staticmethod
    def assert_ci_contract(workflow: str) -> None:
        if "  docker-build:\n" not in workflow:
            raise AssertionError("Docker publication job is missing")
        job = workflow.split("  docker-build:\n", 1)[1]
        required_fragments = (
            "matrix.timeout-minutes || 90",
            "uses: docker/setup-buildx-action@v3",
            "cache-from: type=gha,scope=nexuschain-master",
            "cache-to: type=gha,mode=max,scope=nexuschain-master,ignore-error=true",
            "裁决（至少一次成功才绿）",
            "cancelled/skipped 不代表实际执行过三次重试",
        )
        for fragment in required_fragments:
            if fragment not in job:
                raise AssertionError(f"missing CI contract: {fragment}")
        if job.count("cache-from: type=gha,scope=nexuschain-master") != 3:
            raise AssertionError("all three publication attempts must read the same cache")
        if job.count("cache-to: type=gha,mode=max,scope=nexuschain-master,ignore-error=true") != 3:
            raise AssertionError("all three publication attempts must export the same cache")
        if "timeout-minutes: 40" in job:
            raise AssertionError("a Java module still overrides the 90-minute cold-build budget")
        if "docker build/push 连续 3 次失败" in job:
            raise AssertionError("cancelled attempts are still misreported as three failures")

    def test_ci_timeout_and_cancelled_verdict_contract(self) -> None:
        self.assert_ci_contract(CI_WORKFLOW.read_text(encoding="utf-8"))

    def test_ci_contract_mutation_battery(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        for old, new in (
            ("matrix.timeout-minutes || 90", "matrix.timeout-minutes || 60"),
            ("uses: docker/setup-buildx-action@v3", "uses: docker/setup-buildx-action@v2"),
            (
                "cache-from: type=gha,scope=nexuschain-master",
                "cache-from: type=local,src=/missing",
            ),
            ("裁决（至少一次成功才绿）", "裁决（三次皆失败才红）"),
            (
                "cancelled/skipped 不代表实际执行过三次重试",
                "三次尝试均已执行",
            ),
        ):
            with self.subTest(mutation=old):
                self.assertTrue(old in workflow, f"mutation anchor is absent: {old}")
                with self.assertRaises(AssertionError):
                    self.assert_ci_contract(workflow.replace(old, new))


if __name__ == "__main__":
    unittest.main()
