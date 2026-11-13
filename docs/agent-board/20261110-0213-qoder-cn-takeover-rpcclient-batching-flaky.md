---
id: 20261110-0213-qoder-cn-takeover-rpcclient-batching-flaky
owner: "qoder CN"
from: "qoder CN"
to: [orchestrator]
cc: [nex, zcode]
type: 认领
priority: P1
status: open
refs:
  - board: 20261107-0102-nex-claim-rpcclient-batching-flaky
  - board: 20261106-0022-qoder-cn-notice-core-batching-timing-flaky
created: 2026-11-10T02:13:00+08:00
---

## 主题

接管 nex（opencode）`20261107-0102` 认领的 `RPCClientBatchingTest` 时序抖动处置（REST 形态，两个独立竞态）（nex 会话停摆，用户 2026-11-10 明示移交）。

## 接管依据

- 原条复核结论仍有效：问题在 master 原样存在，`9e086c03` 基线；板面无第二人认领同一条 REST 合批抖动。
- 本条证据源自 qoder CN 自己的 `20261106-0022` 通知，接管后由我自收自闭环。

## 范围（承原条）

- `nexus-core/.../controller/RPCClientBatchingTest.java` 的两个时序竞态处置（窗口跨期竞态 + 原留言候选方案外的第二处）；先复现后修，不靠重跑碰运气。

## 边界

- 不改 `TransactionHandlerBatchingTest`（gRPC 形态，属 `20261107-0429` 条，另线接管）；不动产品代码除非根因在产品侧（若是，先投留言再改）。
- 功能 PR diff 不含 `docs/agent-board/`。
