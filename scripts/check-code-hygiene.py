#!/usr/bin/env python3
"""代码卫生门禁（棘轮 / ratchet）。

背景
    2026-09 全面审查记录了三类日志卫生缺陷：
      · printStackTrace()  —— 绕过日志框架，无级别/时间戳/logger 名，
                              与 traceId 无法关联，日志系统中检索不到
      · System.out.print*  —— 同上，且生产环境写 stdout
      · 空 catch           —— 静默吞异常

    其中部分位于加密/编码核心（Ed25519 / SHA3Utility / Uint256 等），
    批量重构的**风险高于收益**：改动可能影响异常传播路径，
    且为它们注入 logger 需要引入新依赖。

    因此本门禁采用**棘轮**策略，而非"一次清零"：
      · 已知的历史违规记录在基线文件中，**不阻断**构建
      · 任何**新增**违规立即失败
      · 修掉的历史违规会让基线收缩，不允许再回弹

用法
    python scripts/check-code-hygiene.py                  # 检查（CI 用）
    python scripts/check-code-hygiene.py --update-baseline # 基线收缩（修完后）

退出码
    0 = 无新增违规
    1 = 存在新增违规
"""
from __future__ import annotations

import argparse
import glob
import io
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE_PATH = os.path.join(REPO_ROOT, 'scripts', 'code-hygiene-baseline.txt')

# 只扫描生产源码，不含测试与示例
SCAN_GLOBS = [
    'nexus-*/**/src/main/java/**/*.java',
]

# 这些路径下的 System.out 是**合理**的：示例代码本就该打印到控制台
SYSTEM_OUT_ALLOWLIST = (
    '/examples/',
    '/tools/',
    '/test/',
)

RULE_PRINT_STACK_TRACE = 'printStackTrace'
RULE_SYSTEM_OUT = 'SystemOut'
RULE_EMPTY_CATCH = 'EmptyCatch'

RE_PRINT_STACK_TRACE = re.compile(r'\b\w+\.printStackTrace\s*\(\s*\)')
RE_SYSTEM_OUT = re.compile(r'\bSystem\.out\.(?:println|print|printf)\s*\(')
RE_EMPTY_CATCH = re.compile(r'\bcatch\s*\([^)]*\)\s*\{\s*\}')
RE_COMMENT_PREFIX = re.compile(r'^\s*(?://|\*|/\*)')


def is_commented(line: str) -> bool:
    return bool(RE_COMMENT_PREFIX.match(line))


def normalize(line: str) -> str:
    """把源码行归一化，作为基线键的一部分（与行号无关，避免行位移造成误报）。"""
    return re.sub(r'\s+', ' ', line.strip())


def scan_file(path: str):
    """返回该文件的违规列表 [(rule, normalized_line), ...]。"""
    rel = os.path.relpath(path, REPO_ROOT).replace('\\', '/')
    try:
        content = io.open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        return []

    findings = []
    for raw in content.splitlines():
        if is_commented(raw):
            continue
        if RE_PRINT_STACK_TRACE.search(raw):
            findings.append((RULE_PRINT_STACK_TRACE, normalize(raw)))
        if RE_SYSTEM_OUT.search(raw) and not any(a in '/' + rel for a in SYSTEM_OUT_ALLOWLIST):
            findings.append((RULE_SYSTEM_OUT, normalize(raw)))
        if RE_EMPTY_CATCH.search(raw):
            findings.append((RULE_EMPTY_CATCH, normalize(raw)))
    return findings


def scan_repo():
    """返回 {key: rel_path}，key = 'rel_path|rule|normalized_line'。"""
    found = {}
    for pattern in SCAN_GLOBS:
        for path in glob.glob(os.path.join(REPO_ROOT, pattern), recursive=True):
            rel = os.path.relpath(path, REPO_ROOT).replace('\\', '/')
            for rule, norm in scan_file(path):
                found['%s|%s|%s' % (rel, rule, norm)] = rel
    return found


def load_baseline():
    if not os.path.exists(BASELINE_PATH):
        return set()
    with io.open(BASELINE_PATH, encoding='utf-8') as fh:
        return {l.rstrip('\n') for l in fh if l.strip() and not l.startswith('#')}


def write_baseline(keys):
    header = [
        '# 代码卫生基线（棘轮门禁，由 scripts/check-code-hygiene.py 生成）',
        '#',
        '# 格式：<相对路径>|<规则>|<归一化源码行>',
        '# 此文件中的条目**不阻断**构建；任何不在此文件中的新违规会失败。',
        '# 修完历史违规后运行：python scripts/check-code-hygiene.py --update-baseline',
        '# 基线只应收缩，不应增长 —— 增长意味着新增了违规。',
        '#',
    ]
    with io.open(BASELINE_PATH, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(header) + '\n')
        for key in sorted(keys):
            fh.write(key + '\n')


def main() -> int:
    parser = argparse.ArgumentParser(description='代码卫生棘轮门禁')
    parser.add_argument('--update-baseline', action='store_true',
                        help='用当前扫描结果重写基线（仅用于基线收缩）')
    args = parser.parse_args()

    current = scan_repo()

    if args.update_baseline:
        before = load_baseline()
        write_baseline(set(current))
        print('基线已更新：%d 条（原 %d 条）' % (len(current), len(before)))
        return 0

    baseline = load_baseline()
    new = sorted(k for k in current if k not in baseline)
    fixed = sorted(k for k in baseline if k not in current)

    print('=== 代码卫生门禁 ===')
    print('当前违规 %d 条，基线 %d 条' % (len(current), len(baseline)))
    print()

    if fixed:
        print('已修复（基线可收缩，共 %d 条）：' % len(fixed))
        for k in fixed[:20]:
            print('  - ' + k)
        if len(fixed) > 20:
            print('  ... 其余 %d 条' % (len(fixed) - 20))
        print('  → 运行 --update-baseline 收缩基线')
        print()

    if new:
        print('❌ 新增违规 %d 条（必须修复）：' % len(new))
        by_rule = {}
        for k in new:
            rule = k.split('|')[1]
            by_rule.setdefault(rule, []).append(k)
        for rule in sorted(by_rule):
            print('  [%s] %d 条' % (rule, len(by_rule[rule])))
            for k in by_rule[rule][:10]:
                print('     ' + k)
            if len(by_rule[rule]) > 10:
                print('     ... 其余 %d 条' % (len(by_rule[rule]) - 10))
        print()
        print('修复指引：')
        print('  · printStackTrace → 改用 logger：log.error("...", e)')
        print('  · System.out.print → 改用 logger：log.info(...)')
        print('  · 空 catch → 至少记录原因，或恢复中断标志后返回')
        return 1

    print('✅ 无新增违规')
    return 0


if __name__ == '__main__':
    sys.exit(main())
