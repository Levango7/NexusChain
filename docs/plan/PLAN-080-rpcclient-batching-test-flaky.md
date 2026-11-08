# PLAN-080：REST 形态 P2P 合批测试时序抖动去时序化（含折叠覆盖加强）

> 切片归属：nexus lane（consensus / finality / 指标门禁）。
> 触发来源：qoder CN 板务通知 `20261106-0022-qoder-cn-notice-core-batching-timing-flaky`
> （`to: [nex]`，「处置权与实现归 nexus-core lane」）。
> 认领留言：`20261107-0102-nex-claim-rpcclient-batching-flaky`（PR #553）。

## 一、现象（原始证据，来自 CI 工件）

| 项 | 值 |
|---|---|
| run | `41997420158`（主干 tip `e7af302f` = #506） |
| job | `Build & Test (JDK 17)` = `127153995317` |
| 用例 | `RPCClientBatchingTest > 重复交易在合批后按交易哈希折叠，只广播一次` |
| 异常 | `java.lang.ArrayIndexOutOfBoundsException at RPCClientBatchingTest.java:138` |
| 规模 | `1884 tests completed, 1 failed, 19 skipped` |

#506 只改了 `.github/workflows/security-scan.yml`（+14 行），不触达 Java 代码；
相邻 5 个主干 sha 同用例均绿 ⇒ 不是内容回归。

## 二、根因复核：两处**独立**的时序竞态

### 竞态 1：窗口跨期（原留言已列）

`duplicateTransactionsAreDeduplicated` 用 80ms 窗口 + 背靠背两次 `broadcastTransactions`。
两次调用若跨过窗口边界 ⇒ 两次 flush ⇒ 「只广播一次」不成立。

### 竞态 2：Mockito 登记早于 answer 体（原留言未列，本切片实证）

原写法：

```java
verify(client, timeout(3000).times(1)).broadcast(eq("/consensus/transactions"), any());
assertThat(transactionsOf(broadcasts.get(0))).as("同一笔不应重复上线").hasSize(1);
```

Mockito 在 `doAnswer` 体执行完**之前**就登记了 invocation ⇒ `verify` 返回时
`broadcasts` 仍可能是空的 ⇒ `broadcasts.get(0)` 抛 `ArrayIndexOutOfBoundsException`。

**本地探针实测**（探针源码与 JUnit XML 留在仓库外 `C:\Users\winge\AppData\Local\Temp\opencode\p080\`，
不入库）：`spy` + `doAnswer`（体内先 sleep 300ms 再写列表）+ `verify(timeout(3000).times(1))`

```
PROBE_SIZE_AT_VERIFY_RETURN=0
PROBE_VERIFY_RETURNED_BEFORE_ANSWER_BODY=true
PROBE_SIZE_AFTER_JOIN=1
```

⇒ 原日志的 AIOOBE 有确定机制；而「窗口跨期」只会得到 `TooManyActualInvocations`，
两者是**不同的失败形态**。只按「放大窗口」处理，压不掉 AIOOBE 那一支。

## 三、改动（只改测试，不动生产代码）

文件：`nexus-core/nexus-core/src/test/java/org/nexus/controller/RPCClientBatchingTest.java`

1. **等待改为信号量**：新增 `CountDownLatch broadcastLatch`，在 `doAnswer` 体内
   `countDown()` ⇒ `awaitBroadcast()` 返回时广播记录必定已写入列表；
   `verify(timeout(...))` 全部换成 `verify(after(300).times(1))`（等待期后精确断言「恰好一次」）。
2. **窗口放大**：窗口语义用例 80ms → 2000ms（与同文件 `singleTransactionWaitsForWindow`
   既有取值一致）。
3. **折叠用例去时序化**：冲刷由「合批上限 = 2」这一**计数**条件确定性触发，
   两次调用必然落在同一批 ⇒ 不再依赖「两次调用落在同一 80ms 窗口内」。
4. **加强折叠覆盖**：两次改传**同内容、不同实例**的交易（生产重试/重复投递的真实形状），
   并显式断言「同内容同哈希」。原用例两次传的是**同一实例** ⇒ 任何「按对象身份去重」的
   实现也会通过，盖不住回归。

未做（明确不做）：Gradle `retry`、可注入 Ticker、删断言、`Thread.sleep` 掩盖、CI 配置改动。

## 四、验证（JDK 17，本地 nexus-core 单类）

| 项 | 结果 |
|---|---|
| 基线 | 7 用例全绿（`tests="7" failures="0" errors="0"`） |
| 压测 | 折叠用例临时 `@RepeatedTest(20)` ⇒ 26 用例全绿（仅本地，未入库） |
| **反向对照** | 生产侧去重键改成实例身份（`hash + identityHashCode`）⇒ 用例**精确红**在 `[同一笔不应重复上线] Expected size: 1 but was: 2`（红在目标断言上，不是超时） |
| 还原 | 还原生产文件后 `--rerun-tasks` 强制重跑 ⇒ 7/7 绿 |

复现命令（仓库根目录）：

```powershell
$env:JAVA_HOME="E:\dev-tools\jdk17.0.20_8"
.\gradlew.bat :nexus-core:nexus-core:test --tests "org.nexus.controller.RPCClientBatchingTest" --rerun-tasks --no-daemon
```

## 五、边界与剩余风险

1. **只改测试**；生产代码 `RPCClient` / `flushPending` 零改动（去重键仍为交易哈希）。
2. 未改 `TransactionHandlerBatchingTest`（gRPC 形态）——它不在本条报告范围内：
   它用 `CountDownLatch` 等待（无 AIOOBE 那一支），但 80ms/60ms 窗口仍有
   「调用跨窗口」的同型风险，**本切片未处理**，留作独立事项。
3. 未加 retry、未降级断言、未动 CI 配置；未宣称「已消除全部 flake」。
4. 未触碰 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支 / PR。
5. 本地验证只覆盖 nexus-core 该测试类；全量 `check`（含覆盖率门禁）以 CI 为准。
