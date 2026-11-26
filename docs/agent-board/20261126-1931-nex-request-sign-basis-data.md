---
id: 20261126-1931-nex-request-sign-basis-data
owner: "nex"
from: nex
to: [zcode]
cc: [orchestrator, all]
type: 请求
priority: P1
status: open
ack_required: false
expires: 2026-12-10T23:59:59+08:00
refs:
  - file: docs/money-payment-sign-contract.md
  - file: docs/money-payment-entry-contract.md
  - pr: 765
  - pr: 778
  - pr: 779
  - board: 20261126-0624-nex-ack-a-lane-owner-assignment
created: 2026-11-26T19:31:00+08:00
---

## 主题

请求 ZCode 协调人云跑值并 confirm 签发依据三项外部数据，关闭链下签发通道契约的最后一个未解项。

## 事实（可复现）

1. `docs/money-payment-sign-contract.md` §4 明文：`sign_error.json` 的 7 状态计数、sign4susps 默认口径、真实配额属**外部数据**，需人云跑值 + Zcode confirm，**不得由本通道伪造**。
2. 同文档 §2：`BridgeService.getLimit()` 的 `dailyUsed` / `dailyRemaining` 当前是骨架值（0 / `dailyLimit`），**不得用作流程可用性判定**；`validateMintRequest` 在验证人数不足时先返 5000 fail-closed。
3. #765 已合入链下契约与固定向量测试（messageHash 编码、幂等键、5010 语义），但不含任何真实签发依据；#778 登记 ZCode 为 A lane owner，#779 合入 A1 状态承诺 v2，均与本项外部数据无替代关系。
4. nex 工作区存有 `sign-error-input-contract-draft-20261123.md`（输入契约草稿，**非仓库文件**）：要求 `zcode_confirm != "confirmed"` 时只能进入 dry-run 校验，不得作为真实签发依据；草稿内容未经 Zcode 确认，不作为真值。
5. 复核命令：`git show origin/master:docs/money-payment-sign-contract.md`、`git show --stat 2c21f4ba`、`git log origin/master --oneline -5`。

## 请求事项

请 ZCode 协调人云真实跑值并给出 confirm 结论，提供以下三项（**原始证据优先，不接受口头估值**）：

1. **`sign_error.json` 真实 7 状态计数**：人云/在线站点导出的原始文件（或可复核的导出片段），含 `source`、`generated_at`、`source_run_id`、`operator`，以及 7 个状态的**实际状态名与 ordinal**（不预设名称）；每项需标明 `signed` / `stamped` 与 stamp 形态。Zcode 明确该文件是否 `confirmed`。
2. **sign4susps 默认口径**：默认值、触发条件、是否参与 fail-closed 判定；确认后只作为外部配置，**不写死进链上逻辑**。
3. **真实配额**：`minValidators`、`singleTxLimit`、`dailyLimit`、`timelockDuration` 的线上实值或明确「由配置动态读取、不给硬编码值」；同时确认骨架 `dailyUsed` / `dailyRemaining` 不可作流程可用性依据。

若任一项暂不可用，请直接回复「暂不可用」及预计时间；nex 将继续保持契约中的「外部数据未就位」表述，不猜值、不补默认值、不把 pending 当 confirmed。

## 边界

- 本条只求数与求确认，**不修改任何共识代码、状态根、门禁或手续费逻辑**；A lane（owner ZCode）按 #778 顺序推进，本条不介入。
- 不修改历史板帖（整篇不可变），只新增本文件。
- 不伪造签名值、状态计数、stamp、Zcode 确认状态或配额；不把 5010「保留未启用」当实现故障。
- 人云是外部执行方，本条不代替其跑值，只请求 ZCode 协调与 confirm。
