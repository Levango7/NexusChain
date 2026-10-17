#!/usr/bin/env python3
"""迁移版本号棘轮门禁（migration version ratchet）。

## 为什么要有这个门禁

Flyway 的版本号是**单调时钟**，不是编号池。2026-10-17 本仓真实出过一次事故：

- PR #106 带 V99–V102；另一 PR（KEK 轮换）已把 V103 合入 master；
- 于是 V99–V102 变成"**低号后到**"；
- Flyway **未配 `out-of-order`**（默认 false）⇒ 在**已应用 V103 的存量库**上
  启动即抛 `FlywayException: Detected resolved migration not applied to database: 99`。

**CI 结构上测不到这种故障**：CI 跑在全新库上，V1→…→V103 按序应用必然通过。
`strict: true`（要求分支更新到最新）也治不了——它只改变 CI 跑在哪个 base 上，
而无论哪个 base 都是**全新库**。

⇒ 唯一对症的检查是：**直接断言版本号单调性**。

## 作用域：按迁移目录，不按全局

本仓有**多个独立的迁移目录**，各自对应一个 Spring 应用的独立数据源：

    nexus-gateway/.../db/migration        86 支
    nexus-bridge/.../db/migration          5 支
    nexus-signing-service/.../db/migration 4 支
    nexus-wallet-service/.../db/migration  4 支
    nexus-consortium/.../db/migration      1 支

⇒ **V1/V2/V3/V4 在不同目录同号出现是合法的**（各自的 `flyway_schema_history` 独立）。
所以棘轮与重号检查都**必须按目录做**；按全局做会把合法状态判红
（--selftest 样本 7 专门钉这一点）。

## 规则（对每个迁移目录独立成立）

设 `base` 为对照分支（PR 场景 = master；push 场景 = 上一提交）。

1. **棘轮**：本分支在该目录下**新增**的每个迁移，版本号必须 **> 该目录在 base 上的最大值**。
2. **不重号**：该目录下 base ∪ 新增 的并集不得有重复版本号（按路径去重后）。
3. 无新增迁移 ⇒ 通过。

## 用法

    python scripts/check-migration-version-ratchet.py                 # 自动推断 base
    python scripts/check-migration-version-ratchet.py --base FETCH_HEAD
    python scripts/check-migration-version-ratchet.py --selftest      # 钉住判定函数

## 退出码

0 = 通过；1 = 有违规；2 = 自测失败（判定函数坏了，宁可红也不能放过）。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

# 只认 Flyway 的命名：V<数字>__<描述>.sql，且必须位于某模块的 db/migration 下
MIGRATION_RE = re.compile(r"^(.*/db/migration)/V(\d+)__.+\.sql$")


def parse(path: str):
    """返回 (目录, 版本号)；非迁移文件返回 (None, None)。"""
    m = MIGRATION_RE.match(path)
    return (m.group(1), int(m.group(2))) if m else (None, None)


def git_ls_tree(ref: str) -> list[str]:
    out = subprocess.run(["git", "ls-tree", "-r", "--name-only", ref],
                         capture_output=True, text=True)
    if out.returncode != 0:
        return []
    return out.stdout.split()


def migrations_by_dir(ref: str) -> dict[str, dict[str, int]]:
    """ref -> {迁移目录: {文件路径: 版本号}}"""
    result: dict[str, dict[str, int]] = {}
    for path in git_ls_tree(ref):
        d, v = parse(path)
        if d is not None:
            result.setdefault(d, {})[path] = v
    return result


def evaluate(head: dict[str, dict[str, int]],
             base: dict[str, dict[str, int]],
             added: list[str] | None = None) -> list[str]:
    """核心判定，纯函数，便于 --selftest 钉住。

    :param head: HEAD 的 {目录: {路径: 版本号}}
    :param base: 对照分支的同构数据
    :param added: 新增文件路径列表；None 表示按 head/base 差集自动求
    """
    if added is None:
        base_paths = {p for d in base.values() for p in d}
        added = [p for d in head.values() for p in d if p not in base_paths]

    problems: list[str] = []
    added_by_dir: dict[str, list[str]] = {}
    for path in added:
        d, _ = parse(path)
        if d is not None:
            added_by_dir.setdefault(d, []).append(path)

    for d in sorted(set(base) | set(head)):
        base_here = base.get(d, {})
        head_here = head.get(d, {})
        base_max = max(base_here.values()) if base_here else 0
        new_here = added_by_dir.get(d, [])

        for path in new_here:
            v = head_here.get(path)
            if v is None:
                continue
            if v <= base_max:
                problems.append(
                    f"[棘轮] {d}/ 新增 {path.rsplit('/', 1)[-1]}: "
                    f"V{v} <= 该目录 base 最大值 V{base_max}\n"
                    f"        低号后到会让已应用 V{base_max} 的存量库启动失败"
                    f"（Flyway out-of-order 默认 false）。\n"
                    f"        改法：顺延到 V{base_max + 1} 起；"
                    f"V{base_max + 1}.. 被占就继续往上，不要回填更低的空号。"
                )

        # 重号：按路径去重后的并集里，同版本号出现多个路径。
        # ⚠️ 必须按路径去重：head 通常是 base 的超集，直接相加会把存量算两遍。
        union = dict(base_here)
        union.update(head_here)
        by_version: dict[int, list[str]] = {}
        for path, v in union.items():
            by_version.setdefault(v, []).append(path)
        for v, paths in sorted(by_version.items()):
            if len(paths) > 1:
                problems.append(
                    f"[重号] {d}/ 版本 V{v} 被多个迁移占用：\n" +
                    "\n".join(f"          - {p}" for p in sorted(paths))
                )
    return problems


def resolve_base(explicit: str | None) -> str | None:
    """对照分支：显式 --base > origin/master（若非 HEAD）> HEAD~1。"""
    head = subprocess.run(["git", "rev-parse", "HEAD"],
                          capture_output=True, text=True).stdout.strip()
    for ref in ([explicit] if explicit else []) + ["origin/master", "HEAD~1"]:
        r = subprocess.run(["git", "rev-parse", "--verify", ref],
                           capture_output=True, text=True)
        if r.returncode != 0:
            continue
        if ref != explicit and r.stdout.strip() == head:
            continue
        return ref
    return None


def selftest() -> int:
    """钉住判定函数：合成样本，不依赖 git 状态。"""
    failures = []

    def check(name, head, base, added, expect_fail):
        probs = evaluate(head, base, added)
        ok = bool(probs) == expect_fail
        print(f"  {'✓' if ok else '✗'} {name}")
        if not ok:
            failures.append(f"{name}: 期望 {'违规' if expect_fail else '通过'}，"
                            f"实际 {'违规' if probs else '通过'}\n"
                            + ("\n".join(probs) if probs else ""))

    G, B, S, W = ("nexus-gateway/src/main/resources/db/migration",
                  "nexus-bridge/src/main/resources/db/migration",
                  "nexus-signing-service/src/main/resources/db/migration",
                  "nexus-wallet-service/src/main/resources/db/migration")

    print("== 迁移版本棘轮 自测 ==")
    base = {
        G: {f"{G}/V103__kek.sql": 103, f"{G}/V98__sub.sql": 98},
        B: {f"{B}/V1__init.sql": 1},
    }

    # 1) 正常：V104–V107 相对 gateway 的 V103 单调递增 ⇒ 通过
    check("gateway V104–V107 相对 V103 ⇒ 通过",
          {**base, G: {**base[G], f"{G}/V104__a.sql": 104,
                       f"{G}/V105__b.sql": 105, f"{G}/V106__c.sql": 106,
                       f"{G}/V107__d.sql": 107}},
          base, None, False)

    # 2) #106 事故形态 ⇒ 必须拦，且说明要含根因与改法
    bad = {**base, G: {**base[G], f"{G}/V99__a.sql": 99}}
    probs = evaluate(bad, base, None)
    check("gateway V99 低于存量 V103 ⇒ 拦（#106 事故）", bad, base, None, True)
    if not any("V99" in p and "out-of-order" in p for p in probs):
        failures.append("事故形态未给出含 Flyway 根因的说明")

    # 3) 恰等于该目录最大值 ⇒ 拦（是 <=，不是 <）
    eq = {**base, G: {**base[G], f"{G}/V103__dup.sql": 103}}
    check("gateway 新增 V103 同号 ⇒ 拦", eq, base, None, True)

    # 4) 空目录 ⇒ 只查重号，V1 应通过
    check("空 base + V1 ⇒ 通过", {S: {f"{S}/V1__x.sql": 1}}, {}, None, False)

    # 5) 解析器：非迁移路径必须被忽略
    if parse("a/V999__x.sql") != (None, None):
        failures.append("把不在 db/migration 下的文件当成迁移")
    if parse("a/db/migration/Vnotanumber__x.sql") != (None, None):
        failures.append("把 Vnotanumber__ 当成迁移")
    if parse("a/db/migration/V12__ok.sql") != ("a/db/migration", 12):
        failures.append("合法迁移未被识别")
    print("  ✓ 解析器路径与版本号识别")

    # 6) 同目录内跨分支同号 ⇒ 拦
    check("gateway 内重复 V103 ⇒ 拦", eq, base, [f"{G}/V103__dup.sql"], True)

    # 7) **跨目录同号必须放过**：Flyway 按数据源独立编号，这是本仓合法状态
    check("bridge V1 与 gateway V103 并存 ⇒ 通过（跨目录不构成重号）",
          base, base, None, False)

    # 8) bridge 目录自己的棘轮：base_max=1，新增 V1 违规、V2 通过
    check("bridge 新增 V1 低于自身最大值 V1 ⇒ 拦",
          {**base, B: {**base[B], f"{B}/V1__dup.sql": 1}}, base, None, True)
    check("bridge 新增 V2 ⇒ 通过",
          {**base, B: {**base[B], f"{B}/V2__y.sql": 2}}, base, None, False)

    print()
    if failures:
        print("== 自测失败 ==")
        for f in failures:
            print(" -", f)
        return 2
    print("== 自测全部通过 ==")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", help="对照分支/引用，如 FETCH_HEAD、origin/master")
    ap.add_argument("--selftest", action="store_true", help="只跑内置合成样本")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    base_ref = resolve_base(args.base)
    head = migrations_by_dir("HEAD")
    base = migrations_by_dir(base_ref) if base_ref else {}
    base_paths = {p for d in base.values() for p in d}
    added = [p for d in head.values() for p in d if p not in base_paths]

    n_head = sum(len(d) for d in head.values())
    n_base = sum(len(d) for d in base.values())
    print(f"HEAD 迁移 {n_head} 支（{len(head)} 个目录）；"
          f"对照 ref = {base_ref or '(无)'}（迁移 {n_base} 支）；"
          f"新增 {len(added)} 支")

    problems = evaluate(head, base, added)

    if not added:
        print("无新增迁移 ⇒ 无可断言对象，通过")
        return 0

    print("== 违规 ==") if problems else print("== 通过 ==")
    for p in problems:
        print(p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
