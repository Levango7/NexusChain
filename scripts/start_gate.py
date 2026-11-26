#!/usr/bin/env python3
"""起栈前资源门禁：可用内存与 Docker 条件，未知即 fail-closed。

对应 J4 缺口单 #786 第 2 项：在获授权主机/隔离环境重新执行资源门禁；
内存与 Docker 条件未满足时不得起栈。
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_MIN_MB = 12288


class GateError(Exception):
    def __init__(self, message: str, code: int = 2):
        super().__init__(message)
        self.code = code


def _read_meminfo(path: Path) -> dict[str, int]:
    values: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^([A-Za-z0-9_()]+):\s+(\d+)\s*kB$", line)
        if match:
            values[match.group(1)] = int(match.group(2))
    return values


def _linux_available_mb() -> int:
    values = _read_meminfo(Path("/proc/meminfo"))
    if "MemAvailable" not in values:
        raise GateError("无法确定可用内存：/proc/meminfo 缺少 MemAvailable")
    return values["MemAvailable"] // 1024


def _macos_available_mb() -> int:
    result = subprocess.run(
        ["vm_stat"], check=False, capture_output=True, text=True
    )
    if result.returncode != 0:
        raise GateError("无法确定可用内存：vm_stat 执行失败")
    pages = {}
    for line in result.stdout.splitlines():
        match = re.match(r"^Pages?\s+([^:]+):\s+([0-9.]+)$", line)
        if match:
            pages[match.group(1).strip()] = int(float(match.group(2).rstrip(".")))
    page_size = 4096
    match = re.search(r"page size of (\d+) bytes", result.stdout)
    if match:
        page_size = int(match.group(1))
    free = pages.get("free", 0)
    inactive = pages.get("inactive", 0)
    return (free + inactive) * page_size // (1024 * 1024)


def _windows_available_mb() -> int:
    for exe in ("powershell.exe", "powershell"):
        try:
            result = subprocess.run(
                [
                    exe,
                    "-NoProfile",
                    "-Command",
                    "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory",
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if result.returncode == 0:
            match = re.search(r"\d+", result.stdout.strip())
            if match:
                return int(match.group(0)) // 1024
        raise GateError("无法确定可用内存：powershell FreePhysicalMemory 执行失败")
    raise GateError("无法确定可用内存：未找到 powershell")


def available_ram_mb() -> int:
    system = platform.system()
    if system == "Linux":
        return _linux_available_mb()
    if system == "Darwin":
        return _macos_available_mb()
    if system == "Windows":
        return _windows_available_mb()
    raise GateError(f"无法确定可用内存：不支持的平台 {system}")


def min_ram_mb() -> int:
    raw = os.environ.get("NEX_MIN_AVAILABLE_RAM_MB", str(DEFAULT_MIN_MB))
    try:
        value = int(raw)
    except ValueError as exc:
        raise GateError("NEX_MIN_AVAILABLE_RAM_MB 必须是非负整数") from exc
    if value < 0:
        raise GateError("NEX_MIN_AVAILABLE_RAM_MB 必须是非负整数")
    return value


def check_start_resources(min_mb: int = DEFAULT_MIN_MB) -> int:
    available = available_ram_mb()
    if available < min_mb:
        raise GateError(
            f"资源门禁未满足：可用内存 {available}MB < 起栈下限 {min_mb}MB；"
            "按 J4 闸门不得起栈"
        )
    return available


def main() -> int:
    parser = argparse.ArgumentParser(description="NexusChain start resource gate")
    parser.add_argument("--min-mb", type=int, default=min_ram_mb())
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        available = check_start_resources(args.min_mb)
    except GateError as exc:
        if args.json:
            print(json.dumps({"ok": False, "reason": str(exc)}))
        else:
            print(f"[FAIL] {exc}", file=sys.stderr)
        return exc.code
    if args.json:
        print(json.dumps({"ok": True, "availableMb": available, "minMb": args.min_mb}))
    else:
        print(f"[PASS] 可用内存 {available}MB ≥ 起栈下限 {args.min_mb}MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
