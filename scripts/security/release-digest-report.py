#!/usr/bin/env python3
"""
release-digest-report.py — 发布后「按 digest 扫描」的报表与 issue 分诊

背景（E1 之后的缺口）：
`release.yml` 只推 tag，不记录 manifest digest，也不扫描发布产物；
发版镜像里到底有没有 HIGH/CRITICAL 只能靠 build 日志事后猜。
本脚本不负责扫描（扫描在 workflow 里用 trivy 按 `image@sha256:...` 做），
只做两件事：

  1. `--table-output`：把各镜像的 manifest digest 渲染成 Markdown 表，
     由 `github-release` job 拼进 Release body（digest 不再是易失的 build 日志）。
  2. 分诊：汇总各镜像腿的 `trivy-baseline-gate.py --summary-json` 结论，
     生成/更新一条 issue（含 image + digest + 对应 tag + CVE 清单）。

判红语义**完全复用** `trivy-baseline-gate.py`：本脚本只读 summary，不自己判
「新增」。因此 PR 门禁与发布后扫描看到的是同一套门槛（新增 HIGH/CRITICAL、
基线到期、有修复版却登记豁免）。

退出码：
  0 — 正常（无论是否发现问题；发布后扫描**永不阻断发版**）
  1 — 参数/IO 错误（workflow 侧以 continue-on-error 兜住，不判红）
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import subprocess
import sys
import tempfile

# SLA 优先级标签，与 docs/security-sla.md / trivy-to-issues.py 对齐
SLA_LABEL = {"CRITICAL": "P0", "HIGH": "P1"}

STATUS_BY_KEY = {
    "new": "新增（未登记基线）",
    "expired": "基线到期",
    "policy": "有修复版却登记豁免",
}


def run_gh(args: list[str], check: bool = False) -> tuple[int, str, str]:
    cmd = ["gh"] + args
    result = subprocess.run(cmd, capture_output=True, text=True, env=os.environ)
    if check and result.returncode != 0:
        print(f"[ERROR] gh 命令失败: {' '.join(cmd)}", file=sys.stderr)
        print(f"  stderr: {result.stderr.strip()}", file=sys.stderr)
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def load_json_dir(path: str) -> dict:
    """读取目录下所有 *.json，按 module（文件名兜底）索引。"""
    items = {}
    for full in sorted(glob.glob(os.path.join(path, "*.json"))):
        try:
            with open(full, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, json.JSONDecodeError) as e:
            print(f"[WARN] 跳过 {full}: {e}", file=sys.stderr)
            continue
        if not isinstance(data, dict):
            print(f"[WARN] 跳过非对象 JSON：{full}", file=sys.stderr)
            continue
        module = str(data.get("module") or os.path.splitext(os.path.basename(full))[0])
        data["module"] = module
        items[module] = data
    return items


def render_digest_table(digests: dict) -> str:
    lines = [
        "| 模块 | 镜像 | tag | manifest digest |",
        "|------|------|-----|-----------------|",
    ]
    for module, item in sorted(digests.items()):
        tags = ", ".join(str(t) for t in (item.get("tags") or [])) or "—"
        lines.append(
            f"| `{module}` | `{item.get('image', '')}` | `{tags}` | `{item.get('digest', '')}` |"
        )
    return "\n".join(lines) + "\n"


def collect_rows(digests: dict, summaries: dict) -> tuple[list[dict], list[str]]:
    """把各腿 summary 展开成 issue 表格行；返回 (rows, anomalies)。"""
    rows: list[dict] = []
    anomalies: list[str] = []

    for module, digest in sorted(digests.items()):
        summary = summaries.get(module)
        if not summary:
            anomalies.append(f"`{module}`：未产出 gate summary（镜像按 digest 拉取失败或门禁未执行）")
            continue
        for error in summary.get("errors") or []:
            anomalies.append(f"`{module}`：{error}")
        for key, status in STATUS_BY_KEY.items():
            for item in summary.get(key) or []:
                rows.append(
                    {
                        "status": status,
                        "cve": item.get("id", "?"),
                        "package": item.get("package", "?"),
                        "severity": item.get("severity", "?"),
                        "fixed": ", ".join(item.get("fixedVersions") or []) or "（无）",
                        "module": module,
                        "image": digest.get("image", ""),
                        "tags": ", ".join(str(t) for t in (digest.get("tags") or [])),
                        "digest": digest.get("digest", ""),
                    }
                )

    for module in sorted(set(summaries) - set(digests)):
        anomalies.append(f"`{module}`：有 gate summary 但缺 digest 清单（构建腿未上传？）")

    return rows, anomalies


def build_issue_body(tag: str, digests: dict, rows: list[dict], anomalies: list[str],
                     run_url: str | None) -> str:
    counts = {status: 0 for status in STATUS_BY_KEY.values()}
    for row in rows:
        counts[row["status"]] = counts.get(row["status"], 0) + 1
    severities = {row["severity"] for row in rows}
    sla = "P0（24 小时）" if "CRITICAL" in severities else "P1（7 天）"

    lines = [
        f"# 发布后镜像扫描：{tag}",
        "",
        "> 由 `release.yml` → `release-digest-report.py` 自动创建/更新。",
        "> **本 issue 不阻断发版**：镜像已推送、Release 已发布；这里只是把「已发布镜像里的",
        "> 新增 HIGH/CRITICAL」变成可跟踪的工作项。",
        "",
    ]
    if run_url:
        lines += [f"- 扫描 run：{run_url}", ""]

    lines += [
        "## 结论",
        "",
        f"- 按 digest 扫描镜像：**{len(digests)}** 个",
        f"- 新增（未登记基线）：**{counts.get(STATUS_BY_KEY['new'], 0)}**",
        f"- 基线到期：**{counts.get(STATUS_BY_KEY['expired'], 0)}**",
        f"- 有修复版却登记豁免（策略违规）：**{counts.get(STATUS_BY_KEY['policy'], 0)}**",
        f"- 扫描/门禁异常：**{len(anomalies)}**",
        f"- 建议 SLA：**{sla}**（口径见 `docs/security-sla.md`）",
        "",
    ]

    if rows:
        lines += [
            "## 待处理明细",
            "",
            "| 状态 | CVE | 包 | 严重性 | 修复版本 | 模块 | 已发布 tag | digest |",
            "|------|-----|----|--------|----------|------|-----------|--------|",
        ]
        for row in rows:
            lines.append(
                f"| {row['status']} | `{row['cve']}` | `{row['package']}` | {row['severity']} "
                f"| `{row['fixed']}` | `{row['module']}` | `{row['tags']}` | `{row['digest']}` |"
            )
        lines.append("")

    if anomalies:
        lines += ["## 扫描/门禁异常（需人工核对）", ""]
        lines += [f"- {item}" for item in anomalies]
        lines.append("")

    lines += [
        "## 本次发布的镜像 digest（与 Release body 同源）",
        "",
        render_digest_table(digests).rstrip(),
        "",
        "## 处置口径",
        "",
        "- 『新增』= 该 CVE 不在 `config/trivy-baseline.json`，或登记了但本次报出可用修复版；",
        "  按 E1 纪律：**有修复版的 CVE 不允许加基线豁免**，只能升级。",
        "- 『基线到期』= 基线条目超过 `expires` 后仍命中，必须修复或重新评估。",
        "- 『扫描异常』= 镜像按 digest 拉取失败 / 门禁未产出结论，属于信号丢失，需先修链路。",
        "- 修复或复核后重跑同类扫描；同一 CVE 消失后本 issue 可人工关闭（脚本不自动关闭）。",
        "",
        "---",
        "*digest 是发布产物的唯一身份：tag 可被覆盖，`image@sha256:...` 不会。*",
    ]
    return "\n".join(lines) + "\n"


def ensure_labels(repo: str, labels: list[str]) -> None:
    for label in labels:
        color = "B60205" if label.startswith("P") and label[1:].isdigit() else "5319E7"
        run_gh(
            [
                "label", "create", label,
                "--repo", repo,
                "--color", color,
                "--description", "发布后镜像扫描（不阻断发版）",
            ]
        )


def find_existing_issue(repo: str, label: str, tag: str) -> int | None:
    rc, out, err = run_gh(
        [
            "issue", "list",
            "--repo", repo,
            "--label", label,
            "--state", "open",
            "--limit", "100",
            "--json", "number,title",
        ]
    )
    if rc != 0:
        print(f"[WARN] 无法列出现有 issue（gh 未认证或 label 不存在）：{err}", file=sys.stderr)
        return None
    try:
        issues = json.loads(out) if out else []
    except json.JSONDecodeError:
        return None
    prefix = f"[Release/{tag}]"
    for item in issues:
        if str(item.get("title", "")).startswith(prefix):
            return int(item["number"])
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="发布后 digest 报表与 issue 分诊")
    parser.add_argument("--digests", required=True, help="digest 清单目录（build-images 上传的 *.json）")
    parser.add_argument("--tag", required=True, help="发布 tag（如 v2.78.0；试跑为 dryrun）")
    parser.add_argument("--table-output", default=None, help="只渲染 digest 表到该路径（Release body 用）")
    parser.add_argument("--summaries", default=None, help="门禁 summary 目录（分诊模式必填）")
    parser.add_argument("--repo", default=None, help="owner/repo（分诊模式必填）")
    parser.add_argument("--label", default="release-digest-watch")
    parser.add_argument("--dry-run", action="store_true", help="只打印 issue 内容，不创建/更新")
    args = parser.parse_args()

    digests = load_json_dir(args.digests)
    if not digests:
        print(f"[ERROR] digest 目录为空或不可读：{args.digests}", file=sys.stderr)
        return 1

    if args.table_output:
        table = render_digest_table(digests)
        with open(args.table_output, "w", encoding="utf-8") as f:
            f.write(table)
        print(table)
        print(f"[INFO] 已写入 {args.table_output}（{len(digests)} 个镜像）")
        return 0

    if not args.summaries or not args.repo:
        print("[ERROR] 分诊模式需要 --summaries 与 --repo", file=sys.stderr)
        return 1

    summaries = load_json_dir(args.summaries)
    rows, anomalies = collect_rows(digests, summaries)

    print(f"[INFO] 扫描镜像 {len(digests)} 个；命中待处理 {len(rows)} 条；异常 {len(anomalies)} 条")
    for row in rows:
        print(f"  [{row['status']}] {row['cve']} / {row['package']} / {row['module']}@{row['digest']}")
    for item in anomalies:
        print(f"  [ANOMALY] {item}")

    if not rows and not anomalies:
        print("[PASS] 本次发布镜像无新增/到期/策略违规，且全部腿完成扫描（不创建 issue）")
        return 0

    run_url = None
    server = os.environ.get("GITHUB_SERVER_URL")
    repo_slug = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID")
    if server and repo_slug and run_id:
        run_url = f"{server}/{repo_slug}/actions/runs/{run_id}"

    body = build_issue_body(args.tag, digests, rows, anomalies, run_url)
    title = f"[Release/{args.tag}] 发布后镜像扫描：{len(rows)} 项待处理"

    severities = {row["severity"] for row in rows}
    labels = [args.label, "security", "automated"]
    if "CRITICAL" in severities:
        labels.append(SLA_LABEL["CRITICAL"])
    elif rows:
        labels.append(SLA_LABEL["HIGH"])

    if args.dry_run:
        print("[DRY-RUN] 将创建/更新 issue：")
        print(f"  title: {title}")
        print(f"  labels: {', '.join(labels)}")
        print("  --- body ---")
        print(body)
        return 0

    ensure_labels(args.repo, labels)
    existing = find_existing_issue(args.repo, args.label, args.tag)

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".md", delete=False) as tmp:
        tmp.write(body)
        body_path = tmp.name
    try:
        if existing:
            rc, out, err = run_gh(
                ["issue", "edit", str(existing), "--repo", args.repo,
                 "--title", title, "--body-file", body_path]
            )
            if rc != 0:
                print(f"[ERROR] 更新 issue #{existing} 失败：{err}", file=sys.stderr)
                return 1
            print(f"[UPDATED] #{existing}（{len(rows)} 项待处理）")
        else:
            rc, out, err = run_gh(
                ["issue", "create", "--repo", args.repo,
                 "--title", title, "--body-file", body_path,
                 "--label", ",".join(labels)]
            )
            if rc != 0:
                print(f"[ERROR] 创建 issue 失败：{err}", file=sys.stderr)
                return 1
            print(f"[CREATED] {out.strip()}")
    finally:
        os.unlink(body_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
