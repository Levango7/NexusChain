#!/usr/bin/env python3
"""
trivy-to-issues.py — Trivy 漏洞报告 → GitHub Issue 自动分诊

功能：
  - 解析 Trivy JSON 报告，提取 CRITICAL/HIGH 漏洞
  - 对每个唯一漏洞（按 CVE+包名去重）创建 GitHub Issue
  - 通过 label "security-triage" + 漏洞指纹避免重复创建
  - 在 issue body 中标注 SLA 期限（P0 24h / P1 7d / P2 30d）

用法：
  python3 trivy-to-issues.py \
    --report trivy-fs-results.json \
    --repo owner/repo \
    --min-severity HIGH \
    --label security-triage

退出码：
  0 — 成功（无论是否创建 issue）
  1 — 参数错误或报告读取失败
"""

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone

# ============================================================
# SLA 定义（与 docs/security-sla.md 对齐）
# ============================================================
SLA = {
    "CRITICAL": {"label": "P0", "deadline_days": 1,   "sla_text": "24 小时"},   # P0: 24h
    "HIGH":     {"label": "P1", "deadline_days": 7,   "sla_text": "7 天"},      # P1: 7d
    "MEDIUM":   {"label": "P2", "deadline_days": 30,  "sla_text": "30 天"},     # P2: 30d
    "LOW":      {"label": "P3", "deadline_days": 90,  "sla_text": "90 天"},     # P3: 90d（信息性）
}

# 严重性排序（用于 min-severity 过滤）
SEVERITY_ORDER = {"CRITICAL": 4, "HIGH": 3, "MEDIUM": 2, "LOW": 1, "UNKNOWN": 0}


def run_gh(args, check=False):
    """运行 gh CLI 命令，返回 (returncode, stdout, stderr)。"""
    cmd = ["gh"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, env=os.environ)
    if check and result.returncode != 0:
        print(f"[ERROR] gh 命令失败: {' '.join(cmd)}", file=sys.stderr)
        print(f"  stderr: {result.stderr}", file=sys.stderr)
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def list_existing_issues(repo, label):
    """列出带有指定 label 的现有 issue，返回 {title: number} 字典。"""
    rc, out, err = run_gh([
        "issue", "list",
        "--repo", repo,
        "--label", label,
        "--state", "open",
        "--limit", "200",
        "--json", "number,title",
    ])
    if rc != 0:
        print(f"[WARN] 无法列出现有 issue（gh CLI 可能未认证）: {err}", file=sys.stderr)
        return {}
    try:
        issues = json.loads(out) if out else []
        return {item["title"]: item["number"] for item in issues}
    except json.JSONDecodeError:
        return {}


def create_issue(repo, title, body, labels):
    """创建 GitHub Issue，返回 issue number 或 None。"""
    args = [
        "issue", "create",
        "--repo", repo,
        "--title", title,
        "--body", body,
        "--label", ",".join(labels),
    ]
    rc, out, err = run_gh(args)
    if rc != 0:
        print(f"[ERROR] 创建 issue 失败: {title}\n  {err}", file=sys.stderr)
        return None
    # gh issue create 输出 issue URL，提取 number
    try:
        # URL 格式: https://github.com/owner/repo/issues/123
        issue_number = out.strip().rstrip("/").split("/")[-1]
        return int(issue_number)
    except (ValueError, IndexError):
        return None


def build_issue_body(vuln, package, severity, sla_config):
    """构建 issue body（Markdown 格式）。"""
    now = datetime.now(timezone.utc)
    deadline = now + timedelta(days=sla_config["deadline_days"])

    cve = vuln.get("VulnerabilityID", "N/A")
    cvss = vuln.get("CVSS", {})
    cvss_score = cvss.get("nvd", {}).get("V3Score", "N/A") if isinstance(cvss, dict) else "N/A"
    fixed_version = vuln.get("FixedVersion", "未修复")
    description = vuln.get("Description", "无描述")
    references = vuln.get("References", [])
    primary_url = references[0] if references else "无"

    ref_list = "\n".join(f"- [{ref}]({ref})" for ref in references[:5]) if references else "无"

    body = f"""## 🔴 安全漏洞发现 — {severity}

| 字段 | 值 |
|------|-----|
| **CVE / 漏洞 ID** | `{cve}` |
| **严重性** | {severity} |
| **CVSS v3 评分** | {cvss_score} |
| **受影响包** | `{package.get("Name", "N/A")}` |
| **已安装版本** | `{package.get("InstalledVersion", "N/A")}` |
| **修复版本** | `{fixed_version}` |
| **发现时间** | {now.strftime("%Y-%m-%d %H:%M UTC")} |

## ⏱️ SLA 期限

| 优先级 | 修复期限 | 截止时间 |
|--------|---------|---------|
| **{sla_config["label"]}** | {sla_config["sla_text"]} | {deadline.strftime("%Y-%m-%d %H:%M UTC")} |

> ⚠️ 请在截止时间前完成修复或提供缓解方案。逾期未处理将升级至安全负责人。

## 📝 漏洞描述

{description}

## 🔗 参考链接

{ref_list}

## 🛠️ 修复建议

1. 升级 `{package.get("Name", "N/A")}` 至 `{fixed_version}` 或更高版本
2. 如无法立即升级，评估是否可通过配置缓解（如禁用受影响功能）
3. 在 PR 中关联本 issue（`Fixes #<issue_number>`）
4. 修复后关闭本 issue 并验证漏洞已消除

---
*由 `security-scan.yml` → `trivy-to-issues.py` 自动创建*
*SLA 标准见 [docs/security-sla.md](../docs/security-sla.md)*
"""
    return body


def main():
    parser = argparse.ArgumentParser(description="Trivy 漏洞报告 → GitHub Issue 自动分诊")
    parser.add_argument("--report", required=True, help="Trivy JSON 报告路径")
    parser.add_argument("--repo", required=True, help="GitHub 仓库（owner/repo）")
    parser.add_argument("--min-severity", default="HIGH",
                        choices=["CRITICAL", "HIGH", "MEDIUM", "LOW"],
                        help="最小严重性阈值（默认 HIGH）")
    parser.add_argument("--label", default="security-triage",
                        help="用于去重标记的 label（默认 security-triage）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印将创建的 issue，不实际创建")
    args = parser.parse_args()

    # 读取 Trivy JSON 报告
    try:
        with open(args.report, "r", encoding="utf-8") as f:
            report = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"[ERROR] 无法读取报告 {args.report}: {e}", file=sys.stderr)
        return 1

    # Trivy JSON 报告格式: [{ "Target": "...", "Vulnerabilities": [...] }, ...]
    if not isinstance(report, list):
        print(f"[WARN] 报告格式非预期（非数组），可能是空报告或格式变更", file=sys.stderr)
        report = []

    min_severity_rank = SEVERITY_ORDER.get(args.min_severity, 3)

    # 收集所有符合严重性阈值的漏洞
    findings = []
    for result_entry in report:
        target = result_entry.get("Target", "unknown")
        vulns = result_entry.get("Vulnerabilities") or []
        for vuln in vulns:
            severity = vuln.get("Severity", "UNKNOWN").upper()
            if SEVERITY_ORDER.get(severity, 0) < min_severity_rank:
                continue
            findings.append({
                "target": target,
                "vulnerability": vuln,
                "package": {
                    "Name": vuln.get("PkgName", "N/A"),
                    "InstalledVersion": vuln.get("InstalledVersion", "N/A"),
                },
                "severity": severity,
            })

    if not findings:
        print(f"[INFO] 未发现 {args.min_severity} 及以上级别的漏洞，无需创建 issue")
        return 0

    print(f"[INFO] 发现 {len(findings)} 个 {args.min_severity}+ 漏洞，开始分诊...")

    # 列出现有 issue 用于去重
    if not args.dry_run:
        existing = list_existing_issues(args.repo, args.label)
        print(f"[INFO] 现有 {len(existing)} 个 open security-triage issue")
    else:
        existing = {}
        print(f"[INFO] --dry-run 模式：不实际创建 issue")

    created = 0
    skipped = 0

    for finding in findings:
        vuln = finding["vulnerability"]
        pkg = finding["package"]
        severity = finding["severity"]
        cve = vuln.get("VulnerabilityID", "UNKNOWN")
        pkg_name = pkg["Name"]

        sla_config = SLA.get(severity, SLA["MEDIUM"])

        # issue 标题（唯一键）
        title = f"[Security/{severity}] {cve} — {pkg_name} ({finding['target']})"

        # 去重检查
        if title in existing:
            print(f"  [SKIP] 已存在 issue #{existing[title]}: {title}")
            skipped += 1
            continue

        body = build_issue_body(vuln, pkg, severity, sla_config)
        labels = [args.label, "security", sla_config["label"], "automated"]

        if args.dry_run:
            print(f"  [DRY-RUN] 将创建: {title}")
            created += 1
            continue

        issue_num = create_issue(args.repo, title, body, labels)
        if issue_num:
            print(f"  [CREATED] #{issue_num}: {title}")
            created += 1
            existing[title] = issue_num
        else:
            print(f"  [FAILED] {title}")

    print(f"\n[SUMMARY] 创建 {created} 个 issue，跳过 {skipped} 个已存在 issue")
    return 0


if __name__ == "__main__":
    sys.exit(main())