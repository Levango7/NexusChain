#!/usr/bin/env python3
"""事件接线门禁（PLAN-084 断言 A）：发布点 ↔ 监听点 双向静态闭合检查。

两本账：
  · 发布账 —— 事件闭包内每个事件类型的所有发布点（publishEvent 调用）
  · 监听账 —— 事件闭包内每个事件类型的所有监听点（@EventListener / @TransactionalEventListener 首参）

判红条件（命中即 exit 1；豁免账命中的条目只打印 [EXEMPT] 不判红）：
  [ORPHAN-PUBLISH]  发布的闭包事件 T 没有任何监听类型是 T 或 T 的超类（Spring 父类派发）
  [DEAD-LISTENER]   监听的 L 没有任何发布类型是 L 或 L 的子类
  （存在不可解析发布点时，死监听降级为 [DEAD-LISTENER?] —— 可能有藏起来的发布点）

扫描范围：DEFAULT_ROOTS（nexus-gateway/src/main/java；nexus-core / 测试 / 其他模块不在口径内，
见 PLAN-084 §七非目标）。

解析口径（不是逐行 grep）：
  · strip_noise 先剥注释与字符串字面量（保长保行号，防 javadoc / {@code} 假命中）；
  · 事件闭包 = extends 链传递可达 ApplicationEvent 的类（JPA 实体、事件溯源 Serializable 家族、
    同名不同族的 org.nexus.gateway.event.sourcing.PaymentEvent 结构性排除在外）；
  · 父类名与监听首参一律按「包名 + import」逐文件解析，绝不做全仓简单名匹配；
  · 发布点解析阶梯（对 PLAN-084 §三.2 字面口径的实现修订，节末留痕）：
      1) publishEvent(new T( / publishEvent(new pkg.T( 字面量；
      2) 同文件「T v = new T( … 处 publishEvent(v)」变量形式（取最近的同名声明绑定，
         同名变量重声明以最近者为准 —— AccountService 的双 event 变量即此形态）；
      3) 其余实参类型不可证 ⇒ 记为不可解析，只计数不判定（守住 §三.2 原意）。
    修订理由：§五 验收 3 要求复现 A4/B8/B9，而实测 A4（PaymentEventListener:210/:212）
    与 B8（DunningManager:86/:88）均为变量形式，纯字面口径只能抓到 B9 一条，验收无法满足；
    阶梯只扩大召回、不缩小判定面（不会把 B8 藏进去）。
  · 监听点 = @EventListener / @TransactionalEventListener 注解之后首个方法的首参类型；
  · 双向判定共用一条谓词：T 被 L 消费 iff T==L 或 L 在 T 的 extends 链上。

豁免账 config/event-wiring-exemptions.txt：每行 orphan|dead <简单类名> # 理由（必填），
理由为空或格式坏 ⇒ exit 2（配置错误，不是放行）；豁免账缺失同样 exit 2。
豁免账条目逐条回显为 [EXEMPT]（含「账实核对」注记），stale 条目在 --strict 判红。

防「自我失明」硬断言：--selftest 用 9 个合成样本钉住解析器（§四 表 6 个必需样本 +
变量形式 2 例 + 注释噪声 1 例）；实跑断言 java 文件数 > 0、闭包非空（反空集防 glob
失效后「空判绿」）。

exit：0 干净 / 1 有发现 / 2 配置错误（豁免账缺失或坏行、扫描为空、闭包为空等）。
--strict（P2 预留）：不可解析发布点 > 0 或豁免账 stale 条目同样判红。
"""
import argparse
import os
import re
import sys

DEFAULT_ROOTS = ['nexus-gateway/src/main/java']
EXEMPT_DEFAULT = 'config/event-wiring-exemptions.txt'
SENTINEL = 'org.springframework.context.ApplicationEvent'


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


def _ln(clean, idx):
    return clean.count('\n', 0, idx) + 1


def _resolve_type(token, imports, pkg):
    """简单名 → 全限定名：先 import，再 ApplicationEvent 哨兵，最后同包兜底。"""
    if '.' in token:
        return token
    if token in imports:
        return imports[token]
    if token == 'ApplicationEvent':
        return SENTINEL
    return pkg + '.' + token if pkg else token


def _parse_file(text):
    """返回 (包名, import 表, {全限定类名: 父类全限定名或 None})。"""
    clean = strip_noise(text)
    pkg = ''
    m = re.search(r'^\s*package\s+([\w.]+)\s*;', clean, re.M)
    if m:
        pkg = m.group(1)
    imports = {}
    for m in re.finditer(r'^\s*import\s+(?:static\s+)?([\w.]+)\s*;', clean, re.M):
        fq = m.group(1)
        imports[fq.split('.')[-1]] = fq
    classes = {}
    for m in re.finditer(r'\bclass\s+([A-Za-z0-9_]+)(?:\s+extends\s+([A-Za-z0-9_.]+))?', clean):
        simple = m.group(1)
        fq = pkg + '.' + simple if pkg else simple
        parent = m.group(2)
        classes[fq] = _resolve_type(parent, imports, pkg) if parent else None
    return pkg, imports, classes


def _ancestors(fq, parents):
    out = set()
    seen = set()
    cur = parents.get(fq)
    while cur and cur not in seen:
        seen.add(cur)
        out.add(cur)
        cur = parents.get(cur)
    return out


def _reaches_application_event(fq, parents):
    cur = fq
    seen = set()
    while cur:
        if cur == SENTINEL:
            return True
        if cur in seen:
            return False
        seen.add(cur)
        cur = parents.get(cur)
    return False


def _publish_sites(clean):
    """发布点解析阶梯：字面量 → 同文件变量形式绑定 → 不可解析（只计数）。"""
    sites = []  # (行号, 类型token或None, 形式)
    for m in re.finditer(r'\.publishEvent\s*\(\s*new\s+([A-Za-z0-9_.]+)\s*\(', clean):
        sites.append((_ln(clean, m.start()), m.group(1), 'literal'))
    decls = []
    for m in re.finditer(r'\b([A-Z][A-Za-z0-9_]*)\s+([a-z][A-Za-z0-9_]*)\s*=\s*new\s+([A-Z][A-Za-z0-9_]*)\s*\(', clean):
        decls.append((_ln(clean, m.start()), m.group(1), m.group(2), m.group(3)))
    for m in re.finditer(r'\.publishEvent\s*\(\s*([a-z][A-Za-z0-9_]*)\s*[\),]', clean):
        var = m.group(1)
        line = _ln(clean, m.start())
        best = None
        for dl, dtype, vname, ctor in decls:
            if vname == var and dtype == ctor and dl <= line and (best is None or dl > best[0]):
                best = (dl, dtype)
        if best is None:
            sites.append((line, None, 'unresolved'))
        else:
            sites.append((line, best[1], 'var'))
    return sites


def _listen_sites(clean):
    """监听点：注解之后首个方法的首参类型。无参方法记为跳过。"""
    sites = []  # (行号, 类型token或None, 注解种类)
    for m in re.finditer(r'@(Transactional)?EventListener(?:\([^()]*\))?', clean):
        kind = 'TransactionalEventListener' if m.group(1) else 'EventListener'
        tail = clean[m.end():m.end() + 400]
        pm = re.search(r'\(\s*(?:final\s+)?([A-Za-z0-9_.]+)\s+[A-Za-z0-9_]+\s*\)', tail)
        sites.append((_ln(clean, m.start()), pm.group(1) if pm else None, kind))
    return sites


def _load_ledger(text):
    """豁免账：{(orphan|dead, 简单类名): 理由} + 错误列表（坏行/空理由）。"""
    entries = {}
    errors = []
    for i, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        m = re.match(r'^(orphan|dead)\s+([A-Za-z0-9_]+)\s*#\s*(.+?)\s*$', line)
        if m is None:
            errors.append('豁免账第 %d 行格式错误或理由为空（应为 orphan|dead <简单类名> # 理由）: %s' % (i, line))
            continue
        entries[(m.group(1), m.group(2))] = m.group(3)
    return entries, errors


def analyze(files, ledger_text, strict=False):
    """返回 (exit_code, 报告全文)。files: {路径: 源码文本}；ledger_text 为 None 视作空账。"""
    if ledger_text is None:
        ledger_text = ''
    parents = {}
    parsed = []
    for rel, text in sorted(files.items()):
        pkg, imports, classes = _parse_file(text)
        parsed.append((rel, strip_noise(text), pkg, imports))
        for fq, parent in classes.items():
            parents[fq] = parent
    closure = {fq for fq in parents if _reaches_application_event(fq, parents)}

    publishes = []
    listens = []
    unresolvable = []
    listen_kinds = {'EventListener': 0, 'TransactionalEventListener': 0}
    listen_skipped = 0
    for rel, clean, pkg, imports in parsed:
        for line, token, form in _publish_sites(clean):
            if token is None:
                unresolvable.append('%s:%d' % (rel, line))
                continue
            fq = _resolve_type(token, imports, pkg)
            if fq in closure:
                publishes.append((fq, rel, line, form))
            # else: 出口径（JPA 实体 / POJO / 事件溯源 Serializable 家族），丢弃不判定
        for line, token, kind in _listen_sites(clean):
            listen_kinds[kind] += 1
            if token is None:
                listen_skipped += 1
                continue
            fq = _resolve_type(token, imports, pkg)
            if fq in closure:
                listens.append((fq, rel, line))

    ledger, ledger_errors = _load_ledger(ledger_text)
    exempt_orphan = {n for (k, n) in ledger if k == 'orphan'}
    exempt_dead = {n for (k, n) in ledger if k == 'dead'}

    publish_types = {}
    for fq, rel, line, form in publishes:
        publish_types.setdefault(fq, []).append((rel, line, form))
    listen_types = {}
    for fq, rel, line in listens:
        listen_types.setdefault(fq, []).append((rel, line))

    orphans = []
    for t in sorted(publish_types):
        chain = {t} | _ancestors(t, parents)
        if not any(L in chain for L in listen_types):
            orphans.append(t)
    deads = []
    for l in sorted(listen_types):
        if not any(l == t or l in _ancestors(t, parents) for t in publish_types):
            deads.append(l)

    lines = []
    lines.append('== 事件接线门禁（PLAN-084 断言 A）==')
    lines.append('扫描文件: %d 个 Java 文件' % len(files))
    lines.append('事件闭包: %d 个类（extends 链传递可达 ApplicationEvent）' % len(closure))
    form_ct = {}
    for _, _, _, form in publishes:
        form_ct[form] = form_ct.get(form, 0) + 1
    lines.append('发布点: %d 处（字面量 %d / 变量形式 %d / 不可解析 %d）' % (
        len(publishes), form_ct.get('literal', 0), form_ct.get('var', 0), len(unresolvable)))
    lines.append('监听点: %d 处注解（@EventListener %d / @TransactionalEventListener %d；参数不可解析 %d）' % (
        listen_kinds['EventListener'] + listen_kinds['TransactionalEventListener'],
        listen_kinds['EventListener'], listen_kinds['TransactionalEventListener'], listen_skipped))

    findings = 0
    red_orphan = 0
    red_dead = 0
    if orphans or deads:
        lines.append('── 发现（判红）──')
    for t in orphans:
        name = t.split('.')[-1]
        if name in exempt_orphan:
            continue
        findings += 1
        red_orphan += 1
        lines.append('[ORPHAN-PUBLISH] %s 有发布点、无任何监听（父类派发口径）' % name)
        for rel, line, form in publish_types[t]:
            lines.append('    发布点 %s:%d（%s 形式）' % (rel, line, form))
    for l in deads:
        name = l.split('.')[-1]
        if name in exempt_dead:
            continue
        findings += 1
        red_dead += 1
        tok = '[DEAD-LISTENER?]' if unresolvable else '[DEAD-LISTENER]'
        lines.append('%s %s 有监听、无任何发布（父类派发口径%s）' % (
            tok, name, '；存在不可解析发布点，降级提示' if unresolvable else ''))
        for rel, line in listen_types[l]:
            lines.append('    监听点 %s:%d' % (rel, line))
    if not orphans and not deads:
        lines.append('── 发现：无 ──')

    lines.append('── 豁免账（%d 条）──' % len(ledger))
    for (kind, name), reason in sorted(ledger.items()):
        if kind == 'orphan' and name in {t.split('.')[-1] for t in orphans}:
            note = '（已抑制本轮发现）'
        elif kind == 'dead' and name in {l.split('.')[-1] for l in deads}:
            note = '（已抑制本轮发现）'
        else:
            note = '（账实核对：本轮无对应发现）'
        lines.append('[EXEMPT] %s %s # %s%s' % (kind, name, reason, note))

    if unresolvable:
        lines.append('── 警告（只计数不判定）──')
        for u in unresolvable:
            lines.append('    不可解析发布点 %s（变量形式且同文件无声明绑定）' % u)

    strict_red = []
    if strict and unresolvable:
        strict_red.append('[UNRESOLVED-PUBLISH] 不可解析发布点 %d 处' % len(unresolvable))
    if strict:
        closure_simples = {fq.split('.')[-1] for fq in closure}
        for (kind, name) in ledger:
            if name not in closure_simples:
                strict_red.append('[STALE-EXEMPTION] %s %s 不在事件闭包内（账实不符）' % (kind, name))
    if strict_red:
        lines.append('── --strict 判红 ──')
        lines.extend('    ' + s for s in strict_red)

    errors = list(ledger_errors)
    if not closure:
        errors.append('事件闭包为空：解析器疑似损坏（反自我失明硬断言）')
    if errors:
        lines.append('── 配置错误（exit 2）──')
        lines.extend('    ' + e for e in errors)
        lines.append('判定: CONFIG-ERROR')
        return 2, '\n'.join(lines)

    if findings:
        verdict = 'FINDINGS（exit 1）：ORPHAN-PUBLISH %d / DEAD-LISTENER %d' % (red_orphan, red_dead)
        code = 1
    elif strict_red:
        verdict = 'STRICT-RED（exit 1）'
        code = 1
    else:
        verdict = 'CLEAN（exit 0）'
        code = 0
    lines.append('判定: %s' % verdict)
    return code, '\n'.join(lines)


def selftest():
    def ev(name, parent):
        return (
            'package p;\n'
            'import org.springframework.context.ApplicationEvent;\n'
            'public class %s extends %s {\n'
            '    public %s(Object source) { super(source); }\n'
            '}\n' % (name, parent, name)
        )

    def pub(name):
        return (
            'package p;\n'
            'import org.springframework.context.ApplicationEventPublisher;\n'
            'public class Pub {\n'
            '    void go(ApplicationEventPublisher pub) {\n'
            '        pub.publishEvent(new %s(this));\n'
            '    }\n'
            '}\n' % name
        )

    def pubvar(name):
        return (
            'package p;\n'
            'import org.springframework.context.ApplicationEventPublisher;\n'
            'public class Pub {\n'
            '    void go(ApplicationEventPublisher pub) {\n'
            '        %s e = new %s(this);\n'
            '        pub.publishEvent(e);\n'
            '    }\n'
            '}\n' % (name, name)
        )

    def lis(name):
        return (
            'package p;\n'
            'import org.springframework.context.event.EventListener;\n'
            'public class Lis {\n'
            '    @EventListener\n'
            '    public void on(%s e) {}\n'
            '}\n' % name
        )

    no_finding = ['[ORPHAN-PUBLISH]', '[DEAD-LISTENER]']
    cases = [
        ('发布A监听A', {'A.java': ev('A', 'ApplicationEvent'), 'Pub.java': pub('A'), 'Lis.java': lis('A')}, '', [], no_finding, 0),
        ('发布B监听A（B extends A，父类派发不得误报）', {'A.java': ev('A', 'ApplicationEvent'), 'B.java': ev('B', 'A'), 'Pub.java': pub('B'), 'Lis.java': lis('A')}, '', [], no_finding, 0),
        ('发布C无监听', {'C.java': ev('C', 'ApplicationEvent'), 'Pub.java': pub('C')}, '', ['[ORPHAN-PUBLISH]'], [], 1),
        ('监听D无人发布', {'D.java': ev('D', 'ApplicationEvent'), 'Lis.java': lis('D')}, '', ['[DEAD-LISTENER]'], [], 1),
        ('发布E监听E且E在豁免账带理由（回显[EXEMPT]不判红）', {'E.java': ev('E', 'ApplicationEvent'), 'Pub.java': pub('E'), 'Lis.java': lis('E')}, 'orphan E # 自测样本：豁免账回显，理由必填\n', ['[EXEMPT]'], no_finding, 0),
        ('豁免账某行理由为空', {'A.java': ev('A', 'ApplicationEvent'), 'Pub.java': pub('A'), 'Lis.java': lis('A')}, 'orphan E #\n', [], [], 2),
        ('变量形式发布A监听A（阶梯第2级）', {'A.java': ev('A', 'ApplicationEvent'), 'Pub.java': pubvar('A'), 'Lis.java': lis('A')}, '', [], no_finding, 0),
        ('变量形式发布C无监听（阶梯第2级召回）', {'C.java': ev('C', 'ApplicationEvent'), 'Pub.java': pubvar('C')}, '', ['[ORPHAN-PUBLISH]'], [], 1),
        ('注释与字符串提及不算数', {'A.java': ev('A', 'ApplicationEvent'), 'Pub.java': pub('A') + '// publishEvent(new Z(this)) /* @EventListener */\n', 'Lis.java': lis('A')}, '', ['不可解析 0'], no_finding, 0),
    ]
    fails = []
    for label, files, ledger, want_tokens, want_absent, want_code in cases:
        code, out = analyze(files, ledger)
        got = []
        if code != want_code:
            got.append('exit=%d 期望 %d' % (code, want_code))
        for tok in want_tokens:
            if tok not in out:
                got.append('缺少 %r' % tok)
        for tok in want_absent:
            if tok in out:
                got.append('不应出现 %r' % tok)
        if got:
            fails.append('%s：%s' % (label, '；'.join(got)))
            print('FAIL %-46s %s' % (label, '；'.join(got)))
        else:
            print('ok   %-46s exit=%d' % (label, want_code))
    if fails:
        for f in fails:
            print('SELFTEST FAIL: %s' % f, file=sys.stderr)
        print('  selftest: %d/%d 合成样本判定正确 => FAIL' % (len(cases) - len(fails), len(cases)))
        return 1
    print('  selftest: %d/%d 合成样本判定正确（含 PLAN-084 §四 表 6 个必需样本 + 变量形式 2 例 + 注释噪声 1 例）=> PASS' % (len(cases), len(cases)))
    return 0


def main():
    ap = argparse.ArgumentParser(description='事件接线门禁（PLAN-084 断言 A）')
    ap.add_argument('--roots', default=','.join(DEFAULT_ROOTS))
    ap.add_argument('--exemptions', default=EXEMPT_DEFAULT)
    ap.add_argument('--strict', action='store_true', help='P2 预留：不可解析发布点 / stale 豁免条目同样判红')
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    roots = [r for r in args.roots.split(',') if r.strip()]
    files = {}
    for root in roots:
        if not os.path.isdir(root):
            print('CONFIG-ERROR: 扫描根不存在: %s' % root, file=sys.stderr)
            return 2
        for dirpath, _, names in os.walk(root):
            for name in sorted(names):
                if name.endswith('.java'):
                    path = os.path.join(dirpath, name)
                    with open(path, encoding='utf-8') as fh:
                        files[path] = fh.read()
    if not files:
        print('CONFIG-ERROR: 扫描结果为空（反空集硬断言）: roots=%s' % roots, file=sys.stderr)
        return 2
    if not os.path.isfile(args.exemptions):
        print('CONFIG-ERROR: 豁免账不存在: %s（豁免账随门禁同仓提交）' % args.exemptions, file=sys.stderr)
        return 2
    with open(args.exemptions, encoding='utf-8') as fh:
        ledger_text = fh.read()
    code, out = analyze(files, ledger_text, strict=args.strict)
    print(out)
    return code


if __name__ == '__main__':
    sys.exit(main())
