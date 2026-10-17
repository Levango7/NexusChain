#!/usr/bin/env python3
"""调度锁门禁：每个 @Scheduled 方法必须带 @SchedulerLock，或落在两本账里。

两本账（都是"只准缩不准涨"）：
  · scripts/scheduler-lock-baseline.txt   历史缺口，格式 <路径>|<方法>，不要求理由（是记账，不是免检牌）
  · scripts/scheduler-lock-exemptions.txt 复核后认定"多副本重复执行本来就安全"的项，
    格式 <路径>|<方法>|<理由>，理由必填 —— 例如任务依赖**进程内状态**（本地 MeterRegistry、
    内存进度 Map、TCC action 注册表），这类加锁反而会把执行权交给没有该状态的副本。

判红条件：① 出现既没锁又不在任何一本账里的方法（=新增缺口）；② 任一账目条目对应的缺口已不存在
（补了锁却没删记录，防账本腐化成永久免检）；③ 豁免条目缺理由。

扫描范围：**进程内存在多副本部署的服务** —— nexus-gateway / nexus-bridge / nexus-signing-service
（deploy/helm/values-prod.yaml 中 replicaCount>1 的服务）。**不含 nexus-core**（`include ':nexus-core:nexus-core'`
的嵌入式区块链节点，PoW/P2P/merkle 调度是单实例节点语义，且无 ShedLock 依赖），
也不含各 included build（consortium/settlement/... 经查零 @Scheduled）。

解析口径（不是逐行 grep）：
 1. 先剥掉注释与字符串字面量 —— 否则 Javadoc 里一句 {@code @SchedulerLock} 会被当成"已上锁"，
    而注释里提到的 @Scheduled 会被当成"新方法"。
 2. 从每个 @Scheduled 起，沿括号配对走到方法体的 '{'，只在这一段里找 @SchedulerLock，
    因此注解换行、与方法签名同行、@Scheduled 在前在后都能识别。

防"自我失明"的硬断言：--selftest 用四个合成样本（缺锁 / 带锁 / 同行写法 / 纯注释提及）
要求判定结果与预期逐一相等；主判据另外要求 java_files>0，否则空集全绿直接判红。
"""
import argparse
import os
import re
import sys
import tempfile

DEFAULT_ROOTS = [
    'nexus-gateway/src/main/java',
    'nexus-bridge/src/main/java',
    'nexus-signing-service/src/main/java',
]
LEDGER_DEFAULT = 'scripts/scheduler-lock-exemptions.txt'


def strip_noise(text):
    """去掉 // 与 /* */ 注释、以及字符串/字符字面量的内容（保留长度与换行以稳住行号）。"""
    out = []
    i, n = 0, len(text)
    state = 'code'
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if state == 'code':
            if ch == '/' and nxt == '/':
                state = 'line'
                out.append('  ')
                i += 2
                continue
            if ch == '/' and nxt == '*':
                state = 'block'
                out.append('  ')
                i += 2
                continue
            if ch == '"':
                state = 'str'
                out.append('"')
                i += 1
                continue
            if ch == "'":
                state = 'char'
                out.append("'")
                i += 1
                continue
            out.append(ch)
            i += 1
            continue
        if state == 'line':
            if ch == '\n':
                state = 'code'
                out.append('\n')
            else:
                out.append(' ')
            i += 1
            continue
        if state == 'block':
            if ch == '*' and nxt == '/':
                state = 'code'
                out.append('  ')
                i += 2
                continue
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue
        if state in ('str', 'char'):
            quote = '"' if state == 'str' else "'"
            if ch == '\\':
                out.append('  ')
                i += 2
                continue
            if ch == quote:
                state = 'code'
                out.append(quote)
                i += 1
                continue
            out.append('\n' if ch == '\n' else ' ')
            i += 1
            continue
    return ''.join(out)


def member_window(text, start):
    """从 @Scheduled 之后走到该成员的元素体/方法体起始 '{'，返回这段声明文本。"""
    i = start
    n = len(text)
    depth = 0
    seen_open = False
    while i < n:
        ch = text[i]
        if ch == '(':
            depth += 1
            seen_open = True
        elif ch == ')':
            depth -= 1
        elif ch == '{' and seen_open and depth == 0:
            break
        elif ch == ';' and depth == 0:
            break
        i += 1
    return text[start:i]


NAME_RE = re.compile(r'([A-Za-z_$][\w$]*)\s*\(')


def scan_text(rel_path, text):
    """返回该文件里的 (方法名, 是否带锁) 列表。"""
    clean = strip_noise(text)
    found = []
    for m in re.finditer(r'@Scheduled\b', clean):
        window = member_window(clean, m.end())
        names = NAME_RE.findall(window)
        method = names[-1] if names else '?'
        found.append((rel_path, method, bool(re.search(r'@SchedulerLock\b', window))))
    return found


def java_files(roots):
    out = []
    for root in roots:
        if not os.path.isdir(root):
            print('  !! 扫描根不存在: %s' % root, file=sys.stderr)
            continue
        for dirpath, _dirs, names in os.walk(root):
            for name in sorted(names):
                if name.endswith('.java'):
                    full = os.path.join(dirpath, name).replace('\\', '/')
                    out.append((full, full))
    return out


def scan(files):
    entries = []
    for display, actual in files:
        with open(actual, 'r', encoding='utf-8', errors='replace') as fh:
            entries.extend(scan_text(display, fh.read()))
    return entries


def load_ledger(path):
    entries = {}
    if not os.path.isfile(path):
        return entries
    with open(path, 'r', encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('|')
            if len(parts) < 2:
                continue
            reason = parts[2].strip() if len(parts) > 2 else ''
            entries[(parts[0].strip(), parts[1].strip())] = reason
    return entries


def load_baseline(path):
    """历史债务账：格式 <相对路径>|<方法名>，不要求理由（理由要逐条复核后才写进豁免账）。"""
    entries = set()
    if not os.path.isfile(path):
        return entries
    with open(path, 'r', encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = [p.strip() for p in line.split('|')]
            if len(parts) >= 2:
                entries.add((parts[0], parts[1]))
    return entries


def selftest():
    head = ('package p;\n'
            'import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;\n'
            'import org.springframework.scheduling.annotation.Scheduled;\n')
    cases = [
        ('NoLock.java', head + 'public class NoLock {\n    @Scheduled(cron = "0 0 * * * *")\n'
                              '    public void sweep() {}\n}\n',
         ('sweep', False)),
        ('WithLock.java', head + 'public class WithLock {\n    @Scheduled(cron = "0 0 * * * *")\n'
                                '    @SchedulerLock(name = "sweep", lockAtMostFor = "PT10M")\n'
                                '    public void sweep() {}\n}\n',
         ('sweep', True)),
        ('InlineLock.java', head + 'public class InlineLock {\n'
                                  '    @Scheduled(fixedDelay = 60000) @SchedulerLock(name = "x") '
                                  'public void tick() {}\n}\n',
         ('tick', True)),
        ('OnlyMention.java', head + '/** 设计说明：本类故意 **不** 用 @Scheduled，'
                                    'Javadoc 里提到的 @SchedulerLock 不算声明。 */\n'
                                    'public class OnlyMention {\n    public void helper() {}\n}\n',
         None),
    ]
    tmp = tempfile.mkdtemp(prefix='schedlock-selftest-')
    results = []
    for name, body, expect in cases:
        path = os.path.join(tmp, name)
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(body)
        got = scan_text(name, body)
        results.append((name, got, expect))

    failures = []
    for name, got, expect in results:
        if expect is None:
            if got:
                failures.append('%s: 期望识别为 0 个调度方法，实际 %s' % (name, got))
            continue
        method, locked = expect
        match = [g for g in got if g[1] == method]
        if len(match) != 1:
            failures.append('%s: 期望恰好 1 个 %s，实际 %s' % (name, method, got))
            continue
        if match[0][2] != locked:
            failures.append('%s: %s 的带锁判定应为 %s，实际 %s' % (name, method, locked, match[0][2]))

    for name, got, expect in results:
        print('   %-16s got=%-42s expect=%s' % (name, got, expect))
    if failures:
        for f in failures:
            print('  SELFTEST FAIL: %s' % f, file=sys.stderr)
        return 1
    print('  selftest: 4/4 合成样本判定正确（缺锁/带锁/同行写法/纯注释提及）=> PASS')
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--roots', default=','.join(DEFAULT_ROOTS))
    ap.add_argument('--ledger', default=LEDGER_DEFAULT)
    ap.add_argument('--baseline', default='scripts/scheduler-lock-baseline.txt')
    ap.add_argument('--update-baseline', action='store_true')
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    roots = [r for r in args.roots.split(',') if r]
    files = java_files(roots)
    print('  scheduler-lock gate: roots=%s java_files=%d' % (roots, len(files)))
    if not files:
        print('::error::扫描集为空（空集上的"全绿"没有意义），检查 --roots', file=sys.stderr)
        return 1

    entries = scan(files)
    locked = [e for e in entries if e[2]]
    gaps = [(e[0], e[1]) for e in entries if not e[2]]

    if args.update_baseline:
        with open(args.baseline, 'w', encoding='utf-8') as fh:
            fh.write('# 调度锁历史债务账（scripts/check-scheduler-lock.py --update-baseline 生成）\n')
            fh.write('# 格式：<相对路径>|<方法名>\n')
            fh.write('# 这些是**记账前就存在的缺口**，不阻断构建，但只准缩不准涨：\n')
            fh.write('#   · 新写的 @Scheduled 不带锁 ⇒ 判红（这条门禁真正的作用面）\n')
            fh.write('#   · 给某条补了 @SchedulerLock ⇒ 必须删掉对应行，否则"STALE-BASE"判红\n')
            fh.write('#   · 复核后认定"重复执行本来就安全"的，从本账移到 scheduler-lock-exemptions.txt 并写理由\n')
            for path, method in sorted(gaps):
                fh.write('%s|%s\n' % (path, method))
        print('  已写出 %s：%d 条历史缺口' % (args.baseline, len(gaps)))
        return 0

    ledger = load_ledger(args.ledger)
    base = load_baseline(args.baseline)
    gap_set = set(gaps)
    known = set(ledger) | base
    missing = sorted(gap_set - known)
    stale_exempt = sorted([(p, m) for (p, m) in ledger if (p, m) not in gap_set])
    stale_base = sorted([(p, m) for (p, m) in base if (p, m) not in gap_set])
    no_reason = sorted([(p, m) for (p, m) in ledger
                        if not ledger[(p, m)] or ledger[(p, m)].startswith('TODO')])

    print('  @Scheduled 方法=%d（带锁 %d / 缺锁 %d）  豁免账=%d 条  历史基线=%d 条'
          % (len(entries), len(locked), len(gap_set), len(ledger), len(base)))

    rc = 0
    if missing:
        rc = 1
        print('::error::%d 个 @Scheduled 方法既无 @SchedulerLock，也不在豁免账或历史基线里'
              % len(missing), file=sys.stderr)
        for p, m in missing[:40]:
            print('   NEW      %s :: %s' % (p, m), file=sys.stderr)
    if stale_exempt or stale_base:
        rc = 1
        print('::error::%d 条豁免 + %d 条基线已失效（缺口不存在 ⇒ 该删；两本账都只准缩不准涨）'
              % (len(stale_exempt), len(stale_base)), file=sys.stderr)
        for p, m in stale_exempt[:40]:
            print('   STALE-EXEMPT %s :: %s' % (p, m), file=sys.stderr)
        for p, m in stale_base[:40]:
            print('   STALE-BASE   %s :: %s' % (p, m), file=sys.stderr)
    if no_reason:
        rc = 1
        print('::error::%d 条豁免没写理由（或仍是 TODO）—— 豁免必须说明'
              '"为什么多副本重复执行是安全的"' % len(no_reason), file=sys.stderr)
        for p, m in no_reason[:40]:
            print('   NOREASON %s :: %s' % (p, m), file=sys.stderr)

    if rc == 0:
        print('  OK：每个 @Scheduled 要么带锁、要么在豁免账（含理由）或历史基线里；两本账均无囤积')
    return rc


if __name__ == '__main__':
    sys.exit(main())
