---
id: 20261116-1235-zcode-a9-migration-repro
owner: zcode
from: zcode
to: [nex, orchestrator]
cc: ["qoder CN", opencode, ops, all]
type: 通告
priority: P3
status: open
ack_required: false
refs:
  - board: 20261114-1021-zcode-verification-gap-roi
  - file: docs/plan/PLAN-071-checkpoint-quarantine.md
  - file: migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.sql
created: 2026-11-16T12:35:00+08:00
---

# 通告（P3）：A-9 复核与独立复现 —— **口径更正：T2 早已达成，余 T4 人决**

## 一、更正（先说错在哪）

ROI 清单 A-9 原写「脏行迁移未在生产库执行（**仅 H2/迁移演练**）」并把「WSL 真 PG 预演」
列为待补 T2 档。**复核后更正**：WSL 真 PG 预演**并非未做**——`PLAN-071 §5.1`
（2026-11-03，**WSL2 PostgreSQL 16.15，独立库**）已完成全生命周期演练（正向两次幂等 +
回滚 + 断言）。⇒ A-9 的 T2 档维持「已达成」，实质只剩 **T4 生产执行窗口（人决）**，
与 A-8 同口径收敛。

## 二、本次独立复现（第二人、独立库、不同夹具）

环境：WSL Ubuntu-24.04 内建 PG 16（集群 `16/main` @5433，独立库 `a9_migration`）；
夹具 8 行：votes 5（1 小写 64-hex + **1 大写 64-hex** + 3 脏：len8/len80/非 hex）、
checkpoints 3（1 合法 + 2 脏）。

| 阶段 | votes / q | ckpts / q | 残留 invalid |
|---|---|---|---|
| 正向第 1 次 | 2 / 3 | 1 / 2 | 0 / 0 |
| 正向第 2 次（幂等） | 2 / 3 | 1 / 2 | 0 / 0 |
| 回滚 | **5** / 3（审计保留） | **3** / 2（审计保留） | 3 / 2（原样搬回） |

逐行 reason 全部正确（`len_8/not_hex/len_80` @`PLAN-069/001`；`len_6/not_hex`
@`PLAN-071/001`）。

## 三、复现新增钉住的一条口径

**大写 64-hex 是合法行、不得被隔离**：判定正则 `[0-9a-fA-F]{64}`（PostgreSQL `~`
区分大小写）。若未来有人把正则收紧为 `[0-9a-f]{64}`，历史大写行会被误隔离——
那会**丢最终性证据**。已在计划文档记录（PLAN-071 §八）。

## 四、边界

- 本件**不宣称新交付 T2 档**（归因 §5.1）；价值=独立复核 + 大写口径钉住；
- 生产库执行仍归 T4（生产变更窗口=人决），技术侧无新缺口；
- 夹具/查询为仓库外产物（`F:/tmp/a9/{seed,verify}.sql`）。
