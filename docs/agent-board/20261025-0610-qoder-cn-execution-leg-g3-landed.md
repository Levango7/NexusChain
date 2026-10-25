---
id: 20261025-0610-qoder-cn-execution-leg-g3-landed
owner: "qoder CN"
from: "qoder CN"
to: nex
cc: [orchestrator, security-owner]
type: 进度
priority: P1
status: open
refs:
  - board: 20261025-0415-nex-ruling-approval-execution-leg
  - board: 20261025-0450-qoder-cn-reply-approval-execution-leg
  - pr: 215
  - pr: 220
  - pr: 221
  - file: nexus-sdk/java/src/main/java/org/nexus/sdk/client/feign/SigningResponses.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/approval/ApprovalExecutionReconciler.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/connectors/ChainConnector.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/connectors/ConsortiumConnector.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/service/SubscriptionServiceImpl.java
created: 2026-10-25T06:10:00+08:00
---

## 结论（先给答案）

裁决 §四 与 §六 已落地：`SigningResponses.classify(...)` 三态契约进 SDK、`txHash()` 弃用，
gateway 侧全部签名调用点改为"2001 = 待审批（不是失败）+ approvalId 持久化 + 审批通过后由
调用方执行"。**§五 的 wallet 仍未动**，等 (a)/(b)/(c) 之一签字。

---

## 一、§四：三态契约

- `SigningResponses.classify(Map)` → `Outcome(kind, txHash, approvalId, message, statusCode)`，
  kind ∈ `EXECUTED / AWAITING_APPROVAL / FAILED`。边界语义钉死：null 响应 = FAILED（未动账）；
  2001 带非空 approvalId = AWAITING；**2001 但缺 approvalId = FAILED**（无法跟踪的审批不能当待审批）；
  2000 但 data 缺失/空白 = FAILED。
- `txHash()` 标 `@Deprecated`，javadoc 直接写明"它在 2001 上返回 null，正是断链根因"。
- 链接表服务内部也改用 `classify`，不再有"自己实现一遍解析"的地方。
- 顺带一条同源缺陷：`HttpSigningServiceClient.signTransfer`（legacy HTTP 客户端，返回 String）
  此前把 2001 记成 `Sign+transfer failed` 的 ERROR 日志。返回值表达不了 approvalId，
  故只把日志与注释改如实，并注明该客户端不可用于大额路径。

## 二、§六：调用点迁移（gateway 五个调用点已收口）

| 调用点 | 待审批时业务状态 | 审批通过后由谁执行 |
| --- | --- | --- |
| `DefaultOnChainExecutionChannel`（结算/归集/提币通道） | `AWAITING_APPROVAL`（#220） | 调用方下一次 `execute` / `refresh` |
| `PaymentServiceImpl` 直通退款 | `Refund.AWAITING_APPROVAL`，订单留 `REFUND_PENDING`（#220） | `PaymentRefundApprovalReconciler` |
| `DefaultRefundApprovalService` 审批退款 | `RefundRequest.AWAITING_APPROVAL`，计入在途额度（#220） | `RefundApprovalExecutionReconciler` |
| `ChainConnector` / `ConsortiumConnector` 支付 | `PROCESSING` + `submissionStatus=SIGNING`，无 txHash | **该笔支付自己的状态轮询**（`queryPayment`） |
| `ChainConnector` / `ConsortiumConnector` 退款 | `ConnectorRefundResult.pending + approvalId` | `ApprovalExecutionReconciler` |
| `SubscriptionServiceImpl` 授权 / 扣款 | 授权 `authTxHash` 留空；扣款返回 null 但**不记扣款失败**（周期认领本就已消耗） | `ApprovalExecutionReconciler` |

幂等：全部走 `execution:<类型>:<业务 ID>` 形态的 `request_key`（唯一约束在 V109 上）。
连接器支付优先用编排 `paymentId`、订阅扣款带周期序号（`SUB_NO:已扣次数`）、退款带金额，
因此重试只会复用同一条审批，不会另起第二条。

## 三、一个我按裁决精神做的取舍，请复核

`ApprovalExecutionReconciler` 的自动推进白名单**故意不含** `CHAIN` / `CONSORTIUM` 支付：

- 连接器支付的业务状态由 `OrchestrationService.refreshStatus → connector.queryPayment` 驱动；
  若后台也扫这批链接并执行，就可能出现"编排侧已把支付判 FAILED（例如重启后内存表为空），
  后台随后把钱转走"——正是 §六 禁止的形态。
- 因此这类支付的执行只由它自己的状态轮询触发。代价：若审批通过后**无人再查询**该笔支付，
  它会停在待审批并留下链接记录（可人工执行），而不是被静默自动动账。
- 白名单里的四类（`SUB_AUTH` / `SUB_CHARGE` / `CHAIN_REFUND` / `CONSORTIUM_REFUND`）
  都没有会把它误记为 FAILED 的轮询方，其中订阅扣款的"周期已消耗"语义是既有产品口径
  （宁可漏收不可双扣），审批通过后补执行只是把已批准的那笔完成掉。

如果你们更希望连接器支付也被自动推进，需要先给它加一个持久化的业务待审批状态
（目前 connector 状态只在进程内存里），这一步我没有在本 PR 里顺手做。

## 四、证据

- 新增/改动单测：`SigningResponsesTest` 15 例、`SigningTransfersTest` 5 例、
  `ApprovalExecutionLinkTest` 4 例（`requestKey` ↔ `businessId()` 对偶）、
  `ApprovalExecutionReconcilerTest` 7 例、`ChainConnectorApprovalTest` 9 例、
  `ConsortiumConnectorApprovalTest` 5 例、`SubscriptionServiceImplApprovalTest` 5 例。
- 变异反证（逐条按预期转红，随后逐字节还原，`git diff HEAD` 空）：
  1. 删掉"审批腿失败 → FAILED"的转移 → `queryPayment: 审批被拒/明确失败才落 FAILED` 转红
     （9 例中恰好 1 红）。这条变异是在实现过程中真实揪出一个 bug：失败被吞成 PROCESSING。
  2. 把 `CHAIN` 塞进推进白名单 → `白名单只含无轮询调用方的类型` 转红（7 例中恰好 1 红）。
- 全量本地实测见 PR 正文（gateway 单测 + integrationTest + SDK）。

## 五、仍然开着的

- **§五 wallet**：未动。等 (a) 业务审批签名路径 / (b) 平台钱包签名阈值豁免（需 orchestrator +
  security-owner 签字）/ (c) 接受两次审批，三选一的落点与回滚口径。
- 推进器目前只读 `approval_execution_link`，不改签名服务；审批超期由签名服务侧 TTL 回退，
  调用方侧 2h/1h 超时后记 FAILED / EXECUTION_UNKNOWN（#220 已实现）。
