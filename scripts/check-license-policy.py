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
2. **Rust**：解析全部 `Cargo.lock`（文本解析，不依赖 cargo 工具链），许可查
   `scripts/rust-license-cache.json`（经 crates.io 核实过的可提交缓存）。
   缓存未命中时默认计为 miss 并在报告里如实标注（**不伪装成通过**）；
   `--update-cache` 时才联网补查并写回缓存（保证 CI 离线可复现）。
3. **Java/Gradle**（2026-11-06 补齐，板 20261105-0502）：Gradle 无锁文件，故清单由
   `scripts/java-deps-export.init.gradle`（只读导出 `runtimeClasspath` 已解析坐标）
   + `scripts/java-deps-collect.py merge` 合成为**可提交清单**
   `scripts/java-dependencies.json`；许可字符串查 `scripts/java-license-cache.json`
   （Maven Central POM 的 `<licenses>`，含 parent 链继承，经人工/脚本核实后提交）。
   同样**默认离线只读缓存**，`--update-java-cache` 才联网补查。
   清单缺失 ⇒ Java 仍是"未覆盖"，报告如实标 ❌，不假装扫过。

默认 **report-only**（exit 0）；加 `--strict` 时，发现强传染许可即 exit 1。
这与本仓"新门禁先观察模式、再转阻断"的惯例一致
（参见 OWASP DC「永远上报」试运行、#53 的 `Trivy 豁免时效复核（不阻断）`）。

用法
----
    python3 scripts/check-license-policy.py --out-dir build/license-report
    python3 scripts/check-license-policy.py --strict      # 阻断模式
    python3 scripts/check-license-policy.py --selftest    # 内置合成样本（三向）

Java 腿的刷新流程（清单/缓存都由维护者显式刷新，CI 只读）：
    ./gradlew --no-daemon --init-script scripts/java-deps-export.init.gradle \
        -Dnexus.javaDepsOut=$PWD/build/java-deps exportJavaDependencies
    # …对 6 个 included build 各跑一次同一命令（见 ci.yml 的步骤）
    python3 scripts/java-deps-collect.py merge --from build/java-deps \
        --out scripts/java-dependencies.json
    python3 scripts/check-license-policy.py --update-java-cache
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
            # 2026-10-18 修复：原为 `(update_cache or True)` 恒真——任何运行都会对
            # 未命中项联网查 crates.io（CI 网络依赖 + 非确定性），与模块文档
            # 「可提交缓存、CI 离线可复现」的契约冲突。默认离线只读缓存；
            # 未命中项如实计 miss（维护者用 --update-cache 刷新）。
            if lic is None and update_cache:
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


# ---------------------------------------------------------------- Java/Gradle
JAVA_MANIFEST_REL = "scripts/java-dependencies.json"
JAVA_CACHE_REL = "scripts/java-license-cache.json"
MAVEN_POM_BASE = "https://repo1.maven.org/maven2"


def _pom_url(group: str, name: str, ver: str) -> str:
    return (f"{MAVEN_POM_BASE}/{group.replace('.', '/')}/{name}/{ver}/{name}-{ver}.pom")


def _xml_local(el, tag: str) -> list:
    """按 local-name 取子元素——POM 可能带 http://maven.apache.org/POM/4.0.0 命名空间。"""
    out = []
    for c in el:
        t = c.tag.split('}')[-1] if isinstance(c.tag, str) else c.tag
        if t == tag:
            out.append(c)
    return out


def _pom_license_names(group: str, name: str, ver: str, timeout: int = 20,
                     max_hops: int = 6) -> list[str] | None:
    """取 POM 的 `<licenses><name>` 列表；**沿 parent 链继承**（多数 Apache/Spring
    子模块把许可声明在父 POM）。

    返回 None = 取不到（网络失败 / 404 / XML 非法），调用方按"未核实"处理；
    返回 [] = POM 树里确实没有 `<licenses>`，属"已核实但 POM 未声明"，
    与"没查过"必须区分——否则缓存里的真实缺口会被反复当成 miss 或当成通过。
    """
    import urllib.request
    import xml.etree.ElementTree as ET

    names: list[str] = []
    g, n, v = group, name, ver
    seen: set[str] = set()
    for _ in range(max_hops):
        key = f"{g}:{n}:{v}"
        if key in seen:
            break
        seen.add(key)
        try:
            req = urllib.request.Request(_pom_url(g, n, v), headers={
                "User-Agent": "nexuschain-license-audit "
                              "(https://github.com/Levango7/NexusChain)"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                body = r.read(1_000_000)
        except Exception:
            return None
        # POM 是第三方内容：合法 POM 从不含 DOCTYPE，出现即按"不可信"丢弃，
        # 避免实体展开（billion laughs）/ 外部实体这类解析器攻击面。
        if b'<!doctype' in body.lower():
            return None
        try:
            root = ET.fromstring(body)
        except Exception:
            return None
        for lic_node in _xml_local(root, 'licenses'):
            for lic in _xml_local(lic_node, 'license'):
                for nm in _xml_local(lic, 'name'):
                    txt = (nm.text or '').strip()
                    if txt:
                        names.append(txt)
        parents = _xml_local(root, 'parent')
        if not parents:
            break
        par = parents[0]
        pg = (_xml_local(par, 'groupId') or [None])[0]
        pn = (_xml_local(par, 'artifactId') or [None])[0]
        pv = (_xml_local(par, 'version') or [None])[0]
        if not (pg is not None and pn is not None and pv is not None):
            break
        g, n, v = (pg.text or g).strip(), (pn.text or n).strip(), (pv.text or v).strip()
    return names


# POM 里的许可名是自由文本，这里只做**高置信**归一；拿不准的一律原样保留，
# 交给 classify() 判 UNKNOWN（宁可留人工核对项，不要把猜测写成结论）。
_POM_NAME_MAP = (
    ("apache license, version 2", "Apache-2.0"),
    ("apache software license, version 2", "Apache-2.0"),
    ("apache license 2", "Apache-2.0"),
    ("the apache license, version 2", "Apache-2.0"),
    ("gnu general public license, version 3", "GPL-3.0"),
    ("gnu general public license, version 2", "GPL-2.0"),
    ("gnu affero general public license", "AGPL-3.0"),
    ("gnu lesser general public license, version 3", "LGPL-3.0"),
    ("gnu lesser general public license, version 2.1", "LGPL-2.1"),
    ("gnu lesser general public license version 3", "LGPL-3.0"),
    ("eclipse public license v. 2.0", "EPL-2.0"),
    ("eclipse public license 2.0", "EPL-2.0"),
    ("eclipse distribution license, version 1.0", "BSD-3-Clause"),
    ("mozilla public license, version 2.0", "MPL-2.0"),
    ("mozilla public license 2.0", "MPL-2.0"),
    ("common development and distribution license (cddl), version 1.1", "CDDL-1.1"),
    ("common development and distribution license version 1.1", "CDDL-1.1"),
    ("mit license", "MIT"),
    ("the mit license", "MIT"),
    ("new bsd license", "BSD-3-Clause"),
    ("bsd 3-clause", "BSD-3-Clause"),
    ("the bsd license", "BSD-3-Clause"),
    ("bsd licence", "BSD-3-Clause"),
    ("simplified bsd license", "BSD-2-Clause"),
    ("eclipse public license v. 1.0", "EPL-1.0"),
    ("gnu lesser general public license", "LGPL"),
    ("gnu general public license", "GPL"),
)


def _normalize_pom_license(names: list[str]) -> str:
    if not names:
        return ""
    out = []
    for raw in names:
        low = raw.lower().strip()
        hit = None
        for pat, spdx in _POM_NAME_MAP:
            if pat in low:
                hit = spdx
                break
        out.append(hit or raw.strip())
    # 多个 <license> 在 Maven 语义里是"任选其一"，但 classify() 刻意取最危险项，
    # 这里保留 OR 串即可（保守定级由 classify 负责）。
    return " OR ".join(dict.fromkeys(out))


def scan_java(root: str, findings: list, update_cache: bool = False) -> dict:
    """读可提交清单 + 可提交许可缓存；默认**离线**，miss 如实计入。

    与 Rust 腿同构的理由：许可扫描要出现在 CI 门禁里，就必须离线、确定性、
    不新增网络依赖（本仓 2026-10-18 已为 Rust 腿的恒联网问题修过一次）。
    Java 侧唯一的"已解析"视图来自 Gradle，故拆成「维护者跑导出 + 合成提交」与
    「CI 只读清单」两步。
    """
    manifest_path = os.path.join(root, JAVA_MANIFEST_REL)
    cache_path = os.path.join(root, JAVA_CACHE_REL)
    stat = {"present": False, "coords": 0, "modules": 0, "builds": 0,
            "cached": 0, "undeclared": 0, "misses": [], "unresolved": []}

    if not os.path.exists(manifest_path):
        return stat
    try:
        manifest = json.load(open(manifest_path, encoding="utf-8"))
    except Exception as e:
        stat["error"] = f"清单不可解析：{e}"
        return stat

    coords = manifest.get("coordinates") or []
    stat.update({"present": True, "coords": len(coords),
                 "modules": len(manifest.get("modules") or {}),
                 "builds": len(manifest.get("builds") or []),
                 "unresolved": manifest.get("unresolved") or []})

    cache: dict = {}
    if os.path.exists(cache_path):
        try:
            cache = json.load(open(cache_path, encoding="utf-8"))
        except Exception:
            cache = {}

    dirty = 0
    src_by_coord: dict[str, str] = {}
    for mod_key, cs in (manifest.get("modules") or {}).items():
        for c in cs:
            src_by_coord.setdefault(c, mod_key)

    for c in coords:
        lic = cache.get(c)
        if lic is None and update_cache:
            parts = c.split(":")
            if len(parts) == 3:
                names = _pom_license_names(*parts)
                if names is not None:
                    lic = _normalize_pom_license(names)
                    cache[c] = lic
                    dirty += 1
        src = src_by_coord.get(c, "(未归属模块)")
        if lic is None:
            stat["misses"].append(c)
            findings.append({"eco": "java", "file": JAVA_MANIFEST_REL, "name": c,
                             "version": "", "license": "(缓存未核实)",
                             "class": "UNKNOWN", "module": src})
            continue
        if lic == "":
            stat["undeclared"] += 1
            findings.append({"eco": "java", "file": JAVA_MANIFEST_REL, "name": c,
                             "version": "", "license": "(POM 未声明许可)",
                             "class": "UNKNOWN", "module": src})
            continue
        stat["cached"] += 1
        findings.append({"eco": "java", "file": JAVA_MANIFEST_REL, "name": c,
                         "version": "", "license": lic, "class": classify(lic),
                         "module": src})

    if update_cache and dirty:
        try:
            with open(cache_path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(dict(sorted(cache.items())), fh,
                          ensure_ascii=False, indent=1, sort_keys=True)
                fh.write("\n")
        except Exception as e:
            print(f"::warning::Java 许可缓存写入失败：{e}")
    return stat


# ---------------------------------------------------------------- 报告
def build_report(findings, rust_stat, npm_stat, java_stat):
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
    if java_stat.get("present"):
        jmiss = java_stat.get("misses") or []
        L.append(f"| {'✅' if not jmiss else '⚠️'} Java/Gradle | "
                 f"{'已覆盖' if not jmiss else '部分覆盖'} | "
                 f"清单 {java_stat['coords']} 个第三方坐标"
                 f"（{java_stat['modules']} 模块 / {java_stat['builds']} 构建树）；"
                 f"许可缓存命中 {java_stat['cached']}、POM 未声明 {java_stat['undeclared']}、"
                 f"**未核实 {len(jmiss)}**（新增依赖需 `--update-java-cache` 刷新） |")
    else:
        L.append("| **Java/Gradle** | ❌ **未覆盖** | Gradle 无锁文件；可提交清单 "
                 f"`{JAVA_MANIFEST_REL}` 尚未生成（先跑 `java-deps-export.init.gradle` "
                 "+ `java-deps-collect.py merge`）。"
                 "**本脚本不声称覆盖 Java**，勿据此认为 Java 依赖已合规 |")
    L.append("")
    if java_stat.get("error"):
        L.append(f"> ⚠️ Java 清单读取异常：{java_stat['error']}\n")
    if java_stat.get("unresolved"):
        u = java_stat["unresolved"]
        L.append(f"> ℹ️ 导出时 {len(u)} 条依赖边解析失败、不在清单内（**不代表合规**）："
                 f"{'; '.join(u[:5])}{' …' if len(u) > 5 else ''}\n")

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


# ------------------------------------------------------------------ selftest
def _dump(path: str, obj) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")


def selftest() -> int:
    """三向自测：正例通过、清单缺失必判红、缓存与清单不一致必判红。

    为什么要有这条：本脚本的失败模式不是"算错"，而是**把没扫说成扫过**——
    一次 `continue-on-error`、一个空清单、一个静默 return 都会让报告看起来
    "已覆盖、零风险"。三条断言分别钉住：分类正确性、覆盖缺失必须能被 --require-java
    抓到、以及"清单里有坐标但缓存没核实"也必须被抓到。
    """
    import tempfile

    script = os.path.abspath(__file__)
    tmp = tempfile.mkdtemp(prefix="lic-selftest-")
    os.makedirs(os.path.join(tmp, "scripts"), exist_ok=True)
    # 用一份副本跑 subprocess，使 repo_root() 落到 tmp（临时目录不在任何 git 仓内）
    test_script = os.path.join(tmp, "scripts", "check-license-policy.py")
    with open(script, encoding="utf-8") as src, open(test_script, "w",
                                                    encoding="utf-8", newline="\n") as dst:
        dst.write(src.read())

    manifest = {
        "schema": 1,
        "builds": ["nexus"],
        "moduleCount": 1,
        "coordinates": ["com.zaxxer:HikariCP:5.1.0",
                        "org.gpl.example:gpl-thing:1.0",
                        "org.quiet:no-license:2.0"],
        "modules": {"nexus:nexus-gateway": ["com.zaxxer:HikariCP:5.1.0",
                                            "org.gpl.example:gpl-thing:1.0",
                                            "org.quiet:no-license:2.0"]},
        "unresolved": [],
    }
    cache = {"com.zaxxer:HikariCP:5.1.0": "Apache-2.0",
             "org.gpl.example:gpl-thing:1.0": "GPL-3.0",
             "org.quiet:no-license:2.0": ""}

    def run(extra=(), cwd=tmp):
        return subprocess.run([sys.executable, test_script, "--out-dir", "build/lr",
                               *extra], capture_output=True, text=True, cwd=cwd)

    # ① 正例：分类正确、report-only 恒 0、--strict 才因强传染红
    _dump(os.path.join(tmp, JAVA_MANIFEST_REL), manifest)
    _dump(os.path.join(tmp, JAVA_CACHE_REL), cache)
    r = run()
    assert r.returncode == 0, f"报告制应 0：{r.stdout}{r.stderr}"
    assert "强传染 1" in r.stdout, r.stdout
    assert "未核实 0" in r.stdout, r.stdout
    md = open(os.path.join(tmp, "build/lr/license-report.md"), encoding="utf-8").read()
    assert "✅ Java/Gradle | 已覆盖" in md, md[md.find("## 2"):md.find("## 2") + 900]
    r1 = run(["--strict"])
    assert r1.returncode == 1 and "STRONG-COPYLEFT] java org.gpl.example" in r1.stdout, r1.stdout
    print("  ✓ 正例：Java 分类正确（Apache/GPL/未声明各一），报告制 0、--strict 1")

    # ② 反向对照：清单缺失 ⇒ 报告制仍 0（不新增假红），但 --require-java 必判红，
    #    且报告行必须写 ❌ 未覆盖——否则"零 Java 发现"会被读成"Java 已合规"。
    os.remove(os.path.join(tmp, JAVA_MANIFEST_REL))
    r2 = run()
    assert r2.returncode == 0, f"缺清单时报告制仍应 0：{r2.stdout}"
    assert "Java 腿**未覆盖**" in r2.stdout, r2.stdout
    md2 = open(os.path.join(tmp, "build/lr/license-report.md"), encoding="utf-8").read()
    assert "❌ **未覆盖**" in md2, "报告不得把缺清单写成已覆盖"
    r3 = run(["--require-java"])
    assert r3.returncode == 1, "--require-java 下缺清单必须判红"
    print("  ✓ 反向①：清单缺失 ⇒ 报告标 ❌ 且 --require-java 判红（不会假通过）")

    # ③ 反向对照：清单与缓存不一致（新增坐标没核实）⇒ --require-java 必判红并点名
    manifest2 = dict(manifest)
    manifest2["coordinates"] = sorted(set(manifest["coordinates"]) |
                                      {"org.new:unaudited:9.9"})
    _dump(os.path.join(tmp, JAVA_MANIFEST_REL), manifest2)
    r4 = run()
    assert r4.returncode == 0 and "未核实 1" in r4.stdout, r4.stdout
    r5 = run(["--require-java"])
    assert r5.returncode == 1 and "org.new:unaudited:9.9" in r5.stdout, r5.stdout
    print("  ✓ 反向②：缓存未覆盖新增坐标 ⇒ 判红并点名（不静默放过）")

    # ④ 缓存整体缺失也不放过（回到 miss 全量）
    os.remove(os.path.join(tmp, JAVA_CACHE_REL))
    r6 = run(["--require-java"])
    assert r6.returncode == 1 and "未核实 4" in r6.stdout, r6.stdout
    print("  ✓ 反向③：许可缓存文件缺失 ⇒ 全部计未核实并判红")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="build/license-report")
    ap.add_argument("--strict", action="store_true",
                    help="发现强传染许可时 exit 1（默认 report-only）")
    ap.add_argument("--update-cache", action="store_true",
                    help="允许联网查询缓存未命中的 crate，并把结果写回 "
                         "scripts/rust-license-cache.json（默认：离线只读缓存）")
    ap.add_argument("--update-java-cache", action="store_true",
                    help="允许联网按 Maven Central POM（含 parent 链）补齐 "
                         "scripts/java-license-cache.json 的未核实坐标（默认离线只读）")
    ap.add_argument("--require-java", action="store_true",
                    help="Java 腿必须已覆盖：清单缺失、或清单里有坐标未核实许可 ⇒ exit 1。"
                         "用于清单/缓存 bootstrap 完成后，把「覆盖缺口」钉成门禁；"
                         "注意它阻断的是**没扫**，不是**扫出 copyleft**（后者仍报告制）")
    ap.add_argument("--selftest", action="store_true", help="内置合成样本三向自测")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = repo_root()
    findings: list = []
    npm_stat = scan_npm(root, findings)
    rust_stat = scan_rust(root, findings, update_cache=args.update_cache)
    java_stat = scan_java(root, findings, update_cache=args.update_java_cache)

    md, by_class = build_report(findings, rust_stat, npm_stat, java_stat)
    os.makedirs(os.path.join(root, args.out_dir), exist_ok=True)
    md_path = os.path.join(root, args.out_dir, "license-report.md")
    js_path = os.path.join(root, args.out_dir, "license-report.json")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(md)
    with open(js_path, "w", encoding="utf-8") as fh:
        json.dump({"findings": findings, "rust": rust_stat, "java": java_stat,
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

    jmiss = java_stat.get("misses") or []
    if java_stat.get("present"):
        print(f"Java 腿：清单 {java_stat['coords']} 坐标 / "
              f"{java_stat['modules']} 模块 / {java_stat['builds']} 构建树，"
              f"缓存命中 {java_stat['cached']}，POM 未声明 {java_stat['undeclared']}，"
              f"未核实 {len(jmiss)}")
        if jmiss:
            print(f"::warning::Java 有 {len(jmiss)} 个坐标许可未核实："
                  f"{', '.join(jmiss[:10])}{' …' if len(jmiss) > 10 else ''}"
                  f"（刷新：python3 scripts/check-license-policy.py --update-java-cache）")
    else:
        print("::warning::Java 腿**未覆盖**（清单 "
              f"{JAVA_MANIFEST_REL} 未生成）——报告里的合计不含任何 Java 依赖")

    if args.strict and strong:
        print("::error::--strict 模式下存在强传染许可，门禁失败")
        return 1
    if args.require_java and (not java_stat.get("present") or jmiss
                             or java_stat.get("error")):
        print("::error::--require-java：Java 覆盖不完整"
              f"（清单已生成={bool(java_stat.get('present'))}，"
              f"未核实={len(jmiss)}，异常={java_stat.get('error', '-')}）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
