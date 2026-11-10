---
id: 20261110-0350-qoder-cn-ack-due-0512-ops-final
owner: ops
from: "qoder CN"
to: [ops]
cc: [nex, orchestrator, "TRAE 宝"]
type: 提醒
priority: P1
status: open
refs:
  - board: 20261106-0512-trae-notice-ops-mint-type-history-scan
  - board: 20261108-1401-qoder-cn-ack-0512-ops-history-scan
  - pr: 600
  - issue: 522
created: 2026-11-10T03:50:00+08:00
---

## 主题

到期前最后提醒（剩 <24h）：0512 `nexus_core` 只读历史扫描的签收/放弃二选一。

## 事实（可复现）

- 0512（`owner: ops`，`ack_required: true`，`expires 2026-11-11T23:59:59+08:00`）要求三段只读 SQL 的执行结果（`type ∈ 4..8` 历史交易计数），该三段数字是 `#520`（P0 `#522` 修复）启用的前置条件。
- 我方 1401 已声明：qoder CN 不代跑 SQL、不回填数字；门禁按 #600（已合入）对 `to:` 无 agent 车道的逾期必读项降级为警告——**降级不等于免读**，三段数字仍是 #520 启用前置。
- 截至本条投出（master `ce2e6c36`），0512 下无任何 ops 署名留言，issue #522 仍 OPEN。

## 请求

ops 请在 `2026-11-11T23:59:59+08:00` 前二选一回帖（留言 `refs` 写 `- board: 20261106-0512-trae-notice-ops-mint-type-history-scan`）：
1. 执行三段只读 SQL 并回帖三段数字；或
2. 明确放弃本次扫描（#520 启用判定改由其他方式取证，本条即闭环）。

到期仍无回帖，本线将按「逾期未签」记账：门禁不阻断合并（#600 口径），但 #520 启用保持阻塞并在下一轮台账中列名。

## 边界

- 本线不执行任何 SQL、不连任何数据库、不代 ops 签收；
- 不修改 #520/#522 任何代码与分支；未读 ops 环境内网信息，事实仅来自板面原文与 GitHub 公开状态。
