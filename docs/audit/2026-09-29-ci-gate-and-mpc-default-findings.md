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
