#!/usr/bin/env python3
"""把 `java-deps-export.init.gradle` 导出的分片 JSON 合成可提交 Java 依赖清单。

为什么单独一个脚本（而不是并进 check-license-policy.py）：
    许可扫描要**离线可复现**（与 npm 读 lockfile、Rust 读 Cargo.lock + 缓存同构），
    而「解析依赖树」这一步必须联网、必须有 Gradle。两者混在一个脚本里会让门禁
    在 CI 里隐式依赖网络解析（本仓 2026-10-18 就为 Rust 腿的同类问题修过一次：
    原实现 `(update_cache or True)` 恒真，任何运行都会联网查 crates.io）。
    所以这里只做「导出件 → 清单」的机械合成 + 与已提交清单的漂移比对。

清单（scripts/java-dependencies.json）是**事实源快照**，许可字符串在
scripts/java-license-cache.json（两者都由维护者显式刷新，CI 只读）。

用法
    python3 scripts/java-deps-collect.py merge --from build/java-deps \
        --out scripts/java-dependencies.json
    python3 scripts/java-deps-collect.py diff --committed scripts/java-dependencies.json \
        --fresh build/java-deps-manifest.json
    python3 scripts/java-deps-collect.py --selftest

退出码
    merge/diff 默认恒 0（报告制）。diff 加 --fail-on-drift 时，坐标集合有增删 ⇒ 1。
    输入不可读 / JSON 非法 ⇒ 2。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys

SCHEMA = 1
MANIFEST_REL = "scripts/java-dependencies.json"


def _load_shards(from_dir: str) -> list[dict]:
    paths = sorted(glob.glob(os.path.join(from_dir, "*.json")))
    shards = []
    for p in paths:
        try:
            with open(p, encoding="utf-8") as fh:
                d = json.load(fh)
        except Exception as e:
            raise SystemExit(f"FAIL: 分片不可解析 {p}: {e}")
        if not isinstance(d, dict) or "coordinates" not in d or "project" not in d:
            raise SystemExit(f"FAIL: 分片形状不对（缺 project/coordinates）：{p}")
        shards.append(d)
    if not shards:
        # 空目录 = 导出任务根本没跑成（或 init script 没生效）。
        # 合成一份"空清单"是最坏的假象——它会让 Java 腿看起来"已覆盖、零依赖"。
        raise SystemExit(f"FAIL: {from_dir} 下没有任何分片；拒绝合成空清单")
    return shards


def cmd_merge(args) -> int:
    shards = _load_shards(args.from_dir)
    coords: set[str] = set()
    modules: dict[str, list[str]] = {}
    unresolved: list[str] = []
    builds: set[str] = set()
    for s in shards:
        build = s.get("build") or "?"
        key = f"{build}{s['project']}"
        builds.add(build)
        cs = sorted(set(s["coordinates"]))
        modules[key] = cs
        coords.update(cs)
        for u in s.get("unresolved") or []:
            unresolved.append(f"{key}: {u}")

    out = {
        "schema": SCHEMA,
        "generatedBy": args.generated_by,
        "configuration": "runtimeClasspath",
        "builds": sorted(builds),
        "moduleCount": len(modules),
        "coordinates": sorted(coords),
        "modules": dict(sorted(modules.items())),
        "unresolved": sorted(set(unresolved)),
    }
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")
    os.replace(tmp, args.out)
    print(f"Java 依赖清单已合成：{args.out}"
          f"（构建 {len(builds)}、模块 {len(modules)}、第三方坐标 {len(coords)}、"
          f"未解析边 {len(set(unresolved))}）")
    return 0


def cmd_diff(args) -> int:
    try:
        committed = json.load(open(args.committed, encoding="utf-8"))
        fresh = json.load(open(args.fresh, encoding="utf-8"))
    except Exception as e:
        print(f"FAIL: 清单不可读：{e}")
        return 2
    a, b = set(committed.get("coordinates") or []), set(fresh.get("coordinates") or [])
    added, removed = sorted(b - a), sorted(a - b)
    ma = set(committed.get("modules") or {})
    mb = set(fresh.get("modules") or {})
    lost_modules = sorted(ma - mb)

    print(f"Java 依赖清单漂移：新增 {len(added)}、移除 {len(removed)}、"
          f"清单里已不在本次导出的模块 {len(lost_modules)}")
    for c in added[:40]:
        print(f"  + {c}")
    for c in removed[:40]:
        print(f"  - {c}")
    if len(added) > 40 or len(removed) > 40:
        print(f"  （其余见两份清单，本次共 新增 {len(added)} / 移除 {len(removed)}）")
    for m in lost_modules[:20]:
        print(f"  ! 模块缺失于本次导出：{m}")

    if args.fail_on_drift and (added or removed or lost_modules):
        print("::error::Java 依赖清单与本次解析结果不一致（--fail-on-drift）")
        return 1
    return 0


# ------------------------------------------------------------------ selftest
def _w(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def selftest() -> int:
    import subprocess
    import tempfile

    script = os.path.abspath(__file__)
    root = tempfile.mkdtemp(prefix="jdeps-selftest-")
    shards = os.path.join(root, "shards")
    # 正例：两个构建树、三个模块，含重复坐标（应去重）与仓内 project 依赖（已在导出侧剔除）
    _w(os.path.join(shards, "nexus___nexus-gateway.json"), json.dumps({
        "build": "nexus", "project": ":nexus-gateway", "configuration": "runtimeClasspath",
        "coordinates": ["com.zaxxer:HikariCP:5.1.0", "org.springframework:spring-core:6.1.14"],
        "unresolved": []}))
    _w(os.path.join(shards, "nexus___nexus-common.json"), json.dumps({
        "build": "nexus", "project": ":nexus-common", "configuration": "runtimeClasspath",
        "coordinates": ["org.springframework:spring-core:6.1.14"], "unresolved": []}))
    _w(os.path.join(shards, "nexus-settlement__-.json"), json.dumps({
        "build": "nexus-settlement", "project": ":", "configuration": "runtimeClasspath",
        "coordinates": ["org.postgresql:postgresql:42.7.4"],
        "unresolved": ["org.foo:broken:1.0"]}))
    out = os.path.join(root, "manifest.json")

    r = subprocess.run([sys.executable, script, "merge", "--from", shards, "--out", out],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"merge 应成功：{r.stderr}{r.stdout}"
    m = json.load(open(out, encoding="utf-8"))
    assert m["coordinates"] == ["com.zaxxer:HikariCP:5.1.0", "org.postgresql:postgresql:42.7.4",
                                "org.springframework:spring-core:6.1.14"], m["coordinates"]
    assert m["moduleCount"] == 3 and m["builds"] == ["nexus", "nexus-settlement"], m
    assert m["unresolved"] == ["nexus-settlement:: org.foo:broken:1.0"], m["unresolved"]
    print("  ✓ 正例：去重 / 多构建合并 / 未解析边留痕")

    # 反向对照①：空分片目录必须判红，且**不得**产出"看起来覆盖过"的空清单
    empty = os.path.join(root, "empty")
    os.makedirs(empty, exist_ok=True)
    r2 = subprocess.run([sys.executable, script, "merge", "--from", empty,
                         "--out", os.path.join(root, "should-not-exist.json")],
                        capture_output=True, text=True)
    assert r2.returncode != 0, "空分片目录必须失败（否则假覆盖）"
    assert not os.path.exists(os.path.join(root, "should-not-exist.json")), "失败时不得写出清单"
    print("  ✓ 反向①：空导出目录拒绝合成空清单")

    # 反向对照②：分片形状不对（缺 coordinates）也必须失败，而不是静默算 0 个依赖
    bad = os.path.join(root, "bad")
    _w(os.path.join(bad, "x.json"), '{"build": "b", "project": ":p"}')
    r3 = subprocess.run([sys.executable, script, "merge", "--from", bad, "--out",
                         os.path.join(root, "bad.json")], capture_output=True, text=True)
    assert r3.returncode != 0, "形状不符必须失败"
    print('  ✓ 反向②：畸形分片不会被当成"零依赖"')

    # diff：新增/移除都要点名，且默认不阻断、--fail-on-drift 才红
    fresh = json.load(open(out, encoding="utf-8"))
    fresh["coordinates"] = sorted(set(fresh["coordinates"]) | {"org.evil:gpl-thing:1.0"})
    fresh_path = os.path.join(root, "fresh.json")
    _w(fresh_path, json.dumps(fresh))
    r4 = subprocess.run([sys.executable, script, "diff", "--committed", out,
                         "--fresh", fresh_path], capture_output=True, text=True)
    assert r4.returncode == 0 and "+ org.evil:gpl-thing:1.0" in r4.stdout, r4.stdout
    r5 = subprocess.run([sys.executable, script, "diff", "--committed", out,
                         "--fresh", fresh_path, "--fail-on-drift"],
                        capture_output=True, text=True)
    assert r5.returncode == 1, "漂移在 --fail-on-drift 下必须判红"
    print("  ✓ diff：默认报告制、加旗才阻断")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    m = sub.add_parser("merge")
    m.add_argument("--from", dest="from_dir", required=True)
    m.add_argument("--out", default=MANIFEST_REL)
    m.add_argument("--generated-by",
                   default="./gradlew --init-script scripts/java-deps-export.init.gradle "
                           "-Dnexus.javaDepsOut=<dir> exportJavaDependencies")
    d = sub.add_parser("diff")
    d.add_argument("--committed", required=True)
    d.add_argument("--fresh", required=True)
    d.add_argument("--fail-on-drift", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.cmd == "merge":
        return cmd_merge(args)
    if args.cmd == "diff":
        return cmd_diff(args)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
