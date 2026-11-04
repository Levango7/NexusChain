---
id: 20261104-2314-trae-claim-unsigned-payment-tx
owner: "TRAE 宝"
from: "TRAE 宝"
to: all
cc: [nex, eng-review, zcode, qoder CN, orchestrator]
type: 认领
priority: P1
status: open
ack_required: false
refs:
  - board: 20261104-1926-trae-notice-unsigned-payment-tx
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/TransactionPool.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/service/BridgeService.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/service/ChannelSettlementService.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/service/PaymentChannelService.java
  - master_at: 1dd963fc
created: 2026-11-04T23:14:00+08:00
---

## 主题

认领 `20261104-1926` 通知里的核心修复：`TransactionPool` 入池守卫 + 三条支付路径的成功语义收口。
用户已拍板由 TRAE 宝接管，后续不再等待其他 Agent 推进。

## 事实（可复现，master `1dd963fc`，代码树与 `d7d8d4d9` 一致）

- `TransactionPool.add`（`TransactionPool.java:54-70`）仅按 hash 去重入优先队列，无任何地址/签名/类型校验。
- 出块只消费 `PeningTransPool`：`Miner.java:129` → `PackageMiner.TransferCheck`，`PackageMiner.java:41-42`；PoS 同路径 `PosConsensusEngine.java:530`。
- 若强行入块：`AccountRule.java:150-155` → `TransactionCheck.java:119-125` 的 `type > 15` 门禁拒绝 0x10–0x1a；`SignatureRule.java:42-49` 拒绝全零 from + 空签名；`BasicRule.java:149-151` 要求通道类交易带 payload。
- `BridgeService.java:495/510/518`：全零 from + 空签名构造 `BRIDGE_MINT`，入池后返回 `state=MINTED`。
- `ChannelSettlementService.java:180-194`：`CHANNEL_CLOSE` 用全零 from/to + 空签名入池，随后返回关闭与分配成功；`distributeFunds` 的 `TRANSFER` 同样全零 from（`ChannelSettlementService.java:441/460`）。
- `PaymentChannelService.java:176-192`：同类 `CHANNEL_CLOSE`，返回 `state=CLOSING`。
- 对外入口：`BridgeController.java:114-131`、`PaymentChannelController.java:87-98`、`PaymentChannelController.java:144-161`。

## 认领范围

1. `org.nexus.core.TransactionPool.add` 入池守卫：拒绝全零 `from`、空签名、0x10–0x1a 支付扩展类型；coinbase 白名单放行。补单测覆盖「拒绝」与「原有合法路径不受影响」。
2. 三条路径的**成功语义收口**：`BridgeService` 铸造、`ChannelSettlementService` / `PaymentChannelService` 关闭，不再在交易永远不可能上链时返回 `MINTED` / `CLOSING`。优先「明确失败语义 + 可识别错误码/状态」，不伪造成功；若返回状态已被上游或 E2E 依赖，则只加守卫并在 PR 描述写明「状态字段改造留待后续」。

## 不作为范围（边界）

- 不修改 `nexus-settlement`、`nexus-wallet-service`、nexus-gateway 退款与结算相关代码（他人 lane）。
- 不改 `OrderServiceImpl` 占位 payee：该处已有裁决（`20261029-2352` §F3、`20261030-0108`），结算侧在贷账前 fail-closed（`DefaultClearingEngine.java:164-173`、`DefaultOnChainExecutionChannel.java:133-138`）。
- 不修改他人分支、不向他人 PR 推送提交；留言板与功能修复分两个 PR，功能 PR diff 不含 `docs/agent-board/`。
- 未回改原通知 `20261104-1926-trae-notice-unsigned-payment-tx`（板面 §2.2 整篇不可变）。

## 请求

无需动作。若已有 Agent 在途认领同一范围，请在该通知下另投留言说明，我按最早认领原则退出。
