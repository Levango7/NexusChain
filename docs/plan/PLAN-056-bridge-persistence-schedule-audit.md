# 方案 PLAN-056：bridger 持久化装配面盘点（承接 PLAN-055 教训的系统性排查）

- **状态**：已交付（2026-10-28）
- **前置**：PLAN-055（ShedLock primitive 返回 ⇒ relay/event-source 循环
  **从未被调度执行**——第 2 例"单测绿但生产未跑"同类盲区）
- **范围**：nexus-bridge 全部 `@Scheduled` / `@SchedulerLock` 的**可执行性盘点**
  ——把"哪些循环真的会跑"从"看代码/看单测"变成"能批量判定的一道门禁"
- **不包含**：修任何具体缺陷之外的新功能

---

## 一、问题（为什么这篇比"再修一个 bug"更值钱）

PLAN-055 的根因**单一但系统性**：`@SchedulerLock` 拒绝 primitive 返回。
nexus-bridge 有多个循环（saga 恢复、日限额审计、保险基金检查、消息中继、
事件源），都有单测、都"看起来在工作"，但**没有任何机制能一次判定**
"这个循环在真实 Spring 上下文里到底跑不跑"。

继续人肉盯代价极高——PLAN-055 是联调时才发现的，之前它"绿了很久"。
本切片把盲区变成"提交即拦截"。

## 二、方案

### 2.1 静态盘点脚本（新）

`scripts/security/check-shedlock-eligibility.py`（零依赖 Python）：

- 扫描 `nexus-bridge/src/main/java` 中所有 `@SchedulerLock` 方法，
  判定返回类型：primitive ⇒ **FAIL**；void/包装/对象 ⇒ PASS；
- 退出码 0/1，CI 可直接用；`--json` 供后续仓级体检。

### 2.2 JUnit 守卫（新）

`nexus-bridge/src/test/java/org/nexus/bridge/ShedLockEligibilityTest.java`：
调脚本 + 断言零 primitive + 盘点数 = 4（**防移码假绿**：规则失灵时
"扫到 0 个"也会 PASS，故把已知数量钉死）。

### 2.3 CI workflow（新）

`.github/workflows/schedule-contract.yml`：`Bridge Schedule Contract`，
PR/push 均跑，ubuntu-24.04 + Python 3.14，秒级；**即使模块测试被跳过也能拦**。

## 三、盘点结果：本切片当场抓到**第 3 处**

首次扫描输出 4 个 `@SchedulerLock` 方法，其中：

| 方法 | 返回 | 状态 |
|---|---|---|
| `BridgeEventSourceWorker.runOnce()` | `Integer` | PASS（PLAN-055 已修） |
| `MessageRelayWorker.runOnce()` | `Integer` | PASS（PLAN-055 已修） |
| `FundConservationChecker.checkConservation()` | `void` | PASS |
| **`BridgeSagaCoordinator.recoverIncompleteSagas()`** | **`int`** | **FAIL ⇒ 本切片已修** |

`recoverIncompleteSagas` 是 **crash 恢复循环**（把非终态 saga 标 FAIL）——
它同样从未被调度执行 ⇒ 崩溃恢复实际是死的。改为 `Integer`（语义零变化）。

## 四、方法论固化

PLAN-055 的缺陷在**单测全绿**的情况下存活了整个装配周期，根因：
单测 `new` + 手工调方法**绕开 Spring AOP**。
⇒ **任何"调度/锁/事务"类代码，签名契约必须有一道不看 Spring 上下文
也能判定的门禁**。本切片交付三道（脚本 / JUnit / workflow）。

## 五、边界

- **静态扫描 ≠ 行为证明**：守卫证明"签名不会让 ShedLock 拒绝"，
  不证明"循环逻辑正确"（后者仍需联调）；
- 只覆盖 nexus-bridge 的 `@SchedulerLock`；其它模块的 `@Scheduled`
  （无锁）不存在同类拒绝；
- 脚本基于文本/正则，漏判方向是 PASS（需 REVIEW 纪律兜底）。

## 六、后续

- 仓级"调度方法体检"（含 `@Scheduled` 清单化、所有模块）；
- nexus-bridge 其它 `@Scheduled` 循环（日限额审计、保险基金）的行为验证
  ——按本切片的方法论走"真起应用"或"签名契约"两条之一。
