#!/usr/bin/env python3
"""ShedLock 方法签名守卫（PLAN-056）。

背景：PLAN-055 实测发现 ShedLock 4.x 拒绝 primitive 返回类型的
`@SchedulerLock` 方法——AOP 在调用前抛
`LockingNotSupportedException: Can not lock method returning primitive value`，
导致 `BridgeEventSourceWorker` / `MessageRelayWorker` 的调度循环自装配
`relay-enabled=true` 以来从未真正执行（单测用 `new` + 手工 `runOnce()` 绕开
Spring AOP，所以全绿）。

本脚本扫描 Java 源，找出 `@SchedulerLock` 注解所在方法的返回类型，
primitive 返回 ⇒ FAIL（循环会死）；void / 包装类型 / 对象类型 ⇒ PASS。

用法：
    check-shedlock-eligibility.py [--path <src-root>] [--json]

退出码：0 = 全部通过；1 = 发现 primitive 返回（附文件/行号/方法名）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

PRIMITIVES = {
    "int": "int",
    "long": "long",
    "double": "double",
    "float": "float",
    "boolean": "boolean",
    "char": "char",
    "byte": "byte",
    "short": "short",
}

# 匹配 @SchedulerLock(... 之后最近的 "返回类型 方法名(" 行。
SCHEDULER_LOCK_RE = re.compile(r"@\s*SchedulerLock\s*\(")
METHOD_RE = re.compile(
    r"^\s*(?:(public|private|protected|static|final|synchronized|native|abstract|\s)+)\s+"
    r"(?P<ret>[\w\.<>\[\],\s\$]+?)\s+(?P<name>\w+)\s*\(",
)


def scan_file(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    hits: list[dict] = []
    for idx, line in enumerate(lines):
        if not SCHEDULER_LOCK_RE.search(line):
            continue
        # 向下找最近的方法签名（注解与声明之间可能有注释/换行）
        for jdx in range(idx + 1, min(idx + 12, len(lines))):
            match = METHOD_RE.match(lines[jdx])
            if match:
                ret = match.group("ret").strip()
                method = match.group("name").strip()
                ret_base = ret.split("<")[0].split(".")[-1].strip()
                hits.append(
                    {
                        "file": path,
                        "line": idx + 1,
                        "method": method,
                        "return": ret,
                        "primitive": ret_base in PRIMITIVES,
                    }
                )
                break
    return hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--path", default="nexus-bridge/src/main/java")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    root = os.path.abspath(args.path)
    if not os.path.isdir(root):
        print(f"FAIL: path not found: {root}")
        return 1

    all_hits: list[dict] = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if name.endswith(".java"):
                all_hits.extend(scan_file(os.path.join(dirpath, name)))

    bad = [h for h in all_hits if h["primitive"]]
    good = [h for h in all_hits if not h["primitive"]]

    if args.json:
        print(json.dumps({"total": len(all_hits), "bad": bad, "ok": len(good)}, indent=2))
    else:
        print(f"ShedLock 方法签名扫描: {root}")
        print(f"  @SchedulerLock 方法: {len(all_hits)}")
        for hit in good:
            print(f"  PASS: {hit['return']} {hit['method']}()  {hit['file']}:{hit['line']}")
        for hit in bad:
            print(f"  FAIL: primitive 返回 {hit['return']} {hit['method']}()  {hit['file']}:{hit['line']}")

    if bad:
        print()
        print("发现 primitive 返回的 @SchedulerLock 方法——ShedLock 4.x 会拒绝,循环将永远不执行。")
        print("修复：把返回类型改成 void 或包装类型（Integer/Long/Boolean/Void）。")
        return 1
    print("OK: 没有 primitive 返回的 @SchedulerLock 方法")
    return 0


if __name__ == "__main__":
    sys.exit(main())
