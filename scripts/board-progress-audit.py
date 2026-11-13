#!/usr/bin/env python3
"""board-progress-audit.py — 留言板进度盘点（只读）

目的：给「谁进度慢了」提供**客观口径**，避免凭印象点名。
输出四张表：
  ① 各车道未闭环认领（含开放天数）
  ② 最久未闭环的认领明细（用于催办）
  ③ ack_required 逾期未签收（真欠账）
  ④ 等 orchestrator 裁/答复的最久条目

判定口径（写死在此，便于复核）：
  · 「闭环」= 存在**另一条**留言在 refs 里以 `board: <该 id>` 引用它（§7.2 口径）；
    引用它的留言类型不限（交接/闭环/答复/裁决/勘误都算）。
  · 「未闭环认领」= type 为「认领」且未被任何其他留言引用；
    这类条目只是**声明**，不等于交付。
  · 「等裁」= to 含 orchestrator 且 type ∈ {请求, 裁决, 认领} 且未被其他留言引用。
  · 「ack 逾期」= ack_required=true 且 status=open 且 expires 已过且未被其他留言引用。
  · 开放天数 = now - created（按 front-matter 的 created 解析）。

用法：
  python scripts/board-progress-audit.py            # 打印四张表
  python scripts/board-progress-audit.py --days 7   # 只看开放 ≥7 天的
  python scripts/board-progress-audit.py --json     # 机器可读输出

边界：**只读**——不写任何文件、不改任何留言、不代签任何 ack。
"""
import argparse
import datetime as dt
import json
import os
import re
import sys

BOARD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "agent-board")

CLAIM_TYPES = {"认领", "CLAIM", "claim"}
ORCH_TYPES = {"请求", "裁决", "认领", "CLAIM"}
DONE_TYPES = {"交接", "闭环", "答复", "裁决", "勘误", "DONE", "done"}


def parse_front_matter(text):
    m = re.match(r"^---\r?\n(.*?)\r?\n---\r?\n", text, re.S)
    if not m:
        return None, []
    fields, refs = {}, []
    in_refs = False
    for line in m.group(1).splitlines():
        if re.match(r"^\s*refs\s*:", line):
            in_refs = True
            continue
        if in_refs:
            mm = re.match(r"^\s*-\s*([A-Za-z_]+)\s*:\s*(.+?)\s*$", line)
            if mm:
                refs.append((mm.group(1).lower(), mm.group(2).strip().strip("\"'")))
                continue
            if line.strip():
                in_refs = False
        mm = re.match(r"^([A-Za-z_]+)\s*:\s*(.*?)\s*$", line)
        if mm:
            fields[mm.group(1).lower()] = mm.group(2).strip().strip("\"'")
    return fields, refs


def parse_time(value):
    if not value:
        return None
    value = value.strip()
    for fmt in ("%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S"):
        try:
            d = dt.datetime.strptime(value, fmt)
            if d.tzinfo is None:
                d = d.replace(tzinfo=dt.timezone.utc)
            return d
        except ValueError:
            continue
    return None


def load_board(board_dir):
    msgs = []
    if not os.path.isdir(board_dir):
        return msgs
    for name in sorted(os.listdir(board_dir)):
        if not name.endswith(".md") or name == "README.md":
            continue
        path = os.path.join(board_dir, name)
        try:
            with open(path, "r", encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            continue
        fields, refs = parse_front_matter(text)
        if fields is None:
            continue
        mid = fields.get("id", name[:-3])
        subject = ""
        in_body = False
        for line in text.splitlines():
            if line.strip() == "---":
                if in_body:
                    break
                in_body = True
                continue
            if in_body and line.startswith("## "):
                subject = line[3:].strip()
                break
        msgs.append({
            "id": mid,
            "file": name,
            "owner": fields.get("owner", "?"),
            "frm": fields.get("from", fields.get("owner", "?")),
            "to": fields.get("to", ""),
            "type": fields.get("type", ""),
            "priority": fields.get("priority", ""),
            "status": fields.get("status", ""),
            "created": fields.get("created", ""),
            "ack_required": str(fields.get("ack_required", "false")).lower() == "true",
            "expires": fields.get("expires", ""),
            "refs": refs,
            "subject": subject,
            "created_dt": parse_time(fields.get("created", "")),
            "expires_dt": parse_time(fields.get("expires", "")),
        })
    return msgs


def referenced_ids(msgs):
    """所有被别的留言引用过的 id（引用者自身不算）。"""
    refd = set()
    for m in msgs:
        for kind, val in m["refs"]:
            if kind == "board":
                refd.add(val)
    return refd


def age_days(m, now):
    if not m["created_dt"]:
        return -1
    return (now - m["created_dt"]).days


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=0, help="只看开放天数 ≥ N 的条目")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--board-dir", default=BOARD_DIR)
    args = ap.parse_args()

    now = dt.datetime.now(dt.timezone.utc)
    msgs = load_board(args.board_dir)
    if not msgs:
        print("板面为空或路径不可用：%s" % args.board_dir, file=sys.stderr)
        return 2
    refd = referenced_ids(msgs)

    open_claims, ack_overdue, waiting_orch = [], [], []
    for m in msgs:
        closed = m["id"] in refd
        age = age_days(m, now)
        if m["type"] in CLAIM_TYPES and not closed and age >= args.days:
            open_claims.append((m, age))
        if (m["ack_required"] and m["status"] == "open" and not closed
                and m["expires_dt"] and now > m["expires_dt"]):
            ack_overdue.append((m, age))
        if (m["type"] in ORCH_TYPES and "orchestrator" in m["to"].lower()
                and not closed and age >= args.days):
            waiting_orch.append((m, age))

    open_claims.sort(key=lambda x: -x[1])
    waiting_orch.sort(key=lambda x: -x[1])
    ack_overdue.sort(key=lambda x: -x[1])

    by_owner = {}
    for m, age in open_claims:
        by_owner.setdefault(m["owner"], []).append((m, age))

    if args.json:
        out = {
            "generated": now.isoformat(),
            "messages": len(msgs),
            "openClaims": [{"owner": m["owner"], "id": m["id"], "days": a,
                            "subject": m["subject"], "file": m["file"]} for m, a in open_claims],
            "ackOverdue": [{"owner": m["owner"], "id": m["id"], "expires": m["expires"],
                             "days": a, "subject": m["subject"]} for m, a in ack_overdue],
            "waitingOrchestrator": [{"owner": m["owner"], "id": m["id"], "days": a,
                                     "subject": m["subject"]} for m, a in waiting_orch],
        }
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return 0

    print("=== 留言板进度盘点（只读；判据见脚本头）===")
    print("时间：%s ｜ 留言总数：%d" % (now.isoformat(), len(msgs)))
    print()
    print("--- ① 各车道未闭环认领（认领 ≠ 交付） ---")
    print("%-28s %-8s %-10s %s" % ("owner", "未闭环", "最久(天)", "最久条目"))
    for owner in sorted(by_owner, key=lambda o: -max(a for _, a in by_owner[o])):
        items = by_owner[owner]
        worst = max(items, key=lambda x: x[1])
        print("%-28s %-8d %-10d %s" % (owner[:28], len(items), worst[1], worst[0]["id"]))
    print()
    print("--- ② 最久未闭环的认领明细（催办候选） ---")
    for m, age in open_claims:
        print("  %4dd  %-14s %-52s %s" % (age, m["owner"][:14], m["id"][:52], (m["subject"] or "")[:40]))
    print()
    print("--- ③ ack_required 逾期未签收（真欠账） ---")
    if not ack_overdue:
        print("  （无）")
    for m, age in ack_overdue:
        print("  %4dd  %-14s %-52s expires=%s" % (age, m["owner"][:14], m["id"][:52], m["expires"]))
    print()
    print("--- ④ 等 orchestrator 裁/答复（最久 10 条） ---")
    for m, age in waiting_orch[:10]:
        print("  %4dd  %-14s %-52s %s" % (age, m["owner"][:14], m["id"][:52], (m["subject"] or "")[:40]))
    print()
    print("注：本盘点只列**客观可查**的口径（引用关系 + 时间），不评价主观进度；催办仍由 owner 决定。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
