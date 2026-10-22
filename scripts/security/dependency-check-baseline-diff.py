#!/usr/bin/env python3
"""
dependency-check-baseline-diff.py — OWASP Dependency-Check 报告 vs 已受理基线

背景（2026-10-22，PR #177 之后）：
  PR / push 路径的 SCA 扫描改成了「只读 NVD 缓存库」（-Dodc.autoupdate=false），
  好处是门禁不再被 NVD 取数拥塞卡死；代价是 **PR 扫描看不到新披露的漏洞** ——
  扫描判定只覆盖"缓存库里已知的那批"。

  本脚本补上"增量"这一环：把扫描报告里的漏洞集合与**已受理基线**比对，
  凡是基线里没有的漏洞，都视为"新增"并报出来，由人决定升版还是更新基线。

  基线文件：config/dependency-check-baseline.json
  （格式与 scripts/security/trivy-to-issues.py 的报告解析口径一致：读 OWASP 的
    report.json，取 dependencies[].vulnerabilities[].name 作为漏洞 ID，
    CVSS 取 cvssv3.baseScore（缺失则回退 cvssv4.baseScore / cvssv2.baseScore））

用法：
  python3 scripts/security/dependency-check-baseline-diff.py \
    --report build/reports/dependency-check/dependency-check-report.json \
    --baseline config/dependency-check-baseline.json \
    [--min-cvss 0.0] [--allowlist "CVE-2026-0000,CVE-2026-0001"]

退出码：
  0 — 没有新增漏洞（或新增项都在 allowlist 里）
  1 — 存在新增漏洞（需要人处理：升版依赖，或显式更新基线）
  2 — 参数错误 / 报告或基线读取失败
"""

import argparse
import json
import sys


def load_json(path, what):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        print(f"::error::{what} 文件不存在: {path}")
        sys.exit(2)
    except json.JSONDecodeError as exc:
        print(f"::error::{what} 文件不是合法 JSON: {path} ({exc})")
        sys.exit(2)


def cvss_score(vuln):
    """取该漏洞的 CVSS 基础分；取不到返回 None（未知）。"""
    for key in ("cvssv3", "cvssv4", "cvssv2"):
        block = vuln.get(key)
        if isinstance(block, dict):
            score = block.get("baseScore")
            if isinstance(score, (int, float)):
                return float(score)
    return None


def collect_vulnerabilities(report):
    """报告 → {漏洞ID: {"score": float|None, "packages": set(), "count": int}}"""
    found = {}
    deps = report.get("dependencies") or []
    for dep in deps:
        if not isinstance(dep, dict):
            continue
        file_name = dep.get("fileName") or dep.get("filePath") or "(unknown)"
        for vuln in dep.get("vulnerabilities") or []:
            if not isinstance(vuln, dict):
                continue
            vid = vuln.get("name")
            if not vid:
                continue
            entry = found.setdefault(vid, {"score": None, "packages": set(), "count": 0})
            score = cvss_score(vuln)
            if score is not None and (entry["score"] is None or score > entry["score"]):
                entry["score"] = score
            entry["count"] += 1
            if len(entry["packages"]) < 5:
                entry["packages"].add(file_name)
    return found


def baseline_ids(baseline):
    ids = set()
    for item in baseline.get("accepted") or []:
        if isinstance(item, dict) and item.get("id"):
            ids.add(item["id"])
        elif isinstance(item, str):
            ids.add(item)
    return ids


def main():
    parser = argparse.ArgumentParser(description="Dependency-Check 报告 vs 基线比对")
    parser.add_argument("--report", required=True, help="dependency-check-report.json 路径")
    parser.add_argument("--baseline", required=True, help="已受理基线 JSON 路径")
    parser.add_argument("--min-cvss", type=float, default=0.0,
                        help="只关心 CVSS >= 该值的新增漏洞（默认 0.0 = 全部）")
    parser.add_argument("--allowlist", default="",
                        help="逗号分隔的临时忽略漏洞 ID（用于窗口期豁免）")
    args = parser.parse_args()

    report = load_json(args.report, "Dependency-Check 报告")
    baseline = load_json(args.baseline, "基线")
    found = collect_vulnerabilities(report)
    accepted = baseline_ids(baseline)
    allowlist = {x.strip() for x in args.allowlist.split(",") if x.strip()}

    new_items = []
    for vid, info in sorted(found.items()):
        if vid in accepted or vid in allowlist:
            continue
        score = info["score"]
        if score is not None and score < args.min_cvss:
            continue
        new_items.append((vid, info))

    print(f"扫描漏洞种类={len(found)}，基线已受理={len(accepted)}，新增={len(new_items)}")
    for vid, info in new_items:
        score = "未知" if info["score"] is None else info["score"]
        pkgs = ", ".join(sorted(info["packages"]))
        print(f"  ::warning::新增漏洞 {vid}（CVSS={score}，命中 {info['count']} 个构件）：{pkgs}")

    if new_items:
        print("::error::检测到基线之外的新增漏洞 —— 请二选一：① 升级受影响依赖（首选）；"
              "② 若经评估可接受，显式更新 config/dependency-check-baseline.json（写明理由与复核日期）")
        print(f"::error::新增清单：{', '.join(vid for vid, _ in new_items)}")
        return 1

    print("::notice::未发现基线之外的新增漏洞")
    return 0


if __name__ == "__main__":
    sys.exit(main())
