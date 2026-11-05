#!/usr/bin/env python3
"""trivy-baseline-gate.py 的 fixture 回归测试（不依赖 trivy 二进制）。

fixture 中的 Trivy 报告取自 2026-11-05 master 实扫产物
（run 41997420165 → trivy-staleness-raw），字段结构与真实 trivy -f json 一致；
新增/修复版场景在此结构上补字段。运行：

  python3 scripts/security/test_trivy_baseline_gate.py

任一条用例失败即退出码 1。
"""

from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GATE = os.path.join(HERE, "trivy-baseline-gate.py")
FIXTURES = os.path.join(HERE, "fixtures")
TODAY = "2026-11-06"


def run(report: str, baseline: str, today: str = TODAY) -> subprocess.CompletedProcess:
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
        "not-applicable 例外放行但留 warning",
        "report-fixable-only.json",
        "baseline-fix-exception.json",
        0,
        ["[EXCEPTION] CVE-2026-999001", "::warning title=修复版不可用的基线豁免::"],
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
]


def main() -> int:
    failures = 0
    for name, report, baseline, expected_code, expected_snippets in CASES:
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
    print()
    print(f"结果：{len(CASES) - failures}/{len(CASES)} 通过")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
