# 方案 PLAN-059：ShedLock 守卫扩到全仓（PLAN-056 覆盖面收口）

- **状态**：已交付（2026-10-31）；`scripts/security/check-shedlock-eligibility.py`
  缺省改为仓库根 + 跳过测试源树 + 过滤注释伪注解；`schedule-contract.yml`
  两处调用同步扩到 `--path .`
- **前置**：PLAN-055（ShedLock 4.x 拒绝 primitive 返回 ⇒ 两个 relay worker
  自装配起从未执行）、PLAN-056（固化为脚本 + JUnit + workflow 三道门禁，
  并就地抓出第 3 处 `BridgeSagaCoordinator.recoverIncompleteSagas()`）
- **范围**：只扩门禁覆盖面，不改任何被守卫的生产代码
- **不包含**：新增/修改 `@SchedulerLock` 方法；验证人集签名化；real-L1

---

## 一、缺口（PLAN-056 落地时的口子）

PLAN-056 的三道门禁都只覆盖 **nexus-bridge**：

| 层 | 覆盖面 | 说明 |
|---|---|---|
| workflow | `--path nexus-bridge/src/main/java` | `schedule-contract.yml` 两处调用都硬传 |
| 脚本缺省 | 同左 | `--path` 缺省值就是 bridge |
| JUnit | `nexus-bridge` 模块内 | 只扫本模块源 |

而全仓实测（本地脚本全仓扫描）：

```
nexus-bridge      4 处
nexus-gateway    29 处
TOTAL            33 处
```

⇒ **gateway 的 29 处 `@SchedulerLock` 从未被任何一道门禁看过**。
若有人在 gateway 新写一个 primitive 返回的调度方法，本地 gradle test、
CI workflow 都不会红——正是 PLAN-055 那个"从未执行"缺陷的温床。

## 二、顺带修掉的两个噪声源

扩到全仓时这两个立刻显形：

1. **注释伪注解**：`BillingCycleScheduler.java` / `SubscriptionProperties.java`
   的 javadoc 里写 `@SchedulerLock(lockAtMostFor = "PT30M")`，旧脚本把它当成
   真注解，向后匹配到构造器签名 ⇒ 报出一条
   `PASS: public BillingCycleScheduler()`。不致命，但让"扫描出多少处"
   这个数字失真（33 vs 真实 32）；
2. **测试源树**：全仓扫描会把 `*/src/test/*` 也扫进去。测试里的
   `@SchedulerLock` 不产生生产调度语义，扫它只会带来更多同类噪声。

## 三、改动（三处，全是门禁自身）

| 文件 | 改动 |
|---|---|
| `scripts/security/check-shedlock-eligibility.py` | ① `--path` 缺省改为仓库根（`__file__` 上三层）② `scan_file` 跳过非 `src/main` 的 Java 源 ③ 新增 `strip_comments()`，注释里出现的伪注解不再匹配 |
| `.github/workflows/schedule-contract.yml` | 两处调用 `--path nexus-bridge/src/main/java` → `--path .`，头注释补记覆盖面与原因 |
| `docs/plan/PLAN-059-*.md` | 本方案 |

**不变量**：门禁判据一字未改（primitive 返回 ⇒ FAIL；void/包装/对象 ⇒ PASS），
只改"扫哪些文件"与"什么算注解"。

## 四、验证

| 项 | 结果 |
|---|---|
| 全仓扫描（`--path .`） | **32 处，全部 PASS**，退出码 0 |
| 注释伪注解 | `BillingCycleScheduler` 不再出现在结果里 |
| **反向对照（全仓模式）** | 在 `nexus-gateway/.../KeyRotationScheduler.java` 注入一个 `@SchedulerLock public int probe()` ⇒ 门禁 **FAIL（exit=1）** 并给出 `FAIL: primitive 返回 int negControlProbe()` + 文件行号；移除探针后恢复 exit=0 |
| workflow 语法 | YAML 解析通过，两条 `run` 命令均与本地实测一致 |
| 对生产代码的影响 | **零**（未触碰任何被守卫的类） |

反向对照是关键：扩面前的同一探针在 `--path nexus-bridge` 下**不会**被看到，
正是本方案要堵的口子。

## 五、遗留（照实登记）

- `ShedLockEligibilityTest`（JUnit 层）仍只覆盖 bridge 模块。它的定位是
  "开发者本地 gradle test 能拦"，**全仓静态拦截以 workflow 为准**；
  若要单测也覆盖全仓，需要跨模块源路径依赖，属另一切片；
- 只扫了 `@SchedulerLock`。`SchedulerLock` 的**其它**春问题（如 lock name
  冲突、`lockAtMostFor` 短于扫描耗时）不在本方案范围；
- 与 PLAN-058 的 `WiringAuditTest`（MeterBinder 注册）互补：一个管
  "调度方法签名会不会被 AOP 拒"，一个管"指标类有没有被注册"。
  两者都是 PL-038/055/058 同型缺陷的门禁化。

## 六、方法论（第 5 例同型）

PLAN-038（Bean 重复）→ PLAN-055（ShedLock 签名）→ PLAN-058（@Component
缺失）→ **PLAN-059（门禁覆盖面只覆盖一个模块）**。

前三条是"代码没接线"；这一条是**"守卫本身没接线"**——门禁写对了、
也跑通了，但它守护的范围比它名义上守护的小。可复用问句：

> 这道门禁**实际扫过**的范围，和它**名义守护**的范围，是同一个吗？
> 用一次"注入一个探针"验证，而不是读调用参数。
