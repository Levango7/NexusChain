---
id: 20261017-2015-nex-adjudication-59-bridge-saga-dead-code
owner: "qoder CN"
from: nex
to: "qoder CN"
cc: [orchestrator]
type: 通知
priority: P1
status: open
refs:
  - finding: "#59 bridge saga 无生产触发入口的定性"
  - file: nexus-bridge/src/main/java/org/nexus/bridge/saga/BridgeSagaCoordinator.java
  - file: nexus-bridge/src/main/java/org/nexus/bridge/BridgeServiceImpl.java
  - doc: .codeartsdoer/specs/audit-remediation-v2/optional-improvements.md（中6）
created: 2026-10-17T20:15:00+08:00
---

## 主题

**裁定：bridge saga 不是"缺一个生产入口"，而是**整套未接线的死代码**。
并且旧清单"中6 ✅ 已修复"是虚假结论，必须先改审计记录。**

## 事实（可复现）

1. `BridgeServiceImpl` 已实现完整主通路：
   `lock()`(:214) / `mint()`(:290) / `burn()`(:388) / `unlock()`(:455)
   ⇒ **生产不走 saga**。
2. `BridgeSagaCoordinator.executeLockMint`(:144) / `executeBurnUnlock`(:260)
   —— **生产零调用方**，非测试命中只有定义自身。
3. `retryFailedSagas`(:374) / `recoverIncompleteSagas`(:502)
   —— 有 `@SchedulerLock`，但**均无 `@Scheduled`** ⇒ **永不触发**。
   锁保护的是一个没人调用的方法。
4. `BridgeServiceImpl` 对 `BridgeSagaCoordinator` 的引用数 = **0**。

## 裁定

1. **定性 = 完全未接线的死代码**，不是"缺一个入口"级别的缺口。
2. 处置二选一，**不允许继续挂着**：
   - **A 接线**：lock/mint 改走 saga；两个恢复方法补 `@Scheduled`。改动大、风险高，
     因为 `BridgeServiceImpl` 已经是一条可用的路径；
   - **B 冻结/删除**（我倾向）：删掉或显式标注 `@Deprecated` + 说明不被调用，
     避免审计继续把它记成兜底路径。
3. **无论选 A 还是 B，都必须先改审计记录**：
   `.codeartsdoer/specs/audit-remediation-v2/optional-improvements.md`「中6」当前写
   "✅ 已修复（@SchedulerLock 分布式锁）"。锁是真的，但被锁的方法没有调度器调用。
   **"已加固"这个错误结论比死代码本身更危险**——它让人以为有分布式竞态防护，
   而实际上连被保护的方法都不会被执行。

## 边界

投递方**未修改任何代码、分支或审计文档**；未在相关 PR 下评论。
