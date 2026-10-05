#!/usr/bin/env python3
"""把 cargo test 文本输出转换成 JUnit XML。

为什么需要它：NexusChain 的 Java 侧测试通过 Gradle 原生产出 JUnit XML，
CI 有统一的 artifact 采集通道；而 Rust 侧（mpc-engine / zk-groth16-service）
的 `cargo test` 只打印文本，**没有任何 XML 生成器**，导致这两个模块的测试
结果在 CI 工件里完全缺席——「全量测试全绿」无法从工件侧核实。

为什么不用 cargo-nextest：nextest 需要替换测试 runner，会改变现有 CI 中
`cargo test --lib` / `--test integration_test` 的执行语义，风险高于收益。
本脚本只做「捕获 + 转换」，测试仍由原生 `cargo test` 执行。

用法：
    cargo test --no-fail-fast 2>&1 | tee raw.log
    python3 scripts/cargo-test-to-junit.py raw.log -o build/test-results/rust/mpc-engine.xml \\
        --suite mpc-engine

退出码：解析成功返回 0；即使发现失败用例也返回 0（失败与否由 cargo test
自身的退出码决定，本脚本只负责转换，不应篡夺门禁判定权）。
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# cargo 在 TTY 或 `CARGO_TERM_COLOR=always` / `--color always` 下会输出 ANSI
# 转义序列。这些序列会插在行首（如 `\x1b[32mtest foo ... ok\x1b[0m`），
# 使所有以 `^` 锚定的正则**静默失配**——表现为「0 用例」而非报错。
# 因此在解析前统一剥离。同时覆盖 OSC 序列（`\x1b]...\x07`）。
RE_ANSI = re.compile(
    r"\x1b\[[0-9;?]*[ -/]*[@-~]"  # CSI: ESC [ ... 终止符
    r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)?"  # OSC: ESC ] ... BEL/ST
    r"|\x1b[@-Z\\-_]"  # 两字节 ESC 序列
)


def strip_ansi(text: str) -> str:
    """剥离 ANSI 转义序列。见 RE_ANSI 注释。"""
    return RE_ANSI.sub("", text)


# `test <name> ... ok` / `... FAILED` / `... ignored` / `... ignored, <原因>`
#
# 注意两处必须容忍的变体（均为**实测**踩到的静默失配）：
#   1. name 允许含空格——doc-test 形如 `test src/lib.rs - foo (line 10) ... ok`
#   2. 状态后可跟说明文字——`#[ignore = "原因"]` 输出为
#      `test foo ... ignored, 需多节点环境：先启动集群`
#      （ignored 后是 `, ` + 自由文本，**不是行尾**）
# 若不允许变体 2，所有带 reason 的 #[ignore] 用例会被整体丢弃，
# 表现为 `tests` 计数偏低且 skipped 恒为 0 —— 与 ANSI 同类的**静默欠计数**。
# 说明文字用 `(?P<reason>, .*)?` 捕获，供 <skipped> 携带原因。
RE_TEST_LINE = re.compile(
    r"^test (?P<name>.+?) \.\.\. (?P<status>ok|FAILED|ignored|bench)"
    r"(?P<reason>, .*)?\s*$"
)
# `running 5 tests`（每个 test binary 一段；重名 binary 会重复出现）
RE_RUNNING = re.compile(r"^running (?P<n>\d+) tests?$")
# `test result: FAILED. 3 passed; 1 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.01s`
RE_RESULT = re.compile(
    r"^test result: (?P<verdict>ok|FAILED)\. "
    r"(?P<passed>\d+) passed; (?P<failed>\d+) failed; "
    r"(?P<ignored>\d+) ignored; (?P<measured>\d+) measured; "
    r"(?P<filtered>\d+) filtered out; finished in (?P<time>[\d.]+)s"
)
# `---- tests::b stdout ----` 段的起始（name 同样可能含空格，如 doc-test）
RE_FAIL_HEADER = re.compile(r"^---- (?P<name>.+?) stdout ----$")
# 下一个段落的边界：`---- ... stdout ----` 或行首的 `failures:`
RE_SECTION_END = re.compile(r"^(---- .+? stdout ----|failures:)")


@dataclass
class Case:
    name: str
    status: str  # ok | FAILED | ignored
    message: str = ""
    reason: str = ""  # ignored 的原因（来自 `#[ignore = "..."]`）


@dataclass
class SuiteRun:
    """一个 test binary 的一次执行（cargo test 会为每个 target 打印一段）。"""

    label: str
    cases: list[Case] = field(default_factory=list)
    time: float = 0.0


def parse(text: str) -> list[SuiteRun]:
    # 先剥离 ANSI，否则以 `^` 锚定的正则全部失配（cargo 输出会带颜色码）
    lines = strip_ansi(text).splitlines()

    # 先收集所有失败正文：name -> 多行消息
    fail_detail: dict[str, str] = {}
    i = 0
    while i < len(lines):
        m = RE_FAIL_HEADER.match(lines[i])
        if m:
            name = m.group("name")
            body: list[str] = []
            i += 1
            while i < len(lines) and not RE_SECTION_END.match(lines[i]):
                body.append(lines[i])
                i += 1
            fail_detail[name] = "\n".join(body).rstrip()
            continue
        i += 1

    runs: list[SuiteRun] = []
    current: SuiteRun | None = None
    idx = 0
    pending_running = 0

    for line in lines:
        m = RE_RUNNING.match(line)
        if m:
            pending_running = int(m.group("n"))
            # 新的一段：为每个 `running N tests` 起一个 suite
            current = SuiteRun(label=f"run{len(runs) + 1}")
            runs.append(current)
            idx = 0
            continue

        m = RE_TEST_LINE.match(line)
        if m and current is not None:
            name, status = m.group("name"), m.group("status")
            msg = fail_detail.get(name, "") if status == "FAILED" else ""
            reason = (m.group("reason") or "").lstrip(", ").strip()
            current.cases.append(
                Case(name=name, status=status, message=msg, reason=reason)
            )
            continue

        m = RE_RESULT.match(line)
        if m and current is not None:
            current.time = float(m.group("time"))

    return runs


def to_junit(runs: list[SuiteRun], suite_name: str) -> str:
    total = sum(len(r.cases) for r in runs)
    failures = sum(1 for r in runs for c in r.cases if c.status == "FAILED")
    skipped = sum(1 for r in runs for c in r.cases if c.status == "ignored")
    duration = sum(r.time for r in runs)

    out: list[str] = []
    out.append('<?xml version="1.0" encoding="UTF-8"?>')
    out.append(
        f'<testsuites name="{html.escape(suite_name)}" tests="{total}" '
        f'failures="{failures}" errors="0" skipped="{skipped}" '
        f'time="{duration:.3f}">'
    )
    out.append(
        f'  <testsuite name="{html.escape(suite_name)}" tests="{total}" '
        f'failures="{failures}" errors="0" skipped="{skipped}" '
        f'time="{duration:.3f}">'
    )
    # properties 记录来源，便于与 Java 侧工件区分
    out.append("    <properties>")
    out.append('      <property name="generator" value="cargo-test-to-junit"/>')
    out.append('      <property name="runner" value="cargo test"/>')
    out.append("    </properties>")

    for run in runs:
        for case in run.cases:
            parts = case.name.split("::")
            classname = "::".join(parts[:-1]) if len(parts) > 1 else suite_name
            name = parts[-1]
            attrs = (
                f'name="{html.escape(name)}" '
                f'classname="{html.escape(classname)}" '
                f'time="0.000"'
            )
            if case.status == "FAILED":
                out.append(f"    <testcase {attrs}>")
                body = html.escape(case.message) if case.message else ""
                out.append(
                    '      <failure message="test failed">'
                    f"{body}</failure>"
                )
                out.append("    </testcase>")
            elif case.status == "ignored":
                out.append(f"    <testcase {attrs}>")
                if case.reason:
                    # 携带 #[ignore = "原因"] 的文本，使跳过原因可取证
                    out.append(
                        f'      <skipped message="{html.escape(case.reason)}"/>'
                    )
                else:
                    out.append("      <skipped/>")
                out.append("    </testcase>")
            else:
                out.append(f"    <testcase {attrs}/>")

    out.append("  </testsuite>")
    out.append("</testsuites>")
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="把 cargo test 文本输出转换为 JUnit XML。"
    )
    ap.add_argument("input", help="cargo test 输出文件（通常由 tee 生成）")
    ap.add_argument(
        "-o", "--output", required=True, help="写出的 JUnit XML 路径"
    )
    ap.add_argument(
        "--suite",
        default="cargo-test",
        help="testsuite 名称（建议用模块名，如 mpc-engine）",
    )
    args = ap.parse_args(argv)

    src = Path(args.input)
    if not src.exists():
        print(f"[cargo-test-to-junit] 输入文件不存在: {src}", file=sys.stderr)
        return 1

    text = src.read_text(encoding="utf-8", errors="replace")
    runs = parse(text)
    total = sum(len(r.cases) for r in runs)

    if total == 0:
        # 没有解析到任何用例：可能是编译失败，或 cargo 输出格式变化。
        # 不静默——明确报出来，让 CI 日志可见（这比生成空 XML 更有价值）。
        print(
            "[cargo-test-to-junit] 警告：未解析到任何 testcase。"
            "常见原因：编译失败（无测试运行），或 cargo 输出格式已变化。"
            "请核对原始日志。",
            file=sys.stderr,
        )

    xml = to_junit(runs, args.suite)
    dst = Path(args.output)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(xml, encoding="utf-8")

    failures = sum(1 for r in runs for c in r.cases if c.status == "FAILED")
    skipped = sum(1 for r in runs for c in r.cases if c.status == "ignored")
    print(
        f"[cargo-test-to-junit] {args.suite}: {total} 用例 "
        f"(失败 {failures} / 跳过 {skipped}) -> {dst}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
