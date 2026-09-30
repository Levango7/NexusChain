# 现状核验记录：CI 门禁约束力与 MPC 默认口径（2026-09-29）

> 触发：用户要求全面检索项目现状。本文只记**有出处**的结论；未确认项单列，不当作结论。
> 方法：直接读代码/工作流 + 子代理并行盘点后由本人复核承重条目（复核推翻过 1 条子代理结论，见 §5）。

## 1. 已修：安全门禁不在 PR 上运行（结构性风险）

核验到的事实：`security-scan.yml` 原先只有 `push: master` + 周一 cron + `workflow_dispatch`
三个触发器，**没有 `pull_request`**。后果是 gitleaks（密钥泄露）、Trivy fs（依赖漏洞）、
SpotBugs/FindSecBugs、cargo-audit 全部只在代码**已合入 master 之后**才可能报红，
对合并决策零约束力。

本次改动（`.github/workflows/security-scan.yml`）：

- 新增 `pull_request: branches: [master]`；
- 为控制 PR 时长，给三个"重"job 加 `if: github.event_name != 'pull_request'`：
  `trivy-docker-scan`（需构建镜像矩阵）、`owasp-dependency-check`、`dast-zap-baseline`（需起栈）。

净效果：**轻且高信号的（gitleaks / SpotBugs / cargo-audit / Trivy fs）在 PR 执行**；
镜像与 DAST 仍在 push/cron。

**已验证**（PR #14，2026-09-29）：checks 结果 13 pass / 5 skipping / 0 fail，
其中 `Gitleaks Secret Scan`、`Cargo Audit`、`SAST - SpotBugs`、`Trivy Filesystem Scan` 在 PR 上运行，
`Trivy Docker Image Scan`、`OWASP Dependency-Check`、`DAST - OWASP ZAP Baseline` 显示 skipping ——
与改动预期一致。

**但"阻断"这个词需要降级为"暴露"**：实测 `gh api repos/Levango7/NexusChain/branches/master/protection`
返回 `Branch not protected (404)`，即 **master 没有开分支保护，仓库内不存在任何 required check**。
所以门禁目前只能在合并**之前**把失败显示出来，**拦不住**合并或直接 push master。
要让它真正有约束力，需要仓库设置层面启用保护并勾选这些 check（非代码可改，需人工操作）。

## 2. 未修，需产品口径决策：MPC 头牌能力默认关闭

- 引擎是真密码学实现（Rust sidecar）：`mpc-engine/Cargo.toml` 依赖
  `multi-party-ecdsa 0.8.1`（KZen GG18/GG20）与 `cggmp21 0.6.3`（features 含 `state-machine`）；
  GG20 走真实 `MessageA/MessageB(MtA)`、`DLogProof`（`mpc-engine/src/gg20.rs:22-36`），
  CGGMP21 走真实轮次状态机（`src/cggmp.rs:312,332,359`），份额 AES-GCM 加密落盘
  （`src/persistence.rs`）。全仓 Rust 侧 `todo!/unimplemented!` 检索为空。
- **但两处默认值是关的**：
  - `nexus-signing-service/src/main/resources/application.yml:291`
    `cggmp-enabled: ${NEX_MPC_ENGINE_CGGMP_ENABLED:false}`
  - 同文件 `:306` `real-grpc-enabled: ${NEX_MPC_TRANSPORT_GRPC:false}`，
    由 `.../mpc/GrpcMpcTransportStub.java:124` 回落到 `InMemoryMpcTransport()`。
- 只有 `deploy/helm/values-prod.yaml:126` 将 CGGMP 置 `"true"`。

含义（这是事实陈述，不是缺陷判定）：**除 prod 之外，默认/dev/staging 路径不走 CGGMP21
原生 t-of-n，P2P 份额传输走进程内存兜底**。

未决策点：这是否是有意分层（prod 才要真阈值签名）？
- 若有意 → 建议在 README/部署文档明示"dev/staging 的 MPC 非真阈值路径"，避免误读为全环境等价；
- 若无意 → 需要把默认改为按 profile 分级并让 CI 覆盖真实路径。

**我没有替这个决定加门禁**：在没有口径前把"prod 必须开 CGGMP"写成 CI 规则，
等于用我的假设替代产品决策。配置漂移校验的既有正主是
`.github/workflows/k8s-sync-check.yml`（PR 触发，比对 `deploy/k8s/` 与 Helm 渲染，
附带 helm lint + kubeconform），若确定要加，应加在那里而非新建工作流。

## 3. 未修，且我刻意没碰：gateway 集成测试永不阻断

`nexus-gateway` 的 `integrationTest` 步骤带 `continue-on-error: true`（`.github/workflows/ci.yml:219-221`），
注释自述原因是 CI 环境缺 Nacos/Kafka/Redis。后果是这组测试**结构上不可能阻断合并**，
其"通过"不构成证据。正确修法是让 CI 起所需基础设施后转为阻断，或明确它只在本地跑——
二者都比现状好，但都需要改 `ci.yml`。

**为何本次不动**：工作树里已有一处**非我所作的未提交改动** `.github/workflows/ci.yml`
（新增 `gateway-context-smoke` job，单独跑 `PaymentE2EIntegrationTest`，方向与本条一致）。
在他人/另一会话在途改动上再叠一层同文件修改，会把责任边界搞混。留待其落地后处理。

## 4. 规模基线（实测，供后续对照）

- Java 主源码 **1673** 文件 / 测试 **660** 文件（`src/main/java` vs `src/test/java`，排除 `build/`）；
- Rust **30** 文件（`mpc-engine` + `zk-groth16-service`，均不参与 Gradle 构建，经 Docker/Helm 部署）；
- 厚模块：gateway 680、core 416、consortium 170、signing 99、bridge 80；
- 库形态模块（0 controller、0 `@SpringBootApplication`）：settlement / compliance / analytics / oracle；
- 被显式排除构建：`nexus-explorer`、`nexus-devtools`、`demo`（`settings.gradle:77-78`）；
  `nexus-rpc-doc` 仅剩 README（`settings.gradle:29-31`）。
- 文档侧已自我纠偏：`README.md:104` 起专设"避免宣称能力 >> 实际能力"章节，
  `:108-110` 将 explorer 明确降为"MVP 骨架、非生产级"，并指出仓库内大量文件是 `node_modules`。

## 5. 我推翻的一条结论（防止被当依据）

有子代理判定 `ci.yml:855` 的 `... | tee hardhat-e2e-output.log` 因"GitHub 默认 bash 不带
pipefail"而使该步"结构上不可能失败"。**此结论不成立**：GitHub Actions 在 Linux 上 `run`
的默认 shell 是 `bash --noprofile --norc -eo pipefail`，`tee` 不会吞掉上游退出码；
同文件 `:814` 亦声明该 job 为强制门禁。要证实只能观察一次真实失败，我不据此改动任何东西。

## 6. 未确认清单（不下结论）

- ~~分支保护里哪些 check 是 required~~ —— **已查实：master 未启用分支保护**（见 §1），
  因此当前无任何 required check。
- `nexus-settlement`/`compliance`/`oracle` 的 JPA 建表方式（有 JPA starter 但未见 `db/migration` 目录）。
- CI 引用的 `secrets.PERF_API_KEY` 是否已在仓库配置。
- `demo/`、`deploy/kind/`、`deploy/scripts/*` 无任何 CI 引用，只能人工本地跑——其"可用性"未经 CI 证明。

## 7. 更正：jackson 抬版修错了坐标（commit f9efc8b 的结论作废）

`f9efc8b` 的提交信息写"消除 master 恒红的 CVE-2026-68497"，**该结论错误**，特此更正。

在分支 `ci/pr-security-gates` 上 `workflow_dispatch` 跑完整安全扫描（run `36591223183`，
含 PR 上必跳过的镜像扫描）后，扫描表实读到：

```
tools.jackson.core:jackson-databind | CVE-2026-68497 | HIGH | fixed | 3.1.5 | 3.2.2, 3.1.6
```

- 受影响坐标是 **Jackson 3 的 `tools.jackson.core:jackson-databind`，装在 3.1.5，修复线 3.1.6 / 3.2.2**；
- 告警文案里出现的 `com.fasterxml.jackson.core/jackson-databind` 只是 **vendor 别名**，
  我据此误判成 Jackson 2，于是抬了 `2.18.8 → 2.18.10` —— 对这条 CVE **完全无效**；
- 结果：5 条 open HIGH 仍在，镜像扫描仍 5 红（另出现 `Gitleaks Secret Scan` 红，原因未查）。
- 来源链（实测 `gradlew :nexus-core:nexus-core:dependencies`）：
  `org.springframework.boot:spring-boot-jackson:4.0.8 → tools.jackson:jackson-bom:3.1.5
  → tools.jackson.core:jackson-databind:3.1.5`，即由 Spring Boot BOM 管理，不是直接依赖。

**已实施**（commit `72876c9`）：在根 `build.gradle` 的 ext 块加
`set('jackson-bom.version', '3.1.6')`，实测 `:nexus-core:dependencies` 解析为
`tools.jackson.core:jackson-databind:3.1.5 -> 3.1.6` 且 `jackson-core:3.1.6`（同 BOM 对齐，
无 databind/core 错配），`:nexus-core:compileJava` exit=0。
最终确认以重跑的镜像扫描为准（run `36597539567`），不看"版本号变了"。
原先设想的"正确修法（尚未实施）"表述如下，保留作决策依据：参照 `build.gradle:206-210` 处理 Tomcat 的先例
（"Spring Boot BOM 管理解析为 11.0.24——此处 override 到 11.0.25，可修复一律升级不用 ignore"），
给 `tools.jackson.core` 做同样的版本覆盖到 **3.1.6**（含 `jackson-core`，避免 databind/core 错配），
或在依赖管理里对该 BOM 条目做 substitution。抬完后必须**重跑镜像扫描**确认 5 条 HIGH 归零，
不能只看"版本号变了"。

保留项：Jackson 2 的 `2.18.10` 与去掉两处硬编码（signing:170、wallet:154 改回
`${jacksonVersion}`）仍是有效改良——它压住了传递依赖想要的 2.19.1，而 2.19.0–2.21.5
同样在该 CVE 的 Jackson 2 受影响范围内。但**它不是这 5 条红的原因**，不计为修复。
## 8. 部署前置条件：Redis 重放存储（2026-10-01 代码取证，未实施）

`NEXUS_REPLAY_STORE=redis` 是**唯一**能让多副本 gateway 共享防重放 nonce 表的开关
（`RedisReplayNonceStore` 类注释 `:14-15`：Helm 生产 gateway `minReplicas: 2`，
进程内表在 5 分钟窗口内允许跨 Pod 重放）。但打开它有两个必须先处理的前提：

**（a）fail-closed 语义 = 未联通就是全量拒绝，不是降级**
`nexus-gateway/.../security/RedisReplayNonceStore.java:48-51` 捕获 Redis 异常后返回 false，
即「视为重放，拒绝」。所以**没有先验证连通性就打开开关**，表现是 gateway 上所有带签名
请求被拒（对外等于挂掉），而不是退回内存表。

**（b）Helm 注入的键与代码读取的键不是同一个**
- 代码读 `spring.data.redis.*`（`application-prod.yml:89-94`，env `NEX_REDIS_HOST/PORT/PASSWORD/DATABASE`）；
  同样读该键的还有 `ratelimit/RedisRateLimiter.java`、`ratelimit/RedisIdempotencyStore.java`、
  `orchestration/service/OrchestrationWebhookDispatcher.java`。
- 而 nexus-gateway chart 注入的是 `SPRING_REDIS_HOST/PORT`
  （`deploy/helm/charts/nexus-gateway/templates/deployment.yaml:101-106`）——relaxed binding 下落到
  **旧键 `spring.redis.host`**，本仓库 `nexus-gateway/src/main` 内无任何消费方（`git grep` 零命中）。
  于是 `global.infrastructure.redis.host` 的取值**影响不到** gateway 进程。
- 反向也错位：真正的 `NEX_REDIS_*` 只注入在
  `deploy/helm/charts/nexus-api-gateway/templates/deployment.yaml:91-93`，而重放存储/限流/幂等的代码
  在 **nexus-gateway** 模块 → 该 chart 缺此 env 时走 `application-prod.yml:90` 的默认值 `redis`
  （集群内同名地址），与 values 配置无关。
- 文档侧同口径：`deploy/helm/README.md:117,244`、`docs/k8s-deployment.md:358` 都把
  `SPRING_REDIS_HOST/PORT` 记为「Redis 地址（限流用）」，与代码实际读取的键不一致。

**启用前的最小动作（部署侧，非代码可验证）**：① 确认 Service/DNS 名与 `NEX_REDIS_HOST` 一致；
② Pod 内用同一凭据 `redis-cli SET/GET nexus:replay-nonce:probe` 验证读写与 ACL；
③ 观察 `Replay nonce store (redis) unavailable` 日志为 0 后再灰度开开关；
④ 预演回滚（置回 `memory`），并知悉回滚即重新暴露跨 Pod 重放窗口。
若要设防，正主工作流是 `.github/workflows/k8s-sync-check.yml`（已做 Helm 渲染 + kubeconform 比对），
可加「`replay-store=redis` 时必须有对应连接 env」的渲染期校验，而不是等运行期 fail-closed 暴露。

**边界**：以上均为读代码/模板得到的确定事实；「prod 是否真连得上该地址」**未经运行验证**，
故本书记前置条件，不下「可用/不可用」结论。

## 9. 追加：09-30 第二波 CVE（PR #15 续追，2026-10-01）

**Jackson 第二波（CVE-2026-91776 / CVE-2026-91777）**：Trivy 库在 09-30 增补的这两条，
命中的正是上一轮 `CVE-2026-68497` 的**修复版本身**（`com.fasterxml...:jackson-databind 2.21.6`
与 `tools.jackson...:jackson-databind 3.1.6`），修复线：2.x 为 `2.18.11 / 2.21.7 / 2.22.3`、
Jackson 3 为 `3.1.7 / 3.2.3`。取证：master push run `36773548701` 的
`Trivy Docker Image Scan (nexus-core)` 日志 `nexus-core:scan` 段 —— OS 层 `debian 13.7` 为
`Total: 0`，`Java (jar)` 段为 `Total: 4 (HIGH: 4)`，全部为该两条 CVE。
处置（同 68497 先例，升级消除而非 ignore）：根 `build.gradle` `jacksonVersion 2.21.6→2.21.7`、
`ext['jackson-2-bom.version'] 2.21.7`、`ext['jackson-bom.version'] 3.1.7`；
`nexus-api-gateway/build.gradle` 的**独立 ext 副本**同步抬（composite build 不随根自动跟随，
这是该副本第二次需要手工同步 —— 结构性隐患，见下）。

**结构性隐患（建议后续处理）**：同一组 BOM 属性在**两处**声明（根 + `nexus-api-gateway` 的
composite build）。任一处漏改，对应镜像会"静默滞后"到下一轮镜像扫描才暴露。可考虑让
`nexus-api-gateway` 从根读取（如 `gradle.properties` 或 `-P` 传入），或加一条 CI 断言
两处取值一致（正主工作流：`ci.yml` 的 code-hygiene job）。

**libssl3 CVE-2026-84782（无修复版本，已按惯例豁免）**：`openssl` / `libssl3`
`3.0.22-1~deb12u1`（`debian:bookworm-slim` → mpc-engine / zk-groth16-service 两个 Rust 镜像）
状态为 **affected、无 Fixed Version**。两镜像扫描均为 `Total: 2 (HIGH: 2)`，两条即
libssl3 与其符号链接项 openssl 的同一条 CVE；两个服务不提供 DTLS 监听端点，无法触发
DTLS 握手重传路径，故按 `.trivyignore` 开头原则（只豁免上游无修复版本项）加豁免，
待 Debian 出修复版随基础镜像升级移除。Java 模块镜像（distroless java17-debian13）OS 层为 0，
不涉及。

**本轮结束后 master 侧仍红的项**：`OWASP Dependency-Check`（缺 `NVD_API_KEY` secret，
仓库设置层面动作，非代码可改）——这是唯一非代码可闭合的红。
**（已于 2026-10-01 闭合：key 已配置且实测有效，阻断语义已恢复，见 §10。）**

## 10. 追加：OWASP DC 的 NVD key 实测 + 阻断语义恢复 + 「空心绿」取证（2026-10-01）

**背景**：§9 末记录的「master 侧唯一非代码可闭合的红 = OWASP Dependency-Check（缺
`NVD_API_KEY`）」已于本轮闭合。

**动作与证据**

| 项 | 事实 |
|----|------|
| secret | `NVD_API_KEY` 由 owner 于 2026-09-30 创建（`gh secret list` 实测 `total_count: 1`，`updated_at 2026-09-30T22:11:42Z`） |
| 对照 run | push run `36780102329` 的 OWASP job：`started 22:10:35Z` / `completed 22:10:42Z`（**7 秒**）→ 失败 |
| **失败真实原因** | **竞态**：job 启动（22:10:35Z）比 secret 创建（22:11:42Z）**早 67 秒**，读到的仍是空串 → 命中 `if [[ -z "$NVD_API_KEY" ]]` 守卫。**不是 key 无效**（当时一度被误记为"key 无效"） |
| 复核 run | `workflow_dispatch` run `36784537663`（key 已在库）：`22:31:36Z → 22:51:48Z`，**20 分 12 秒**，结论 `success` |
| 库连通性证据 | 报告 `scanInfo.dataSource`：`NVD API Last Checked = 2026-09-30T22:51:36Z`；engine `13.0.0` |
| 处置 | 摘除 `security-scan.yml` 该 job 的 `continue-on-error: true`，恢复 `--failOnCVSS=9` 阻断语义 |

**判定逻辑**：缺 key 时该 job 约 5~7 秒即 `exit 1`；本次运行 **20 分 12 秒**且报告里留下 NVD
拉取时间戳 —— 两侧互证 key 有效、且确实在用 NVD 库（而非同名不同源的库）。

**⚠️ 本轮最重要的发现：这个绿是「空心绿」（检测力为零）**

解析 run `36784537663` 的 `dependency-check-report.json`：

| 指标 | 实测值 |
|------|--------|
| `dependencies` 条目数 | 54 |
| 构成 | `.js` 37 / `.json` 15 / `.jar` 2（仅 `gradle-wrapper.jar`） |
| **取得 CPE（`packages`）** | **0 / 54** |
| `vulnerabilities` | 0 |
| JVM 侧 | **未解析 Gradle 依赖图**（只有 wrapper jar） |

单个依赖对象的字段集合实测为
`isVirtual | fileName | filePath | md5 | sha1 | sha256 | evidenceCollected` ——
**连 `packages` 字段都不存在**（未识别出任何组件）。故该 job 的绿只说明
「NVD 库拉通了 + 提交物里没有可识别的组件漏洞」，**不说明 JVM 依赖无漏洞**。

**覆盖来源澄清（纠正旧文档表述）**
`docs/dependency-check-update-policy.md` 原文称「Trivy 的 fs 与镜像扫描**仍覆盖 Gradle 依赖
漏洞**」——**fs 侧不成立**：
- Trivy 对 Java 只识别 `pom.xml`(Maven) 与 `gradle.lockfile`；本仓库 `git ls-files` 显示
  **两者均不存在**（仅有 6 个位于 build 缓存内的 `pom.xml`，未跟踪）；
- Trivy **不解析 `build.gradle`**（仓库跟踪 **19 个** `build.gradle`，不在其识别范围）。

→ JVM 依赖的**唯一真实门禁是 Trivy 镜像扫描的 `Java (jar)` 层**（run `36780102329` 实测：
nexus-core 由 `Java (jar) Total: 4 (HIGH: 4)` 转 success，即 jackson `2.21.7/3.1.7` 在该层验证）；
**未被打进任何镜像的依赖（如仅测试期使用）当前无人覆盖**。

**本轮落地的防误读措施**：`security-scan.yml` 新增「扫描覆盖度自检」步骤（`if: always()`），
每次运行把「条目数 / 已识别（CPE 或漏洞 ID）数 / 漏洞数 / 条目类型分布 / NVD API Last Checked」
打印到日志与 Job Summary；覆盖度为零时发 `::warning::` 而**不失败**（扫描器能力不足属待升级项，
不应表现成"门禁抓到漏洞"）。政策文档同步改写（含"命中 CVSS≥9 时的处置顺序"与 suppressions 用法）。

**未修缺口（需决策）**
1. **`owasp/dependency-check:latest` 未钉 digest**：浮动 tag → 每次运行可能拉到不同引擎版本，
   门禁结果不可复现，与本仓库"禁止浮动引用"的供应链原则冲突（该原则此前只约束
   `Dependency-Check_Action` 的 commit SHA，自 2026-09-10 改用 `docker run` 后**约束落空**）。
   建议：钉 digest + 纳入季度更新流程，或显式承认该例外并记录理由。
2. **Gradle 依赖图未解析**（本 job 检测力为 0 的根因）：升级路径见
   `docs/dependency-check-update-policy.md`「升级为『真覆盖』的前置条件」。

## 11. 追加：required check「永不上报」死锁 —— k6 Smoke Test (PR)（2026-10-01，PR #16 实证）

**发现路径**：PR #16 由受守卫的 watcher 自动 squash 合并时，**18 个 check 全部 `COMPLETED`、0 失败、
0 未完成**，却返回：

```
X Pull request Levango7/NexusChain#16 is not mergeable: the base branch policy prohibits the merge.
To have the pull request merged after all the requirements have been met, add the `--auto` flag.
To use administrator privileges to immediately merge the pull request, add the `--admin` flag.
```

**取证（逐步排除）**

| 检查项 | 实测 | 结论 |
|--------|------|------|
| 是否缺 reviewer 审批 | `required_pull_request_reviews` = **none** | 不是审批问题 |
| 是否管理员可绕过 | `enforce_admins.enabled` = **true** | **`--admin` 也不行** |
| required checks 清单（7 项） | Build & Test / Gitleaks / Trivy FS / SAST / Cargo Audit / Code Hygiene / **k6 Smoke Test (PR)** | 其中 6 项在 PR #16 里均为 `SUCCESS` |
| PR #16 的 check rollup | 18 项，**没有 `k6 Smoke Test (PR)`** | 该 check **从未上报** |
| 分支上实际启动的 workflow | `gh run list --branch ci/owasp-dc-blocking` 只有 2 个（Security Scan、CI/CD Pipeline） | `performance-test.yml` **没被触发** |
| 触发条件 | `pull_request.paths = [nexus-gateway/**, nexus-bridge/**, perf/k6/**, .github/workflows/performance-test.yml]` | 而 PR #16 只改 `security-scan.yml`/`CHANGELOG.md`/`docs/**` → **不匹配** |
| 对照 | PR #15（改了 `nexus-gateway/**`）的 `k6 Smoke Test (PR)` = `SUCCESS`，正常合并 | 差异由 paths 匹配与否解释，非偶发 |

**机理**：GitHub 对 required check 的判定是「**必须存在一个针对该 head 的、结论为成功的 check run**」。
workflow 因 `paths` 过滤未启动 → **没有 check run** → 状态永远停在 "Expected — Waiting for status to
be reported" → `mergeStateStatus` 恒为 `BLOCKED`。由于 `enforce_admins=true`，**任何身份都无法绕过**。

**影响面（结构性，非本 PR 特有）**：凡「变更集不触碰那四条路径」的 PR 都**永远合不进去** ——
纯文档、纯 CHANGELOG、其它 workflow 的改动全部中招；且失败信息 ("base branch policy prohibits the
merge") 完全不提示是哪个 check 缺失，排查成本高。

**修复**（`performance-test.yml`，同一 PR 内）
- 删除 `paths` 白名单 → workflow 每次都启动、check 必上报；
- 新增 `id: relevance` 步骤（**无 `if`，永远执行**）：用
  `git diff --name-only "${{ github.event.pull_request.base.sha }}...HEAD"` 判定相关性；
  不相关 → 后续重步骤跳过、job 仍 `success`（30 分钟开销降为秒级）；
  判定失败 → **倾向于实跑**（保守）；`workflow_dispatch` → 实跑；
- 4 个重步骤加 `if: steps.relevance.outputs.run == 'true'`；收尾步骤
  `always() && steps.relevance.outputs.run == 'true'`；
- `checkout` 补 `fetch-depth: 0`（浅克隆会让上述 diff 失败）；
- **job 名不动**（required check 名必须逐字一致）。

**验证方法（可复现）**：`notes/nexus-validation/validate-perf-workflow.py` ——
① YAML 结构断言（`pull_request` 无 `paths`/`paths-ignore`、job 名未变、守卫与步骤顺序正确）；
② `bash -n` 语法检查；③ **在临时 git 仓库真跑脚本**四用例并断言退出码为 0：
doc-only → `run=false`、`nexus-gateway/**` → `run=true`、无效 base → `run=true`、dispatch → `run=true`。

**留下的通用规则（后续新建门禁必须遵守）**
1. **列为 required check 的 workflow 不得使用 `paths`/`paths-ignore` 过滤**；
2. 同理**不要**把 required check 放在「可能被 job 级 `if` 跳过」的 job 上 —— skipped 的 check
   能否满足保护项属 GitHub 隐式语义，不应依赖；
3. 分支保护启用 `enforce_admins` 后**没有逃生舱**：门禁自身的可满足性必须先被验证，
   否则会把整个仓库的合并能力一起锁死。


