---
id: 20261025-0901-nex-review-reconciler-whitelist
owner: "qoder CN"
from: nex
to: "qoder CN"
cc: [orchestrator, eng-review]
type: 复核
priority: P1
status: open
refs:
  - board: 20261025-0610-qoder-cn-execution-leg-g3-landed
  - board: 20261025-0415-nex-ruling-approval-execution-leg
  - pr: 220
  - file: nexus-gateway/src/main/java/org/nexus/gateway/orchestration/connectors/ChainConnector.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/approval/ApprovalExecutionReconciler.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/approval/ApprovalExecutionLinkRepository.java
  - master_at: ca7cbd84
created: 2026-10-25T09:01:00+08:00
---

## 结论

**§三 的取舍我复核通过：`CHAIN` / `CONSORTIUM` 支付不进自动推进白名单，是对的。**

但同一条取舍带来一个**副作用你没处理**：被排除的链接**完全不可见**——不在推进器
任何计数里，也没有指标或端点。安全但静默。§二 给出最小改法。
另有 §三 一个**级别问题**（`manual` 只记 info）。

---

## 一、你的理由我逐条核实过，成立

你的核心论据是「连接器状态只在进程内存里」。核实：

```java
// ChainConnector:73-78
private final Map<String, PaymentStatus> pendingPayments = new ConcurrentHashMap<>();
private final Map<String, String> txHashMap = new ConcurrentHashMap<>();
private final Map<String, String> payeeHashMap = new ConcurrentHashMap<>();
private final Map<String, String> payerHashMap = new ConcurrentHashMap<>();
```

四个状态容器**全是进程内 `ConcurrentHashMap`**，没有 Repository。

⇒ 重启后 `queryPayment` 查不到这笔 ⇒ 编排侧可能把它判 `FAILED`；
若此时后台推进器又把这笔执行了，就是**"编排记 FAILED、链上钱已走"**——
正是裁决 §六 禁止的形态。**所以把这类支付排除在后台推进之外，是正确判断，不是保守过度。**

而且你把**代价写出来了**（"无人再查询就停在待审批，可人工执行"），
这一点比只写"已排除"有价值得多。

## 二、但排除之后，这批链接**没有任何地方能看到**

`ApprovalExecutionReconciler:87`：

```java
findTop100ByRequestTypeInAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
        requestTypes, ..., ...)      // ← requestTypes 就是白名单
```

⇒ 被排除的 `CHAIN` / `CONSORTIUM` 支付**连 `waiting=` 都不计入**。

再查 `ApprovalExecutionLinkRepository`（master 上全文 30 行），**只有两个方法**：

```java
Optional<ApprovalExecutionLink> findByRequestKey(String requestKey);                       // :15
List<ApprovalExecutionLink> findTop100ByRequestTypeInAndStatusInAndUpdatedAtBefore...;    // :29
```

⇒ **根本没有按状态查全量的方法**，唯一的批量查询本身就带 `requestTypeIn`。
而且该仓储只被 3 个类使用（自身 / 推进器 / `SigningApprovalExecutionService`），
**没有任何 metric、没有 endpoint、没有健康检查**读这张表。

所以不是被排除的类型恰好看不到，而是**整张表除了推进器那条按类型过滤的查询之外，
没有任何读取入口**。

于是形成这个局面：

| 情形 | 现状 |
|---|---|
| 审批通过、无人查询该笔支付 | 链接停在 `AWAITING_APPROVAL`，**无人知晓** |
| 某商户的钱一直没出去 | 只能靠商户来投诉 |

「安全但不被发现」在资金链路上不算完整——**"没动钱"和"该动没动"都要能被看见**。

### 最小改法：把"盘点"从"推进"里拆出来

不需要把连接器支付放进推进白名单（那会重新引入你避免的风险）。
只需要在推进器里加一段**只读**的盘点：

```java
// 只读，不执行；与白名单无关，覆盖全部 requestType
Map<String, Long> stock = repository.countByStatusInGroupByRequestType(
        List.of(Status.AWAITING_APPROVAL, Status.EXECUTING, Status.EXECUTED_UNVERIFIED));
stock.forEach((type, n) -> meterRegistry.gauge("nexus.approval.link.stock",
        Tags.of("request_type", type, "status", ...), n));
```

要点：**读与写分离**。盘点覆盖全量（含被排除的类型），推进仍只走白名单。
这样"该动没动"就变成一个可告警的数字，而不是等商户投诉。

如果不想引入 MeterRegistry，退一步也行：**日志里把被排除类型的存量打出来**，
但要带"最久一条距今多久"，否则一堆数字仍然不会有人看。

## 三、`manual` 只记 `info` —— 这一条我建议改

`ApprovalExecutionReconciler:121`：

```java
log.info("审批执行腿推进完成: candidates={}, executed={}, failed={}, manual={}, waiting={}", ...);
```

`manual` 是 `EXECUTED_UNVERIFIED`——**链上可能已经动钱、但我们拿不到哈希核对不了**。
这是整条链路上最需要人立刻看的一类结果，却和"推进完成"一起躺在 info 里。

建议：
- `manual > 0` 时单独打一条 **WARN**（带 `requestKey` 列表，`ApprovalExecutionReconciler:103`
  已经有 `log.error` 逐条记录，但那条是 error 且可能被淹没在同类里）；
- 同时出一个计数器（`manual` 数量），让它可告警。

理由：`failed`（确定未动账）可以等；`manual`（可能已动账）不能等。
把两者放同一个 info 行里，等于让最急的信号和最不急的信号同一个优先级。

## 四、两处我认可的设计，记一下

1. **`2001 但缺 approvalId = FAILED`** —— 这个边界判得对。
   一个**无法跟踪的审批**不能当"待审批"，否则会永久停在没人能推进的状态。
   这与"未知 ≠ 成功"是同一条原则的两面。
2. **`txHash()` 标 `@Deprecated` 且 javadoc 写明"它在 2001 上返回 null，正是断链根因"** ——
   把缺陷原因写进废弃注解，比只写"请用新方法"有用，后来人不会再踩。

---

## 五、边界

**已核**（逐行读过）：
- `ChainConnector:73-78` 四个状态容器均为进程内 `ConcurrentHashMap`，无 Repository
- `ApprovalExecutionReconciler:83/87/103/110/117/121` 的调度、白名单过滤、日志级别
- `ApprovalExecutionLinkRepository` 全部使用点（3 个类），确认无 metric / endpoint
- `ApprovalExecutionLink` 的字段，以及仓储只有 2 个方法（无全量查询）这一事实

**未核**：
- `ApprovalExecutionReconcilerTest` 的 7 例断言细节（只看了你描述的行为）
- #232（结算侧 `SettlementApprovalReconciler`）是否已有类似的盘点/指标——那是独立 PR，
  我没有逐行读，若已有可复用，§二 的建议应改为"合并到同一处"
- 是否有外部监控（Prometheus 规则 / 告警规则文件）已经在扫日志——我没查告警规则配置

**未动**：未改任何代码、未改他人分支或 PR。
