#!/usr/bin/env python3
"""Trivy JSON 报告 × 带到期日的基线差分门禁（E1）。

设计约束（见 docs/security-sla.md「基线豁免与到期」一节）：
  * 门禁只对「新增」的 HIGH/CRITICAL 判红；已知项必须登记在基线文件里；
  * 基线条目按 **(CVE 编号, 包名) 二元组**严格匹配。2026-11-06 修正：此前按
    「纯 CVE 编号」匹配，于是 A 包里登记一条 not-applicable 会顺带豁免 B 包里
    的同一条 CVE（实测 CVE-2026-102554：nacos 内嵌副本的豁免吞掉了
    com.google.guava:guava 上的真实修复版），豁免机制整体失效；
  * 基线条目必填 CVE 编号 / 包名 / 存在理由 / 登记日 addedAt / 到期日，缺任一项
    直接拒收；package 为空或含通配符（`*` / `?` / any / all）一律拒收，不允许用
    通配实现「全局豁免」；
  * **有效期只从 addedAt 起算**：`expires == addedAt + 90 天`，与运行当天是哪一天
    无关。此前按「expires - 今天 <= 上限」判定，导致同一份配置在东八区清晨（UTC
    仍是前一天）会多算 1 天、以退出码 2 全体拒收——结论随运行时刻漂移，已废弃；
  * 到期条目一旦再次命中即判红（不允许无到期的永久豁免）；
  * Trivy 报出可用修复版（FixedVersion）时一律拒收。`fixable=not-applicable`
    也只在该二元组**本身无修复版**时成立；同一 CVE 在别的包有修复版会作为独立
    finding 各自判定（不再共享豁免）；
  * `kind: shaded_vendored` 是唯一「有修复版仍可不判红」的有界例外：命中来自
    第三方 jar 的内嵌（relocated）副本、本仓库无法替换。必须给出四件套 evidence
    （jar_path / jar_sha256 / vulnerable_copy + shaded_prefix /
    declared_dependency_fixed + upstream_max_version），有效期上限 90 天，
    命中时强制打印 `[SHADED]` 并计入 `[PASS]` 的例外条数，不静默通过。

退出码：
  0  通过（无新增、无到期、无策略违规）
  1  门禁判红（新增 HIGH/CRITICAL / 基线条目到期 / 有修复版却登记豁免）
  2  输入或配置错误（基线缺字段 / 包名为空或通配 / 无到期日 / 日期越界 / 报告不可读等）

用法：
  python3 scripts/security/trivy-baseline-gate.py \
      --baseline config/trivy-baseline.json \
      --report trivy-fs-gate.json \
      --report trivy-gate-nexus-core.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys

SEVERITY_RANK = {"LOW": 1, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4, "UNKNOWN": 0}

# 有效期固定为「登记日 + 90 天」，所有 kind 一视同仁。
# 判定只依赖 addedAt（确定性），不再依赖运行当天，避免「同一份基线换个 UTC
# 日期就以退出码 2 全体拒收」。
VALIDITY_DAYS = 90

# kind 取值：default（普通条目）/ not-applicable（该二元组无修复版）/ shaded_vendored /
#            pending_base_rebuild（上游已发修复版，但**尚无包含该修复版的已发布基础镜像**）
KINDS = ("default", "not-applicable", "shaded_vendored", "pending_base_rebuild")

# pending_base_rebuild 是**有界**例外：只允许 14 天，到期即判红，必须重新取证。
PENDING_VALIDITY_DAYS = 14
SHADED_EVIDENCE_FIELDS = (
    "jar_path",
    "jar_sha256",
    "vulnerable_copy",
    "shaded_prefix",
    "declared_dependency_fixed",
    "upstream_max_version",
)

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


def _package_rejection(pkg: str) -> str | None:
    """package 必须是精确包名；返回拒收理由，合法则返回 None。

    空值会让 `(id, "")` 变成「只按 CVE 匹配」的变种；通配（`*` / `?` / any /
    all）则直接等于「全局豁免」。两者都违背二元组口径，必须拒收。
    """
    if not pkg:
        return "缺少字段 package（基线必须按 (CVE, 包名) 二元组登记，不允许全局豁免）"
    if "*" in pkg or "?" in pkg:
        return f"package={pkg!r} 含通配符（不允许用通配实现全局豁免，请写 Trivy 报出的精确 PkgName）"
    if pkg.lower() in ("any", "all", "any-package"):
        return f"package={pkg!r} 是占位值（不允许用通配实现全局豁免，请写 Trivy 报出的精确 PkgName）"
    return None


def _shaded_evidence_rejection(evidence) -> str | None:
    """kind=shaded_vendored 必须带四件套 evidence；返回拒收理由，合法则返回 None。"""
    if not isinstance(evidence, dict):
        return ("kind=shaded_vendored 的 evidence 必须是对象，且四件套齐全："
                + " / ".join(SHADED_EVIDENCE_FIELDS))
    missing = [k for k in SHADED_EVIDENCE_FIELDS if not str(evidence.get(k) or "").strip()]
    if missing:
        return (f"kind=shaded_vendored 的 evidence 缺少字段：{', '.join(missing)}"
                "（jar_path / jar_sha256 / vulnerable_copy+shaded_prefix / "
                "declared_dependency_fixed+upstream_max_version 必须齐全）")
    return None


def load_baseline(path: str) -> tuple[dict[tuple[str, str], dict], list[str]]:
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

    by_key: dict[tuple[str, str], dict] = {}
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

        pkg = str(entry.get("package") or "").strip()
        rejection = _package_rejection(pkg)
        if rejection:
            errors.append(f"{where} {rejection}")
        if not str(entry.get("reason") or "").strip():
            errors.append(f"{where} 缺少字段 reason（CVE 编号 / 包名 / 存在理由 / 到期日 均为必填）")

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

        kind = str(entry.get("kind") or "").strip() or "default"
        if kind not in KINDS:
            errors.append(
                f"{where} kind 只能是 {' / '.join(KINDS)}，实际：{kind}"
            )
            kind = "default"

        fixable = str(entry.get("fixable") or "none").strip()
        if kind == "not-applicable":
            fixable = "not-applicable"
        allowed_fixable = ("none", "not-applicable", "upstream-fixed")
        if fixable not in allowed_fixable:
            errors.append(
                f"{where} fixable 只能是 {' / '.join(allowed_fixable)}，实际：{fixable}"
            )
            fixable = "none"
        # pending_base_rebuild 必须显式声明"上游已有修复版"，且必须带证据：
        # 没有证据就不该存在这条豁免（防止把它当成普通"加基线"的通道）。
        if kind == "pending_base_rebuild":
            if fixable != "upstream-fixed":
                errors.append(
                    f"{where} kind=pending_base_rebuild 必须 fixable=upstream-fixed（实际：{fixable}）"
                )
            if not str(evidence or "").strip():
                errors.append(
                    f"{where} kind=pending_base_rebuild 必须填写 evidence（证明当前无包含该修复版的已发布镜像）"
                )

        evidence = entry.get("evidence")
        if kind == "shaded_vendored":
            rejection = _shaded_evidence_rejection(evidence)
            if rejection:
                errors.append(f"{where} {rejection}")
        else:
            if not str(evidence or "").strip():
                evidence = ""
            if fixable == "not-applicable" and not str(evidence).strip():
                errors.append(f"{where} fixable=not-applicable 必须填写 evidence（说明修复版为何不适用）")

        # addedAt 必填：有效期只按登记日 + 90 天计算，缺登记日就无法做确定性校验。
        added_raw = str(entry.get("addedAt") or "").strip()
        added = None
        if not added_raw:
            errors.append(
                f"{where} 缺少 addedAt（有效期必须从登记日 addedAt 起算：expires = addedAt + {VALIDITY_DAYS} 天）"
            )
        else:
            try:
                added = dt.date.fromisoformat(added_raw)
            except ValueError:
                errors.append(f"{where} addedAt 不是 ISO 日期（YYYY-MM-DD）：{added_raw}")

        if expires and added:
            validity_days = PENDING_VALIDITY_DAYS if kind == "pending_base_rebuild" else VALIDITY_DAYS
            expected = added + dt.timedelta(days=validity_days)
            if expires != expected:
                errors.append(
                    f"{where} expires={expires} 与 addedAt={added} + {validity_days} 天 = {expected} 不一致"
                    f"（有效期只按登记日起算，与运行当天无关；超出或缩短都必须重新取证）"
                )

        key = (vuln_id, pkg)
        if key in by_key:
            errors.append(f"{where} 与前面的条目重复（同一 (CVE, 包) 只允许登记一次）")
            continue

        by_key[key] = {
            "id": vuln_id,
            "package": pkg,
            "reason": str(entry.get("reason") or "").strip(),
            "expires": expires,
            "fixable": fixable,
            "kind": kind,
            "evidence": evidence if isinstance(evidence, dict) else str(evidence or "").strip(),
            "added": added,
        }

    return by_key, errors


def _serialize_finding(finding: dict, entry: dict | None = None) -> dict:
    data = {
        "id": finding["id"],
        "package": finding["package"],
        "severity": finding["severity"],
        "installed": finding["installed"],
        "fixedVersions": sorted(finding["fixedVersions"]),
        "targets": sorted(finding["targets"]),
    }
    if entry is not None:
        data["kind"] = entry["kind"]
        data["expires"] = entry["expires"].isoformat() if entry["expires"] else None
        data["evidence"] = entry["evidence"]
    return data


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


def _sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _norm_ts(value) -> str:
    """归一化时间戳，使 `...577403+00:00` 与 `...Z` 两种写法可直接比较。"""
    text = str(value or "").strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    text = re.sub(r"(\.[0-9]{6})[0-9]+", r"\1", text)
    try:
        parsed = dt.datetime.fromisoformat(text)
    except ValueError:
        return text
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc).isoformat()


def _verify_db_identity(db_dir: str, fingerprint_path: str) -> list[str]:
    """校验「本次结论所依据的库」= 刷新步下载并取指纹的那份库。

    背景（2026-11-06 实测）：Trivy v0.76.2 的 JSON 报告 `Metadata` 只有
    Size/OS/ImageID/DiffIDs/RepoTags/Reference/ImageConfig/Layers，**不含**
    VulnerabilityDB（无 Version/UpdatedAt/sha256），因此无法把结论直接绑定到
    报告内嵌身份，只能退一步用文件指纹绑定：

      刷新步  → trivy-db-freshness.py 记录 metadata 字段 + trivy.db sha256
      门禁步  → 读同一份指纹，重算 trivy.db sha256、重读 metadata.json，三者比对

    任何一项不一致都说明「门禁日志里那句『基于哪份库』」不再是产出报告的那份
    库（例如中间有 actions/cache 把缓存目录覆盖回旧库），必须判红而不是照打。
    """
    errors: list[str] = []
    try:
        fingerprint = _load_json(fingerprint_path)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        return [f"刷新步 DB 指纹不可读：{fingerprint_path}（{e}）"]
    if not isinstance(fingerprint, dict) or not fingerprint.get("dbSha256"):
        return [f"刷新步 DB 指纹格式不可识别或缺少 dbSha256：{fingerprint_path}"]

    metadata_path = os.path.join(db_dir, "metadata.json")
    db_path = os.path.join(db_dir, "trivy.db")
    try:
        meta = _load_json(metadata_path)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        return [f"门禁步实际 metadata.json 不可读：{metadata_path}（{e}）"]
    if not isinstance(meta, dict):
        return [f"门禁步实际 metadata.json 格式不可识别：{metadata_path}"]

    try:
        actual_sha256 = _sha256_file(db_path)
    except OSError as e:
        return [f"门禁步实际 trivy.db 不可读：{db_path}（{e}）"]

    if fingerprint["dbSha256"] != actual_sha256:
        errors.append(
            f"DB 文件指纹不一致：刷新步 sha256={fingerprint['dbSha256']}，"
            f"门禁步 sha256={actual_sha256}（{db_path}）"
        )
    for field in ("Version", "UpdatedAt", "NextUpdate"):
        expected = fingerprint.get(field)
        actual = meta.get(field)
        if field == "Version":
            expected = str(expected) if expected is not None else None
            actual = str(actual) if actual is not None else None
        else:
            expected = _norm_ts(expected)
            actual = _norm_ts(actual)
        if expected != actual:
            errors.append(
                f"metadata.json 的 {field} 不一致：刷新步={fingerprint.get(field)}，"
                f"门禁步={meta.get(field)}"
            )

    if not errors:
        print(
            f"[DB] 本次门禁结论基于 Trivy 漏洞库：Version={meta.get('Version')} "
            f"UpdatedAt={meta.get('UpdatedAt')} NextUpdate={meta.get('NextUpdate')} "
            f"sha256={actual_sha256}（已与刷新步指纹交叉校验）"
        )
    return errors


def gate(findings: list[dict], baseline: dict[tuple[str, str], dict], today: dt.date) -> tuple[int, dict]:
    new_findings = []
    expired = []
    policy = []
    shaded = []
    pending_rebuild: list[tuple[dict, dict]] = []
    nofix: list[tuple[dict, dict]] = []

    for finding in findings:
        # 严格二元组匹配：同一 CVE 换到别的包就是另一条 finding，必须独立判定。
        entry = baseline.get((finding["id"], finding["package"]))
        if entry is None:
            new_findings.append(finding)
            continue

        # 该二元组本身报出修复版 → 一律拒收（not-applicable 也不放行）；
        # 唯一例外是 kind=shaded_vendored（命中来自第三方 jar 内嵌副本），仍受到期约束。
        if finding["fixedVersions"]:
            if entry["kind"] == "shaded_vendored":
                if entry["expires"] and today > entry["expires"]:
                    expired.append((finding, entry))
                else:
                    shaded.append((finding, entry))
            elif entry["kind"] == "pending_base_rebuild":
                # 有界例外：上游修复版已发布、但**尚无包含它的基础镜像**（已按 digest 实证）。
                # 只放行到 expires（登记日 + 14 天）；到期即走 expired → 判红。
                if entry["expires"] and today > entry["expires"]:
                    expired.append((finding, entry))
                else:
                    pending_rebuild.append((finding, entry))
            else:
                policy.append((finding, entry))
            continue

        if entry["expires"] and today > entry["expires"]:
            expired.append((finding, entry))
        elif entry["fixable"] == "none":
            # 无修复版且本次仍命中：允许放行，但必须在日志逐条可见（[NOFIX]），
            # 避免「登记一次就静默通过」——每次跑都要能看出这几条还在。
            nofix.append((finding, entry))

    # stale 同样按二元组比对：同一 CVE 在别的包命中了，不代表本条目命中。
    hit_keys = {(f["id"], f["package"]) for f in findings}
    stale = [e for key, e in baseline.items() if key not in hit_keys]
    # 「长期未命中」复核提示：只提示、不判红。判据是条目登记日 addedAt 距今 >= 90 天
    # 且本次仍未命中（addedAt 缺失则无法计算，跳过）。
    stale_aged = [
        e for e in stale
        if e.get("added") and (today - e["added"]).days >= STALE_HINT_DAYS
    ]

    print(f"[INFO] 门禁阈值 HIGH/CRITICAL；唯一 (CVE, 包) 命中 {len(findings)} 条；基线 {len(baseline)} 条")
    print(f"[INFO] 基线命中 {len(baseline) - len(stale)} 条，未命中（stale，仅提示）{len(stale)} 条")

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
        note = ""
        if entry["fixable"] == "not-applicable":
            note = "；fixable=not-applicable 也不适用：该 (CVE, 包) 本身已报出修复版"
        print(
            f"[POLICY] {finding['id']} / {finding['package']} 报出可用修复版 "
            f"({fixed})，禁止按普通基线豁免{note}"
        )
        print(
            f"::error title=Trivy 有修复版不得豁免::"
            f"{finding['id']} / {finding['package']} 报出修复版，请升级而不是加基线"
        )

    for finding, entry in shaded:
        fixed = ", ".join(sorted(finding["fixedVersions"]))
        ev = entry["evidence"]
        print(
            f"[SHADED] {finding['id']} / {finding['package']} 有修复版 {fixed}，"
            f"但命中来自 {ev['jar_path']} 内嵌副本（{ev['vulnerable_copy']}），"
            f"本仓库不可替换；到期 {entry['expires']}"
        )
        print(
            f"::notice title=Trivy 内嵌副本例外::"
            f"{finding['id']} / {finding['package']} 命中 {ev['jar_path']} 内嵌副本 "
            f"{ev['vulnerable_copy']}（shaded {ev['shaded_prefix']}），"
            f"声明依赖已解析到修复版；到期 {entry['expires']}"
        )

    for finding, entry in pending_rebuild:
        fixed = ", ".join(sorted(finding["fixedVersions"]))
        print(
            f"[PENDING-REBUILD] {finding['id']} / {finding['package']} 上游已发布修复版 {fixed}，"
            f"但尚无包含该修复版的已发布基础镜像；到期 {entry['expires']}（到期即判红）"
        )
        print(
            f"::warning title=Trivy 待基础镜像重建::"
            f"{finding['id']} / {finding['package']} 修复版 {fixed} 未随镜像发布，"
            f"已登记待重建（到期 {entry['expires']}）"
        )

    for finding, entry in nofix:
        print(
            f"[NOFIX] {finding['id']} / {finding['package']} 无修复版（Trivy 未给出 FixedVersion），"
            f"已评估登记；到期 {entry['expires']}；reason：{entry['reason']}"
        )
        print(
            f"::notice title=Trivy 无修复版登记::"
            f"{finding['id']} / {finding['package']} 本次仍命中且无修复版，"
            f"按 fixable=none 登记；到期 {entry['expires']}"
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
            "shaded": len(shaded),
            "pendingRebuild": len(pending_rebuild),
            "nofix": len(nofix),
            "stale": len(stale),
            "staleAged": len(stale_aged),
        },
        "new": [_serialize_finding(f) for f in new_findings],
        "expired": [dict(_serialize_finding(f), expires=entry["expires"].isoformat()) for f, entry in expired],
        "policy": [_serialize_finding(f) for f, _ in policy],
        "shaded": [_serialize_finding(f, entry) for f, entry in shaded],
        "pendingRebuild": [_serialize_finding(f, entry) for f, entry in pending_rebuild],
        "nofix": [_serialize_finding(f, entry) for f, entry in nofix],
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

    extras = []
    if shaded:
        extras.append(
            f"含 shaded_vendored 例外 {len(shaded)} 条：有修复版但命中来自第三方 jar 内嵌副本，本次不判红"
        )
    if nofix:
        extras.append(
            f"含无修复版登记 {len(nofix)} 条：FixedVersion 为空，已在日志逐条打印 [NOFIX]"
        )
    if extras:
        print(f"[PASS] 无新增、无到期、无策略违规（{'；'.join(extras)}）")
    else:
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
    parser.add_argument(
        "--db-dir",
        default=None,
        help="可选：Trivy DB 缓存目录（含 trivy.db 与 metadata.json）；"
        "与 --db-fingerprint 成对提供时强制校验结论所依据的库",
    )
    parser.add_argument(
        "--db-fingerprint",
        default=None,
        help="可选：刷新步 trivy-db-freshness.py 写出的 DB 身份指纹 JSON；"
        "与 --db-dir 成对提供时强制校验结论所依据的库",
    )
    args = parser.parse_args()

    # 基准日期显式取 **UTC**（GitHub runner 就是 UTC，本机东八区比 UTC 早 8 小时）。
    # 有效期上限已改为「addedAt + 90 天」的确定性校验，与 today 无关；today 现在
    # 只用于两件事：① 到期判定（today > expires 即到期，这是真实时间事件）；
    # ② stale/长期未命中的天数展示。两者都不再影响配置是否被拒收。
    today = dt.date.fromisoformat(args.today) if args.today else dt.datetime.now(dt.timezone.utc).date()
    threshold_rank = SEVERITY_RANK[args.min_severity]

    baseline, baseline_errors = load_baseline(args.baseline)
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

    if args.db_fingerprint or args.db_dir:
        if not (args.db_fingerprint and args.db_dir):
            message = "--db-dir 与 --db-fingerprint 必须成对提供，否则无法验证结论基于哪份库"
            print(f"[REJECT] {message}")
            print(f"::error title=Trivy DB 身份无法校验::{message}")
            return 2
        db_errors = _verify_db_identity(args.db_dir, args.db_fingerprint)
        if db_errors:
            for message in db_errors:
                print(f"[DB][FAIL] {message}")
                print(f"::error title=Trivy DB 身份不一致::{message}")
            print(f"[REJECT] DB 身份校验失败 {len(db_errors)} 处，门禁拒收（退出码 2）")
            return 2

    code, summary = gate(findings, baseline, today)
    _write_summary(args.summary_json, summary)
    return code


if __name__ == "__main__":
    sys.exit(main())
