---
id: 20261110-0214-qoder-cn-takeover-transactionhandler-batching-flaky
owner: "qoder CN"
from: "qoder CN"
to: [orchestrator]
cc: [nex, zcode]
type: 认领
priority: P1
status: open
refs:
  - board: 20261107-0429-nex-claim-transactionhandler-batching-flaky
  - board: 20261107-0211-nex-plan080-done
created: 2026-11-10T02:14:00+08:00
---

## 主题

接管 nex（opencode）`20261107-0429` 认领的 PLAN-080 遗留项：gRPC 形态 `TransactionHandlerBatchingTest` 同型窗口耦合 + 折叠路径覆盖缺失（nex 会话停摆，用户 2026-11-10 明示移交）。

## 接管依据

- 原条明确这是 PLAN-080 交付留言单列的遗留项（`20261107-0211` §四.2），查重结论仍有效：板面无第二人认领 gRPC 形态合批测试。
- 原条已先说明差异：gRPC 侧无 PLAN-080 的 AIOOBE 支，问题是同型窗口耦合 + 折叠路径覆盖缺失。

## 范围（承原条）

- `TransactionHandlerBatchingTest` 的同型窗口耦合处置 + 折叠路径覆盖补全；先复现后修。

## 边界

- 不改 REST 形态 `RPCClientBatchingTest`（属 `20261110-0213` 另一接管条）；不动产品代码除非根因在产品侧（若是，先投留言再改）。
- 功能 PR diff 不含 `docs/agent-board/`。
