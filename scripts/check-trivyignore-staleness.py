#!/usr/bin/env python3
"""`.trivyignore` 豁免清单时效复核（**不阻断**）。

背景
    `.trivyignore` 的处置原则写在文件头：**只豁免"上游无可用修复版本"的系统包漏洞**，
    并且每条都给了删除条件（"Debian 发布修复后移除"）。问题是这个条件在 CI 里
    **没有任何观测点**：镜像扫描的两步都带 `trivyignores: .trivyignore`，被豁免的
    finding 直接被抑制，所以"上游出了补丁"这件事在一条全绿的流水线里是隐形的。
    2026-10-07 的实测（`docs/audit/2026-10-06-open-alert-triage.md` §6）给了两个后果：
      · 25 条里有 13 条在**当前两个在用的运行阶段基础镜像**上已无命中——它们的注释
        钉在 `debian:bookworm-slim`，而 Rust 模块 2026-10-01 已整批迁到 `trixie-slim`；
      · 另有 3 条**非豁免**项（libc6/libc-bin、libpng16-16t64）当日已出现修复版，
        即"无上游修复版本"是**会过期的判断**。
    本脚本把这两类漂移变成每天可见的 warning。

判定口径
    A. 命中且 `FixedVersion` 非空  → 该条豁免已掩盖"其实能修"的漏洞：**应移除豁免并升级基础镜像**
    B. 在所有被扫镜像里都无命中      → 作用域可能已失效（**提示，不是结论**，见下面的限制）
    C. 命中且无 `FixedVersion`       → 豁免仍然成立（正常，不报）

限制（刻意不把话说满）
    · 扫的是**运行阶段基础镜像**，不是构建产物；`--input` 走 docker save 的 tar。
    · 应用层依赖类豁免（如 `CVE-2025-66017` 的 cggmp21 crate）不在此覆盖范围——
      它由 `cargo-audit` 那条腿负责。所以 B 类一律写成"可能失效，请核扫描口径"，
      而不是"请删除"。
    · 曾在本仓踩过反向的坑：`CVE-2023-45853` 在 debian13 迁移批被当"作用域不适用"删除，
      随后 CI run 33966509897 实证它在 bookworm-slim 侧仍匹配而不得不补回。
      **因此本脚本永不自动删条目，也永不阻断构建。**

退出码
    0 = 复核已执行（有无 warning 都是 0 —— 阻断会让上游 DB 波动随机挡住合并，属自伤）
    1 = 输入本身有问题（缺文件 / JSON 解析失败 / 没读到任何 CVE 条目）
"""
from __future__ import annotations

import argparse
import json
import os
import sys


def read_ignored_ids(path: str) -> list[str]:
    ids = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("CVE-") or s.startswith("GHSA-") or s.startswith("RUSTSEC-"):
                ids.append(s)
    return ids


def read_scan(label: str, path: str) -> dict[str, list[dict]]:
    """返回 {vuln_id: [{pkg, fixed, severity}, ...]}"""
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    out: dict[str, list[dict]] = {}
    for result in data.get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            vid = v.get("VulnerabilityID")
            if not vid:
                continue
            out.setdefault(vid, []).append(
                {
                    "pkg": v.get("PkgID") or "%s@%s" % (v.get("PkgName"), v.get("InstalledVersion")),
                    "fixed": v.get("FixedVersion"),
                    "severity": v.get("Severity"),
                }
            )
    total = sum(len(x) for x in out.values())
    print("  [scan] %-32s 漏洞条目=%d（去重 id=%d）" % (label, total, len(out)), file=sys.stderr)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="复核 .trivyignore 里的豁免是否仍然成立（不阻断）")
    ap.add_argument("--ignore", default=".trivyignore", help="豁免白名单路径")
    ap.add_argument(
        "--scan",
        action="append",
        required=True,
        metavar="LABEL=JSON",
        help="一次扫描的 `<标签>=<trivy json 路径>`，可重复",
    )
    args = ap.parse_args()

    if not os.path.exists(args.ignore):
        print("::error::找不到豁免清单 %s（检查 checkout 是否成功）" % args.ignore, file=sys.stderr)
        return 1

    ignored = read_ignored_ids(args.ignore)
    if not ignored:
        print("::error::%s 里没解析到任何 CVE/GHSA/RUSTSEC 条目" % args.ignore, file=sys.stderr)
        return 1

    scans: dict[str, dict[str, list[dict]]] = {}
    for item in args.scan:
        if "=" not in item:
            print("::error::--scan 需要 LABEL=JSON 形式，收到 %r" % item, file=sys.stderr)
            return 1
        label, path = item.split("=", 1)
        if not os.path.exists(path):
            print("::error::扫描结果不存在：%s（=%s）" % (label, path), file=sys.stderr)
            return 1
        scans[label] = read_scan(label, path)

    if not scans:
        print("::error::没有传入任何 --scan", file=sys.stderr)
        return 1

    # 逐条对表
    class_a: list[str] = []      # 命中且已有修复版 → 必须动手
    class_b: list[str] = []      # 所有被扫镜像都无命中 → 可能失效
    class_c: list[str] = []      # 命中且无修复版 → 豁免仍成立
    detail: dict[str, list[str]] = {}

    for vid in ignored:
        hit_any = False
        lines = []
        for label, data in scans.items():
            hits = data.get(vid)
            if not hits:
                lines.append("%s: 无命中" % label)
                continue
            hit_any = True
            fixes = sorted({h["fixed"] for h in hits if h["fixed"]})
            pkgs = sorted({h["pkg"] for h in hits})
            if fixes:
                lines.append("%s: 命中 %s，**已有修复版 %s**" % (label, ",".join(pkgs), ",".join(fixes)))
            else:
                lines.append("%s: 命中 %s，无修复版" % (label, ",".join(pkgs)))
        detail[vid] = lines
        has_fix = any("已有修复版" in l for l in lines)
        if has_fix:
            class_a.append(vid)
        elif not hit_any:
            class_b.append(vid)
        else:
            class_c.append(vid)

    labels = " + ".join(scans.keys())
    print("=" * 72)
    print("豁免时效复核：白名单 %d 条 × 被扫镜像 %s" % (len(ignored), labels))
    print("  A 命中且上游已有修复版（应移除豁免）= %d" % len(class_a))
    print("  B 所有被扫镜像均无命中（作用域可能失效）= %d" % len(class_b))
    print("  C 命中且无修复版（豁免仍成立）= %d" % len(class_c))
    print("=" * 72)

    for vid in class_a:
        print("::warning::[豁免已掩盖可修漏洞] %s —— %s" % (vid, " / ".join(detail[vid])), file=sys.stderr)
    for vid in class_b:
        print(
            "::warning::[豁免作用域存疑] %s —— 在 %s 上均无命中。"
            "若该条目针对的是已弃用的基础镜像/包名，可移除；"
            "若它是应用层依赖（本 job 不覆盖），请转 cargo-audit 腿核实。"
            % (vid, labels),
            file=sys.stderr,
        )

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as fh:
            fh.write("### `.trivyignore` 豁免时效复核（非阻断）\n\n")
            fh.write(
                "白名单 **%d** 条 × 被扫镜像 `%s`；A=%d B=%d C=%d\n\n"
                % (len(ignored), labels, len(class_a), len(class_b), len(class_c))
            )
            fh.write("| CVE | 类别 | 逐镜像状态 |\n|---|---|---|\n")
            for vid in class_a + class_b + class_c:
                cls = "A 应移除" if vid in class_a else ("B 作用域存疑" if vid in class_b else "C 仍成立")
                # 表格里的竖线必须转义，否则整张表错位
                cell = " / ".join(detail[vid]).replace("|", "\\|")
                fh.write("| %s | %s | %s |\n" % (vid, cls, cell))

    if class_a or class_b:
        print(
            "共 %d 条待处置（A %d + B %d）。本复核刻意不阻断：豁免失效不是本仓代码缺陷，"
            "把它做成红门禁会让上游 DB 的波动随机挡住合并。" % (len(class_a) + len(class_b), len(class_a), len(class_b)),
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
