#!/usr/bin/env python3
"""trivy-baseline-gate.py 的 fixture 回归测试（不依赖 trivy 二进制）。

fixture 中的 Trivy 报告取自 2026-11-05 master 实扫产物
（run 41997420165 → trivy-staleness-raw），字段结构与真实 trivy -f json 一致；
新增/修复版场景在此结构上补字段。运行：

  python3 scripts/security/test_trivy_baseline_gate.py

任一条用例失败即退出码 1。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.join(HERE, "trivy-baseline-gate.py")
FIXTURES = os.path.join(HERE, "fixtures")
TODAY = "2026-11-06"


def run(report: str, baseline: str, today: str = TODAY, extra: list[str] | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            sys.executable,
            GATE,
            "--baseline",
            os.path.join(FIXTURES, baseline),
            "--report",
            os.path.join(FIXTURES, report),
            "--today",
            today,
            *(extra or []),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


CASES = [
    (
        "已知项不红",
        "report-known-only.json",
        "baseline-known.json",
        0,
        ["[PASS]", "基线命中 1 条"],
    ),
    (
        "新增项精确红（只报新增那条）",
        "report-known-plus-new.json",
        "baseline-known.json",
        1,
        ["[NEW] CVE-2026-999001", "[FAIL] 门禁判红：新增 1 条"],
    ),
    (
        "到期项精确红",
        "report-known-only.json",
        "baseline-expired.json",
        1,
        ["[EXPIRED] CVE-2026-113351", "已于 2026-10-01 到期"],
    ),
    (
        "无到期日被拒收",
        "report-known-only.json",
        "baseline-no-expiry.json",
        2,
        ["缺少 expires", "[REJECT]"],
    ),
    (
        "有修复版却登记普通豁免被拒",
        "report-known-plus-new.json",
        "baseline-fixable.json",
        1,
        ["[POLICY] CVE-2026-999001", "禁止按普通基线豁免"],
    ),
    (
        "not-applicable 但该二元组报出修复版 → 策略违规判红",
        "report-fixable-only.json",
        "baseline-fix-exception.json",
        1,
        ["[POLICY] CVE-2026-999001", "fixable=not-applicable 也不适用"],
    ),
    (
        "MEDIUM 不判红",
        "report-medium-only.json",
        "baseline-known.json",
        0,
        ["[PASS]", "命中 0 条"],
    ),
    (
        "到期日越界被拒收",
        "report-known-only.json",
        "baseline-horizon-too-far.json",
        2,
        ["超出最长有效期 180 天", "[REJECT]"],
    ),
    (
        "not-applicable 恰为 90 天边界放行（UTC 边界，该二元组无修复版）",
        "report-no-fix-only.json",
        "baseline-fix-exception-exact90.json",
        0,
        ["[PASS]", "基线命中 1 条"],
    ),
    (
        "not-applicable 超出 90 天被拒收",
        "report-no-fix-only.json",
        "baseline-fix-exception-over90.json",
        2,
        ["超出最长有效期 90 天", "[REJECT]"],
    ),
    (
        "① 同 CVE 不同包：未登记的包必须 NEW 判红（不再跨包豁免）",
        "report-guava-two-pkgs.json",
        "baseline-guava-shaded-only.json",
        1,
        ["[NEW] CVE-2026-777777 / com.google.guava:guava", "[FAIL] 门禁判红：新增 1 条"],
    ),
    (
        "③ 同一 (CVE, 包) 正常命中 → 绿",
        "report-guava-shaded-only.json",
        "baseline-guava-shaded-only.json",
        0,
        ["[PASS]", "基线命中 1 条"],
    ),
    (
        "④ 同 CVE 两个包都已登记 → 各自独立判定（有修复版的那个仍判红）",
        "report-guava-two-pkgs.json",
        "baseline-guava-both.json",
        1,
        [
            "[POLICY] CVE-2026-777777 / com.google.guava:guava",
            "禁止按普通基线豁免",
            "有修复版却登记豁免 1 条",
        ],
    ),
    (
        "② package 为通配 * → 退出码 2",
        "report-known-only.json",
        "baseline-wildcard-package.json",
        2,
        ["含通配符", "[REJECT]"],
    ),
    (
        "② package 为空 → 退出码 2",
        "report-known-only.json",
        "baseline-empty-package.json",
        2,
        ["缺少字段 package", "[REJECT]"],
    ),
    (
        "干净报告（Results 为空数组）不误判红",
        "report-clean-empty.json",
        "baseline-known.json",
        0,
        ["[PASS]", "命中 0 条"],
    ),
    (
        "干净报告（Trivy 省略 Results 键）不误判红",
        "report-clean-omitted.json",
        "baseline-known.json",
        0,
        ["[PASS]", "命中 0 条"],
    ),
    (
        "结构不可识别的报告被拒收",
        "report-unrecognizable.json",
        "baseline-known.json",
        2,
        ["报告结构不可识别", "[REJECT]"],
    ),
    (
        "报告缺失拒收",
        "does-not-exist.json",
        "baseline-known.json",
        2,
        ["报告不可读"],
    ),
    (
        "stale 超过 90 天给出复核提示（不判红）",
        "report-clean-empty.json",
        "baseline-stale-aged.json",
        0,
        ["[STALE-AGED]", "已 158 天未命中", "[PASS]"],
    ),
]

# (名称, 报告, 基线, 不允许出现的片段)
ABSENT_CASES = [
    (
        "stale 不足 90 天不出复核提示",
        "report-clean-empty.json",
        "baseline-stale-fresh.json",
        ["[STALE-AGED]"],
    ),
]


def check_summary_json() -> tuple[bool, str]:
    """--summary-json 要与文本结论一致（下游 issue 分诊依赖它）。"""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "summary.json")
        result = run(
            "report-known-plus-new.json",
            "baseline-known.json",
            extra=["--summary-json", out],
        )
        if result.returncode != 1:
            return False, f"期望退出码 1，实际 {result.returncode}"
        try:
            with open(out, encoding="utf-8") as f:
                summary = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            return False, f"summary JSON 不可读：{e}"
        if summary["counts"]["new"] != 1:
            return False, f"counts.new 应为 1，实际 {summary['counts']['new']}"
        if [item["id"] for item in summary["new"]] != ["CVE-2026-999001"]:
            return False, f"new 列表不符：{summary['new']}"
        if summary["errors"]:
            return False, f"errors 应为空：{summary['errors']}"
        return True, ""


def main() -> int:
    failures = 0
    total = 0
    for name, report, baseline, expected_code, expected_snippets in CASES:
        total += 1
        result = run(report, baseline)
        output = result.stdout + result.stderr
        missing = [s for s in expected_snippets if s not in output]
        ok = result.returncode == expected_code and not missing
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {name}（期望退出码 {expected_code}，实际 {result.returncode}）")
        if not ok:
            failures += 1
            if missing:
                print(f"  缺少输出片段：{missing}")
            print("  --- 实际输出 ---")
            for line in output.splitlines():
                print(f"  {line}")

    for name, report, baseline, absent_snippets in ABSENT_CASES:
        total += 1
        result = run(report, baseline)
        output = result.stdout + result.stderr
        present = [s for s in absent_snippets if s in output]
        ok = result.returncode == 0 and not present
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {name}（期望退出码 0，实际 {result.returncode}）")
        if not ok:
            failures += 1
            if present:
                print(f"  不应出现却出现了：{present}")

    total += 1
    ok, reason = check_summary_json()
    print(f"[{'PASS' if ok else 'FAIL'}] --summary-json 输出与结论一致")
    if not ok:
        failures += 1
        print(f"  {reason}")

    print()
    print(f"结果：{total - failures}/{total} 通过")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
