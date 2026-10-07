#!/usr/bin/env python3
"""
依赖许可证策略检查（许可治理缺口补齐）

背景
----
本仓此前**没有任何许可扫描器**（CI 只有 Trivy 漏洞 / Gitleaks 密钥 /
OWASP DC 漏洞）。`docs/licensing.md` §4.3 自述该缺口，§5 列为待办。
后果：新增一个 copyleft（GPL/AGPL）依赖不会有任何门禁提示——
而 `mpc-engine` 依赖的 `multi-party-ecdsa` 正是 **GPL-3.0-or-later**，
属"分发即须以 GPL-3.0 开源整个组合作品"的强传染许可。

本脚本做什么
------------
1. **npm**：解析全部 `package-lock.json`（lockfileVersion 3 自带 `license`
   字段）→ 离线、确定、全覆盖。
2. **Rust**：对每个含 `Cargo.lock` 的目录跑 `cargo metadata --locked`，
   取每个包的 `license` 字段（cargo 是权威来源）。
   若 cargo 不可用或失败 → 该模块标记 UNSCANNED（**不伪装成通过**）。
3. **Java/Gradle**：**未覆盖**——Gradle 无锁文件，需解析已解析依赖树
   （另行评估）。报告里显式标注，不假装覆盖。

默认 **report-only**（exit 0）；加 `--strict` 时，发现强传染许可即 exit 1。
这与本仓"新门禁先观察模式、再转阻断"的惯例一致
（参见 OWASP DC「永远上报」试运行、#53 的 `Trivy 豁免时效复核（不阻断）`）。

用法
----
    python3 scripts/check-license-policy.py --out-dir build/license-report
    python3 scripts/check-license-policy.py --strict      # 阻断模式
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import Counter, defaultdict

# ---------------------------------------------------------------- 许可分类
# 强传染（copyleft）：分发含此许可的组合作品，通常要求整体以同许可开源。
STRONG_COPYLEFT = (
    "GPL-", "AGPL-", "SSPL", "OSL-", "EUPL-", "CPAL-", "CC-BY-NC", "CC-BY-SA",
    "RPL-", "Sleepycat", "QPL-", "NPL-",
)
# 弱传染：库级 copyleft，允许闭源调用，但有源码/重链接等义务。
WEAK_COPYLEFT = ("LGPL-", "MPL-", "EPL-", "CDDL-", "CPL-", "IPL-", "APSL-")
# 宽松：可闭源商用，仅需保留声明。
PERMISSIVE = (
    "MIT", "Apache-2.0", "Apache-1", "BSD-", "ISC", "Zlib", "0BSD", "Unlicense",
    "CC0-1.0", "Unicode-", "W3C", "BSL-1.0", "OpenSSL", "PostgreSQL", "X11",
    "Python-2.0", "Artistic-", "AFL-", "PSF-", "NCSA", "BlueOak-",
)

RANK = {"STRONG_COPYLEFT": 0, "WEAK_COPYLEFT": 1, "UNKNOWN": 2, "PERMISSIVE": 3}


def classify(expr: str) -> str:
    """把 SPDX 表达式归入四类。取表达式中**最危险**的一项定级。"""
    if not expr or not expr.strip():
        return "UNKNOWN"
    # SPDX 表达式按 OR/AND 拆分；OR 时可择宽，AND 时须全守 → 取最危险
    tokens = [t.strip().strip("()") for t in expr.replace("/", " OR ").split()]
    best = "PERMISSIVE"
    for t in tokens:
        if not t or t in ("OR", "AND", "WITH"):
            continue
        up = t.upper()
        if any(up.startswith(p.upper()) for p in STRONG_COPYLEFT):
            return "STRONG_COPYLEFT"          # 强传染直接定级
        if any(up.startswith(p.upper()) for p in WEAK_COPYLEFT):
            best = "WEAK_COPYLEFT" if RANK["WEAK_COPYLEFT"] < RANK[best] else best
        elif not any(up.startswith(p.upper()) for p in PERMISSIVE):
            best = "UNKNOWN" if RANK["UNKNOWN"] < RANK[best] else best
    return best


# ---------------------------------------------------------------- 扫描器
def repo_root() -> str:
    try:
        out = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True).stdout
        return out.strip()
    except Exception:
        return os.getcwd()


def git_files(root: str, pattern: str) -> list[str]:
    """按**文件名**枚举受版本控制的文件。

    注意：不能用 `git ls-files "package-lock.json"` —— git pathspec 不带 `/`
    时**不匹配子目录**（实测返回空）。故先列全部，再按 basename 过滤。
    """
    try:
        out = subprocess.run(["git", "ls-files"], cwd=root,
                             capture_output=True, text=True, check=True).stdout
    except Exception:
        return []
    want = os.path.basename(pattern)
    return [l for l in out.splitlines() if l.strip() and os.path.basename(l) == want]


def scan_npm(root: str, findings: list) -> tuple[int, int]:
    """返回 (包数, 含许可数)。"""
    total = licensed = 0
    for rel in git_files(root, "package-lock.json"):
        p = os.path.join(root, rel)
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception as e:
            findings.append({"eco": "npm", "file": rel, "name": "(解析失败)",
                             "version": "", "license": f"ERROR: {e}",
                             "class": "UNKNOWN"})
            continue
        pkgs = d.get("packages") or {}
        if not pkgs:  # lockfileVersion 1/2 回退
            pkgs = {f"node_modules/{k}": v for k, v in (d.get("dependencies") or {}).items()}
        for path, meta in pkgs.items():
            if not path or not isinstance(meta, dict):
                continue
            # 仓库自身的 workspace 包（link:true 或 resolved 指向本地路径）
            # 不是第三方依赖，不计入许可统计，避免"未声明"噪声淹没真问题。
            if meta.get("link") is True or (
                    meta.get("resolved") and not str(meta["resolved"]).startswith("http")):
                continue
            total += 1
            lic = meta.get("license") or ""
            if lic:
                licensed += 1
            findings.append({"eco": "npm", "file": rel,
                             "name": path.replace("node_modules/", ""),
                             "version": meta.get("version", ""),
                             "license": lic or "(未声明)",
                             "class": classify(lic)})
    return total, licensed


CACHE_REL = "scripts/rust-license-cache.json"


def _parse_cargo_lock(path: str) -> list[tuple[str, str]]:
    """从 Cargo.lock 解析 (name, version)。只做文本解析，不需要 cargo。

    跳过**本地包**（无 `source` 字段者，如 mpc-engine / zk-groth16-service
    自身）——它们不是第三方依赖，查 crates.io 必然查不到。
    """
    import re
    out = []
    try:
        txt = open(path, encoding="utf-8").read()
    except Exception:
        return out
    for blk in txt.split("[[package]]")[1:]:
        n = re.search(r'^name\s*=\s*"([^"]+)"', blk, re.M)
        v = re.search(r'^version\s*=\s*"([^"]+)"', blk, re.M)
        src = re.search(r'^source\s*=', blk, re.M)
        if n and v and src:
            out.append((n.group(1), v.group(1)))
    return out


def _cratesio_license(name: str, ver: str, timeout: int = 20) -> str | None:
    import urllib.request
    url = f"https://crates.io/api/v1/crates/{name}/{ver}"
    req = urllib.request.Request(url, headers={
        "User-Agent": "nexuschain-license-audit (https://github.com/Levango7/NexusChain)"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return (json.load(r).get("version") or {}).get("license") or ""
    except Exception:
        return None


def scan_rust(root: str, findings: list, update_cache: bool = False) -> dict:
    """解析 Cargo.lock，许可证取自 crates.io（带**可提交缓存**，使 CI 离线可复现）。

    为什么不直接 `cargo metadata`：它需要可用的 Rust 工具链 + registry 索引，
    在 security-scan 这类未装 Rust 的 job 里代价过高且易受网络影响。
    Cargo.lock + 缓存是等价的确定性来源，且能离线运行。
    """
    cache_path = os.path.join(root, CACHE_REL)
    cache: dict = {}
    if os.path.exists(cache_path):
        try:
            cache = json.load(open(cache_path, encoding="utf-8"))
        except Exception:
            cache = {}

    locks = sorted(git_files(root, "Cargo.lock"))
    seen, misses, added = set(), [], 0
    for rel in locks:
        for name, ver in _parse_cargo_lock(os.path.join(root, rel)):
            key = f"{name}@{ver}"
            if key in seen:
                continue
            seen.add(key)
            lic = cache.get(key)
            if lic is None and (update_cache or True):
                got = _cratesio_license(name, ver)
                if got is not None:
                    lic = got
                    cache[key] = got
                    added += 1
            if lic is None:
                misses.append(key)
                lic = ""
            findings.append({"eco": "rust", "file": rel, "name": name, "version": ver,
                             "license": lic or "(缓存/查询均未命中)",
                             "class": classify(lic)})

    if update_cache and added:
        try:
            # newline="\n" 强制 LF：否则 Windows 写出 CRLF，与 Linux CI 的
            # blob 内容不一致，会造成"本地与 CI 缓存哈希不同"的伪差异。
            with open(cache_path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(dict(sorted(cache.items())), fh,
                          ensure_ascii=False, indent=1, sort_keys=True)
                fh.write("\n")
        except Exception as e:
            print(f"::warning::缓存写入失败：{e}")

    return {"locks": len(locks), "crates": len(seen),
            "cache_hit": len(seen) - len(misses) - added,
            "api_fetched": added, "misses": misses}


# ---------------------------------------------------------------- 报告
def build_report(findings, rust_stat, npm_stat):
    by_class = defaultdict(list)
    for f in findings:
        by_class[f["class"]].append(f)
    lic_counter = Counter(f["license"] for f in findings)

    L = []
    L.append("# 依赖许可证策略报告\n")
    L.append("> 由 `scripts/check-license-policy.py` 生成。**本报告不是法律意见**，")
    L.append("> 只做机械分类与暴露，处置需人工/法务确认。\n")
    L.append("## 1. 汇总\n")
    L.append("| 分类 | 组件数 | 含义 |")
    L.append("|---|---|---|")
    L.append(f"| 🔴 强传染 (GPL/AGPL…) | **{len(by_class['STRONG_COPYLEFT'])}** | 分发含此组件通常须整体同许可开源 |")
    L.append(f"| 🟠 弱传染 (LGPL/MPL…) | {len(by_class['WEAK_COPYLEFT'])} | 允许闭源调用，有源码/重链接义务 |")
    L.append(f"| ⚪ 未识别 | {len(by_class['UNKNOWN'])} | 需人工核对 |")
    L.append(f"| 🟢 宽松 | {len(by_class['PERMISSIVE'])} | 可闭源商用 |")
    L.append(f"| **合计** | **{len(findings)}** | |\n")

    L.append("## 2. 扫描覆盖（**诚实标注，不假装全覆盖**）\n")
    L.append("| 生态 | 状态 | 说明 |")
    L.append("|---|---|---|")
    L.append(f"| npm | ✅ 已覆盖 | 解析 {len(git_files(repo_root(),'package-lock.json'))} 个 lockfile，"
             f"{npm_stat[1]}/{npm_stat[0]} 个包含 license 字段（离线确定） |")
    if rust_stat.get("crates"):
        miss = rust_stat.get("misses") or []
        if miss:
            L.append(f"| Rust | ⚠️ 部分覆盖 | {rust_stat['crates']} 个 crate；"
                     f"缓存命中 {rust_stat['cache_hit']}、本次查询 {rust_stat['api_fetched']}、"
                     f"**未命中 {len(miss)}**（新增依赖未核实） |")
        else:
            L.append(f"| Rust | ✅ 已覆盖 | {rust_stat['crates']} 个 crate"
                     f"（缓存命中 {rust_stat['cache_hit']}、本次查询 {rust_stat['api_fetched']}） |")
    else:
        L.append("| Rust | ➖ 无 | 未发现 Cargo.lock |")
    L.append("| **Java/Gradle** | ❌ **未覆盖** | Gradle 无锁文件，需解析已解析依赖树；"
             "**本脚本不声称覆盖 Java**，勿据此认为 Java 依赖已合规 |\n")

    if by_class["STRONG_COPYLEFT"]:
        L.append("## 3. 🔴 强传染许可组件（**须处置**）\n")
        L.append("| 生态 | 组件 | 版本 | 许可 | 来源文件 |")
        L.append("|---|---|---|---|---|")
        for f in sorted(by_class["STRONG_COPYLEFT"], key=lambda x: (x["eco"], x["name"])):
            L.append(f"| {f['eco']} | `{f['name']}` | {f['version']} | `{f['license']}` | {f['file']} |")
        L.append("")
        L.append("> 处置路径见 `docs/licensing.md`：接受开源 / 替换依赖 / 隔离+法务确认。\n")

    if by_class["WEAK_COPYLEFT"]:
        L.append("## 4. 🟠 弱传染许可组件\n")
        L.append("| 生态 | 组件 | 版本 | 许可 |")
        L.append("|---|---|---|---|")
        for f in sorted(by_class["WEAK_COPYLEFT"], key=lambda x: (x["eco"], x["name"]))[:60]:
            L.append(f"| {f['eco']} | `{f['name']}` | {f['version']} | `{f['license']}` |")
        if len(by_class["WEAK_COPYLEFT"]) > 60:
            L.append(f"\n（其余 {len(by_class['WEAK_COPYLEFT']) - 60} 条见 JSON 报告）")
        L.append("")

    if by_class["UNKNOWN"]:
        L.append("## 5. ⚪ 未识别许可（需人工核对）\n")
        L.append("| 生态 | 组件 | 版本 | 许可字段 |")
        L.append("|---|---|---|---|")
        for f in sorted(by_class["UNKNOWN"], key=lambda x: (x["eco"], x["name"]))[:60]:
            L.append(f"| {f['eco']} | `{f['name']}` | {f['version']} | `{f['license']}` |")
        if len(by_class["UNKNOWN"]) > 60:
            L.append(f"\n（其余 {len(by_class['UNKNOWN']) - 60} 条见 JSON 报告）")
        L.append("")

    L.append("## 6. 许可分布（Top 20）\n")
    L.append("| 许可 | 组件数 |")
    L.append("|---|---|")
    for k, v in lic_counter.most_common(20):
        L.append(f"| `{k}` | {v} |")
    L.append("")
    return "\n".join(L), by_class


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="build/license-report")
    ap.add_argument("--strict", action="store_true",
                    help="发现强传染许可时 exit 1（默认 report-only）")
    ap.add_argument("--update-cache", action="store_true",
                    help="把本次从 crates.io 查到的 Rust 许可写回 scripts/rust-license-cache.json")
    args = ap.parse_args()

    root = repo_root()
    findings: list = []
    npm_stat = scan_npm(root, findings)
    rust_stat = scan_rust(root, findings, update_cache=args.update_cache)

    md, by_class = build_report(findings, rust_stat, npm_stat)
    os.makedirs(os.path.join(root, args.out_dir), exist_ok=True)
    md_path = os.path.join(root, args.out_dir, "license-report.md")
    js_path = os.path.join(root, args.out_dir, "license-report.json")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(md)
    with open(js_path, "w", encoding="utf-8") as fh:
        json.dump({"findings": findings, "rust": rust_stat,
                   "npm_total": npm_stat[0], "npm_licensed": npm_stat[1]},
                  fh, ensure_ascii=False, indent=2)

    strong = by_class["STRONG_COPYLEFT"]
    print(f"许可扫描：组件 {len(findings)}；强传染 {len(strong)}；"
          f"弱传染 {len(by_class['WEAK_COPYLEFT'])}；未识别 {len(by_class['UNKNOWN'])}")
    print(f"报告：{md_path}")
    for f in strong:
        print(f"  [STRONG-COPYLEFT] {f['eco']} {f['name']} {f['version']} = {f['license']}")
    if rust_stat.get("misses"):
        m = rust_stat["misses"]
        print(f"::warning::Rust 有 {len(m)} 个 crate 许可未核实（新增依赖？）："
              f"{', '.join(m[:10])}{' …' if len(m) > 10 else ''}")

    if args.strict and strong:
        print("::error::--strict 模式下存在强传染许可，门禁失败")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
