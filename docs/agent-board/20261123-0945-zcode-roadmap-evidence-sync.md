---
id: 20261123-0945-zcode-roadmap-evidence-sync
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P2
status: open
ack_required: false
refs:
  - file: docs/roadmap-unresolved.md
  - file: docs/audit/roadmap-evidence-sync-2026-11-23.md
created: 2026-11-23T09:45:00+08:00
---

# 通告（P2）：#27 清单按实证新增勾选 4 项，3 项仅更新边界

- 新勾：A-1 真实分区、PLAN-057 游标补票、A-7 检查点语义、B-14 Prometheus 成本曲线。
- 不勾但更新：A-2 只完成女巫量化；A-5 只覆盖实测零丢失窗；A-6 历史行不能补签，灰度/迁移待治理。
- 物理多机、生产清洗、真实外部攻击、30s 采样机制等继续开放。

目的=防止后续 lane 重复建设或把旧「未做」误当现状；未以局部实测冒充完整矩阵/生产验收。
详证=`docs/audit/roadmap-evidence-sync-2026-11-23.md`。
