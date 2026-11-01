#!/usr/bin/env python3
"""从 PrometheusRule CRD 提取 spec.groups，产出 promtool 可解析的普通 rules 文件。

用法
    python3 scripts/extract-prometheus-rules.py <crd.yaml> <out.yaml>

背景（PLAN-064）
    deploy/k8s/50-prometheus-rules.yaml 是 monitoring.coreos.com/v1 的
    PrometheusRule CRD——promtool 不能直接吃 CRD 形状，需要把 spec.groups
    原样抽出后才能做 `promtool check rules` / `promtool test rules`。
    （规则文件头部注释自 2026-09-09 起就写了这件事，但一直没有落地执行。）

设计
    · 只做结构提取，逐键透传：不改写任何表达式/标签/注解；
    · 输出 UTF-8 + LF、保持字段顺序（sort_keys=False），便于人眼比对；
    · 依赖 PyYAML——CI 由 k8s-sync-check 的 "Install PyYAML" 步骤提供。
"""

import sys

import yaml


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: extract-prometheus-rules.py <crd.yaml> <out.yaml>",
            file=sys.stderr,
        )
        return 2

    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8-sig") as f:
        doc = yaml.safe_load(f)

    groups = (doc.get("spec") or {}).get("groups")
    if not groups:
        print("FAIL: no spec.groups found in %s" % src, file=sys.stderr)
        return 1

    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        yaml.safe_dump(
            {"groups": groups}, f, allow_unicode=True, sort_keys=False, width=10**9
        )

    n_rules = sum(len(g.get("rules") or []) for g in groups)
    n_alerts = sum(
        1 for g in groups for r in (g.get("rules") or []) if "alert" in r
    )
    print(
        "extracted %d groups / %d rules (%d alerts) -> %s"
        % (len(groups), n_rules, n_alerts, dst)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
