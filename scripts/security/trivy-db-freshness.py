#!/usr/bin/env python3
"""Trivy 漏洞库新鲜度断言（扫描前执行）。

背景（2026-11-06）：CI 之前把 trivy 钉在 `ghcr.io/aquasecurity/trivy:0.51.1`，
且日志里没有任何 DB 更新时间，结果是 2026-11 新披露的 HIGH 根本没进比对集——
门禁 `success` 只代表「扫描器看不见」，不代表「真的没漏洞」。失明必须以**红灯**
表现出来，不能表现为通过。

本脚本读取 Trivy 的 DB 元数据（`.cache/trivy/db/metadata.json`），打印
`Version` / `UpdatedAt`，并在 `UpdatedAt` 距今超过阈值（默认 7 天）时以非零
退出码失败，失败消息固定包含「Trivy 漏洞库过期，无法保证结论有效性」。

退出码：
  0  DB 新鲜（age <= --max-age-days）
  1  DB 过期（age > --max-age-days）
  2  元数据不可读 / 缺 UpdatedAt / 时间格式不可解析（同样无法保证结论）

用法：
  python3 scripts/security/trivy-db-freshness.py \
      --metadata .cache/trivy/db/metadata.json \
      --max-age-days 7
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys

STALE_MESSAGE = "Trivy 漏洞库过期，无法保证结论有效性"
DEFAULT_MAX_AGE_DAYS = 7


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _load(path: str):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _parse_ts(raw, field: str) -> dt.datetime:
    text = str(raw or "").strip()
    if not text:
        raise ValueError(f"缺少 {field}")
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    # Trivy 元数据可能带纳秒（9 位）小数秒，Python 只接受最多 6 位，截断即可。
    text = re.sub(r"(\.[0-9]{6})[0-9]+", r"\1", text)
    parsed = dt.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def main() -> int:
    parser = argparse.ArgumentParser(description="Trivy DB 新鲜度断言")
    parser.add_argument(
        "--metadata",
        default=".cache/trivy/db/metadata.json",
        help="Trivy DB 元数据文件（默认 .cache/trivy/db/metadata.json）",
    )
    parser.add_argument(
        "--max-age-days",
        type=int,
        default=DEFAULT_MAX_AGE_DAYS,
        help=f"允许的最大库龄（天），默认 {DEFAULT_MAX_AGE_DAYS}",
    )
    parser.add_argument(
        "--now",
        default=None,
        help="覆盖当前 UTC 时间（ISO），仅用于测试",
    )
    parser.add_argument(
        "--fingerprint-out",
        default=None,
        help="可选：把本次下载的 DB 身份（metadata 字段 + trivy.db sha256）写成 JSON，"
        "供门禁步交叉校验「门禁结论基于哪份库」",
    )
    args = parser.parse_args()

    now = (
        dt.datetime.fromisoformat(args.now).replace(tzinfo=dt.timezone.utc)
        if args.now
        else dt.datetime.now(dt.timezone.utc)
    )
    if now.tzinfo is None:
        now = now.replace(tzinfo=dt.timezone.utc)

    try:
        meta = _load(args.metadata)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"[DB-STALE] {STALE_MESSAGE}：DB 元数据不可读 {args.metadata}（{e}）")
        print(f"::error title=Trivy 漏洞库过期::{STALE_MESSAGE}（元数据不可读）")
        return 2
    if not isinstance(meta, dict):
        print(f"[DB-STALE] {STALE_MESSAGE}：DB 元数据格式不可识别 {args.metadata}")
        print(f"::error title=Trivy 漏洞库过期::{STALE_MESSAGE}（元数据格式不可识别）")
        return 2

    try:
        updated_at = _parse_ts(meta.get("UpdatedAt"), "UpdatedAt")
    except ValueError as e:
        print(f"[DB-STALE] {STALE_MESSAGE}：{e}（{args.metadata}）")
        print(f"::error title=Trivy 漏洞库过期::{STALE_MESSAGE}（缺 UpdatedAt）")
        return 2

    age_days = (now - updated_at).total_seconds() / 86400
    print(
        f"[DB] Trivy 漏洞库 Version={meta.get('Version')} "
        f"UpdatedAt={meta.get('UpdatedAt')} NextUpdate={meta.get('NextUpdate')}"
    )
    print(f"[DB] 库龄 {age_days:.2f} 天（阈值 {args.max_age_days} 天，判定时间 {now.isoformat()}）")

    if age_days > args.max_age_days:
        print(
            f"[DB-STALE] {STALE_MESSAGE}"
            f"（UpdatedAt={updated_at.isoformat()} 距今 {age_days:.2f} 天 > {args.max_age_days} 天）"
        )
        print(
            f"::error title=Trivy 漏洞库过期::"
            f"{STALE_MESSAGE}（DB UpdatedAt={updated_at.isoformat()}，{age_days:.2f} 天前）"
        )
        return 1

    print(f"[DB-OK] Trivy 漏洞库新鲜（{age_days:.2f} 天 <= {args.max_age_days} 天）")

    if args.fingerprint_out:
        db_path = os.path.join(os.path.dirname(os.path.abspath(args.metadata)), "trivy.db")
        try:
            sha256 = _sha256(db_path)
        except OSError as e:
            print(f"[DB-STALE] {STALE_MESSAGE}：无法对 DB 文件取指纹 {db_path}（{e}）")
            print(f"::error title=Trivy 漏洞库不可绑定::{STALE_MESSAGE}（DB 文件不可读）")
            return 2
        fingerprint = {
            "Version": meta.get("Version"),
            "UpdatedAt": meta.get("UpdatedAt"),
            "NextUpdate": meta.get("NextUpdate"),
            "dbSha256": sha256,
        }
        with open(args.fingerprint_out, "w", encoding="utf-8") as f:
            json.dump(fingerprint, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[DB] DB 指纹 sha256={sha256}（已写入 {args.fingerprint_out}）")

    return 0


if __name__ == "__main__":
    sys.exit(main())
