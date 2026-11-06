#!/usr/bin/env python3
"""PR 路径过滤：算出「本轮真正需要构建/扫描的镜像模块」。

背景（板 20261106，E1 收尾）：`Trivy Image Baseline Gate (PR)` 此前对每个 PR
都跑 13 条完整 docker build，一个只改 docs/ 的 PR 也要排队 20+ 分钟。E1 的语义
是「红灯落在该红的地方」，不是「所有 PR 交一遍全量成本」。本脚本只做范围计算，
判定口径不变（基线差分、新增 HIGH/CRITICAL 才判红、UTC 到期全部沿用）。

数据来源不是猜的，是 13 个 Dockerfile 的 `COPY` 列表（2026-11-06 提取）：

  · 10 个 Java 模块（nexus-core/gateway/bridge/signing-service/wallet-service/
    api-gateway/settlement/compliance/analytics/oracle）的 Dockerfile 都以 repo 根
    为构建上下文，`COPY` 了同一组共享目录（gradle wrapper、根构建配置、
    nexus-rpc-doc、nexus-core、nexus-sdk、nexus-common、nexus-consortium、
    nexus-settlement、nexus-compliance、nexus-analytics、nexus-oracle、
    nexus-api-gateway）。→ 改这些目录会改变**绝大多数 Java 镜像**的 jar 内容，
    因此归为「Java 全量」。
  · nexus-core 的 Dockerfile 额外 `COPY nexus-bridge / nexus-signing-service /
    nexus-wallet-service`（运行期依赖）。→ 改这三个目录除了构建自己的镜像，
    也必须重建 nexus-core。
  · mpc-engine / zk-groth16-service 是 `COPY . .`，但最终阶段只
    `COPY --from=builder …/target/release/<bin> /usr/local/bin/<bin>`。
    → 只有 Cargo 输入（模块目录、Dockerfile）会改变镜像内容；docs/ 之类不会。
  · nexus-explorer 只 `COPY nexus-explorer/`（含 package.json/package-lock.json）。
    → 只由该目录触发。

不在上面任何一条里的路径：**按全量处理**（保守兜底）。宁可多建几条腿，也不能
让「新增目录里的镜像输入」被静默跳过——那正是 E1 之前「红灯不出现在阻断路径上」
的反面。命中兜底时会在 step summary 里点名具体路径，便于后续把规则补准。

用法（CI）：
  python3 scripts/security/image-scan-scope.py --event pull_request --base-ref master \
      --output "$GITHUB_OUTPUT"

用法（本地验证）：
  python3 scripts/security/image-scan-scope.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

# 与 security-scan.yml 的 trivy-docker-scan 矩阵保持同一顺序（顺序只影响日志可读性）。
MODULES = [
    "nexus-core",
    "nexus-gateway",
    "nexus-bridge",
    "nexus-signing-service",
    "nexus-wallet-service",
    "nexus-api-gateway",
    "nexus-settlement",
    "nexus-compliance",
    "nexus-analytics",
    "nexus-oracle",
    "mpc-engine",
    "zk-groth16-service",
    "nexus-explorer",
]

JAVA_MODULES = [m for m in MODULES if m not in ("mpc-engine", "zk-groth16-service", "nexus-explorer")]

# 被全部/几乎全部 Java Dockerfile COPY 的共享输入（来自 COPY 列表，见模块头部说明）。
COMMON_JAVA_TOPS = {
    "nexus-rpc-doc",
    "nexus-core",
    "nexus-sdk",
    "nexus-common",
    "nexus-consortium",
    "nexus-settlement",
    "nexus-compliance",
    "nexus-analytics",
    "nexus-oracle",
    "nexus-api-gateway",
}

# 根级构建输入：换了 Gradle wrapper / 根构建配置，10 个 Java 镜像全部要重建。
ROOT_BUILD_FILES = {
    "gradlew",
    "gradlew.bat",
    "build.gradle",
    "settings.gradle",
    "gradle.properties",
    "gradle",
    ".dockerignore",
}

# 门禁自身 / 判定口径：改了就全量验证，否则「改了门禁却只验证 0 条腿」。
GATE_SELF_FILES = {
    ".github/workflows/security-scan.yml",
    ".github/trivyignore-empty",
    "config/trivy-baseline.json",
}
GATE_SELF_PREFIXES = ("scripts/security/",)

# 明确与镜像内容无关的顶层目录（已核对：不在任何 Dockerfile 的 COPY 列表里）。
NON_IMAGE_TOPS = {
    "docs",
    "demo",
    "deploy",          # 仅部署清单/编排；13 个 Dockerfile 均无 COPY deploy/
    "migrations",
    "nacos-config",    # 运行时配置，镜像内 config 来自构建产物（COPY --from=builder）
    "jdk-provision",
    "nexus-devtools",
    "perf",
    "seata",           # 独立 docker 镜像，不在本矩阵
    "tests",
    "scripts",         # scripts/security/ 已在 GATE_SELF 里先行拦截
    ".github",         # 其余 workflow 不改变镜像内容
}

# 文件级噪声：不影响镜像内容。
FILE_NOISE = {
    ".gitignore",
    ".gitleaks.toml",
    ".trivyignore",
    "CODEOWNERS",
}

# 这些顶层目录是「构建配置 + 共享源」之外的模块自身目录，按模块粒度触发。
MODULE_TOPS = set(MODULES)

BUILD_BASENAMES = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                   "pom.xml", "gradle.properties", "gradlew", "gradlew.bat"}


def _is_build_config(path: str) -> bool:
    base = path.rsplit("/", 1)[-1]
    if base in BUILD_BASENAMES or base.startswith(("build.gradle", "settings.gradle")):
        return True
    # gradle wrapper 目录（由 ROOT_BUILD_FILES 的 gradle 前缀覆盖，这里兜住子路径）
    return path.startswith("gradle/") or "/gradle/wrapper/" in path


def classify(paths: list[str]) -> tuple[list[str], str, dict]:
    """返回 (模块列表, 一句话原因, 明细)。"""
    selected: set[str] = set()
    detail = {"gate_self": [], "java_all": [], "module": {}, "ignored": [], "unknown": []}

    for raw in paths:
        path = raw.strip()
        if not path:
            continue

        # 0) 门禁自身 / 判定口径 → 全量
        if path in GATE_SELF_FILES or path.startswith(GATE_SELF_PREFIXES):
            detail["gate_self"].append(path)
            continue

        if path.endswith(".md") or path.rsplit("/", 1)[-1] in FILE_NOISE:
            detail["ignored"].append(path)
            continue

        top = path.split("/")[0]

        # 1) 根级构建输入 / 任意 Gradle·Maven 构建文件 → Java 全量
        if top in ROOT_BUILD_FILES or _is_build_config(path):
            detail["java_all"].append(path)
            continue

        # 2) 共享 Java 源目录 → Java 全量
        if top in COMMON_JAVA_TOPS:
            detail["java_all"].append(path)
            continue

        # 3) 模块自身目录
        if top in MODULE_TOPS:
            detail["module"].setdefault(top, []).append(path)
            continue

        # 4) 明确无关
        if top in NON_IMAGE_TOPS:
            detail["ignored"].append(path)
            continue

        # 5) 未知路径 → 全量兜底（宁可多建，不可漏建）
        detail["unknown"].append(path)

    if detail["gate_self"]:
        return list(MODULES), "门禁自身/判定口径变化 → 13/13 全量", detail
    if detail["unknown"]:
        return list(MODULES), "含未知路径（未登记为镜像输入也未登记为无关） → 13/13 保守全量", detail
    if detail["java_all"]:
        selected.update(JAVA_MODULES)

    for top, hits in detail["module"].items():
        selected.add(top)
        # nexus-core 的 Dockerfile 额外 COPY 了 bridge / signing / wallet（运行期依赖）
        if top in ("nexus-bridge", "nexus-signing-service", "nexus-wallet-service"):
            selected.add("nexus-core")

    modules = [m for m in MODULES if m in selected]
    if not modules:
        return [], "无镜像相关改动 → 0/13（必需检查走 no relevant changes 分支）", detail
    return modules, f"命中镜像输入 → {len(modules)}/13", detail


def changed_files_from_git(base_ref: str) -> list[str]:
    base = f"origin/{base_ref}"
    out = subprocess.run(
        ["git", "diff", "--name-only", "-z", f"{base}...HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout
    return [p for p in out.split("\x00") if p]


def emit(modules: list[str], reason: str, detail: dict, output_path: str | None) -> None:
    payload = {
        "modules": json.dumps(modules, separators=(",", ":")),
        "count": str(len(modules)),
        "reason": reason,
    }
    if output_path:
        with open(output_path, "a", encoding="utf-8") as f:
            for k, v in payload.items():
                f.write(f"{k}={v}\n")

    print(f"模块范围：{reason}")
    print(f"modules={payload['modules']}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"### 镜像扫描范围：{reason}\n\n")
            f.write("| 规则 | 命中路径（示例） |\n| --- | --- |\n")
            for key, label in (("gate_self", "门禁自身 → 全量"),
                               ("java_all", "Java 共享输入 → 10 腿"),
                               ("unknown", "未知路径 → 全量兜底")):
                if detail[key]:
                    sample = ", ".join(f"`{p}`" for p in detail[key][:5])
                    f.write(f"| {label} | {sample} |\n")
            for top, hits in detail["module"].items():
                sample = ", ".join(f"`{p}`" for p in hits[:3])
                f.write(f"| 模块 `{top}` | {sample} |\n")
            f.write("\n")


def self_test() -> int:
    cases = [
        ("docs only", ["docs/security-sla.md", "docs/agent-board/x.md"], []),
        ("deploy only", ["deploy/k8s/app.yaml"], []),
        ("nexus-core only", ["nexus-core/src/main/java/A.java"], JAVA_MODULES),
        ("bridge change rebuilds core", ["nexus-bridge/src/A.java"], ["nexus-core", "nexus-bridge"]),
        ("explorer only", ["nexus-explorer/frontend/src/index.tsx"], ["nexus-explorer"]),
        ("mpc only", ["mpc-engine/src/lib.rs"], ["mpc-engine"]),
        ("zk only", ["zk-groth16-service/src/main.rs"], ["zk-groth16-service"]),
        ("root gradle", ["build.gradle"], JAVA_MODULES),
        ("gate self", ["scripts/security/trivy-baseline-gate.py"], MODULES),
        ("other workflow", [".github/workflows/ci.yml"], []),
        ("unknown dir", ["brand-new-dir/Dockerfile"], MODULES),
    ]
    failures = 0
    for name, paths, expected in cases:
        got, reason, _ = classify(paths)
        ok = got == [m for m in MODULES if m in expected] if expected is not MODULES else got == MODULES
        if not ok:
            failures += 1
            print(f"[FAIL] {name}: 期望 {expected} 实际 {got}")
        else:
            print(f"[OK]   {name}: {len(got)} 腿 | {reason}")
    print(f"结果：{len(cases) - failures}/{len(cases)} 通过")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--event", default="", help="github.event_name")
    ap.add_argument("--base-ref", default="", help="PR 的目标分支（github.base_ref）")
    ap.add_argument("--changed-files", default="", help="可选：从文件读取变更路径（每行一个）")
    ap.add_argument("--output", default="", help="GITHUB_OUTPUT 文件路径")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    # 只有 PR 做过滤；push/schedule/workflow_dispatch 一律全量（合并后的真实状态不能漏）。
    if args.event != "pull_request":
        reason = f"event={args.event or 'unknown'} → 13/13 全量（仅 PR 做路径过滤）"
        emit(list(MODULES), reason, {"gate_self": [], "java_all": [], "module": {}, "ignored": [], "unknown": []}, args.output)
        return 0

    if not args.base_ref:
        print("::warning::PR 事件缺少 base_ref，按全量处理", file=sys.stderr)
        emit(list(MODULES), "缺少 base_ref → 13/13 保守全量", {"gate_self": [], "java_all": [], "module": {}, "ignored": [], "unknown": []}, args.output)
        return 0

    try:
        if args.changed_files:
            with open(args.changed_files, encoding="utf-8") as f:
                paths = [line.rstrip("\n") for line in f]
        else:
            paths = changed_files_from_git(args.base_ref)
    except subprocess.CalledProcessError as exc:
        print(f"::warning::git diff 计算失败（{exc}），按全量处理", file=sys.stderr)
        emit(list(MODULES), "git diff 失败 → 13/13 保守全量", {"gate_self": [], "java_all": [], "module": {}, "ignored": [], "unknown": []}, args.output)
        return 0

    modules, reason, detail = classify(paths)
    emit(modules, reason, detail, args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
