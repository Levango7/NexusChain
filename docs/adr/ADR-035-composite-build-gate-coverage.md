# ADR-035: composite build 门禁覆盖——SpotBugs / JaCoCo / 测试聚合对 includeBuild 模块的真实化

- **状态**：Accepted（已执行，2026-10-18）
- **日期**：2026-10-18
- **触发**：工程评审批次⑦（见 `docs/audit/2026-10-18-eng-review-and-remediation-plan.md` §4/§6）
- **范围**：根 `build.gradle` 聚合任务、`gradle/composite-spotbugs.gradle`（新增共享脚本）、6 个 composite 模块 `build.gradle`、`.github/workflows/ci.yml`、`.github/workflows/security-scan.yml`、`spotbugs-exclude-filter.xml`

---

## 1. 背景与问题

以下 6 个构建通过 `settings.gradle` 的 `includeBuild` 以 composite build 方式接入根构建：

`nexus-consortium`（多子项目：consortium / common / crypto）、`nexus-settlement`、`nexus-compliance`、`nexus-analytics`、`nexus-oracle`、`nexus-api-gateway`。

它们不在根构建 `subprojects` 账下，导致三类门禁**系统性空转**（写而不测）：

| 空转项 | 表现 |
| --- | --- |
| SpotBugs / FindSecBugs | 根 `spotbugsAll` 只遍历 `subprojects`；security-scan 的 SAST gate 扫描全仓 `build/reports/spotbugs/*.xml`，composite 模块从未产出该报告——门禁对其完全盲区 |
| JaCoCo 覆盖率门禁 | compliance / analytics / api-gateway 的 0.50 `jacocoTestCoverageVerification` 已配置，但 CI 只跑 `:test` 未跑 `:check`——校验从未执行；composite 报告生成任务也未在 CI 调用，codecov 覆盖失明 |
| 本地聚合测试 | `testAll` 不含 composite 模块，与 CI 口径不一致（README 此前已诚实标注"不含"） |

**空转的自证**：SpotBugs 接入后首扫即暴露 **21 条存量 findings**（见 §3.2）——证明此前的"全绿"是盲区而非清白。

## 2. 决策

### 2.1 SpotBugs 接入方式：共享脚本插件，逐模块接线

- 新增 `gradle/composite-spotbugs.gradle`，以 `apply from:` 脚本插件形式复用根构建同一套口径：
  `toolVersion 4.8.6` / `effort=max` / `reportLevel=low` / `ignoreFailures=true` / FindSecBugs 1.13.0 / 根 `spotbugs-exclude-filter.xml`；排除 protobuf 生成代码；`spotbugsTest.enabled=false`。
- 新增 composite 模块接入清单（**为后续新模块固定契约**）：
  1. `plugins {}` 声明 `id 'com.github.spotbugs' version '5.1.5'`；
  2. `apply from: "${rootDir}/../gradle/composite-spotbugs.gradle"`；多子项目构建在 `subprojects`/`afterEvaluate` 内**逐子项目** apply，且**必须显式携带项目接收者**（`proj.`，见 §4 陷阱二）；
  3. 根 `build.gradle` 的 `spotbugsAll` / `testAll` 以 `gradle.includedBuild('name').task(':module:tasks')`（相对该构建根的路径）增加引用；
  4. CI：SAST 依赖 `spotbugsAll` 聚合（无需额外步骤）；`:check` 翻闸与 JaCoCo 任务/R报告路径加入 ci.yml 对应步骤；
  5. 新增豁免只允许进根 `spotbugs-exclude-filter.xml`，且必须收窄到具体类/方法 + 论证（延续 `.trivyignore` 同策略）。
- **门禁失败判定保持单一**：`ignoreFailures=true`（与根构建逐项一致）——报告永远生成，失败由 security-scan 的 SAST gate（统计全部报告 `total>0` 即 exit 1）裁决，避免"分析中断 → 无报告 → gate 空转"的第二类盲区。

### 2.2 聚合任务与 CI 真实化

- `spotbugsAll` / `testAll` 扩展：以 `gradle.includedBuild().task()` 引用 6 个构建的 `spotbugsMain` / `check`（consortium 三子项目逐一声明）。
- ci.yml：composite 模块由 `:test` 翻为 **`:check`**（测试 + 覆盖率校验）；新增"生成 JaCoCo 覆盖率报告（composite 模块）"步骤（8 个 `jacocoTestReport` 任务）；codecov `files:` 扩展至 composite 报告路径；artifact 递归 glob 自然覆盖 composite 报告。
- security-scan.yml：SAST job `timeout-minutes` 15→25（新增 composite 编译+扫描时长；本机全量实测约 6 分钟量级）。

### 2.3 明确不做（本轮）

- **buildSrc / version catalog 重构暂缓**：全仓 19 个 `build.gradle`、显式版本声明 324 处（spring boot 插件 4.0.8 重复 10 处）；收益（单点升级）对比风险（19 文件机械化改写 + 6 个 composite 独立构建根各自插件解析不一致）后，**记账为独立批次候选**，评审收口期不引入大面积构建改写。

## 3. 验证证据（2026-10-18 当场复跑）

### 3.1 门禁真实化
- 五个 composite 单模块 + consortium 三子项目 `:check` 本机实测 **BUILD SUCCESSFUL（3m53s）**，jacoco XML 五份落盘（46KB–273KB，含 compliance/analytics/api-gateway 的 0.50 门禁真实执行）；
- `spotbugsAll`（根 + composite 全量）夹具实测 **BUILD SUCCESSFUL（5m57s）**，8 份 composite 报告全部生成。

### 3.2 首扫 21 条 findings 的处置（全部就地闭环）

**代码修复 10 条（真缺陷）**：

| 类别 | 位置 | 修复 |
| --- | --- | --- |
| `HE_EQUALS_USE_HASHCODE` | `org.nexus.common.HexBytes` | 补 `hashCode()`（`Arrays.hashCode(bytes)`），修复 equals/hashCode 契约破损 |
| `UR_UNINIT_READ_CALLED_FROM_SUPER_CONSTRUCTOR` ×2 | `ChainCache` → `ChainCacheWrapper` | 基类构造器改调私有 `doPut/doPutAll`（消除构造期对可覆写 `put` 的虚分派——否则子类锁字段未初始化必然 NPE）；`evict()` 内移除改直连私有 `remove(String)` |
| `VA_PRIMITIVE_ARRAY_PASSED_TO_OBJECT_VARARG` | `InMemoryStateTree.get` | 以 `Arrays.equals(root.getHash().getBytes(), where)` 替代 `equals(new HexBytes(where))`（语义等价且免分配） |
| `RV_ABSOLUTE_VALUE_OF_RANDOM_INT` | `PeersCache.getPeers` | `Math.abs(rand.nextInt()) % n` → `rand.nextInt(n)`（消除 `Integer.MIN_VALUE` 负索引 + 取模偏置） |
| `ICAST_INTEGER_MULTIPLY_CAST_TO_LONG` | `TransactionPool.get` | `skip(page * size)` → `skip((long) page * size)`（int 溢出） |
| `OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE` | `TestCommunicationTool.getBytesByFile` | 改 try-with-resources（异常路径泄漏） |
| `RR_NOT_CHECKED` | `FileSNAllocator.readSN` | 校验 `raf.read` 返回值，短读抛 `IOException` |
| `DC_DOUBLECHECK` + `IS2_INCONSISTENT_SYNC` | `AggregatedPriceOracle.subscriptionScheduler` | 字段加 `volatile`（Java 5+ 双检锁唯一正确写法） |

**收窄豁免 11 条（设计语义/误报，逐条记入 `spotbugs-exclude-filter.xml`）**：
`CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE` ×6（本仓自定义 `Cloneable<T>` 约定接口，非 `java.lang.Cloneable` 语义）；`BC_VACUOUS_INSTANCEOF`（`Start.consensusEngine` 为未来共识引擎预留的注入守卫）；`UNENCRYPTED_SOCKET` ×2（`net.Util` 节点连通性探测工具，传输加密属部署层）；`CIPHER_INTEGRITY` ×2（`AES256CTR` 即套件实现本身，CTR 无完整性属算法语义，无生产调用点）。

## 4. 已知陷阱（Gradle composite build，已实证，供后续接线参考）

1. **`--dry-run` 对 `includeBuild` 任务不生效**：会真实执行 composite 任务（spotbugsAll 的 "dry-run" 实跑了 8 个 spotbugsMain）；判断任务图真伪须查磁盘产物或任务输出。
2. **嵌套闭包内 `apply from:`/依赖声明的隐式接收者会沿 owner 链落到构建根项目**（无 java 插件）→ 报 `Could not find method compileOnly()`；修复 = 显式 `proj.` 接收者 + 闭包参数捕获。
3. **includedBuild 任务引用语法**：`gradle.includedBuild('name').task(':相对构建根的路径')`；CI/CLI 形如 `:nexus-consortium:consortium:check`。

## 5. 后续约束

- 任何**新增 composite build 模块**必须按 §2.1 清单接入并通过 `spotbugsAll` 0 findings 方可合入；
- 新增豁免一律收窄 + 论证，禁止宽 pattern 豁免（延续本文与 `.trivyignore` 策略）；
- buildSrc / version catalog 重构作为独立批次候选另行评估（§2.3）。
