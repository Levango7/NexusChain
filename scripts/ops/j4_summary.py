#!/usr/bin/env python3
"""汇总 J4 证据目录，生成 summary.json；SKIP 不计为 PASS。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REQUIRED_NO_START = ["02-resource-gate", "03-prod-check"]
REQUIRED_FULL = [
    "02-resource-gate",
    "03-prod-check",
    "04-compose-up",
    "05-doctor",
    "06-acceptance",
    "07-status",
    "08-compose-down",
]


def load_receipt(directory: Path, name: str) -> dict:
    path = directory / f"{name}.json"
    if not path.is_file():
        return {"action": name, "ok": False, "exitCode": 2, "reason": "missing"}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"action": name, "ok": False, "exitCode": 1, "reason": "invalid json"}


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) < 1:
        print("用法：python j4_summary.py <证据目录> [--no-start]", file=sys.stderr)
        return 2
    directory = Path(args[0]).resolve()
    no_start = "--no-start" in args[1:]
    required = REQUIRED_NO_START if no_start else REQUIRED_FULL
    steps = {}
    for name in required:
        receipt = load_receipt(directory, name)
        steps[name] = {
            "ok": bool(receipt.get("ok")),
            "exitCode": receipt.get("exitCode"),
            "reason": receipt.get("reason"),
        }
    skip = [name for name in required if "SKIP" in str(steps[name]["reason"])]
    passed = [name for name in required if steps[name]["ok"]]
    failed = [name for name in required if not steps[name]["ok"]]
    summary = {
        "evidenceDir": str(directory),
        "noStart": no_start,
        "required": required,
        "pass": passed,
        "fail": failed,
        "skip": skip,
        "verdict": "PASS" if not failed and not skip else "FAIL",
        "skipDoesNotCountAsPass": True,
    }
    (directory / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["verdict"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
