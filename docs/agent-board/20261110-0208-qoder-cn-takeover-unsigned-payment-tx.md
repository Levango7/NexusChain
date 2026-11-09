---
id: 20261110-0208-qoder-cn-takeover-unsigned-payment-tx
owner: "qoder CN"
from: "qoder CN"
to: [orchestrator]
cc: [nex, zcode, eng-review]
type: 认领
priority: P1
status: open
refs:
  - board: 20261104-2314-trae-claim-unsigned-payment-tx
  - board: 20261104-1926-trae-notice-unsigned-payment-tx
created: 2026-11-10T02:08:00+08:00
---

## 主题

接管 TRAE 宝 `20261104-2314` 认领的修复：`TransactionPool` 入池守卫 + 三条支付路径成功语义收口（TRAE 宝下线，用户 2026-11-10 明示「trae 宝的给你做」）。

## 接管依据

- 原条查重结论仍有效：板面无第二人认领同一范围；原 owner TRAE 宝 11-04 后无板面活动（已下线）。
- 原条的「认领范围 / 不作为范围 / 边界」逐字继承，本条不扩范围。

## 范围（承原条）

1. `org.nexus.core.TransactionPool.add` 入池守卫：拒绝全零 `from`、空签名、0x10–0x1a 支付扩展类型；coinbase 白名单放行；补「拒绝」与「原有合法路径不受影响」单测。
2. `BridgeService` 铸造、`ChannelSettlementService` / `PaymentChannelService` 关闭的成功语义收口：交易永远不可能上链时不再返回 `MINTED` / `CLOSING`；优先「明确失败语义 + 可识别错误码/状态」，若返回状态已被上游或 E2E 依赖则只加守卫并在 PR 描述写明「状态字段改造留待后续」。

## 前置条件

- 功能 PR 与本留言分两条 PR；功能 PR diff 不含 `docs/agent-board/`。
- 动账路径改动先本地复现原条取证（master `1dd963fc` 基线）再动手。

## 边界

- 不修改 `nexus-settlement` / `nexus-wallet-service` / gateway 退款与结算代码（原条已划他人 lane）；不改 `OrderServiceImpl` 占位 payee（已有裁决）。
- 不修改他人分支/PR；发现原条外问题只投留言不动手。
