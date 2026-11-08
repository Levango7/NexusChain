"""trivy-db-consistency.py 的自测：同源绿、异源红、缺腿红。"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "trivy-db-consistency.py")


def run(dirpath: str, expected_count: int) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, SCRIPT, "--dir", dirpath, "--expected-count", str(expected_count)],
        capture_output=True, text=True, encoding="utf-8",
    )


def write(dirpath: str, name: str, sha: str) -> None:
    with open(os.path.join(dirpath, name), "w", encoding="utf-8") as f:
        json.dump({"Version": 2, "dbSha256": sha}, f)


def main() -> int:
    failures = 0
    total = 0

    with tempfile.TemporaryDirectory() as tmp:
        for i in range(13):
            write(tmp, f"db-fingerprint-module-{i}.json", "sha-A")
        case = run(tmp, 13)
        total += 1
        ok = case.returncode == 0 and "[DB-AGG]" in case.stdout
        print(f"[{'PASS' if ok else 'FAIL'}] 13 腿同源 → 绿")
        if not ok:
            failures += 1
            print(case.stdout + case.stderr)

        write(tmp, "db-fingerprint-module-0.json", "sha-B")
        case = run(tmp, 13)
        total += 1
        ok = case.returncode == 1 and "指纹不一致" in case.stdout
        print(f"[{'PASS' if ok else 'FAIL'}] 任一腿异源 → 判红")
        if not ok:
            failures += 1
            print(case.stdout + case.stderr)

        case = run(tmp, 14)
        total += 1
        ok = case.returncode == 1 and "腿数不一致" in case.stdout
        print(f"[{'PASS' if ok else 'FAIL'}] 缺腿（数量不足）→ 判红")
        if not ok:
            failures += 1
            print(case.stdout + case.stderr)

    print(f"\n结果：{total - failures}/{total} 通过")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
