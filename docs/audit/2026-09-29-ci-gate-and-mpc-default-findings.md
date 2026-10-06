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

> **→ 已决策（2026-10-02，取「若有意」出口）**：核实 staging 也已置
> `NEX_MPC_TRANSPORT_GRPC=true`（P0-10「同 values-prod 接入」注释佐证），
> 分层判定为**有意三级阶梯**：dev=进程内+GG20 / staging=真实 gRPC+GG20
> （keyshare 供给未配，CGGMP21 留关）/ prod=全分布式 CGGMP21。
> 出口动作已全部落地：README「MPC 分层口径」段明示非等价性；
> 门禁按上文建议加在 k8s-sync-check（`scripts/check-mpc-tier-policy.sh`：
> staging 断言 transport=true，prod 断言 transport+cggmp=true，K8s 静态清单断言
> transport=true）。staging 未来升 CGGMP21 的前置（keyshare 供给）已在脚本头注释记录。

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
  **2026-10-06 收口**：该项已不再复现——本仓 `workflow_dispatch`（`fetch-depth: 0`，全历史）
  的 run 37414095658 里 `Gitleaks Secret Scan = success`。仓内可见的处置轨迹是
  `e1794e14`（08-31，两个已轮换 JWT 密钥从全部历史 blob 替换为 `REDACTED-ROTATED-JWT-SECRET`，
  重写前 HEAD 备份在 `F:\Nexus\_backup\NexusChain-pre-rewrite.git`）与
  `7320dc0`（10-01，"gitleaks 豁免根因修复"）。**只记到"现象消失 + 有这两笔处置"为止**，
  不再往前推"是哪一笔让它变绿"——当时那条红我没有留下日志。
  仍留在的口径差异是结构性的：`pull_request` 只扫 PR 内 commit、`push` 只扫推送范围，
  全历史只在 schedule/dispatch 才扫得到，所以"PR 上 gitleaks 绿"不等于历史无泄漏。
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

## 12. 追加：Build & Test 的「定时炸弹」测试 —— required check 从 2026-10-01T00:00Z 起永久红（2026-10-01）

**发现路径**：PR #16 修完 k6 悬空 check 后，`Build & Test (JDK 17 / ubuntu-latest)` 转为 `FAILURE`，
watcher 按守卫规则拒绝合并。该 job 是 required check → **全仓合并能力再次被锁死**。

**取证（逐层排除 flaky 假设）**

| 步骤 | 实测 | 推论 |
|------|------|------|
| 失败测试 | `DataExportServiceTest > processExport — WEBHOOK_DELIVERIES 类型 CSV 导出成功 FAILED`，`AssertionFailedError` at `DataExportServiceTest.java:293`；CI `2408 tests completed, 1 failed` | 单点失败 |
| 断言内容 | 第 293 行 = `assertEquals(1, request.getRecordCount())`；**紧邻的第 292 行 `assertEquals(COMPLETED, request.getStatus())` 通过** | 非异常路径（异常会把状态置 FAILED）→ 只可能是 `recordCount` 被设成 **0**（记录被过滤） |
| 是否本次改动引入 | 本 PR 只改 `.github/workflows/performance-test.yml` / `CHANGELOG.md` / `docs/**`，**未触任何 Java**；同分支上一 SHA 同一 job 为 success | 与本次改动无关 |
| 两次运行时间 | 成功 `2026-09-30T23:16Z`；失败 `2026-10-01T00:05Z` | 指向"时间相关" |

**定因（代码取证）**

- 测试第 **625** 行：`delivery.setCreatedAt(java.time.Instant.now());`
- 服务端 `DataExportService.queryDataForExport()` 的 `WEBHOOK_DELIVERIES` 分支**按请求窗口过滤**：
  `!w.getCreatedAt().isBefore(dateFrom.atZone(UTC).toInstant()) && !…isAfter(dateTo…)`
- 测试第 **275** 行写死的窗口：`dateTo = LocalDateTime.of(2026, 9, 30, 23, 59)`（UTC 转换）

→ `Instant.now()` 一旦越过 `2026-09-30T23:59Z`，记录即被过滤 → `recordCount = 0` → 断言必失败。
**这是定时炸弹而非随机 flaky**：自 **2026-10-01T00:00Z** 起该 required check 会**永久红**。

**同类风险排查**：全仓仅 3 个测试使用写死的 `2026-09-30` 窗口
（`DataExportControllerTest` / `DataExportServiceTest` / `ReconciliationFileServiceTest`），
其中**只有 `DataExportServiceTest` 同时用 `.now()` 造记录时间戳** → 与"只红 1 个测试"一致，无其它同类炸弹。
（`ChainSettlementConfirmationServiceTest` 等使用 `Instant.now().minus(35, MINUTES)` 这类**相对**窗口，本质不同。）

**修法（仅测试，不动生产代码）**
`createWebhookDelivery(...)` 的 `createdAt` 由隐式 `Instant.now()` 改为**显式入参**，
调用处传入窗口内的固定时刻（`2026-09-15T12:00Z`）→ 断言与运行时钟解耦。

**验证（本地实跑）**
`./gradlew :nexus-gateway:test --tests '*DataExportServiceTest*'` ——
修前 **BUILD FAILED in 3m 10s**（同一行 293、`24 tests completed, 1 failed`）；
修后 **BUILD SUCCESSFUL in 2m 9s**。

**留下的通用规则**：凡"按时间窗口过滤"的断言，测试数据的时间戳**必须**用固定值或**相对**偏移，
**禁止** `now()` 搭配写死的过去窗口 —— 否则到期即全仓不可合并，且报错信息完全不指向门禁可操作性。

## 13. 追加：OWASP DC 迁到官方 Gradle 插件（真依赖图）+ 覆盖度自检失败化（2026-10-01，PR #17）

**背景**：§10 取证了 docker 路径的「空心绿」（54 条目 / **0** 条取得 CPE）。§13 记录把扫描对象
换成**解析后的依赖坐标（GAV）**的迁移、实跑证据，以及**第一次真扫描立刻暴露的 13 个 CRITICAL**
及其模块级根因。

### 13.1 改动（PR #17）

| 文件 | 改动 |
|------|------|
| `build.gradle` | `plugins{}` 声明 `org.owasp.dependencycheck:13.0.0` **apply false**；`if (project.hasProperty('owaspScan'))` 内才 `apply plugin`，配置 `scanConfigurations=['runtimeClasspath']`、HTML+JSON、`failBuildOnCVSS=9.0f`、`suppressionFiles`、仅 NVD 分析器、`nvd.apiKey←env`、`nvd.delay=8000`、`data.directory→GRADLE_USER_HOME/dependency-check-data` |
| `.github/workflows/security-scan.yml` | OWASP job 加 `Setup JDK 17`；`./gradlew dependencyCheckAggregate -PowaspScan --no-daemon --stacktrace`；key 走 `env:`（**不进命令行**）；报告路径迁到 `build/reports/dependency-check/`；**覆盖度自检由 warning 收紧为 error** |
| `config/dependency-check-suppressions.xml` | 新增空基线 + 策略头（显式指定，非自动加载） |

**为什么必须用 `-PowaspScan` 开关（本地三断言，`verify-owasp-gradle.ps1`）**

| 断言 | 结果 | 含义 |
|------|------|------|
| `gradlew help` | `exit 0`、`dependencyCheck` 命中 **0** 次 | 插件可解析，且**不给开关时不生效** |
| `dependencyCheckAggregate -PowaspScan --dry-run` | `exit 0`、聚合任务存在 | DSL 全键被接受，任务真实存在 |
| **`gradlew check --dry-run`** | `exit 0`、`dependencyCheck` 命中 **0** 次 | **Build & Test 不会被拖去下 NVD** |

两个失效方向都是**响的**：去掉开关 → 任务不存在 → job 立刻红；去掉 `hasProperty` 门控 →
`check` 拉 ~20 分钟 NVD → `Build & Test`（required check）剧慢且因缺 key 而红。**不是静默跳过。**

### 13.2 实跑证据（run `36803239132`，`workflow_dispatch` @ `ci/owasp-gradle-plugin` = `a8da204`）

| 项 | 实测 |
|----|------|
| workflow 结论 / OWASP job | `failure`（**只有扫描步骤红**） |
| `运行 OWASP Dependency-Check` 步骤 | **`failure`** = `failBuildOnCVSS=9.0f` 被 13 个 CRITICAL 触发（**门禁按设计工作**） |
| `扫描覆盖度自检` 步骤 | `success`（覆盖度非 0 → 不再报空心绿） |
| `上传 OWASP 报告` | `success`；artifact `owasp-dependency-check-report`（JSON **4,127,760 B**） |
| NVD 数据源 | `NVD API Last Checked = 2026-10-01T02:38:53Z`（key 在插件路径下同样有效） |
| 报告位置 | `build/reports/dependency-check/dependency-check-report.{html,json}` |

### 13.3 覆盖度对比：门禁是否真的「看得见」了

| 指标 | 旧 docker（run `36784537663`） | 新插件（run `36803239132`） |
|------|------------------------------|----------------------------|
| `dependencies` 条目 | 54 | **252** |
| 构成 | `.js 37 / .json 15 / .jar 2` | **`.jar 242 / .dll 9 / .json 1`** |
| **取得 CPE（已识别）** | **0 / 54** | **242 / 252**（96%） |
| `vulnerabilities` | **0** | **141**（59 个依赖） |
| 严重度 | — | **CRITICAL 19 / HIGH 45 / MEDIUM 75 / LOW 2** |
| 覆盖对象 | 仓库里的**文件**（含 37 个 `.js`） | **19 个 Gradle 模块的 runtimeClasspath** |
| 报告覆盖的模块引用 | — | `nexus-core:runtimeClasspath`、`java:runtimeClasspath`（= `nexus-sdk/java`）、`nexus-sdk` |

**结论**：旧路径对 JVM 依赖的**检测力为 0**（0 条可取 CPE），新路径**能报出真实依赖漏洞** ——
这正是「空心绿」与「真门禁」的分界。**0 → 242 条已识别**是本次迁移唯一重要的指标。

### 13.4 第一次真扫描立刻暴露的 13 个 CRITICAL（去重）+ 模块级溯源

`failBuildOnCVSS=9.0f` 触发项（19 条原始记录 → 去重 **13 个 CVE**，落在 **7 个制品**）：

| CVE | CVSS | 制品 | 修复版本（CVE 描述直读） |
|-----|------|------|------------------------|
| CVE-2026-45674 / 47691 | **10.0** | `netty-all-4.1.115.Final` | 4.1.135.Final / 4.2.15.Final |
| CVE-2026-42579 / 42581 / 42584 | 9.8 / 9.8 / 9.1 | `netty-all-4.1.115.Final` | 4.1.133.Final / 4.2.13.Final |
| CVE-2026-56820 / 75595 | 9.1 / 9.1 | `netty-all-4.1.115.Final` | 4.1.137.Final / 4.2.17.Final |
| CVE-2026-53914 | 9.8 | `kotlin-stdlib(-jdk7/-jdk8)-2.2.21` | **2.4.20** |
| CVE-2026-65637 / 65905 | 9.8 / 9.8 | `tomcat-embed-core/-websocket-11.0.24` | **11.0.25** |
| CVE-2026-65182 / 68525 | 9.1 / 9.1 | `tomcat-embed-core/-websocket-11.0.24` | **11.0.25** |
| CVE-2023-39017 | 9.8 | `quartz-2.3.2` | 描述指向 **`quartz-jobs`** 组件（见 13.5-④） |

**溯源（`dependencies[].projectReferences`）—— 泄漏点只有两个 runtimeClasspath：**

| 制品（严重度） | 出现的模块 |
|----------------|-----------|
| `tomcat-embed-core-11.0.24`（CRITICAL/HIGH/MEDIUM） | **`java:runtimeClasspath`**（`nexus-sdk/java`） |
| `tomcat-embed-core-11.0.25`（**无**） | `nexus-core:runtimeClasspath` |
| `netty-all-4.1.115.Final`（CRITICAL…） | `nexus-core:runtimeClasspath` |
| `netty-all-4.2.17.Final`（仅 MEDIUM） | `java:runtimeClasspath` |
| `kotlin-stdlib(-jdk7/-jdk8)-2.2.21`（CRITICAL） | `java` + `nexus-core` |
| `quartz-2.3.2`（CRITICAL） | `nexus-core:runtimeClasspath` |

### 13.5 根因分类：**不是「全都没修」**，而是四种不同性质的问题

① **模块级 BOM 覆盖漏了一个模块（真缺陷，且图像扫描结构上看不到）**
`nexus-sdk/java` 是 `java-library`（**不产出镜像**），仓库的 tomcat 修复
（`ext['tomcat.version'] = '11.0.25'`）只加在 `nexus-gateway` / `nexus-bridge` /
`nexus-wallet-service` / `nexus-signing-service` / `nexus-core` **5 个模块**里 ——
`io.spring.dependency-management` 的 `ext[...]` 覆盖是**项目局部**的，不跨模块传播，
所以 sdk/java 按自己导入的 Boot 4.0.8 BOM 解析回 **11.0.24**。
**Trivy 扫的是镜像**，而这个模块**没有镜像** → 该缺陷对镜像扫描**结构上不可见**。
→ 修法：给 `nexus-sdk/java` 补同一条 `ext['tomcat.version'] = '11.0.25'`（零风险，与既有 5 处一致）。

② **显式钉版过旧（真缺陷，可升级）**
`nettyVersion = '4.1.115.Final'` 在根 `build.gradle:211` 与
`nexus-core/nexus-core/build.gradle:126` 两处硬编码 → 7 个 CVE。
CVE 描述给出的修复线都在 **4.1.133/135/137.Final**（或 4.2.13/15/17.Final）——
升到 **4.1.137.Final 即同时清掉全部 7 条**，且**不跨 minor 线**（代码/文档按 4.1 API 写）。
旁证：`nexus-sdk/java` 的 runtimeClasspath 里**已经**有 `netty-all-4.2.17.Final`（仅剩 1 条 MEDIUM）
→ 说明修复版本完全可用，只是 nexus-core 这条线没跟上。

③ **传递依赖未纳管（真缺陷，需显式约束）**
`kotlin-stdlib(-jdk7/-jdk8) 2.2.21` 由传递路径引入（两个模块都有），修复版 **2.4.20**（跨 minor，
但 kotlin stdlib 向后兼容、风险低）。→ 修法：显式约束/`ext['kotlin.version']` 抬到 2.4.20，
**不要**靠"没人报"忽略。

④ **CPE 过度匹配（误报，证据充分）**
`quartz-2.3.2` 命中的 **CVE-2023-39017**，描述明确是
「**quartz-jobs** 2.3.2 and below … **`org.quartz.jobs.ee.jms.SendQueueMessageJob`**」——
而仓库依赖的是 **`org.quartz-scheduler:quartz`（核心）**，报告里**没有 `quartz-jobs` 制品**。
→ 该 CVE 的可利用组件**不在 classpath 上**。按仓库既有口径（"可修复一律升级，不用 ignore"）
这条属于**不可修**（不是版本问题），走 `suppressions.xml` + **证据 + 到期日**，并写清理由。

### 13.6 结构性发现（再次出现）：这个 job **不能**设为 required check

该 job 的 `if: github.event_name != 'pull_request'`（成本高，PR 不跑）意味着
**在任何 PR 上都不会上报**。若把它加进 branch protection 的 required checks，
就会**精确复现 §11 的「永不上报」死锁**：required check 永不出现 → PR 永久 `BLOCKED`，
且 `enforce_admins=true` 下 `--admin` 也无法绕过。

唯一可行路径 = 复刻 §11 的修法：让该 job 在 PR 路径上**总是上报**（不设 `paths`/job 级 `if`），
把重活（NVD 下载 + 扫描）放到**相关性判断之后**按需执行；代价是 NVD 冷启动 ~20 分钟
会落在 PR 首次运行上 —— **需产品口径确认**，本次未动分支保护。

**补充通用规则（接 §11 第 3 条）**
4. **"能不能当 required check"由「是否在所有 PR 上上报」决定，与"门禁重不重要"无关** ——
   高成本门禁（镜像/SCA 全量扫描）天然倾向用 `if`/`paths` 省算力，而这**恰好**使它不可被设为
   required；需要它成为硬门禁时，必须改成**总是上报 + 内部相关性门控**，而不是靠加保护项施压。

### 13.7 未关闭项（诚实记录）

1. **13 个 CRITICAL 待整改**（13.5 的 ①②③④ 四项）—— 本 PR 只交付**看得见**的能力，
   不夹带生产依赖升版；整改应独立成 PR（netty/kotlin 升版 + sdk/java 补覆盖 + quartz 证据化抑制）；
2. **本 job 仍非 required check**（13.6）；
3. **未启用 Gradle 依赖校验**（仓库无 `verification-metadata.xml`）→ 插件 jar 及传递依赖
   **哈希未固定**；精确版本挡"版本漂移"，挡不住"同版本被替换产物"；
4. **NVD 数据目录未接 CI 缓存**（`data.directory` 指向 `GRADLE_USER_HOME/...`，无 `actions/cache`）
   → 冷启动可能重复下载（时间成本，非正确性问题）；
5. **仅 NVD 分析器** → npm 侧（`nexus-core` 的 Hardhat/`yarn.lock`）不在本 job 覆盖范围，
   仍由 Trivy fs 承担；本次报告里 `.json ×1` 即为该侧残留信号，不应解读为"npm 已覆盖"。

### 13.8 这次迁移真正的价值（一句话）

**同一个仓库、同一批依赖，换扫描对象后，从「0 条可取 CPE」变成「242 条已识别 + 13 个 CRITICAL」，**
其中一个还是**镜像扫描结构上永远看不到的库模块缺陷**。这说明：门禁的"绿"必须先证明
**它有能力变红**，否则绿只是"没在看"。

### 13.9 整改实施（PR #18，2026-10-01）

按 §13.5 的四类根因逐条处置，原则：**可修一律升级，不可修才抑制**。

| # | 制品 | 处置 | 落地位置 |
|---|------|------|---------|
| ① | `netty-all 4.1.115.Final` | → **4.1.137.Final**（清 7 个 CRITICAL） | 根 `build.gradle` 的 `ext.nettyVersion` + `nexus-core/…/build.gradle`（**两处各自消费，必须同步**） |
| ② | `kotlin-stdlib(-jdk7/-jdk8) 2.2.21` | → **2.4.20**（CVE-2026-53914） | nexus-core：`ext['kotlin.version']`；`nexus-sdk/java`：Gradle `constraints{}` |
| ③ | `tomcat-embed-core/-websocket 11.0.24` | → **11.0.25** | `nexus-sdk/java` 新增 `constraints{}`（该模块**未应用** dependency-management 插件，`ext[...]` 对它无效） |
| ④ | `quartz 2.3.2` / CVE-2023-39017 | **证据化抑制**（`until="2027-01-31Z"` + 复核方式） | `config/dependency-check-suppressions.xml` |

**本地实证（`dependencyInsight --configuration runtimeClasspath`，改后实测）**

| 项目 | 依赖 | 解析结果 |
|------|------|---------|
| nexus-core | `io.netty:netty-all` | 4.1.115.Final → **4.1.137.Final**（selected by rule） |
| nexus-core | `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.21 → **2.4.20**（selected by rule） |
| nexus-core | `kotlin-stdlib-jdk7` / `-jdk8` | 1.8.0 / 1.4.10 → **2.4.20** |
| nexus-sdk/java | `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 → **11.0.25** |
| nexus-sdk/java | `kotlin-stdlib-jdk8` | 1.4.10 / 1.8.0 / 1.8.21 / 1.9.10 → **2.4.20** |

**两条可复用的纪律**

1. **只写仓库源里真实存在的版本号**：三个目标版本落地前都在 Maven Central 元数据核对过
   （`netty-all 4.1.137.Final` ✓、`kotlin-stdlib-jdk7 2.4.20` ✓、`tomcat-embed-core 11.0.25` ✓）。
   注意 `netty-all 4.1.138.Final`、`tomcat-embed-core 11.0.26` 也已存在 —— 此处**刻意**取
   CVE 描述给出的修复版（可追溯"为什么是这个版本"），tomcat 还刻意与仓库既有 5 个模块的
   目标值一致（避免同一制品出现两个版本）。
2. **`ext['x.version']`（BOM 属性覆盖）与 Gradle `constraints{}` 不是同一机制**：
   前者只对**应用了 `io.spring.dependency-management` 的项目**生效；未应用该插件的模块
   （如 `nexus-sdk/java`，只用 `platform(...)`）必须用原生 `constraints{}` —— 这也是
   §13.5① 那个"漏一个模块"缺陷容易复发的机制原因。

**同一次 dispatch 里出现与本次改动无关的红（必须区分，避免误判）**
run `36842401445` 中 Trivy 镜像扫描 `mpc-engine` / `zk-groth16-service` 同样为红，但这两个模块是
**纯 Rust（有 `Cargo.toml`、无 `build.gradle`）**，与本次 JVM 依赖抬版**无因果关系**；
（另：`36799326089` 是**另一个 workflow**（NexusChain CI/CD Pipeline）的 run，勿混引。
master 安全扫描基线 run `36799326118`（01:04Z）里这两项均为 success，
且该 run 的红是 `Docker Build & Push (nexus-oracle)` —— 与本主题无关。）
本 PR 不含 Rust 侧改动，另行排查（不在 SCA 整改范围内）。

### 13.10 改前红 / 改后绿（同一门禁、同一批依赖）+ 一次闪断的判定（2026-10-01）

**对照证据（两个 run 的依赖树只差 PR #18 本身）**

| 维度 | 改前：master `dee3f5d`（不含 #18） | 改后：`fix/sca-critical-deps` `aae5d3a`（含 #18） |
|------|-----------------------------------|--------------------------------------------------|
| run / job | `36856139060` / `110348949388` | `36842401445` / `110304350054` |
| 「运行 OWASP DC」步骤 | **failure**（`failBuildOnCVSS=9.0f` 被 13 个 CRITICAL 触发） | **success** |
| 「覆盖度自检」步骤 | success（红**不是**能力回退造成） | success |
| 墙钟 | 46m05s（11:33:46Z→12:19:51Z） | 3h03m（09:23:25Z→12:26:53Z，与左列 run 并发抢 NVD 配额） |

**改后报告实测（下载 artifact `owasp-dependency-check-report` 独立复算）**

```
dependencies=250  含漏洞条目=53  去重 CVE=3
按严重度: {'MEDIUM': 53}          ← CRITICAL 0 / HIGH 0
覆盖度: 240/250 = 96%（与改前持平 —— 未用「扫不到」换绿）
dataSource NVD API Last Checked = 2026-10-01T12:26:34Z
四个目标制品：netty-all-4.1.115 / kotlin-stdlib-2.2.21 / tomcat-embed-core-11.0.24 → 0 次 ✓
              quartz-2.3.2 → 条目在、CVE 数 0（抑制生效）✓
```

⇒ §13.5 的 13 个 CRITICAL **连同 45 条 HIGH 一并清零**；剩余 **3 个 MEDIUM CVE**
（`CVE-2023-0833` = okhttp `logging-interceptor 4.9.0`、`CVE-2025-48924` = `commons-lang3 3.12.0`、
`CVE-2026-89044` = netty `4.2.x` 系列），非阻断，另按模块分批处置。

**一次闪断的判定（记录在案，避免下次误判为「抬版引入」）**

强推后首个 run `36859632865` 的 `Build & Test` 在 `:nexus-signing-service:test` 失败：
`GrpcMpcTransportStubTest.testRealGrpcBroadcast()` → `java.io.IOException`，
`Caused by: io.grpc.netty.shaded.io.netty.channel.unix.Errors$NativeIoException`。
判为**既有闪断**的两条硬依据：① 异常类型位于 **gRPC 自带 shaded netty**（`io.grpc.netty.shaded.*`）
命名空间，与本次抬版的坐标 `io.netty:netty-all` **不是同一制品**（`nexus-signing-service`
依赖的是 `io.grpc:grpc-netty-shaded`）；② 同一棵树在 rebase 前的 run 中全绿，
**原样重跑即通过**（同 run 内 `--failed` 重跑结果 success）。⇒ 属真实 unix socket 测试的
稳定性问题，应单独修（不在 SCA 整改范围）。

### 13.11 「永远上报」试运行（2026-10-01 ~ 2026-10-15）

本 job 从「PR 一律跳过」改为「同仓库 PR 真跑」（fork PR 仍跳过、如实显示 `skipped`），
并新增按 ISO 周轮换的 NVD 漏洞库缓存。动机、触发矩阵、成本实测、评估项、两条出口与
逐字回退步骤见 `docs/dependency-check-update-policy.md` 的「永远上报试运行」小节。

一句话理由：**「能不能设为 required check」缺的是 PR 上的成本/稳定性数据，不是判断力**；
而 §13.6 的「不能设为 required」担心，现已被实测部分推翻 —— 本 job 在 PR 路径下会以
`skipped` **上报**（`OWASP Dependency-Check = COMPLETED/SKIPPED`，见 PR #18 的 checks），
并非「永不上报」；「skipped 在 branch protection 下是否阻合并」留作评估项 5 实测。



