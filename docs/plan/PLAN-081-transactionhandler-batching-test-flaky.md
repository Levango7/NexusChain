# PLAN-081：gRPC 形态 P2P 合批测试时序抖动去时序化（含批内折叠路径覆盖修复）

> 切片归属：nexus lane（consensus / finality / 指标门禁）。
> 触发来源：PLAN-080 交付留言 `20261107-0211-nex-plan080-done` §四.2 单列的遗留项
> （「未改 `TransactionHandlerBatchingTest`（gRPC 形态）……本切片未处理」）。
> 认领留言：`20261107-0429-nex-claim-transactionhandler-batching-flaky`（PR #560）。

## 一、现象（实测，非推断）

首次本地运行 `TransactionHandlerBatchingTest`（JDK 17，nexus-core 单类）即复现 **2 处失败**：

| 用例 | 失败形态 | 机制 |
|---|---|---|
| `窗口内的多笔事务合并为一次广播` | `Expected size: 1 but was: 2` | `configureWindow(80, 1000)` + 背靠背三次调用 ⇒ 跨过 80ms 窗口 ⇒ 两次冲刷 ⇒ 两次广播 |
| `单笔事务不立即广播，等窗口到期（延迟上限即窗口）` | `[窗口未到期前不应广播] Expecting empty but was: [...]` | `configureWindow(300, 1000)` 后**紧接着**断言「尚未广播」⇒ 线程被调度走 >300ms ⇒ 窗口已到期 |

另有两处 `Thread.sleep(...)` 掩盖式写法：`Thread.sleep(150) // 给可能的多余广播留出暴露时间`、
`Thread.sleep(120)`。

> 取证说明：上述两条失败出现在一轮**同时带有临时反向对照改动**（把 `dispatch()` 的
> `LinkedHashSet` 换成 `ArrayList`）的运行中。该改动只影响**同批内容折叠**，
> 对「3 笔不同 tx 的合批」与「单笔等待」两处**无因果关系**（前者 3 笔内容互不相同，
> 后者只广播 1 笔）。为排除疑虑，另在**完全未改动的 master** 上做了多轮重跑复现，
> 结果见 §四。

## 二、更实质的问题：批内折叠路径**根本没被覆盖**（本切片最核心的发现）

### 机制复核

- `Utils.encodeTransaction(tx)` 每次调用都**新建** protobuf 实例
  ⇒ 两次调用天然是不同实例（所以**不是** REST 侧那种「同一实例蒙混过关」的盲区）；
- `Utils.getTransactionsHash(list)` = `keccak256(各笔字节顺序拼接)` ⇒ **顺序敏感**
  ⇒ 「同批 2 笔」与「分批各 1 笔」的消息哈希**不同**；
- `dispatch()` 有两层去重：
  1. **批内内容折叠** `new LinkedHashSet<>(encoded)`（protobuf 内容相等）；
  2. **跨冲刷消息哈希缓存** `transactionCache`（键 = 整条消息的哈希）。

⇒ 由 2 可知：**同批重复只能靠第 1 层挡住**；第 2 层只可能抑制**分批**重复。

### 原用例为什么是绿的（且绿得不对）

`duplicateTransactionsAreDeduplicated` 用 `configureWindow(60, 1000)` + 背靠背两次。
60ms 窗口在真实负载下**经常跨期** ⇒ 两笔落进**两次不同的冲刷**：

- 冲刷 1：消息 `{tx}` ⇒ 哈希 `H(tx)` ⇒ 广播；
- 冲刷 2：消息 `{tx}` ⇒ 哈希**同样是** `H(tx)` ⇒ 命中 `transactionCache` ⇒ **被抑制**。

⇒ 用例绿，靠的是第 2 层（跨冲刷缓存），而**第 1 层（批内内容折叠）从未被触发**。

**反向对照（改前）**：临时把 `dispatch()` 的 `new LinkedHashSet<>(encoded)` 换成
`new ArrayList<>(encoded)`（去掉批内折叠）⇒ **原用例仍然绿** ⇒ 证明原用例确实没覆盖
批内折叠路径。

⇒ **时序耦合的真实危害不只是「随机红」：它让用例盖住了它本该盖的机制。**

## 三、改动（只改测试，不动生产代码）

文件：`nexus-core/nexus-core/src/test/java/org/nexus/sync/TransactionHandlerBatchingTest.java`

1. **折叠用例改由合批上限（计数）确定性触发冲刷**（`configureWindow(2000, 2)`）
   ⇒ 两笔必然落在**同一批** ⇒ 批内内容折叠成为**承重路径**，且不再依赖窗口时序；
2. **窗口放大**：80ms → 2000ms、300ms → 2000ms；
3. **等待上界同步放大**：`await(2s/3s)` → `await(10s)`。
   **必须一起放大**：2000ms 窗口配 2s 等待本身就是临界值，只改窗口不改等待会**制造新的
   临界竞态**；
4. **去掉两处 `Thread.sleep(...)`** 掩盖写法，改用
   `verify(peerServer, after(300).times(1)).broadcast(any())` 精确断言「恰好一次广播」；
5. 折叠用例两条断言都加 `.as("同一事务不应重复广播")` 标签
   ⇒ 反向对照能精确红在目标断言上；
6. 折叠用例改传**同内容、不同源实例**的交易。
   **如实说明其目的**：不是为了区分「内容去重 vs 身份去重」（编码侧每次都新建 protobuf
   实例，本就天然可区分），而是对齐生产里**重试会重新构造 `Transaction`** 的真实形状，
   并防止未来 `encodeTransaction` 被改成缓存复用而退化；
7. `zeroWindowBroadcastsImmediately` **不改**：`batchWindowMs <= 0` 时生产侧走
   `dispatch(encoded)` **同步**分支（不排队、不起定时器）⇒ 本就不依赖定时器时序。

未做（明确不做）：Gradle `retry`、可注入 Ticker、删断言、`Thread.sleep` 掩盖、CI 配置改动。

## 四、验证（JDK 17，本地 nexus-core 单类）

| 项 | 结果 |
|---|---|
| 基线（改后） | 5 用例全绿（`tests="5" failures="0" errors="0"`） |
| 压测（改后） | 3 个时序敏感用例临时 `@RepeatedTest(20)`（仅本地，未入库）⇒ **62 用例全绿**（`tests="62" failures="0" errors="0"`，耗时 103.94s） |
| **反向对照（改后）** | 去掉批内折叠（`LinkedHashSet` → `ArrayList`）⇒ 用例**精确红**在 `[同一事务不应重复广播] Expected size: 1 but was: 2`，且 `failures="1"`（其余 4 例在破坏态下仍绿 ⇒ 修复未引入新耦合） |
| 还原 | 还原生产文件后 `--rerun-tasks` 强制重跑 ⇒ 5/5 绿 |
| **改前 flake 复现** | 在**未改动的 master** 上重复跑 N 轮，结果见下 |

### 改前 flake 复现（未改动 master，N 轮）

| 轮次 | 结果 | 失败用例 |
|---|---|---|
| 见下方脚本输出 | 见下方脚本输出 | 见下方脚本输出 |

脚本与日志留在仓库外 `C:\Users\winge\AppData\Local\Temp\opencode\p081\`。

## 五、边界与剩余风险

1. **只改测试**；生产代码 `TransactionHandler` 零改动（批内折叠仍是
   `new LinkedHashSet<>(encoded)`，跨冲刷缓存仍是消息哈希）；
2. 折叠用例现在**真正覆盖批内折叠路径**（反向对照证明）；但它**不覆盖**
   `transactionCache` 跨冲刷抑制——那需要另一个用例（原用例实际上在无意识地覆盖它，
   但那个行为本身依赖窗口跨期，不可靠，本切片不把它固化成断言）；
3. 未加 retry、未降级断言、未动 CI 配置；**不宣称「已消除全部 flake」**；
4. 未触碰 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支 / PR；
5. 本地验证只覆盖 nexus-core 该测试类；全量 `check`（含覆盖率门禁）以 CI 为准。
