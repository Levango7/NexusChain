#!/usr/bin/env python3
"""
trivy-to-issues.py — Trivy 漏洞报告 → GitHub Issue 自动分诊

功能：
  - 解析一份或多份 Trivy JSON 报告（fs / 镜像矩阵），提取 CRITICAL/HIGH 漏洞
  - 按 (CVE + 包 + 严重性) 聚合：同一漏洞命中多个模块只开一个 issue，
    body 列出全部受影响 target
  - 自动分类（E1 跑步机分诊，2026-11-05）：
      * os-no-fix    — OS 层包且上游无修复版 ⇒ 标签 os-no-fix，
                       处置建议=豁免纪律评估（不是"升级"）
      * os-fixable   — OS 层包但有修复版 ⇒ 标签 fix-available（随基础镜像升级）
      * app-no-fix / app-fixable — 应用/依赖层同理
  - 通过 label "security-triage" + 标题指纹 + CVE 子串检索三层去重
    （CVE 子串检索保证与旧标题格式 issue 的兼容：改标题格式不产生重复）
  - 在 issue body 中标注 SLA 期限（P0 24h / P1 7d / P2 30d）

用法：
  python3 trivy-to-issues.py \
    --report trivy-fs-results.json \
    --report trivy-docker-reports/nexus-core.json \
    --repo owner/repo \
    --min-severity HIGH \
    --label security-triage

退出码：
  0 — 成功（无论是否创建 issue；指定 --exit-nonzero-on-findings 时，
      阈值以上漏洞已全部有关联 issue → 仍为 0）
  1 — 参数错误或全部报告读取失败；或指定 --exit-nonzero-on-findings 且
      本次新开了 issue / 创建失败（观测型 job 用它表达「出现了未被跟踪的漏洞」）
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
    """列出带有指定 label 的现有 open issue，返回 [{number,title,body}]。"""
    rc, out, err = run_gh([
        "issue", "list",
        "--repo", repo,
        "--label", label,
        "--state", "open",
        "--limit", "200",
        "--json", "number,title,body",
    ])
    if rc != 0:
        print(f"[WARN] 无法列出现有 issue（gh CLI 可能未认证）: {err}", file=sys.stderr)
        return []
    try:
        return json.loads(out) if out else []
    except json.JSONDecodeError:
        return []


def cve_has_open_issue(cve, existing):
    """CVE 子串级去重：任何 open issue 的标题**或正文**含该 CVE 即视为已开。

    2026-11-06（发布后 digest 扫描）：此前只匹配标题，会漏掉人工维护的汇总型
    issue（上游观测的 #495 正文列了 node:24-alpine 的 6 个 CVE，标题里没有编号），
    结果同一批 CVE 被重复开成多条新 issue。正文匹配把这种情况覆盖掉。
    """
    for item in existing:
        blob = f"{item.get('title') or ''}\n{item.get('body') or ''}"
        if cve and cve in blob:
            return item["number"]
    return None


def ensure_label(repo, label, color="D93F0B", description="自动分诊分类标签"):
    """确保 label 存在（已存在时 gh 报错，忽略）。"""
    run_gh(["label", "create", label, "--repo", repo, "--color", color,
            "--description", description])


def ensure_labels(repo, labels):
    """确保 issue 用到的**全部** label 存在。

    2026-11-06（E1）：此前只创建 category 一个标签，其余（security / P0 / P1 /
    automated / 上游观测 label）靠人工预置。gh 在遇到不存在的 label 时会**整条
    issue 创建失败**，于是「失败自动开 issue」的观测任务会静默丢信号。
    这里逐个兜底（已存在时返回非零，忽略）。
    """
    for label in labels:
        ensure_label(repo, label, color=_label_color(label), description=_label_description(label))


def _label_color(label):
    if label.startswith("P") and label[1:].isdigit():
        return "B60205"   # SLA 优先级标签
    if label == "upstream-watch":
        return "5319E7"
    return "D93F0B"


def _label_description(label):
    if label.startswith("P") and label[1:].isdigit():
        return "SLA 优先级"
    if label == "upstream-watch":
        return "上游浮动镜像观测（不阻断合并）"
    return "自动分诊分类标签"


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


def classify(result_class, vuln):
    """E1 跑步机自动分诊：返回 (category, category_text)。

    category:
      os-no-fix    — OS 层包，上游无修复版（跑步机型：豁免纪律评估）
      os-fixable   — OS 层包，有修复版（随基础镜像升级）
      app-no-fix   — 应用/依赖层，无修复版（盯上游 / 换依赖）
      app-fixable  — 应用/依赖层，有修复版（升级依赖）
    """
    fixed = (vuln.get("FixedVersion") or "").strip()
    status = (vuln.get("Status") or "").strip().lower()
    has_fix = bool(fixed) or status == "fixed"
    is_os = (result_class == "os-pkgs")
    if is_os:
        return ("os-no-fix", "OS 层包 · 上游无修复版") if not has_fix \
            else ("os-fixable", "OS 层包 · 有修复版")
    return ("app-no-fix", "应用/依赖层 · 无修复版") if not has_fix \
        else ("app-fixable", "应用/依赖层 · 有修复版")


def build_issue_body(vuln, package, severity, sla_config, category, category_text,
                     affected_targets):
    """构建 issue body（Markdown 格式）。"""
    now = datetime.now(timezone.utc)
    deadline = now + timedelta(days=sla_config["deadline_days"])

    cve = vuln.get("VulnerabilityID", "N/A")
    cvss = vuln.get("CVSS", {})
    cvss_score = cvss.get("nvd", {}).get("V3Score", "N/A") if isinstance(cvss, dict) else "N/A"
    fixed_version = vuln.get("FixedVersion", "") or "未修复（上游无修复版）"
    description = vuln.get("Description", "无描述")
    references = vuln.get("References", [])

    ref_list = "\n".join(f"- [{ref}]({ref})" for ref in references[:5]) if references else "无"
    target_list = "\n".join(f"- `{t}`" for t in affected_targets)

    if category == "os-no-fix":
        advice = f"""1. **上游无修复版，"升级"不适用**：按 `.trivyignore` 既有豁免纪律评估——
   只豁免「OS 层 + 无修复版 + 服务不触达该库」的系统包，每条注明删除条件
   （Debian/Alpine 发布修复后随基础镜像升级移除），并确认 staleness 复核覆盖；
2. 若不可豁免（服务真实触达），评估缓解（配置/禁用功能/换实现）并记录到本 issue；
3. 关联 PR 时注明豁免或缓解二选一，不得静默关闭。"""
    elif category == "os-fixable":
        advice = f"""1. 随基础镜像升级吸收（`{package.get("Name", "N/A")}` 已有修复版
   `{fixed_version}`）；确认基础镜像引用为浮动 tag 后重扫即可消除；
2. 若基础镜像侧无新版本，回到 os-no-fix 流程评估豁免纪律。"""
    else:
        advice = f"""1. 升级 `{package.get("Name", "N/A")}` 至 `{fixed_version}` 或更高版本
2. 如无法立即升级，评估是否可通过配置缓解（如禁用受影响功能）
3. 在 PR 中关联本 issue（`Fixes #<issue_number>`）
4. 修复后关闭本 issue 并验证漏洞已消除"""

    body = f"""## 🔴 安全漏洞发现 — {severity}

| 字段 | 值 |
|------|-----|
| **CVE / 漏洞 ID** | `{cve}` |
| **严重性** | {severity} |
| **CVSS v3 评分** | {cvss_score} |
| **受影响包** | `{package.get("Name", "N/A")}` |
| **已安装版本** | `{package.get("InstalledVersion", "N/A")}` |
| **修复版本** | `{fixed_version}` |
| **自动分类** | **{category_text}**（`{category}`） |
| **发现时间** | {now.strftime("%Y-%m-%d %H:%M UTC")} |

## 🎯 受影响范围（本仓聚合）

{target_list}

## ⏱️ SLA 期限

| 优先级 | 修复期限 | 截止时间 |
|--------|---------|---------|
| **{sla_config["label"]}** | {sla_config["sla_text"]} | {deadline.strftime("%Y-%m-%d %H:%M UTC")} |

> ⚠️ 请在截止时间前完成修复、豁免（按纪律）或缓解。逾期未处理将升级至安全负责人。

## 📝 漏洞描述

{description}

## 🔗 参考链接

{ref_list}

## 🛠️ 处置建议（按自动分类）

{advice}

---
*由 `security-scan.yml` → `trivy-to-issues.py` 自动创建（多报告聚合 + 自动分类）*
*SLA 标准见 docs/security-sla.md*
"""
    return body


def main():
    parser = argparse.ArgumentParser(description="Trivy 漏洞报告 → GitHub Issue 自动分诊")
    parser.add_argument("--report", action="append", required=True,
                        help="Trivy JSON 报告路径（可重复传多份：fs + 镜像矩阵各腿）")
    parser.add_argument("--repo", required=True, help="GitHub 仓库（owner/repo）")
    parser.add_argument("--min-severity", default="HIGH",
                        choices=["CRITICAL", "HIGH", "MEDIUM", "LOW"],
                        help="最小严重性阈值（默认 HIGH）")
    parser.add_argument("--label", default="security-triage",
                        help="用于去重标记的 label（默认 security-triage）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印将创建的 issue，不实际创建")
    parser.add_argument("--exit-nonzero-on-findings", action="store_true",
                        help="发现阈值以上漏洞时返回退出码 1（供『上游浮动镜像观测』job 置红）")
    args = parser.parse_args()

    # 读取一份或多份 Trivy JSON 报告；单份损坏只告警不中断（其余报告照常分诊）
    reports = []
    load_failures = 0
    for path in args.report:
        try:
            with open(path, "r", encoding="utf-8") as f:
                reports.append(json.load(f))
        except (FileNotFoundError, json.JSONDecodeError) as e:
            print(f"[WARN] 跳过报告 {path}: {e}", file=sys.stderr)
            load_failures += 1
    if load_failures == len(args.report):
        print("[ERROR] 所有报告均不可读", file=sys.stderr)
        return 1

    # Trivy JSON 报告格式: [{ "Target": "...", "Class": "...", "Vulnerabilities": [...] }, ...]
    min_severity_rank = SEVERITY_ORDER.get(args.min_severity, 3)

    # 收集所有符合严重性阈值的漏洞，并按 (CVE, 包, 严重性) 聚合
    aggregated = {}
    for report in reports:
        # 2026-11-06（E1）：`trivy -f json` 的原始输出是对象 {"SchemaVersion":2,"Results":[...]}，
        # 不是数组。此前一律按数组处理，导致镜像/fs 报告全部被当成"格式非预期"跳过，
        # 自动分诊实际上从未产出过 issue；这里兼容两种形态。
        if isinstance(report, dict):
            result_entries = report.get("Results")
        elif isinstance(report, list):
            result_entries = report
        else:
            result_entries = None
        if not isinstance(result_entries, list):
            print("[WARN] 报告格式非预期（既非数组也无 Results 数组），跳过该报告", file=sys.stderr)
            continue
        for result_entry in result_entries:
            target = result_entry.get("Target", "unknown")
            result_class = result_entry.get("Class", "")
            vulns = result_entry.get("Vulnerabilities") or []
            for vuln in vulns:
                severity = vuln.get("Severity", "UNKNOWN").upper()
                if SEVERITY_ORDER.get(severity, 0) < min_severity_rank:
                    continue
                cve = vuln.get("VulnerabilityID", "UNKNOWN")
                pkg_name = vuln.get("PkgName", "N/A")
                key = (cve, pkg_name, severity)
                if key not in aggregated:
                    aggregated[key] = {
                        "vulnerability": vuln,
                        "package": {
                            "Name": pkg_name,
                            "InstalledVersion": vuln.get("InstalledVersion", "N/A"),
                        },
                        "severity": severity,
                        "result_class": result_class,
                        "targets": [],
                    }
                if target not in aggregated[key]["targets"]:
                    aggregated[key]["targets"].append(target)

    if not aggregated:
        print(f"[INFO] 未发现 {args.min_severity} 及以上级别的漏洞，无需创建 issue")
        return 0

    print(f"[INFO] 发现 {len(aggregated)} 个 {args.min_severity}+ 唯一漏洞（已跨报告聚合），开始分诊...")

    # 列出现有 issue 用于去重
    if not args.dry_run:
        existing = list_existing_issues(args.repo, args.label)
        print(f"[INFO] 现有 {len(existing)} 个 open security-triage issue")
    else:
        existing = []
        print("[INFO] --dry-run 模式：不实际创建 issue")

    created = 0
    skipped = 0
    failed = 0

    for (_cve, _pkg_name, _severity), finding in sorted(aggregated.items()):
        vuln = finding["vulnerability"]
        pkg = finding["package"]
        severity = finding["severity"]
        cve = vuln.get("VulnerabilityID", "UNKNOWN")

        sla_config = SLA.get(severity, SLA["MEDIUM"])
        category, category_text = classify(finding["result_class"], vuln)

        # issue 标题（唯一键：CVE+包；target 收进 body，跨模块聚合不再刷屏）
        title = f"[Security/{severity}] {cve} — {pkg['Name']}"

        # 双层去重：标题精确匹配（新格式）+ CVE 子串检索（标题或正文，兼容汇总型 issue）
        dup = next(
            (item["number"] for item in existing if item.get("title") == title),
            None,
        ) or cve_has_open_issue(cve, existing)
        if dup:
            print(f"  [SKIP] 已存在 issue #{dup}: {title}")
            skipped += 1
            continue

        body = build_issue_body(vuln, pkg, severity, sla_config,
                                category, category_text, finding["targets"])
        labels = [args.label, "security", sla_config["label"], "automated", category]

        if args.dry_run:
            print(f"  [DRY-RUN] 将创建: {title}（{category_text}；影响 {len(finding['targets'])} 个 target）")
            created += 1
            continue

        ensure_labels(args.repo, labels)
        issue_num = create_issue(args.repo, title, body, labels)
        if issue_num:
            print(f"  [CREATED] #{issue_num}: {title}")
            created += 1
            existing[title] = issue_num
        else:
            print(f"  [FAILED] {title}")
            failed += 1

    print(f"\n[SUMMARY] 创建 {created} 个 issue，跳过 {skipped} 个已存在 issue，失败 {failed} 个")
    if args.exit_nonzero_on_findings and (created > 0 or failed > 0):
        # 观测型 job 用它把「出现未被跟踪的 HIGH/CRITICAL」变成一次红灯：
        # 本次已开 issue ⇒ 判红（提醒有人看），下次定时运行同 finding 会被去重跳过 ⇒ 回绿。
        # 这样红灯只在"新出现且无跟踪"时亮，不会因历史遗留问题长期常红。
        print(f"[OBSERVE] 新增未被跟踪的漏洞 {created} 个 → 退出码 1（观测任务判红，不阻断合并）")
        return 1
    if args.exit_nonzero_on_findings:
        print(f"[OBSERVE] 阈值以上漏洞 {len(aggregated)} 个，均已有关联 issue → 退出码 0（已跟踪）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
