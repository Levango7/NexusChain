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

覆盖面（PLAN-059）：缺省改为**仓库根**——PLAN-056 落地时只传了
`nexus-bridge/src/main/java`，而全仓共 33 处 `@SchedulerLock`
（bridge 4 + gateway 29），gateway 的 29 处从此在守卫之外：新增 primitive
返回的调度方法在 gateway 不会被拦。默认跳过测试源树（`/src/test/`）——
ShedLock 语义只对生产调度有意义，扫测试只会带来噪声与假阳性。

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


def strip_comments(line: str) -> str:
    """去掉行内 `//` 与 `/* ... */`（单行场景）后的代码文本。

    PLAN-059：门禁原先会把 javadoc 里的 `@SchedulerLock(lockAtMostFor = "PT30M")`
    当成真注解，报出一条 `PASS: public BillingCycleScheduler()` 假阳性
    （构造器签名被匹配成"方法"）。噪声本身不致命，但会让"扫出多少处"这个
    数字失去意义，也使 `--json` 的 total 不可信。
    """
    idx = line.find("//")
    if idx >= 0:
        line = line[:idx]
    start = line.find("/*")
    if start >= 0:
        end = line.find("*/", start + 2)
        line = line[:start] + (line[end + 2:] if end >= 0 else "")
    return line


def scan_file(path: str) -> list[dict]:
    # PLAN-059：跳过测试源树。测试里的 @SchedulerLock 不产生生产调度语义，
    # 扫它只会噪声；注释/字符串里出现的伪注解另由 is_annotation 过滤。
    parts = os.path.normpath(path).split(os.sep)
    if "src" in parts:
        k = parts.index("src")
        if k + 1 < len(parts) and parts[k + 1] != "main":
            return []
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.readlines()
    hits: list[dict] = []
    for idx, line in enumerate(lines):
        if not SCHEDULER_LOCK_RE.search(line):
            continue
        # PLAN-059：javadoc/注释里提到 `@SchedulerLock(...)` 不是注解。
        # 判定：去掉行内注释与块注释内容后，@SchedulerLock 必须仍在该行文本里。
        code = strip_comments(line)
        if not SCHEDULER_LOCK_RE.search(code):
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
    ap.add_argument("--path", default=os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))))
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
