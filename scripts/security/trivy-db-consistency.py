"""聚合 job 校验：13 条腿扫描所用的 DB 指纹必须完全一致。

共享库方案里，prep 下载一次 DB，各腿下载同一 artifact 后各自断言新鲜度并
打印 `[DB-LEG] sha256=...`。本脚本把这些指纹汇总，回答一个可核的问题：
**这 13 条腿是不是在同一份库上判断的？**

退出码：0 一致 / 1 不一致或缺失（聚合 job 必须因此判红）。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="校验多腿 Trivy DB 指纹一致")
    parser.add_argument("--dir", required=True, help="各腿指纹 JSON 所在目录")
    parser.add_argument("--expected-count", type=int, required=True, help="期望的腿数")
    args = parser.parse_args()

    paths = sorted(glob.glob(os.path.join(args.dir, "*.json")))
    fingerprints: list[tuple[str, str]] = []
    errors: list[str] = []

    for path in paths:
        name = os.path.basename(path)
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            errors.append(f"{name} 不可读：{e}")
            continue
        sha = data.get("dbSha256") if isinstance(data, dict) else None
        if not sha:
            errors.append(f"{name} 缺少 dbSha256")
            continue
        fingerprints.append((name, sha))
        print(f"[DB-LEG] {name} sha256={sha}")

    if len(fingerprints) != args.expected_count:
        errors.append(
            f"腿数不一致：期望 {args.expected_count} 条指纹，实际 {len(fingerprints)} 条"
        )

    distinct = sorted({sha for _, sha in fingerprints})
    if len(distinct) > 1:
        detail = "；".join(f"{name}={sha}" for name, sha in fingerprints)
        errors.append(f"13 腿 DB 指纹不一致（{len(distinct)} 种）：{detail}")

    if errors:
        for message in errors:
            print(f"[DB-AGG][FAIL] {message}")
            print(f"::error title=Trivy DB 指纹不一致::{message}")
        return 1

    print(f"[DB-AGG] {len(fingerprints)} 腿 DB 指纹一致 sha256={distinct[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
