#!/usr/bin/env python3
"""Trivy JSON 报告 × 带到期日的基线差分门禁（E1）。

设计约束（见 docs/security-sla.md「基线豁免与到期」一节）：
  * 门禁只对「新增」的 HIGH/CRITICAL 判红；已知项必须登记在基线文件里；
  * 基线条目必填 CVE 编号 / 包名 / 存在理由 / 到期日，缺到期日直接拒收；
  * 到期条目一旦再次命中即判红（不允许无到期的永久豁免）；
  * Trivy 报出可用修复版（FixedVersion）的条目默认拒收；只有「修复版不适用于
    被扫产物」（例如 nacos-client 内嵌的 relocated 副本）才能显式登记为
    fixable=not-applicable，且必须写 evidence，最长 90 天。

退出码：
  0  通过（无新增、无到期、无策略违规）
  1  门禁判红（新增 HIGH/CRITICAL / 基线条目到期 / 有修复版却按普通豁免登记）
  2  输入或配置错误（基线缺字段、无到期日、日期越界、报告不可读等）

用法：
  python3 scripts/security/trivy-baseline-gate.py \
      --baseline config/trivy-baseline.json \
      --report trivy-fs-gate.json \
      --report trivy-gate-nexus-core.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys

SEVERITY_RANK = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4, "UNKNOWN": 0}

# 普通基线条目最长有效期；fixable=not-applicable 再收紧到 90 天
MAX_HORIZON_DAYS = 180
MAX_HORIZON_DAYS_FIX_EXCEPTION = 90

# 基线条目「长期未命中」的复核提示阈值（只提示，不判红）
STALE_HINT_DAYS = 90


def _load_json(path: str):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _iter_result_entries(report):
    """兼容 Trivy 的两种 JSON 形态；返回 None 表示结构不可识别。

    * `trivy -f json` 的原始输出是 {"SchemaVersion": 2, "Results": [...]}；
    * 少数场景（多 artifact 聚合、历史脚本）会是 [{"Results": [...]}, ...]。

    **本次无 finding 的干净报告必须视为合法（返回空数组）**：
    Trivy 在没有任何结果时会给出 `"Results": []`，某些版本/参数下会直接省略
    `Results` 键。若把这两种情况当"报告损坏"，干净镜像的腿就会以退出码 2
    判红 —— 等于让 PR 永久红（本仓踩过的形态）。故只在「既无 Results、也没有
    Trivy 报告特征字段」时才认定为不可识别。
    """
    if isinstance(report, dict):
        results = report.get("Results")
        if isinstance(results, list):
            return results
        if "SchemaVersion" in report or "ArtifactName" in report:
            return []
        return None
    if isinstance(report, list):
        flattened = []
        for item in report:
            if isinstance(item, dict) and isinstance(item.get("Results"), list):
                flattened.extend(item["Results"])
            elif isinstance(item, dict):
                flattened.append(item)
        return flattened
    return None


def collect_findings(paths: list[str], threshold_rank: int) -> tuple[list[dict], list[str]]:
    """把多份报告摊平成 finding 列表；同 (CVE, 包) 跨模块合并。"""
    findings: dict[tuple[str, str], dict] = {}
    errors: list[str] = []

    for path in paths:
        try:
            report = _load_json(path)
        except (FileNotFoundError, json.JSONDecodeError) as e:
            errors.append(f"报告不可读：{path}（{e}）")
            continue

        results = _iter_result_entries(report)
        if results is None:
            errors.append(f"报告结构不可识别（既非 Trivy 对象也非数组）：{path}")
            continue

        for result in results:
            if not isinstance(result, dict):
                continue
            target = result.get("Target") or "unknown"
            result_class = result.get("Class") or ""
            for vuln in result.get("Vulnerabilities") or []:
                if not isinstance(vuln, dict):
                    continue
                severity = str(vuln.get("Severity") or "UNKNOWN").upper()
                if SEVERITY_RANK.get(severity, 0) < threshold_rank:
                    continue
                vuln_id = (vuln.get("VulnerabilityID") or "").strip()
                pkg = (vuln.get("PkgName") or "").strip()
                if not vuln_id or not pkg:
                    continue
                key = (vuln_id, pkg)
                record = findings.setdefault(
                    key,
                    {
                        "id": vuln_id,
                        "package": pkg,
                        "severity": severity,
                        "installed": vuln.get("InstalledVersion") or "",
                        "fixedVersions": set(),
                        "targets": set(),
                        "classes": set(),
                    },
                )
                fixed = (vuln.get("FixedVersion") or "").strip()
                if fixed:
                    record["fixedVersions"].add(fixed)
                record["targets"].add(f"{target} [{result_class}]")
                if SEVERITY_RANK.get(severity, 0) > SEVERITY_RANK.get(record["severity"], 0):
                    record["severity"] = severity
                record["classes"].add(result_class)

    return list(findings.values()), errors


def load_baseline(path: str, today: dt.date) -> tuple[dict[str, dict], list[str]]:
    """读取并校验基线；任何不合规条目都进 errors（调用方以退出码 2 拒收）。"""
    errors: list[str] = []
    try:
        baseline = _load_json(path)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        return {}, [f"基线文件不可读：{path}（{e}）"]

    if not isinstance(baseline, dict):
        return {}, [f"基线格式错误：顶层必须是对象，实际是 {type(baseline).__name__}"]

    entries = baseline.get("entries")
    if not isinstance(entries, list) or not entries:
        return {}, ["基线格式错误：entries 必须是非空数组"]

    by_id: dict[str, dict] = {}
    for index, entry in enumerate(entries, start=1):
        where = f"entries[{index}]"
        if not isinstance(entry, dict):
            errors.append(f"{where} 不是对象")
            continue

        vuln_id = str(entry.get("id") or "").strip()
        if not vuln_id:
            errors.append(f"{where} 缺少 CVE 编号字段 id")
            continue
        where = f"{where}({vuln_id})"

        for field in ("package", "reason"):
            if not str(entry.get(field) or "").strip():
                errors.append(f"{where} 缺少字段 {field}（CVE 编号 / 包名 / 存在理由 / 到期日 均为必填）")

        expires_raw = str(entry.get("expires") or "").strip()
        if not expires_raw:
            errors.append(f"{where} 缺少 expires（不允许无到期的永久豁免）")
            expires = None
        else:
            try:
                expires = dt.date.fromisoformat(expires_raw)
            except ValueError:
                errors.append(f"{where} expires 不是 ISO 日期（YYYY-MM-DD）：{expires_raw}")
                expires = None

        fixable = str(entry.get("fixable") or "none").strip()
        if fixable not in ("none", "not-applicable"):
            errors.append(f"{where} fixable 只能是 none / not-applicable，实际：{fixable}")
        if fixable == "not-applicable" and not str(entry.get("evidence") or "").strip():
            errors.append(f"{where} fixable=not-applicable 必须填写 evidence（说明修复版为何不适用）")

        # addedAt 可选：登记进基线的日期，用于「长期未命中」复核提示（只提示、不判红）。
        # 缺失时不报错（历史条目不强制回填），但也就无法计算 stale 天数。
        added_raw = str(entry.get("addedAt") or "").strip()
        added = None
        if added_raw:
            try:
                added = dt.date.fromisoformat(added_raw)
            except ValueError:
                errors.append(f"{where} addedAt 不是 ISO 日期（YYYY-MM-DD）：{added_raw}")

        if expires:
            horizon = MAX_HORIZON_DAYS_FIX_EXCEPTION if fixable == "not-applicable" else MAX_HORIZON_DAYS
            if (expires - today).days > horizon:
                errors.append(
                    f"{where} expires={expires} 超出最长有效期 {horizon} 天"
                    f"（fixable={fixable}），请缩短复核周期"
                )

        if vuln_id in by_id:
            errors.append(f"{where} 与前面的条目重复（同一 CVE 只允许登记一次）")
            continue

        by_id[vuln_id] = {
            "id": vuln_id,
            "package": str(entry.get("package") or "").strip(),
            "reason": str(entry.get("reason") or "").strip(),
            "expires": expires,
            "fixable": fixable,
            "evidence": str(entry.get("evidence") or "").strip(),
            "added": added,
        }

    return by_id, errors


def _serialize_finding(finding: dict) -> dict:
    return {
        "id": finding["id"],
        "package": finding["package"],
        "severity": finding["severity"],
        "installed": finding["installed"],
        "fixedVersions": sorted(finding["fixedVersions"]),
        "targets": sorted(finding["targets"]),
    }


def _age_days(today: dt.date, added: dt.date) -> int:
    # 门禁按 UTC 日期判定，而 addedAt 允许是「今天（本地）」登记的日期；
    # 东八区清晨跑 UTC 还是前一天，直接相减会得到 -1。展示层夹到 0，避免
    # 输出「登记 -1 天」这种看起来像算错了的日志（不影响 90 天判定）。
    return max(0, (today - added).days)


def _serialize_entry(entry: dict, today: dt.date) -> dict:
    stale_days = _age_days(today, entry["added"]) if entry.get("added") else None
    return {
        "id": entry["id"],
        "package": entry["package"],
        "reason": entry["reason"],
        "expires": entry["expires"].isoformat() if entry["expires"] else None,
        "addedAt": entry["added"].isoformat() if entry.get("added") else None,
        "staleDays": stale_days,
    }


def gate(findings: list[dict], baseline: dict[str, dict], today: dt.date) -> tuple[int, dict]:
    new_findings = []
    expired = []
    policy = []
    fix_exceptions = []

    for finding in findings:
        entry = baseline.get(finding["id"])
        if entry is None:
            new_findings.append(finding)
            continue

        if finding["fixedVersions"] and entry["fixable"] != "not-applicable":
            policy.append((finding, entry))
            continue

        if entry["fixable"] == "not-applicable" and finding["fixedVersions"]:
            fix_exceptions.append((finding, entry))

        if entry["expires"] and today > entry["expires"]:
            expired.append((finding, entry))

    hit_ids = {f["id"] for f in findings}
    stale = [e for eid, e in baseline.items() if eid not in hit_ids]
    # 「长期未命中」复核提示：只提示、不判红。判据是条目登记日 addedAt 距今 >= 90 天
    # 且本次仍未命中（addedAt 缺失则无法计算，跳过）。
    stale_aged = [
        e for e in stale
        if e.get("added") and (today - e["added"]).days >= STALE_HINT_DAYS
    ]

    print(f"[INFO] 门禁阈值 HIGH/CRITICAL；唯一 (CVE, 包) 命中 {len(findings)} 条；基线 {len(baseline)} 条")
    print(f"[INFO] 基线命中 {len(baseline) - len(stale)} 条，未命中（stale，仅提示）{len(stale)} 条")

    for finding, entry in fix_exceptions:
        fixed = ", ".join(sorted(finding["fixedVersions"]))
        print(
            f"[EXCEPTION] {finding['id']} / {finding['package']} 报出修复版 "
            f"({fixed})，按 fixable=not-applicable 放行："
            f"{entry['evidence']}（到期 {entry['expires']}）"
        )
        print(
            f"::warning title=修复版不可用的基线豁免::"
            f"{finding['id']} 依赖 fixable=not-applicable 放行，到期日 {entry['expires']}"
        )

    for finding in new_findings:
        targets = "、".join(sorted(finding["targets"]))
        print(f"[NEW] {finding['id']} / {finding['package']} {finding['severity']} 未登记基线（目标：{targets}）")
        print(
            f"::error title=Trivy 新增 {finding['severity']}::"
            f"{finding['id']} / {finding['package']} 未登记基线（目标：{targets}）"
        )

    for finding, entry in expired:
        print(
            f"[EXPIRED] {finding['id']} / {finding['package']} {finding['severity']} "
            f"基线条目已于 {entry['expires']} 到期（reason：{entry['reason']}）"
        )
        print(
            f"::error title=Trivy 基线到期::"
            f"{finding['id']} / {finding['package']} 基线到期日 {entry['expires']}，必须修复或重新评估"
        )

    for finding, entry in policy:
        fixed = ", ".join(sorted(finding["fixedVersions"]))
        print(
            f"[POLICY] {finding['id']} / {finding['package']} 报出可用修复版 "
            f"({fixed})，禁止按普通基线豁免"
        )
        print(
            f"::error title=Trivy 有修复版不得豁免::"
            f"{finding['id']} / {finding['package']} 报出修复版，请升级而不是加基线"
        )

    for entry in stale:
        days = _age_days(today, entry["added"]) if entry.get("added") else None
        age_text = f"登记 {days} 天" if days is not None else "登记日期未知"
        print(
            f"[STALE] 基线条目 {entry['id']} / {entry['package']} 本次未命中"
            f"（{age_text}，到期 {entry['expires']}）"
        )

    for entry in stale_aged:
        days = _age_days(today, entry["added"])
        print(
            f"[STALE-AGED] 基线条目 {entry['id']} / {entry['package']} 已 {days} 天未命中，"
            f"需复核是否可清理（到期 {entry['expires']}）"
        )
        print(
            f"::notice title=基线长期未命中::"
            f"{entry['id']} / {entry['package']} 登记于 {entry['added']}，已 {days} 天未命中；"
            f"请复核是否仍需要该豁免（只提示，不判红）"
        )

    summary = {
        "today": today.isoformat(),
        "counts": {
            "findings": len(findings),
            "baseline": len(baseline),
            "new": len(new_findings),
            "expired": len(expired),
            "policy": len(policy),
            "fixExceptions": len(fix_exceptions),
            "stale": len(stale),
            "staleAged": len(stale_aged),
        },
        "new": [_serialize_finding(f) for f in new_findings],
        "expired": [dict(_serialize_finding(f), expires=entry["expires"].isoformat()) for f, entry in expired],
        "policy": [_serialize_finding(f) for f, _ in policy],
        "fixExceptions": [
            dict(_serialize_finding(f), evidence=entry["evidence"], expires=entry["expires"].isoformat())
            for f, entry in fix_exceptions
        ],
        "stale": [_serialize_entry(e, today) for e in stale],
        "staleAged": [_serialize_entry(e, today) for e in stale_aged],
        "errors": [],
    }

    if new_findings or expired or policy:
        print(
            f"[FAIL] 门禁判红：新增 {len(new_findings)} 条 / 到期 {len(expired)} 条 / "
            f"有修复版却登记豁免 {len(policy)} 条"
        )
        return 1, summary

    print("[PASS] 无新增、无到期、无策略违规")
    return 0, summary


def _write_summary(path: str | None, summary: dict) -> None:
    """可选的机器可读结论。写失败只告警，不改变门禁退出码。"""
    if not path:
        return
    try:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
            f.write("\n")
    except OSError as e:
        print(f"[WARN] 无法写入 summary JSON：{path}（{e}）")


def main() -> int:
    parser = argparse.ArgumentParser(description="Trivy 基线差分门禁")
    parser.add_argument("--baseline", required=True, help="基线 JSON（config/trivy-baseline.json）")
    parser.add_argument("--report", action="append", required=True, help="Trivy JSON 报告（可重复）")
    parser.add_argument(
        "--min-severity",
        default="HIGH",
        choices=["CRITICAL", "HIGH"],
        help="判红阈值（默认 HIGH，即 HIGH+CRITICAL）",
    )
    parser.add_argument(
        "--today",
        default=None,
        help="覆盖当前日期（ISO），仅用于 fixture 测试；缺省取 UTC 日期",
    )
    parser.add_argument(
        "--summary-json",
        default=None,
        help="可选：把门禁结论写成机器可读 JSON（供发布后 digest 扫描等下游消费）",
    )
    args = parser.parse_args()

    # 基准日期显式取 **UTC**，不用本地日期：
    # GitHub runner 的时区是 UTC，而开发者本机（本仓为东八区）比 UTC 早 8 小时，
    # 于是「本机看到今天，CI 上还是昨天」。若用本地日期做「有效期上限」校验，
    # 同一份基线会在 CI 上多算 1 天而被 `MAX_HORIZON_DAYS_FIX_EXCEPTION` 误判
    # 越界、以退出码 2 永久拒收（本 PR 第一次跑 CI 就踩到了：
    # entries[10](CVE-2026-102554) expires=2027-02-04 超出 90 天）。
    # 固定 UTC 让 CI 与本地结论一致、可复现；副作用是到期判定最多晚 1 天（可接受，
    # 到期复核本就是人工动作）。基线到期日请按 UTC 计算，并留 1 天余量。
    today = dt.date.fromisoformat(args.today) if args.today else dt.datetime.now(dt.timezone.utc).date()
    threshold_rank = SEVERITY_RANK[args.min_severity]

    baseline, baseline_errors = load_baseline(args.baseline, today)
    findings, report_errors = collect_findings(args.report, threshold_rank)

    errors = baseline_errors + report_errors
    if errors:
        for message in errors:
            print(f"[CONFIG] {message}")
            print(f"::error title=Trivy 基线配置无效::{message}")
        print(f"[REJECT] 配置/输入错误 {len(errors)} 处，门禁拒收（退出码 2）")
        _write_summary(args.summary_json, {
            "today": today.isoformat(),
            "counts": {"new": 0, "expired": 0, "policy": 0, "fixExceptions": 0,
                       "stale": 0, "staleAged": 0, "baseline": len(baseline), "findings": 0},
            "new": [], "expired": [], "policy": [], "fixExceptions": [],
            "stale": [], "staleAged": [], "errors": errors,
        })
        return 2

    code, summary = gate(findings, baseline, today)
    _write_summary(args.summary_json, summary)
    return code


if __name__ == "__main__":
    sys.exit(main())
