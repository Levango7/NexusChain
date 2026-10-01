# OWASP Dependency-Check 版本与覆盖策略（官方 Gradle 插件）

## 概述

本文档定义检查引擎（OWASP Dependency-Check）在本仓库的**引入方式、版本纳管、更新流程
与覆盖度保证**，用于防范供应链攻击风险，并防止「扫描绿了、但其实什么都没扫到」
（空心绿）被误读为安全结论。

## 当前引入方式（2026-10-01 起，PR #17）

| 项目 | 值 |
|------|-----|
| 引入方式 | 官方 Gradle 插件 `org.owasp.dependencycheck`（**不再使用** docker 镜像 / GitHub Action） |
| 版本 | `13.0.0`（钉在根 `build.gradle` 的 `plugins{}`，**不得用动态版本**） |
| 解析源 | Gradle Plugin Portal（`settings.gradle` 的 `pluginManagement` 首项）；注意 **Maven Central 自 11.0.0 起不再发布该插件** |
| 引擎内核 | `dependency-check-core:13.0.0`（由插件 POM 传递） |
| 扫描对象 | 解析后的依赖图坐标（`scanConfigurations = ['runtimeClasspath']`），**不再**是归档文件级识别 |
| 生效条件 | 必须显式传 `-PowaspScan`（理由见下） |
| 执行位置 | `.github/workflows/security-scan.yml` 的 `owasp-dependency-check` job（`workflow_dispatch` / `push master` / 周 cron；**同仓库 PR 也跑** —— 「永远上报」试运行，2026-10-01 ~ 2026-10-15，见下文专节） |
| 阻断阈值 | `failBuildOnCVSS = 9.0f`（CRITICAL 才红） |
| 抑制基线 | `config/dependency-check-suppressions.xml` |
| 报告 | `build/reports/dependency-check/dependency-check-report.{html,json}` |

> ✅ **已关闭的历史缺口**：2026-09-10 ~ 2026-10-01 期间该 job 用
> `docker run owasp/dependency-check:latest` 执行 —— 属**浮动 tag、未钉 digest**，
> 每次运行可能拉到不同引擎版本（门禁结果不可复现）；且它**解析不出 Gradle 依赖图**
> （实测 CPE 命中 0/54，见 `docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md` §10）。
> PR #17 起改为插件路径：**浮动 tag 缺口消失**，引擎版本由 `plugins{}` 中的精确版本号固定。

### 为什么用 `-PowaspScan` 开关，而不是默认生效（刻意的，不是遗漏）

上游插件会把 `dependencyCheckAnalyze` / `dependencyCheckAggregate` 挂到 `check` 任务上。
而本仓库 `ci.yml` 的 **Build & Test** 跑的是 `./gradlew check`，且**没有** `NVD_API_KEY`：
若插件默认 apply，该 job 会被拖去下载约 20 分钟的 NVD 库并失败（每次 PR 时长翻倍且必然红）。

因此根 `build.gradle` 采用 `plugins{} apply false` + `if (project.hasProperty('owaspScan'))` 门控。
**两个失效方向都是响的，不会静默通过**：

- 有人去掉 `-PowaspScan`：DC 任务不存在 → `./gradlew dependencyCheckAggregate` 直接
  「task not found」→ job 立刻红（而不是"跳过扫描却绿"）；
- 有人去掉 `hasProperty` 门控：`./gradlew check` 会拉 NVD → Build & Test 剧慢/变红，立刻可见。

本地实证（`verify-owasp-gradle.ps1`）：

| 断言 | 结果 |
|------|------|
| `./gradlew help`（不给开关） | `exit 0`，任务表中 **dependencyCheck 命中 0 次** |
| `./gradlew dependencyCheckAggregate -PowaspScan --dry-run` | `exit 0`，聚合任务存在（DSL 全键被接受） |
| `./gradlew check --dry-run` | `exit 0`，**dependencyCheck 命中 0 次**（Build & Test 不受影响） |

## 版本引用规则

**强制要求**：插件版本必须是**精确版本号**（如 `13.0.0`），禁止 `latest.release`、`+`、
`[13,)` 等动态/范围版本 —— 与「Action 必须钉 40 位 SHA」同理：门禁引擎自身必须可复现。

```groovy
// ✅ 正确：精确版本
id 'org.owasp.dependencycheck' version '13.0.0' apply false

// ❌ 错误：动态/范围版本（引擎可被上游悄然替换，门禁结果不可复现）
id 'org.owasp.dependencycheck' version 'latest.release'
id 'org.owasp.dependencycheck' version '13.+'
id 'org.owasp.dependencycheck' version '[13,)'
```

**已知未实施项（诚实记录，勿当成已做）**：本仓库**尚未**启用 Gradle 依赖校验
（`git grep verification-metadata` 无命中，CI 也未跑 `--write-verification-metadata`），
因此插件 jar 及其传递依赖的**哈希未被固定**：精确版本号挡住了"版本漂移"，但挡不住
"同一版本被替换产物"。补法与代价见审计 §13 的「未关闭项」。

## 更新频率

- **常规**：每季度检视一次（与依赖升级同批），关注插件 release notes 与引擎 NVD 客户端变更
- **紧急**：上游发布安全修复 / 当前版本被报漏洞 / NVD API 变更导致 CI 红

## 更新审批流程

1. **发起**：改根 `build.gradle` 的 `plugins{}` 版本号，开 PR（base = master）
2. **验证**：PR 必须附以下证据
   - 版本号、发布日期、变更摘要（release notes 链接）
   - 「验证清单」中三项本地断言的**实际输出**
   - 一次 `workflow_dispatch` 的实跑链接（含覆盖度自检输出）
3. **审批**：安全负责人复核并批准
4. **合并**：合并到 master，并**立即**手动 `workflow_dispatch` 一次，确认新引擎真能出结果
5. **记录**：更新本文档「当前引入方式」表 + 审计文档

## 责任人

| 角色 | 职责 | 默认人员 |
|------|------|---------|
| 发起人 | 发起版本更新 PR | 开发工程师 |
| 审核人 | 审核更新安全性与必要性、复核覆盖度证据 | 安全负责人 |
| 执行人 | 合并并跑 `workflow_dispatch` 复验 | DevOps 工程师 |

## 验证清单

更新版本（或改动 DC 接线）时必须逐项确认 —— **每条都要求可复现的证据**：

- [ ] 插件来自 Gradle Plugin Portal 的官方 `org.owasp.dependencycheck`
- [ ] 为精确版本（非动态/范围）
- [ ] `./gradlew help` → `exit 0`，且任务表中 **dependencyCheck 命中 0 次**
- [ ] `./gradlew dependencyCheckAggregate -PowaspScan --dry-run` → `exit 0`
- [ ] `./gradlew check --dry-run` → `exit 0`，且 **dependencyCheck 命中 0 次**（Build & Test 不被污染）
- [ ] `workflow_dispatch` 实跑：OWASP job `success`
- [ ] **覆盖度自检通过**：条目数 > 0 且「已识别（CPE/漏洞 ID）」> 0
      （2026-10-01 起为 0 时该步骤**会失败**，见下节）
- [ ] artifact `owasp-dependency-check-report` 可下载，HTML + JSON 齐备
- [ ] 本文档已同步更新

### 「永远上报」试运行（TEMP：2026-10-01 ~ 2026-10-15）

**为什么试**：本 job 原本只在 `push master` / 周 cron / `workflow_dispatch` 上产生结论 ——
PR 路径下它**不产出任何结论**（`if: github.event_name != 'pull_request'`）。因此它**不能被设为
required check**：会复现审计 §11 的「永不上报」死锁。而「能不能设为 required」这个决定，缺的
正是 **PR 上真跑的成本与稳定性数据**，不是判断力 —— 所以先试运行，再用数据定去留。

> 已有的事实（PR #18 的 checks 列表实测）：本 job 在 PR 路径下会以 **`skipped` 上报**
> （`OWASP Dependency-Check = COMPLETED/SKIPPED`），**不是「永不上报」** —— §13.6 的担心
> 需要按「skipped 在 branch protection 下算不算通过」重新实测，不能预设结论（见评估项 5）。

**试运行期间的触发矩阵**

| 触发 | 是否扫描 | checks 列表里的表现 |
|------|----------|---------------------|
| 同仓库 PR（head 在 `Levango7/NexusChain`） | ✅ 真跑全量（依赖图 + NVD） | 真实结论（绿/红都可见） |
| fork PR | ❌ 跳过（无 `NVD_API_KEY` secret） | `skipped`（如实显示，**不伪装成绿**） |
| `push master` / 周 cron / `workflow_dispatch` | ✅ 真跑（与以前一致） | 真实结论 |

**成本（2026-10-01 实测）**：冷启动 **46m05s**（独占）~ **3h03m**（与 master 侧扫描并发抢
NVD 配额）。因此试运行**必须**配套「缓存 NVD 漏洞库目录」两步（`~/.gradle/dependency-check-data`，
key 按 ISO 周轮换）——命中缓存后是**增量更新**，不是重新全量拉取。

**评估项（2026-10-15 前逐项记录结论）**

| # | 指标 | 通过线 |
|---|------|--------|
| 1 | 同仓库 PR 上 DC job 的墙钟时间（冷/热缓存） | 热缓存 ≤ 10 分钟 |
| 2 | 失败是否可归因（真漏洞 / NVD 侧网络或配额） | 网络类失败偶发，且日志可区分 |
| 3 | 缓存命中与体积 | 未把仓库 10GB 缓存挤满 |
| 4 | 与 push/cron 运行的 NVD 配额互相挤占 | 不出现「两份都退化成小时级」 |
| 5 | fork PR 的 `skipped` 在 branch protection 下是否阻合并 | **必须实测**（不预设） |

**两条出口（评估后二选一，并把结论写回本文件）**

- **(a) 保留**：去掉 TEMP 标记，并把本 job 加入 required checks —— 这才是「永远上报」的目的
  （在 PR 阶段就拦住 SCA 回归）。加入前先解决评估项 5（fork PR 的 `skipped` 语义）；
- **(b) 回退**：按 job 注释逐字还原 `if:` 并删掉两步缓存，回到「只在 push/cron 上跑」；
  回退时**必须**在同一处记录「为什么没能成为 required check」，避免下次从头讨论一遍。

**试运行不做的事**：不改 branch protection；不弱化 `failBuildOnCVSS = 9.0f`；不批量豁免。

## NVD API Key 配置（✅ 已完成，job 已恢复阻断语义）

### 现状（2026-10-01，PR #17 切换插件后）

| 项目 | 值 |
|------|-----|
| secret | `NVD_API_KEY`（仓库 Actions secrets，2026-09-30 创建） |
| 注入方式 | workflow 以 `env: NVD_API_KEY` 传入 → 根 `build.gradle` 读该环境变量赋给 `dependencyCheck.nvd.apiKey`（**不经命令行参数**，避免 key 进进程表/日志） |
| 限速 | `nvd.delay = 8000`（毫秒） |
| 数据目录 | `GRADLE_USER_HOME/dependency-check-data`（可被 CI 缓存复用，避免每次重下） |
| job 阻断性 | **阻断**（`continue-on-error` 已于 2026-10-01 摘除） |
| 首次合格证据（docker 路径） | run `36784537663`：22:31:36Z → 22:51:48Z，**20 分 12 秒**，`success`；报告 `scanInfo.dataSource` 含 `NVD API Last Checked = 2026-09-30T22:51:36Z`（engine 13.0.0） |
| 插件路径证据 | run `36803239132`（PR #17 分支 dispatch）—— 实测数字见下节 |

**判定依据（为什么这能证明"key 有效、且确实在用 NVD 库"）**：缺 key 时该 job 约 5~7 秒
即 `exit 1`（下方守卫），而合格运行是**分钟级**且报告里留有 NVD 拉取时间戳 ——
两面互证（不是同名不同源的库）。

该守卫**保留不动**（刻意设计，不是缺陷）：与其静默跳过让人以为扫描过了，不如明确报错
并给出申请地址。

```bash
if [[ -z "$NVD_API_KEY" ]]; then
  echo "::error::缺少 NVD_API_KEY secret——NVD 已强制 API key，匿名无法拉取漏洞库。"
  exit 1
fi
```

### 覆盖度实测：空心绿（历史证据）→ 真覆盖（PR #17 起）

**历史（docker 路径 + 文件级扫描，run `36784537663`）—— "空心绿"的证据，保留备查：**

| 指标 | 实测值 |
|------|--------|
| `dependencies` 条目数 | 54 |
| 构成（`.js` / `.json` / `.jar`） | 37 / 15 / 2 |
| **取得 CPE（`packages`）的条目** | **0 / 54** |
| `vulnerabilities` 条数 | 0 |
| JVM 侧 | 仅 2 个 `gradle-wrapper.jar`；**完全未解析 Gradle 依赖图** |

**那里的含义**：该 job 只做**文件级识别**，没有取得任何可比对 NVD 的包坐标，对依赖漏洞的
**实际检测力为零** —— 它的绿 ≠ 依赖无漏洞。同期 JVM 覆盖完全由 **Trivy 镜像扫描的
`Java (jar)` 层**承担（run `36780102329`：`nexus-core` 镜像「Java (jar) Total: 4 (HIGH: 4)」
由红转绿）。

**现在（插件路径 + `runtimeClasspath` 依赖图，PR #17，run `36803239132` 实测）**：扫描对象换成
**解析后的 GAV 坐标**，条目数 **252**（`.jar 242 / .dll 9 / .json 1`），其中**取得 CPE 的 242 条
（96%）**，报出 **141 条漏洞**（CRITICAL 19 / HIGH 45 / MEDIUM 75 / LOW 2，去重后 13 个 CRITICAL CVE）
—— 对比旧路径 **0 / 54** 条可取 CPE：**这才是可参与 NVD 比对的真覆盖**。

| 指标 | 旧 docker（`36784537663`） | 新插件（`36803239132`） |
|------|---------------------------|------------------------|
| 条目数 | 54 | **252** |
| 取得 CPE | **0** | **242** |
| 漏洞数 | 0 | **141** |
| 覆盖对象 | 仓库文件（37 个 `.js`） | **19 个模块的 runtimeClasspath** |

> **重要**：**首次运行 red 是预期结果，不是迁移失败** —— `failBuildOnCVSS=9.0f` 被 13 个 CRITICAL
> 触发（详见审计 §13.4）。这说明门禁**第一次就抓到了真问题**；整改清单（netty/kotlin 升版、
> `nexus-sdk/java` 补 `ext['tomcat.version']`、quartz 证据化抑制）见审计 §13.5。

#### 模块级 BOM 覆盖的坑（2026-10-01 实证，必须记住）

`io.spring.dependency-management` 的 `ext['tomcat.version']` 覆盖**只在本项目内生效、不跨模块传播**。
仓库里的 tomcat 修复只加在 5 个模块，`nexus-sdk/java`（`java-library`，**不产出镜像**）因此
按自己导入的 Boot BOM 解析回 **11.0.24** —— 而 **Trivy 镜像扫描结构上看不到它**（没有镜像）。
**规则：抬版/覆盖必须逐个模块核查；"+1 个新模块"就意味着"+1 次核查"。**


#### 覆盖度自检的语义（2026-10-01 PR #17 起**收紧**）

`security-scan.yml` 的**「扫描覆盖度自检」**步骤每次运行都把关键数字（条目数 / 已识别 /
含漏洞条目 / `NVD API Last Checked`）打印到日志与 Job Summary，并作如下判定：

| 情形 | 旧（PR #16） | 新（PR #17） |
|------|-------------|-------------|
| 报告缺失，且扫描步骤本身失败/跳过 | `::warning::` | `::warning::`（job 已红，不重复制造噪声） |
| 报告缺失，但扫描步骤自称 `success` | `::warning::` | **`::error::` + 非零退出**（自称成功却没产出报告 = 没真跑） |
| 条目数 = 0 | `::warning::` | **`::error::` + 非零退出**（未解析到任何依赖） |
| 已识别（CPE/漏洞 ID）= 0 | `::warning::` | **`::error::` + 非零退出**（扫描能力回退） |
| 识别率 < 50%（但非 0） | 无 | `::warning::`（能力指标偏低，先不阻断） |
| 正常 | 打印 | 打印 |

**关键区分**：升级为失败的只有**覆盖度**，**不是漏洞数** —— 本步骤不会因为"有漏洞"而失败
（那是 `failBuildOnCVSS = 9.0f` 的职责），它保证的是"绿"绝不可能是"什么都没扫到"。

### 命中 CRITICAL（CVSS≥9）时的处置

`failBuildOnCVSS = 9.0f` 一旦触发，job 变红并阻断 workflow。处置顺序：

1. 取 artifact `owasp-dependency-check-report`（HTML/JSON）定位条目
   （插件路径下报告位于 `build/reports/dependency-check/`）；
2. 能修则升版依赖（最低可用修复版本，不跨大版本）；
3. 确属**误报 / 无利用路径**，再写 `config/dependency-check-suppressions.xml`
   （路径由 `dependencyCheck.suppressionFiles` 显式指定，**不会**自动加载），
   附证据与**到期日**；
4. **不要用 `continue-on-error` 兜底** —— 那等于关闭门禁。

#### 首次真实处置（2026-10-01，PR #18）—— 可直接照抄的判定顺序

run `36803239132` 报出的 13 个 CRITICAL 全部按上表流程处理完，结论分两类：

| 类别 | 判据 | 例（首次处置） |
|------|------|---------------|
| **可修** → 升级 | CVE 描述给出修复版本，且该版本在仓库源（如 repo1）**真实存在** | `netty-all 4.1.115.Final → 4.1.137.Final`（7 条）、`kotlin-stdlib 2.2.21 → 2.4.20`、`tomcat-embed-core 11.0.24 → 11.0.25` |
| **不可修** → 证据化抑制 | CVE 的**受影响制品**与依赖图里的制品**不是同一个** | `quartz-2.3.2` 命中 CVE-2023-39017，但该 CVE 属于 **`quartz-jobs`**（组件 `org.quartz.jobs.ee.jms.SendQueueMessageJob`），依赖图里**没有**该制品 |

**"是不是误报"的判定方法（不要凭感觉）**：把 CVE 描述里的**受影响坐标/组件名**与
**扫描报告里的 `packages` 列表**逐字比对 —— 前者出现在后者的制品牌照里才可用；
否则优先怀疑 **CPE 过度匹配**（NVD 把某一坐标的漏洞挂到了同名的核心制品上）。

**升级时的两条硬约束**

1. **版本必须先核实存在**：写进 `build.gradle` 的版本号要能在仓库源（Maven Central 元数据）
   查到；"看起来像修复版"不等于存在。同一修复线通常有多个补丁版，**优先取 CVE 明示的修复版**
   （让"为什么是这个版本"可追溯），并注意**同 minor 不跨线**；跨模块共享的坐标（如 tomcat）
   还要**与既有模块的目标值一致**，否则同一制品会在依赖图里出现两个版本。
2. **覆盖机制要选对**：`io.spring.dependency-management` 的 `ext['x.version']` **只对应用了该插件的
   项目生效**；只用 `platform(...)` 的模块（如 `nexus-sdk/java`）必须用 Gradle 原生 `constraints{}`；
   **根 `resolutionStrategy force` 对本仓库的 Boot BOM 无效**（已实测两次）。
   → 抬版后**必须**用 `dependencyInsight --configuration runtimeClasspath` 验证**实际解析结果**，
   而不是只看文件里写的那一行（`11.0.24 -> 11.0.25` 这类"被别处拉回"的坑，见审计 §13.5①）。

### 为什么必须配 key

NVD 自 **2023-12** 起强制要求 API key，**匿名访问返回 403**。
限速参数（CLI `--nvdApiDelay` / 插件 `nvd.delay`）只管限速，**无 key 连 403 都过不去**
（run 34541896852 实证：客户端报 `Invalid API Key, length of 0`）。

### 配置步骤

1. 到 <https://nvd.nist.gov/developers/request-an-api-key> 申请（**免费**，
   只需邮箱；个人邮箱即可，无需企业邮箱）
2. 邮件收到 key 后，在仓库 **Settings → Secrets and variables → Actions**
   新建 secret，名称必须是 **`NVD_API_KEY`**（与 workflow 中的 `${{ secrets.NVD_API_KEY }}` 一致）
3. **无需改代码** —— 配好 key 后 job 自动恢复正常运行；
   摘除 `continue-on-error` 恢复阻断语义的动作已于 **2026-10-01** 执行
   （由用户确认后落地，见 `.github/workflows/security-scan.yml` 该 job 头部注释）

### JVM 依赖的真实覆盖来源（2026-10-01 取证）

| 扫描器 | 覆盖 Gradle/JVM 依赖？ | 依据 |
|--------|------------------------|------|
| Trivy **fs**（`trivy-fs-sbom`） | **否** | Trivy 对 Java 只识别 `pom.xml`(Maven) 与 `gradle.lockfile`；本仓库 `git ls-files` 显示**两者均不存在**，且 Trivy **不解析 `build.gradle`**（仓库跟踪 19 个 `build.gradle`，不在其识别范围）。fs 侧实际覆盖的是 `yarn.lock` 等 npm 类锁文件。 |
| Trivy **镜像**（`trivy-image-*`） | **是** | 扫描打包进镜像的 jar，输出 `Java (jar)` 台账；PR #15 的 jackson `2.21.7/3.1.7` 修复即在该层由红转绿（run `36780102329`）。**局限**：只覆盖"会被打进镜像"的依赖，仅测试期/未部署模块的依赖看不到。 |
| **OWASP DC（本 job，插件路径）** | **是（PR #17 起）** | 直接消费 `runtimeClasspath` 的**解析结果（GAV 坐标）**，不再依赖归档文件识别；覆盖所有模块（含未部署模块）的运行时依赖。实测见审计 §13。 |

**结论**：JVM 依赖漏洞现有**两条互补的真门禁** —— Trivy 镜像扫描（部署面，含镜像内其它生态）
+ OWASP DC 插件（依赖图全量面）。此前"未打进镜像的依赖无人覆盖"的缺口，由 PR #17 补上。

### 常见误解（避免走弯路）

- ❌ 「加 `--nvdApiDelay` 就能匿名跑」—— 该参数只控制请求频率，不能绕过鉴权
- ❌ 「用企业邮箱才能申请」—— 个人邮箱即可，免费
- ❌ 「不配 key 就等于没扫描」—— 见上，Trivy 镜像扫描仍在覆盖 JVM 依赖
- ❌ 「Trivy fs 覆盖 Gradle 依赖」—— 仓库无 `gradle.lockfile`/`pom.xml`，
  fs 侧并不覆盖 Gradle；JVM 覆盖在**镜像扫描**与**OWASP DC 插件**两层
- ❌ 「摘了 `continue-on-error` 就是强门禁」—— **阻断语义 ≠ 检测能力**；
  PR #16 之前的 docker 路径正是"阻断但零覆盖"（CPE 命中 0/54）
- ❌ 「换成官方插件就万事大吉」—— 插件只保证"喂给引擎的是依赖图坐标"；
  若 `-PowaspScan` 没生效、或 `scanConfigurations` 配错，照样是零覆盖 ——
  这正是覆盖度自检存在的意义（为 0 现在**直接失败**）
- ❌ 「覆盖度自检失败 = 发现漏洞」—— 相反：它失败说明**扫描能力坏了**（0 条 / 0 已识别）；
  发现漏洞由 `failBuildOnCVSS` 负责，两者不要混为一谈

## 相关文档

- `docs/security-sla.md` — 安全漏洞 SLA 策略
- `.github/workflows/security-scan.yml` — 安全扫描流水线
- `config/dependency-check-suppressions.xml` — 抑制基线（当前为空，策略写在文件头）
- `docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md` §10（空心绿根因）/ §13（本次迁移取证）
- [OWASP Dependency-Check Gradle 插件（官方）](https://github.com/dependency-check/OWASP-Dependency-Check-Gradle-Plugin)
- [NVD API Key 申请](https://nvd.nist.gov/developers/request-an-api-key)