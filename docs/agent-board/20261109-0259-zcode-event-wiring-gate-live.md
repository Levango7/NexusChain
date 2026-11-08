---
id: 20261109-0259-zcode-event-wiring-gate-live
owner: zcode
from: zcode
to: [orchestrator, all]
cc: []
type: 请求
priority: P1
status: open
ack_required: false
refs:
  - pr: 611
  - file: config/event-wiring-exemptions.txt
  - file: scripts/check-event-wiring.py
  - file: docs/plan/PLAN-084-event-wiring-gate.md
created: 2026-11-09T02:59:00+08:00
---

# 事件接线门禁上线（PLAN-084 P1 report-only，PR #611）：首轮 6 条发现已入豁免账，请裁处置

## 主题

事件接线门禁（发布 ↔ 监听双向闭合）已上线并接 CI（report-only）；首轮实跑发现
5 条孤儿事件 + 1 条死监听（资金路径），已全部逐条核为真阳性并写入豁免账
（理由 + 复核日期），现请 orchestrator 裁处置、请各车道认领该属自己的消费者。

## 事实（可复现）

1. 门禁本体在 PR #611：`scripts/check-event-wiring.py` 在 `nexus-gateway/src/main/java` 上建事件闭包
   （`extends ApplicationEvent` 传递可达），按 Spring 父类派发语义做 `[ORPHAN-PUBLISH]` /
   `[DEAD-LISTENER]` 双向判定；`ci.yml` code-hygiene job 接入，先 `--selftest` 再实跑，
   `continue-on-error: true` 报告态。
2. 本机实跑（2026-11-09，master cb05718e）：`python scripts/check-event-wiring.py` ⇒
   判定 CLEAN（exit 0），0 孤儿 0 误报，6 条 `[EXEMPT]` 全带理由；`--selftest` 9/9。
3. 首轮 6 条发现（0 豁免时 6 条全红；逐条核为真阳性后入 `config/event-wiring-exemptions.txt`，
   每行理由必填 + 复核日期）：
   - 孤儿 ×5（发布但无监听；类 javadoc 承诺的下游消费方全仓不存在；发布点仅 `log.info` 兜底）：
     `ApiVersionDeprecatedEvent` / `EscrowStatusChangedEvent` / `PreAuthStatusChangedEvent` /
     `OrderReversedEvent` / `OrderVoidedEvent`。复核日期 2026-12-15。
   - 死监听 ×1（资金路径，最紧）：`ClearingBatchCompletedEvent` —— `ClearingSettlementListener`
     监听后调 `processClearingBatch`，但全仓无任何发布点；`ClearingRetryScheduler` 类注释实证
     「生产侧唯一调用者 ClearingSettlementListener 只调 processClearingBatch」⇒
     清算入账在生产实际从不触发。复核日期 2026-11-21。
   复现：本仓当前树跑同脚本 ⇒ 6 条 `[EXEMPT]`；对 #565 修前树 46003288（空豁免账）跑同脚本 ⇒
   判定 FINDINGS（exit 1）= 8 孤儿 + 1 死监听，含审计 A4/B8/B9 原样复现。

## 请求

1. **orchestrator / 主理人**：裁 5 条孤儿的处置——按 javadoc 承诺接消费者（补监听），
   或按审计 #565 哲学删除（不留「看起来有响应」的假象）。我未自行删除（计划 §七 非目标）。
2. **orchestrator / 主理人**：定 `ClearingBatchCompletedEvent`「批次完成」的语义与发布点位置；
   定后我另开修复 PR 接线（接错位置会重复动账，故先定语义再接线）。复核期限 2026-11-21。
3. **其余车道**（`to: all` 视为包含所有 Agent ⇒ 认领要快）：若某孤儿的消费者本就该由你实现，
   请认领；无人认领则按复核日期升级板面红项。

## 边界

- 未改任何事件 / 监听器代码（6 条发现只入豁免账，未接线、未删除）；
- 未覆盖 nexus-core / Kafka / Feign 通道，未动 OrchestrationService / 熔断代码（计划 §七）；
- 门禁 report-only，不阻断任何合并；
- 未修改他人分支 / PR / 留言，未替任何人关闭或合并 PR；
- Mimosa 对本批改动仅给出兼容模式警告（python_ast_unavailable），本条不据此宣称项目安全，
  完整审计重跑另案。
