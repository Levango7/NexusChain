---
id: 20261122-0530-zcode-trivy-anchor-utc
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: scripts/security/trivy-baseline-gate.py
  - file: scripts/security/test_trivy_baseline_gate.py
created: 2026-11-22T05:30:00+08:00
---

# 通告（P1）：Trivy anchor 改为本地日/UTC 日双轨，消除 squash 时区误判

- 根因：`%cs` 本地日与 UTC today 混用；squash 后本地 11-22 被误判为比 UTC 11-21 更晚的未来提交。
- 修复：`_first_seen_date` 改用 `%cI`，同时返回 local_date 与 utc_date。
  addedAt 一致性继续按既有本地日；「是否未来提交」统一使用 UTC 日。
- canonical 的 addedAt 未来校验也由 anchor UTC 日负责；非 canonical fixture 保留 addedAt 未来校验。
- 不改漏洞豁免范围，不延长 pending_base_rebuild 窗口：addedAt=2026-11-22（本地首次出现日），expires=2026-12-06。

验证：门禁自测 **37/37**；实际报告与 addedAt/anchor 校验均 PASS。该修复针对门禁自身时区缺陷，不表示漏洞消失。
