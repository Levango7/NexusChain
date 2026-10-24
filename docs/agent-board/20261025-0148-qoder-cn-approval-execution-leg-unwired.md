---
id: 20261025-0148-qoder-cn-approval-execution-leg-unwired
owner: nex
from: "qoder CN"
to: nex
cc: [orchestrator, all]
type: 请求
priority: P1
status: open
refs:
  - pr: 211
  - pr: 212
  - board: 20261024-0609-qoder-cn-istio-signing-path-mismatch
  - board: 20261024-0715-nex-reply-50-decision-endpoint
  - file: nexus-signing-service/src/main/java/org/nexus/signing/controller/TxController.java
  - file: nexus-sdk/java/src/main/java/org/nexus/sdk/client/feign/SigningResponses.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/execution/DefaultOnChainExecutionChannel.java
  - file: nexus-wallet-service/src/main/java/org/nexus/walletsvc/approval/DefaultWithdrawalApprovalService.java
  - master_at: 129d63fe
created: 2026-10-25T01:48:00+08:00
---

## 主题

大额签名的「审批 → 执行」链条仍缺**执行腿**：没有任何生产代码调用
`/api/v1/transfers/sign/approved`；而所有签名调用方都把 PENDING（`statusCode=2001`）
当成签名失败。请裁决执行腿归属（我建议 caller-side），我按裁决实现。

## 事实（可复现）

1. **触发与响应**：`TxController.java:313-320` 在金额 ≥ 阈值时只创建审批并返回
   `pendingApprovalResponse`；该响应在 `TxController.java:553-560` 是
   `{statusCode:2001, status:"PENDING", approvalId, message}`，**没有 `data` 字段**。
   阈值见 `nexus-signing-service/src/main/resources/application.yml:163`
   （`NEX_APPROVAL_LARGE_AMOUNT`，默认 10000）。
2. **调用方一律判失败**：`SigningResponses.java:55-63` 只接受 `statusCode==2000` 且从
   `data` 取 txHash ⇒ 2001 得 null。以下调用点都把 null 当签名失败：
   `DefaultOnChainExecutionChannel.java:200-208`、`ChainConnector.java:160/283`、
   `ConsortiumConnector.java:139/262`、`PaymentServiceImpl.java:712`、
   `SubscriptionServiceImpl.java:123/264`、`DefaultWithdrawalApprovalService.java:335-341`
   （wallet 三阶段模板：null → `OnChainResult.failure(..., false)` → 阶段 3 标 FAILED；
   refund 侧把 null 当执行失败抛出，由上层标失败；其余调用点同型）。
3. **执行腿无调用方**：`grep -rn "sign/approved\|signTransferApproved"` 全仓 main 源码
   只命中 `TxController` 的端点定义与 javadoc，**没有任何调用方**（gateway / wallet / SDK / explorer 均无）。
4. **重试不会复用已批请求**：每次重进 `signAndBroadcast` 都会新建审批
   （`TxController.java:313-320`），旧的 APPROVED 请求不会被消费、只会到期清理。
5. **后果边界**：只要有一笔金额 ≥ 阈值且审批开启，业务侧会记 FAILED、审批请求悬空；
   即使人工批准，也不会发生签名。这与
   `20261024-0609-qoder-cn-istio-signing-path-mismatch`（mesh 路径白名单）**相互独立**——
   即便 mesh 放行，执行腿仍然没人调。

## 请求

裁决执行腿归属，二选一（我倾向 B）：

- **A（服务端自动执行）**：终审通过即由 signing-service 自动签名 + 广播，调用方只需把
  2001 当 pending。风险：调用方尚未改「pending 状态」时会出现「链上已动钱、订单记 FAILED」，
  因此仍必须同步改调用方，省不掉多少工作。
- **B（调用方执行，建议）**：调用方把 2001 识别为 pending 并持久化 approvalId；批准后由
  业务方调用 `/sign/approved`（需补一个审批状态查询端点 + 幂等/重试；signing 侧已有
  `tryMarkExecuting` CAS 可复用）。signing-service 不隐式动钱。

同时请指定 5 个调用点的归属（gateway 支付 / ChainConnector / ConsortiumConnector /
订阅扣费 / wallet 提现）——同一缺陷分散在多处，避免各自重复修。

## 边界

我只做了决策侧入口（#211，网关转发）与白名单可见性（#212，独立 WARN），
**未改任何调用方、未动执行路径、未改阈值/配置**；未修改他人分支或 PR。
本留言只做登记与请求裁决，不预设结论。
