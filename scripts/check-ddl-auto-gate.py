#!/usr/bin/env python3
"""ddl-auto:update 扩散门禁（审计 C1，2026-11-10）。

背景
    审计 production-readiness-audit-2026-11-07 §四 C1：nexus-oracle 是全仓唯一
    ``ddl-auto: update``（基档）+ 零 Flyway 迁移的模块。该模块 README 首屏已按
    ADR-001「仅维护诚实声明」政策自述「当前为接口骨架」，审计将其降级为一致
    性问题。本门不改任何代码，只防**扩散**：任何新模块 / 新 profile 把 ddl-auto
    设为 update（即绕过 Flyway 的受控 schema 演进）即红。

口径
    · 只匹配**属性行**（行首可空白，形如 ``ddl-auto: update``）；
    · 排除注释行（``#`` 起头）与历史陈述行（用等号的 ``ddl-auto=update``）；
    · 只扫 ``src/main/resources``（部署基档）；``src/test/resources`` 的
      ``application-test.yml``（H2 内存库测试档，ddl-auto:update 是测试建表
      的常规做法）不在本门口径内——审计 C1 的发现对象是**部署基档**；
    · 允许集 = 审计基线（2026-11-10 实测）：
        nexus-gateway/src/main/resources/application-dev.yml   # dev 档设计如此
        nexus-oracle/src/main/resources/application.yml        # 接口骨架，README 已声明
    · 判定：实际命中集合必须是允许集的**子集**。允许集内某文件将来不再含
      update（如 oracle 补齐 Flyway 后改 validate）属正常收缩，不红；
      允许集之外出现任何命中 ⇒ exit 1 并列出文件。

用法
    python scripts/check-ddl-auto-gate.py            # 检查（CI 用）
    python scripts/check-ddl-auto-gate.py --selftest # 扫描器口径自测

退出码
    0 = 无扩散
    1 = 允许集之外出现 ddl-auto: update
    2 = 自测失败
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 允许集（相对仓库根）。键为 POSIX 风格相对路径，Windows 下 git 检出同样适用。
ALLOWED = {
    "nexus-gateway/src/main/resources/application-dev.yml",
    "nexus-oracle/src/main/resources/application.yml",
}

# 属性行：行首可空白，键 ddl-auto，分隔符冒号，值 update（行尾可空白/注释）。
PROP_RE = re.compile(r"^\s*ddl-auto:\s*update\s*(?:#.*)?$")

# 仓库根 = 本脚本上一级目录的上一级
REPO_ROOT = Path(__file__).resolve().parent.parent


def scan(root: Path) -> set[str]:
    """返回所有命中 ddl-auto: update 属性行的文件相对路径集合。"""
    hits: set[str] = set()
    for yml in root.rglob("*.yml"):
        # 只扫部署基档 src/main/resources；src/test/resources 的 H2 测试档
        # ddl-auto:update 是测试建表常规做法，不在本门口径内（见模块 docstring）
        parts = yml.parts
        if "src" not in parts or "main" not in parts:
            continue
        try:
            text = yml.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if any(PROP_RE.match(line) for line in text.splitlines()):
            hits.add(yml.relative_to(root).as_posix())
    return hits


def selftest() -> int:
    """扫描器口径自测：属性行命中 / 注释行与等号行不命中。"""
    cases = [
        ("  ddl-auto: update", True),
        ("ddl-auto: update", True),
        ("ddl-auto: update # 说明", True),
        ("# 表由 ddl-auto=update 运行时创建", False),
        ("ddl-auto=update", False),
        ("ddl-auto: validate", False),
        ("ddl-auto: create-drop", False),
        ("  ddl-auto:update", True),
    ]
    bad = 0
    for line, expect in cases:
        got = bool(PROP_RE.match(line))
        if got != expect:
            print(f"SELFTEST line={line!r} expect={expect} got={got}")
            bad += 1
    return 2 if bad else 0


def main() -> int:
    if "--selftest" in sys.argv:
        rc = selftest()
        print("ddl-auto gate selftest: " + ("FAIL" if rc else "PASS"))
        return rc

    hits = scan(REPO_ROOT)
    offenders = sorted(hits - ALLOWED)
    print(f"ddl-auto:update hits={len(hits)} allowed={len(ALLOWED)} "
          f"offenders={len(offenders)}")
    for f in sorted(hits):
        tag = "OK" if f in ALLOWED else "RED"
        print(f"  [{tag}] {f}")
    if offenders:
        print("FAIL: ddl-auto:update 扩散到允许集之外（审计 C1）：")
        for f in offenders:
            print(f"  {f}")
        print("  允许集仅含 application-dev.yml 档与 nexus-oracle（README 已声明骨架）；"
              "新模块必须走 Flyway。")
        return 1
    print("PASS: ddl-auto:update 未扩散（审计 C1 门禁）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
