#!/usr/bin/env python3
"""留言板收件箱与签收门禁（agent-board inbox gate）。

背景
    留言板解决的是「跨 Agent 的信息同步」，但它只保证**写了**，不保证
    **被看见**。2026-10-30 的实测事故：一条 P1 复核结论在 21:21 就已产出并
    写进本地日志，12 分钟后 PR 被合并——没有任何人在合并前看过它。板面留言
    本身也是同样形状：`to:` 里写了对方，却没有任何机制回答「对方是否已经
    读过、多久内必须读」。§2.2 又规定留言一旦投出**整篇不可变**，所以
    「改 status 表示已处理」这条路是被明确废除过的（实测会造成 git 真冲突
    与悬空引用）。

    本门禁把「送达」变成机器可读且可阻断的一件事，且不新增任何常驻服务：
      · 留言可选 `ack_required: true` + `expires: <ISO-8601>`；
      · 签收 = 另一条**署名不同**的留言在 `refs:` 里写 `- board: <该 id>`
        （沿用 §2.2 既有口径：真正生效的是后来留言的引用关系）；
      · 逾期未签收的 P0/P1 必读项 ⇒ CI 红；其余只列清单，不阻断。

口径
    A. 阻断（exit 1）
       A1 结构性错误：无 front-matter / id 与文件名不符 / id 重复 /
          缺 id|owner|to|type|priority|created / created 不可解析或晚于当前时刻 /
          `ack_required` 非布尔 / `ack_required: true` 但缺 `expires` 或不可解析 /
          `expires` 早于 `created`
       A2 逾期未签收：`ack_required: true` 且 `status: open` 且 `priority` ∈ {P0,P1}
          且 `expires` 已过且无任何他人署名留言引用其 id
    B. 只报告（不影响退出码）
       B1 逾期但优先级为 P2 ⇒ 警告（阻断会放大成「为一条知会卡住全仓合并」）
       B2 各 owner 名下仍开放的留言清单（收件箱）
    C. 棘轮
       历史遗留的结构性错误记在 `scripts/agent-board-baseline.txt`，
       **只应收缩，不得增长**；新增结构性错误一律阻断。
       （不这样就必须回头改别人已投出的留言，违反 §2.2。）

为什么默认不阻断「未签收」
    存量 99 条留言都没有 `ack_required` 字段 ⇒ 缺省 false，门禁上线当天
    不可能把已有板面判红；新协议只对未来自愿使用的留言生效。

用法
    python scripts/check-agent-board-inbox.py                 # 检查（CI 用）
    python scripts/check-agent-board-inbox.py --selftest      # 内置合成样本
    python scripts/check-agent-board-inbox.py --update-baseline

退出码
    0 = 无新增结构性错误、无逾期未签收必读项
    1 = 命中 A1（非基线）或 A2
    2 = 输入本身不可读（目录不存在）
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
import io
import os
import re
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOARD_DIR = os.path.join(REPO_ROOT, 'docs', 'agent-board')
BASELINE_PATH = os.path.join(REPO_ROOT, 'scripts', 'agent-board-baseline.txt')
LF = chr(10)

REQUIRED_FIELDS = ('id', 'owner', 'to', 'type', 'priority', 'created')
BLOCKING_PRIORITIES = ('P0', 'P1')


def parse_front_matter(text: str):
    """返回 (fields, list_refs)；front-matter 缺失时返回 (None, None)。

    只做本板实际用到的 YAML 子集：`k: v`、`k: [a, b]`、以及 `refs:` 下的
    `- board: x` / `- pr: 123` 列表项。刻意不引入 PyYAML（本 job 的既有口径
    是「纯 Python 标准库、秒级完成」）。
    """
    m = re.match(r'^---\r?\n(.*?)\r?\n---\r?\n', text, re.S)
    if not m:
        return None, None
    body = m.group(1)
    fields: dict[str, str] = {}
    refs: list[str] = []
    in_refs = False
    for raw in body.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        item = re.match(r'^\s+-\s+(.*)$', line)
        if item:
            if in_refs:
                refs.append(item.group(1).strip())
            continue
        kv = re.match(r'^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)$', line)
        if not kv:
            continue
        key, value = kv.group(1), kv.group(2).strip()
        if key == 'refs':
            in_refs = value == ''
            continue
        in_refs = False
        fields[key] = value
    return fields, refs


def clean(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] in '"\'' and value[-1] == value[0]:
        return value[1:-1].strip()
    return value


def parse_ts(value: str):
    """解析 ISO-8601（允许无时区，按本地时区补）；失败返回 None。"""
    try:
        parsed = dt.datetime.fromisoformat(clean(value))
    except (ValueError, TypeError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.astimezone()
    return parsed


def truthy(value: str) -> bool:
    return clean(value).lower() in ('true', 'yes', '1')


def board_refs_of(refs) -> list[str]:
    out = []
    for entry in refs or []:
        m = re.match(r'^board:\s*(.+)$', entry)
        if m:
            out.append(clean(m.group(1)))
    return out


def scan(board_dir: str, now: dt.datetime):
    """返回 (errors, warnings, inbox, stats)。errors/warnings 为 (kind, id, msg)。"""
    files = sorted(f for f in glob.glob(os.path.join(board_dir, '*.md'))
                   if os.path.basename(f) != 'README.md')
    errors: list[tuple[str, str, str]] = []
    warnings: list[tuple[str, str, str]] = []
    messages: dict[str, dict] = {}
    acks: dict[str, list[str]] = {}

    for path in files:
        stem = os.path.basename(path)[:-3]
        try:
            with io.open(path, encoding='utf-8') as fh:
                text = fh.read()
        except OSError as exc:                      # 读不动等于没检查，必须显式失败
            errors.append(('unreadable', stem, '读取失败：%s' % exc))
            continue
        fields, refs = parse_front_matter(text)
        if fields is None:
            errors.append(('no-front-matter', stem, '缺 YAML front-matter'))
            continue
        _id = clean(fields.get('id', '')) or stem
        for field in REQUIRED_FIELDS:
            if not fields.get(field):
                errors.append(('missing-field', _id, '缺必填字段 `%s`（文件 %s.md）' % (field, stem)))
        if fields.get('id') and clean(fields['id']) != stem:
            errors.append(('id-mismatch', _id, 'id `%s` 与文件名 `%s` 不符' % (clean(fields['id']), stem)))
        created = None
        if fields.get('created'):
            created = parse_ts(fields['created'])
            if created is None:
                errors.append(('bad-created', _id, 'created 不是 ISO-8601：%r' % fields['created']))
            elif created > now:
                errors.append(('future-created', _id,
                               'created 晚于当前时刻（%s > %s）——整块板会因此判红' %
                               (created.isoformat(), now.isoformat())))
        ack_required_raw = fields.get('ack_required')
        ack_required = False
        if ack_required_raw is not None:
            if clean(ack_required_raw).lower() not in ('true', 'false', 'yes', 'no', '1', '0'):
                errors.append(('bad-ack-required', _id, 'ack_required 必须是布尔：%r' % ack_required_raw))
            else:
                ack_required = truthy(ack_required_raw)
        expires = None
        if ack_required:
            if not fields.get('expires'):
                errors.append(('missing-expires', _id, 'ack_required: true 必须同时给 expires'))
            else:
                expires = parse_ts(fields['expires'])
                if expires is None:
                    errors.append(('bad-expires', _id, 'expires 不是 ISO-8601：%r' % fields['expires']))
                elif created is not None and expires <= created:
                    errors.append(('expires-before-created', _id,
                                   'expires(%s) 不晚于 created(%s)' % (expires.isoformat(), created.isoformat())))
        if _id in messages:
            errors.append(('duplicate-id', _id, 'id 重复（另一处已占用），文件 %s.md' % stem))
            continue
        messages[_id] = {
            'id': _id,
            'owner': clean(fields.get('owner', '')),
            'from': clean(fields.get('from', '')),
            'to': fields.get('to', ''),
            'type': clean(fields.get('type', '')),
            'priority': clean(fields.get('priority', '')),
            'status': clean(fields.get('status', 'open')),
            'created': created,
            'ack_required': ack_required,
            'expires': expires,
        }

    # 签收关系：他人署名的留言在 refs 里写 `- board: <id>` 即视为该 id 已被签收。
    # 判据刻意比对的是**被引用那条的 owner**（不是引用者自己）；自己引自己无效。
    # 需要第二遍读文件，因为 refs 只在原文里，而 §2.2 规定留言不可变 ⇒
    # "状态"只能由后来留言的引用关系推出（与 README §2.2 口径一致）。
    for path in files:
        with io.open(path, encoding='utf-8') as fh:
            fields, refs = parse_front_matter(fh.read())
        if not fields:
            continue
        self_id = clean(fields.get('id', '')) or os.path.basename(path)[:-3]
        author = clean(fields.get('owner', '')) or clean(fields.get('from', ''))
        for target in board_refs_of(refs):
            original = messages.get(target)
            if original is None or target == self_id:
                continue
            if author and original.get('owner') and author != original['owner']:
                acks.setdefault(target, []).append(self_id)

    for _id, msg in messages.items():
        if msg['status'].lower() != 'open':
            continue
        if not msg['ack_required']:
            continue
        if msg['expires'] is None or msg['expires'] > now:
            continue
        if acks.get(_id):
            continue
        line = ('逾期未签收（expires=%s，owner=%s，to=%s）——签收方式：投一条新留言，'
                'refs 里写 `- board: %s`' % (msg['expires'].isoformat(), msg['owner'],
                                            msg['to'], _id))
        if msg['priority'] in BLOCKING_PRIORITIES:
            errors.append(('overdue-unacked', _id, line))
        else:
            warnings.append(('overdue-unacked-p2', _id, line))

    inbox: dict[str, list[dict]] = {}
    for msg in messages.values():
        if msg['status'].lower() == 'open':
            inbox.setdefault(msg['owner'] or '(无 owner)', []).append(msg)
    stats = {'messages': len(messages), 'acked': sum(1 for v in acks.values() if v),
             'ack_required': sum(1 for m in messages.values() if m['ack_required'])}
    return errors, warnings, inbox, stats


def load_baseline() -> set[str]:
    if not os.path.exists(BASELINE_PATH):
        return set()
    with io.open(BASELINE_PATH, encoding='utf-8', newline='\n') as fh:
        return {line.strip() for line in fh
                if line.strip() and not line.startswith('#')}


def write_baseline(keys) -> None:
    header = [
        '# 留言板历史结构性缺口基线（scripts/check-agent-board-inbox.py）',
        '#',
        '# 这些条目是**门禁上线之前**已存在的留言缺项。按 §2.2「整篇不可变」，',
        '# 不能回头改别人投出的留言，所以先记账、只要求不再增长。',
        '# 基线只应收缩；补齐后运行 --update-baseline 重写。',
        '#',
        '# 记录格式：kind|id',
    ]
    with io.open(BASELINE_PATH, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(header) + '\n')
        for key in sorted(keys):
            fh.write(key + '\n')


def render_summary(inbox, stats) -> str:
    lines = ['## 留言板收件箱', '',
             '留言 %d 条（有签收 %d，声明 ack_required %d）' %
             (stats['messages'], stats['acked'], stats['ack_required']), '',
             '| owner | 开放条目 | 其中必读未签收 |',
             '| --- | --- | --- |']
    for owner in sorted(inbox, key=lambda o: (-len(inbox[o]), o)):
        items = inbox[owner]
        pending = sum(1 for m in items if m['ack_required'] and not _acked(m['id'], inbox))
        lines.append('| %s | %d | %d |' % (owner, len(items), pending))
    return '\n'.join(lines)


def _acked(item_id, inbox) -> bool:
    return False     # 表格里只做近似提示；判定一律走 errors/warnings 那条路径


def selftest() -> int:
    """合成样本正反证：每类判定都要有「该红的红、该绿的绿」。"""
    now = dt.datetime(2026, 11, 1, 12, 0, tzinfo=dt.timezone.utc)
    base_created = dt.datetime(2026, 10, 31, 10, 0, tzinfo=dt.timezone.utc)
    hour = dt.timedelta(hours=1)

    def item(owner, type_='通知', priority='P2', status='open', expires=None,
             ack_required=False, refs=(), created=base_created, id_override=None):
        return dict(owner=owner, type=type_, priority=priority, status=status, expires=expires,
                    ack_required=ack_required, refs=list(refs), created=created,
                    id_override=id_override)

    def write_file(path, fname, spec):
        _id = spec['id_override'] or fname[:-3]
        lines = ['---', 'id: %s' % _id, 'owner: "%s"' % spec['owner'],
                 'from: "%s"' % spec['owner'], 'to: owner-2', 'type: %s' % spec['type'],
                 'priority: %s' % spec['priority'], 'status: %s' % spec['status'],
                 'created: %s' % spec['created'].isoformat()]
        if spec['ack_required']:
            lines.append('ack_required: true')
            if spec['expires'] is not None:
                lines.append('expires: %s' % spec['expires'].isoformat())
        elif spec['expires'] is not None:
            lines.append('expires: %s' % spec['expires'].isoformat())
        lines.append('refs:')
        for ref in spec['refs']:
            lines.append('  - %s' % ref)
        lines += ['---', '', '## 主题', '正文', '']
        with io.open(path, 'w', encoding='utf-8', newline=LF) as fh:
            fh.write(LF.join(lines))

    cases = [
        ('T1 干净板（无必读项）', 0, {
            '20261031-0100-a.md': item('owner-1')}),
        ('T2 必读未签收且已过期 ⇒ 红', 1, {
            '20261031-0200-b.md': item('owner-1', '请求', 'P1', expires=now - hour, ack_required=True)}),
        ('T3 已被他人署名留言引用 ⇒ 绿', 0, {
            '20261031-0300-c.md': item('owner-1', '请求', 'P1', expires=now - hour, ack_required=True),
            '20261031-0400-d.md': item('owner-2', '认领', 'P1', refs=['board: 20261031-0300-c'])}),
        ('T4 自己引用自己不算签收 ⇒ 红', 1, {
            '20261031-0500-e.md': item('owner-1', '请求', 'P0', expires=now - hour, ack_required=True),
            '20261031-0600-f.md': item('owner-1', '通知', 'P2', refs=['board: 20261031-0500-e'])}),
        ('T5 到期时间未到 ⇒ 绿', 0, {
            '20261031-0700-g.md': item('owner-1', '请求', 'P0', expires=now + dt.timedelta(hours=6),
                                        ack_required=True)}),
        ('T6 ack_required 但缺 expires ⇒ 红', 1, {
            '20261031-0800-h.md': item('owner-1', '请求', 'P1', ack_required=True)}),
        ('T7 P2 逾期 ⇒ 只警告不红', 0, {
            '20261031-0900-i.md': item('owner-1', '通知', 'P2', expires=now - hour, ack_required=True)}),
        ('T8 id 与文件名不符 ⇒ 红', 1, {
            '20261031-1000-j.md': item('owner-1', id_override='wrong-id-j')}),
        ('T9 created 在未来 ⇒ 红', 1, {
            '20261031-1100-k.md': item('owner-1', created=now + dt.timedelta(days=1))}),
        ('T10 expires 早于 created ⇒ 红', 1, {
            '20261031-1200-l.md': item('owner-1', '请求', 'P1', expires=base_created - hour,
                                        ack_required=True)}),
        ('T11 已收尾（status=done）不再计时 ⇒ 绿', 0, {
            '20261031-1300-m.md': item('owner-1', '请求', 'P0', expires=now - hour,
                                        ack_required=True, status='done')}),
        ('T12 无 front-matter ⇒ 红', 1, {
            '20261031-1400-n.md': None}),
    ]

    failures = 0
    for name, expect_rc, items in cases:
        with tempfile.TemporaryDirectory() as tmp:
            board = os.path.join(tmp, 'agent-board')
            os.mkdir(board)
            for fname, spec in items.items():
                target = os.path.join(board, fname)
                if spec is None:
                    with io.open(target, 'w', encoding='utf-8', newline=LF) as fh:
                        fh.write('没有 front-matter 的正文' + LF)
                else:
                    write_file(target, fname, spec)
            errors, _warnings, _inbox, _stats = scan(board, now)
            got = 1 if errors else 0
            flag = 'PASS' if got == expect_rc else 'FAIL'
            if got != expect_rc:
                failures += 1
            detail = '; '.join('%s:%s' % (kind, ident) for kind, ident, _ in errors)
            print('  [%s] %-32s expect=%d got=%d %s' % (flag, name, expect_rc, got, detail[:70]))
    print('selftest: %d/%d 通过' % (len(cases) - failures, len(cases)))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description='留言板收件箱与签收门禁')
    ap.add_argument('--board-dir', default=BOARD_DIR)
    ap.add_argument('--selftest', action='store_true', help='只跑内置合成样本')
    ap.add_argument('--update-baseline', action='store_true', help='收缩历史缺口基线')
    ap.add_argument('--summary', help='把收件箱表格追加到该文件（CI 用 $GITHUB_STEP_SUMMARY）')
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not os.path.isdir(args.board_dir):
        print('❌ 找不到留言板目录：%s' % args.board_dir)
        return 2

    now = dt.datetime.now(dt.timezone.utc)
    errors, warnings, inbox, stats = scan(args.board_dir, now)
    baseline = load_baseline()
    keys = {'%s|%s' % (kind, item_id) for kind, item_id, _ in errors}
    new_structural = sorted(k for k in keys if k not in baseline and k.split('|')[0] != 'overdue-unacked')
    overdue = sorted(k for k in keys if k.split('|')[0] == 'overdue-unacked')

    print('=== 留言板签收门禁 ===')
    print('留言 %d 条；声明 ack_required %d 条；已有签收关系 %d 条' %
          (stats['messages'], stats['ack_required'], stats['acked']))
    print('历史缺口基线 %d 条' % len(baseline))
    print()

    if args.update_baseline:
        # 基线只记结构性缺口：逾期项是"还没人签收"，不是"历史遗留"，写进基线等于永久豁免
        structural = {k for k in keys if k.split('|')[0] != 'overdue-unacked'}
        write_baseline(structural)
        print('基线已更新：%d 条（原 %d 条，逾期项不入基线）' % (len(structural), len(baseline)))
        return 0

    if overdue:
        print('❌ 逾期未签收的必读留言 %d 条（阻断合并）：' % len(overdue))
        for kind, item_id, msg in errors:
            if kind == 'overdue-unacked':
                print('  - %s ⇒ %s' % (item_id, msg))
        print()

    if new_structural:
        print('❌ 新增结构性错误 %d 条（不在基线内）：' % len(new_structural))
        for kind, item_id, msg in errors:
            key = '%s|%s' % (kind, item_id)
            if key in new_structural:
                print('  - [%s] %s ⇒ %s' % (kind, item_id, msg))
        print()

    if warnings:
        print('⚠️ 逾期但非必读（P2）%d 条：' % len(warnings))
        for kind, item_id, msg in warnings:
            print('  - %s ⇒ %s' % (item_id, msg))
        print()

    if args.summary:
        try:
            with io.open(args.summary, 'a', encoding='utf-8', newline='\n') as fh:
                fh.write(render_summary(inbox, stats) + '\n')
        except OSError as exc:
            print('⚠️ 无法写 step summary：%s' % exc)

    if overdue or new_structural:
        if overdue:
            print('签收方式：目标 Agent 投一条**新留言**，在 refs 里写 `- board: <上面的 id>`；'
                  '事项已完成则投「交接/裁决」留言收尾。')
            print('窗口太短需要延长 ⇒ 按 §2.2 **另投一条**说明新口径的新留言，不得回改原留言。')
        if new_structural:
            print('结构性错误的修法：留言一旦投出整篇不可变（README §2.2），所以只能由 owner 本人'
                  '另投勘误留言，或重投该条（新 id）并在正文说明取代哪条；'
                  '门禁上线前的历史缺口可 `--update-baseline` 记账，但基线只准收缩。')
        return 1

    print('✅ 无逾期未签收必读项、无新增结构性错误')
    return 0


if __name__ == '__main__':
    sys.exit(main())
