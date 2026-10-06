# 2026-10-06 open-alert 分诊（Trivy code-scanning）

取证时点：`origin/master = ebd9949`，抓取时间 2026-10-06 12:2x（本地时钟，UTC 04:2x）。
所有数字来自 API 全量分页 + 逐条解析，不是抽样。

## 0. 为什么有这份文档：先纠正我此前带出的错数

本轮会话开始时，我给出的待办里写的是
「**4 条** open medium Trivy 告警（CVE-2026-95512，#2629–#2632）」。

**这个数是错的**（错在我的会话口径，仓库文档里并没有这个说法）——该 CVE 实测有
**10 条** open（#2623–#2632，12 个镜像里 10 个命中 `libfreetype6`），我当时只取了
API 第一页尾部的 4 条就当总数。按 `?state=open`
全量分页实测：

```
total open alerts: 870      （全部 tool=Trivy，全部 most_recent_instance.ref=refs/heads/master）
```

错因是我只取了单页并按 severity 过滤，把「一页里看到的 4 条」当成了总量——
空/截断的 grep 结果不是阴性证据。本文件即为修正后的基线，后续判"告警是否归零"以这里为准。

## 1. 全量构成

| 维度 | 拆分 | 条数 |
|---|---|---|
| 扫描腿 | `trivy-docker-scan`（镜像内容，location 为 `library/<模块>`） | 849 |
| | `trivy-fs-scan`（仓库内 lockfile / go.mod / 源码） | 21 |
| 上游 severity | MEDIUM 532 / LOW 323 / UNKNOWN 15 | 870 |
| GitHub 映射 | warning 532 / note 338 | 870 |
| 是否有上游修复版本 | 无修复版本 835 / 有修复版本 35 | 870 |
| 去重后真实发现数 | **149 个不同 (包, CVE) 组合**，92 个不同 CVE | — |

**870 ÷ 149 ≈ 5.84**：同一条发现在 12 个镜像里各记了一次。单是 `libc6` + `libc-bin`
两个 Debian 系统包（21 条 CVE）就贡献了 **504 条 = 全量的 58%**。

这些全部**不阻断 CI**：阻断步骤是 `--severity CRITICAL,HIGH --exit-code 1`，
本批里没有 CRITICAL/HIGH（唯一的 CRITICAL 是 proxy-addr 2.0.7，由 PR #50 处理）。
所以这是一份**分诊台账**，不是一份红名单。

## 2. 按记录对象

镜像腿（`trivy-docker-scan`，849 条，按 `environment.module` 计）：

```
121  mpc-engine            121  zk-groth16-service
 64  nexus-gateway          62  nexus-core            61  nexus-api-gateway
 60  ×7（wallet/signing/bridge/compliance/settlement/oracle/analytics）
```

仓库腿（`trivy-fs-scan`，21 条，按 `location.path` 逐条实测）：

```
 5  nexus-explorer/package-lock.json        4  mpc-engine/Cargo.lock
 3  nexus-sdk/go/go.mod                     2  demo/package-lock.json
 2  nexus-explorer/frontend/package-lock.json
 2  …/org/nexus/tools/cmd-monitor/yarn.lock 1  …/org/nexus/tools/yarn.lock
 1  zk-groth16-service/Cargo.lock
 1  nexus-core/nexus-core/src/main/java/org/nexus/util/JWTUtil.java  ← 唯一一条"非依赖"类
```

那 1 条是 `nexus-core/.../util/JWTUtil.java:149-155` 里**一段注释掉的 `main` 演示块**写死了
一枚 2019-02 的示例 JWT，命中 Trivy `jwt-token` 规则（MEDIUM，alert #138）。
**已由 PR #54 删除该注释块**（零行为变更，`compileJava` 实测通过）。
但它**不等于密钥消失**：token 仍在 git 历史里，当年那把 HS256 签名 key 是否还在
任何环境使用属**轮换决策**，未由工具侧代做。

> 附（2026-10-07 复核后改写，纠正我 10-06 写下的一段过度表述）：那两份 `tools/**` 下的 yarn.lock
> 属于**运维小工具**。我原先写"既不影响构建产物也**进不了镜像**"——后半句不准：
> `nexus-core/nexus-core/src/main/java/org/nexus/tools/cmd-monitor/Dockerfile` 是存在的，
> 人工 `build_docker.sh:3` 就能打出 `nexus/service-monitor`。
> 实测后的正确说法是三条：
> 1. **CI/Gradle/compose 对它零引用**：`git grep -l "org/nexus/tools" -- '*.gradle' '*.yml' '*.sh' '*.json'` 空，
>    `git grep -n "service-monitor"` 只命中它自己的 build 脚本 → 12 个受扫描的镜像里没有它。
> 2. **那两条 lockfile 不被任何安装路径消费**：Dockerfile 走 `COPY package*.json` + `RUN npm install`，
>   目录里没有 `package-lock.json`，npm 不读 `yarn.lock`，`^` 范围现取最新版。
> 3. 该工具自身的形态问题比这 3 条告警大：`FROM node:10-alpine`，而本仓 2026-10-06 已把
>    Node 口径统一到 `engines >=24.0.0 <27.0.0`（commit `8895d38`）。
>    也就是说它是一条**独立的小工具治理项**（要不要留、留则升级），不是"依赖漏洞待修"。

## 3. 逐族处置（只列有上游修复版本的 35 条）

> **处置进度（2026-10-06）**：加粗的 4 行已落地——react-router/-dom 由**本 PR** 治，
> c3p0 / commons-lang3 / x/crypto 由 **PR #54** 治（都在来源查清之后动手）。
>
> **2026-10-07 复核补记**：余下每一行都做了**前置条件实测**，不再是"需要拍板"的悬空问号；
> 结论分五类，理由与可重跑命令见 §3.1：
> - **判定不可达，因而不做**：qs 2 个 CVE × 2 份 lockfile = 4 条（前提选项在本仓与上游默认值里都未开启）；
> - **上游版本集合阻塞，仓库侧改不到**：5 个 Rust major（逐包父依赖已实测列出）；
> - **BOM 层取舍，可执行但缺验证面**：httpclient5、lz4-java（覆盖写法已备好，需先有带 Nacos+Kafka 的冒烟）；
> - **对象是一棵没人安装的树**：3 条 yarn.lock 告警（工具实际走 `npm install` 且无 package-lock，
>   而 registry 上这两个包的**最新补丁版恰是告警给的修复版**，见 §3.1 的 yarn.lock 条目）；
> - **不属仓库层**：libpng16-16t64 的 10 条（基础镜像 / 构建可复现性评审）。

| 包 | 现装 | 修复版 | 位置 | 条 | 处置与理由 |
|---|---|---|---|---|---|
| **react-router-dom** | 6.30.4 | **6.30.6** | `nexus-explorer/package-lock.json` | 1 | **本 PR 已升**（`frontend/package.json` 声明 `^6.26.0`，属范围内补丁） |
| react-router | 6.30.4 | 7.18.0 | 根 + `frontend/` 两份 lockfile | 4 | 已随之到 6.30.6；CVE-2026-53669/53666 要 **v7** 才闭合 → 大版本迁移，另案 |
| @remix-run/router | 1.23.3 | 1.23.4 | 根 lockfile | — | 随 react-router 6.30.6 联动，非独立告警 |
| libpng16-16t64 | 1.6.48-1+deb13u5 | deb13u6 | 10 个镜像 | 10 | **仓库代码改不到**：基础镜像层。要么等 distroless/`debian13` 出 u6，要么在 Dockerfile 里显式 `apt-get install libpng16-16t64=...` 并纳入构建可复现性评审 |
| **com.mchange:c3p0** | 0.12.0 | 0.14.0 | core、gateway 镜像 | 2 | **已由 PR #54 抬到 0.14.0**。来源已查清：`nexus-core/nexus-core/build.gradle:265` **显式声明**（接在他们 0.9.5.4→0.12.0 的既有修复注释后面续追）。实测源码对 `com.mchange` **零 import**——它是 `org.quartz-scheduler:quartz` 的运行时数据源池供给，**不是可删的未使用依赖**（删了是运行时炸，不是编译期炸） |
| **commons-lang3** | 3.12.0 | 3.18.0 | core、api-gateway | 2 | **已由 PR #54 治根**。来源：全仓三处声明、**两个真值**——`nexus-core/nexus-core/build.gradle:159` 与 `nexus-api-gateway/build.gradle:107` 硬编码 `3.12.0`，而 `nexus-gateway/build.gradle:138` 是无版本声明、由 Boot 4.0.8 BOM 解析成 **3.19.0**。改法是两处去掉硬编码交 BOM（与 gateway 同口径），不是再钉一个新字面量。实测两棵树解析 3.19.0、`3.12.0` 归零 |
| httpclient5 | 5.5.2 | 5.6.3 | gateway 镜像内 `app/app.jar/BOOT-INF/lib/httpclient5-5.5.2.jar` | 1 | **BOM 层取舍，写法已备好但我不推**（#1732，CVE-2026-64607，MEDIUM）。来源：`nacos-client:3.1.1 → httpclient5:5.4.4 -> 5.5.2`，`spring-boot-dependencies:4.0.8` pom 第 75 行有 `<httpclient5.version>5.5.2</httpclient5.version>` → 覆盖写法是 `ext['httpclient5.version'] = '5.6.3'`（与 PR #14/#15 的 `ext['jackson-2-bom.version']` 同一机制，已验证有效）。**活路径确认**：gateway 默认注册 Nacos（`nexus-gateway/src/main/resources/application.yml:250-251 nacos.enabled: true`，dev compose `NEX_NACOS_ENABLED=${NEX_DEV_NACOS_ENABLED:-true}`，`docker-compose.yml:55`）。**为什么不单方推**：唯一能抓回归的门禁 `Build & Test` 里既没有 Nacos 也没有 Kafka（CI 全文只有一个 `services:` 块 = Flyway 用的 MySQL 8.0，`ci.yml:818`；kind 冒烟 `ci.yml:665` 明确写"Nacos/PG/Redis 等基础设施 kind 内不具备"；性能冒烟 `performance-test.yml:164` 反过来把 Nacos 门控关掉）→ 推上去必然是"全绿但未验证"。要落地就得配一次带 Nacos 的全栈冒烟再合。 |
| at.yawk.lz4:lz4-java | 1.10.1 | 1.11.1 | gateway 镜像内 `app/app.jar/BOOT-INF/lib/lz4-java-1.10.1.jar` | 1 | **BOM 层取舍，且是活压缩路径**（#1746，CVE-2026-59949，MEDIUM）。来源：`spring-kafka:4.0.7 → kafka-clients:4.1.2 → lz4-java:1.10.1`；Boot BOM 里**没有** lz4 的属性行（该 pom 只出现 `<kafka.version>4.1.2</kafka.version>`，第 120 行），所以要么抬 `ext['kafka.version']`，要么在 gateway 显式声明 `implementation 'at.yawk.lz4:lz4-java:1.11.1'` 覆盖传递版本。**可达性反而是"确实在用"**：`deploy/kafka/kafka-client-config.yaml:59 compression.type: lz4`、`:138 SPRING_KAFKA_PRODUCER_PROPERTIES_COMPRESSION_TYPE: "lz4"`，且 `kafka-topics.yaml` 六个 topic 都是 `compression.type: producer`。不推的理由与上一行相同（CI 无 Kafka，见 §3.1）；这条的额外注意点是 CVE 落在 **JNI XXHash** 路径上，若运行环境实际走纯 Java 实现则暴露面不同——这一点我**未实测**，留给带 Kafka 的冒烟一并确认。 |
| qs | 6.15.3 | 6.16.0 | demo + explorer lockfile | 4 | **判定不可达，不做 `overrides`**（2026-10-07 实测，见 §3.1）。两个 CVE 各有硬前提，本仓与上游默认配置都不满足：CVE-2026-82562 需 `qs.parse(..., {comma: true, throwOnLimitExceeded: true})`；CVE-2026-82417 需**调用 `qs.stringify`** 序列化攻击者可控键的对象。而本仓 `qs` 只由 `express@4.22.2` 与 `body-parser` 以 `~6.15.1` 传递引入（两处都是 `parse`，不 `stringify`），全仓零处 `require('qs')`/`from 'qs'`、零处 `query parser` 覆盖 → 抬到 6.16.0 只能改写上游声明的约束，却换不到实际暴露面的收敛。另有一层：这两个 npm 树**不进任何镜像**（18 个 Dockerfile 与 docker-compose 无一引用 `nexus-explorer`/`demo`，只在 CI 的 `npm ci` 步骤里装）。 |
| **golang.org/x/crypto** | 0.55.0 | 0.56.0 | `nexus-sdk/go/go.mod` | 2 | **已由 PR #54 升**。代价如实记：x/crypto v0.56.0 自身 `go.mod` 声明 `go=1.26.0`，`go get` 因此把本模块 go 指令 1.25.0 → **1.26.0**；而 CI 的 `SDK Go regression` **没有任何 setup-go**（`git grep setup-go` 零命中），用 runner 预装 Go，低版本会走 `GOTOOLCHAIN=auto` 现场下载。同包那条 `GO-2026-5932` 仍**无修复版本**，升完还在 |
| brace-expansion | 1.1.20 | 1.1.21 | `…/org/nexus/tools/yarn.lock`、`…/tools/cmd-monitor/yarn.lock` | 2 | **告警描述的是一棵没人安装的树**（#2572/#2571，MEDIUM；修复线 1.1.21/2.1.7/3.0.9/5.0.12）。链：`glob@^7.1.4 → minimatch@^3.1.1 → brace-expansion@^1.1.7`（父链从 lock 里逐块读出）。这两份是 **yarn lockfile v1**，而该工具唯一声称的构建路径 `cmd-monitor/Dockerfile` 写的是 `COPY package*.json ./` + `RUN npm install`——**目录里没有 `package-lock.json`，npm 根本不读 yarn.lock**，按 `^` 范围现取最新版；实测 registry 上 `1.x` 的**最新就是 1.1.21，正好是修复版**（`npm view brace-expansion versions` → `[..., 1.1.19, 1.1.20, 1.1.21]`）→ 真打出的镜像不会带 1.1.20 这棵树。CI/Gradle/compose 对 `org/nexus/tools` 零引用（`git grep -l "org/nexus/tools" -- '*.gradle' '*.yml' '*.sh' '*.json'` 空），镜像名 `nexus/service-monitor` 只出现在该工具自己的 `build_docker.sh:3`。本机无 yarn（`which yarn` 空），手改 v1 lock 的 version/resolved/integrity 三行属"改完无法验证"，而收益只是关掉 2 条对幽灵树的记账 → **不手改**；要让告警消失的正确动作是 §2 附注里那条治理项（要么给工具补 `package-lock.json` + 升级 Node 基线，要么明确它已废弃）。 |
| moment | 2.30.1 | 2.31.0 | `…/tools/cmd-monitor/yarn.lock` | 1 | 同上一条，判定与理由一致（#2463，MEDIUM，触发面是**把不可信对象传给 `moment.locale()`**）。链：`node-schedule@^1.3.2 → cron-parser@^2.18.0 → moment-timezone@^0.5.31 → moment@^2.29.4`；`node-schedule` 确实在用（`schedule-monitor.js:5`、`wait-pool-empty.js:3` 只调它的 schedule 接口）。实测 registry `2.x` 最新 = **2.31.0，正是修复版** → `npm install` 现取即已闭合；钉着 2.30.1 的只有那份不被任何安装路径读取的 yarn.lock。 |
| serde_with | 2.3.3 | 3.21.0 | `mpc-engine/Cargo.lock` | 1 | **上游版本集合阻塞**（#1892，GHSA-7gcf-g7xr-8hxj，MEDIUM）。父依赖实测：`cggmp21`、`cggmp21-keygen`、`generic-ec`、`key-share` 四方都声明 `serde_with = "2"`；而同一个 lock 里已有 `serde_with 3.22.0`（父：`paillier-zk`）→ cargo 本来就在容纳两条大版本。把 2.3.3 抬到 3.x 不是改数字，是要这四个密码学 crate 一起动，仓库侧无可操作点。 |
| tracing-subscriber | 0.2.25 | 0.3.20 | `zk-groth16-service/Cargo.lock` | 1 | **上游版本集合阻塞**（#139，CVE-2025-58160，LOW）。父依赖：`ark-relations`（`zk-groth16-service/Cargo.toml:13` 锁 ark-relations 0.4）。本仓自己声明的 `tracing-subscriber` 已经是 `"0.3"`（`Cargo.toml:21`，lock 解析 0.3.23）——**被编进图里的那份是 ark 的传递依赖，不是我们的调用面**；且唯一 EnvFilter 用法是常量串 `with_env_filter("info")`（`src/main.rs:153`），该 CVE 的触发面（解析不可信 directive 串 panic）在本仓不成立。 |
| rand | 0.7.3 | 0.8.6 / 0.9.3 / 0.10.1 | `mpc-engine/Cargo.lock` | 1 | **上游版本集合阻塞 + 本仓有意锁定**（#2，GHSA-cq8v-f236-94qc，LOW）。父依赖：`curv-kzen`、`mpc-engine` 自身。`mpc-engine/Cargo.toml:90-93` 写明锁 0.7 的两条理由：与 curv-kzen 0.9 / multi-party-ecdsa 0.8.1 依赖一致，且 rand 0.8 的 `getrandom 0.2 + windows-sys 0.61` 在 Windows GNU 工具链需要 dlltool。图里另已共存 0.4.6 / 0.6.5 / 0.8.8 三份（父：pairing-plus / curv-kzen+secp256k1+zk-paillier / tower）→ 抬 0.7 只会分裂版本，不减少任何一条密码学路径的暴露。 |
| curve25519-dalek | 3.2.0 | 4.1.3 | `mpc-engine/Cargo.lock` | 1 | **上游版本集合阻塞**（#1，CVE-2024-58262，LOW）。父依赖：`curv-kzen`（0.9 系）；同 lock 里 `4.1.3` 已存在，父为 `generic-ec`（cggmp21 系）。3→4 历史上伴随**常量时间/校验点语义变化**，绝不能只改数字，须与 curv-kzen 一起评估。 |
| secp256k1 | 0.20.3 | 0.22.2 / 0.23.5 / 0.24.2 | `mpc-engine/Cargo.lock` | 1 | **上游版本集合阻塞**（#3，GHSA-969w-q74q-9j8v，MEDIUM）。父依赖：`curv-kzen`、`mpc-engine` 直接声明（`Cargo.toml:82`）。`Cargo.toml:72-81` 的**注释记录**（不是我本轮复现的）：曾试 0.29，`Signature` 不再从根导出、`verify()` 换成 `verify_ecdsa()`、`from_slice` 弃用，需重写 `gg20.rs` 全部调用，而 multi-party-ecdsa 0.8.1 内部仍绑 0.20 API → 版本分裂。**修复线从 0.22 起，0.20/0.21 没有补丁版可留**，所以仓库侧无从下手，只能等 multi-party-ecdsa 上游。⚠ 该注释把它挂成"REQ-26/P2，详见 spec.md REQ-26"，但**`spec.md` 未被仓库跟踪**（`git ls-files \| grep -E '(^\|/)spec\.md$'` 零命中，`git grep REQ-26` 在 docs 下也零命中）→ 这条待办目前无处可查，需重新挂到一个真实工单/文档上。 |

余下 **835 条没有上游修复版本**（47 个不同包，主体是 `libc6` 252 / `libc-bin` 252 /
`zlib1g` 24 / `libuuid1` 24 / `libstdc++6` 24 / `libgcc-s1` 24 / `gcc-14-base` 24 /
`libexpat1` 20 / …）。仓库内**没有可操作动作**：等 Debian/distroless 出补丁，或按
`.trivyignore` 的既有口径「仅豁免上游声明不修/无可用修复版本的系统包」逐条登记。

### 3.1 处置原则：可达性优先于版本号（2026-10-07）

10-06 那一版把余下每一行统统称"需要决策"，其实是**没查完**。这一节把每条的
"能不能改 / 该不该改"拆成三个正交问题，并给出可重跑命令：

| 问题 | 判据 | 命令 |
|---|---|---|
| 这棵树**真的被安装/运行吗**？ | lockfile 是否在任何 `npm ci`/`npm install`/Gradle/CI/镜像路径上被消费 | `cat <tool>/Dockerfile`、`git grep -l "<路径关键字>" -- '*.gradle' '*.yml' '*.sh' '*.json'` |
| CVE 的**前提选项**开着吗？ | 读告警正文 `rule.full_description` 里列的触发条件，再 grep 本仓与上游默认值 | `gh api repos/Levango7/NexusChain/code-scanning/alerts/<n> --jq .rule.full_description` |
| 版本改得动吗？ | 解析 lock 的**反向依赖**：父包是上游 crate/BOM 还是我们自己声明的 | 见下面的 python 片段（Cargo.lock v4） |

**结论落位**（五条。它们分别落在"不必 / 不能 / 升了也没用 / 能升但缺验证面 / 不属仓库层"这五个不同格子里，
所以处置方式各不同——这正是不能只看 `Fixed Version` 一列的原因）：

- **qs（4 条）**：不升。两个 CVE 分别要 `qs.parse(comma+throwOnLimitExceeded)` 和
  **`qs.stringify` 处理攻击者可控键**；本仓零处 `require('qs')`/`from 'qs'`、零处 `query parser`
  覆盖（`git grep -nE "\bqs\.(parse|stringify)|require\('qs'\)" -- '*.js' '*.ts' ...` 空），
  而唯一两个消费者 `express@4.22.2` / `body-parser@1.20.6` 只做 `parse`
  （`express/lib/utils.js:289` 传 `{allowPrototypes, arrayLimit}`；
  `body-parser/lib/types/urlencoded.js:169-175` 传 `{allowPrototypes, arrayLimit, depth, strictDepth, parameterLimit}`
  —— **两处都没有 `comma` / `throwOnLimitExceeded` / `plainObjects`；行号取自本机按 lockfile 装出的
  `demo/node_modules`，`qs` 实测 6.15.3，与告警里的 Installed Version 一致）。
  抬到 6.16.0 需要 `overrides` 改写 express 自己声明的 `~6.15.1`，代价换不来暴露面收敛。
  **重新评估触发条件**：任何人引入 `qs` 直接调用、给 express 传 query-parser 选项、
  或把 explorer/demo 打进镜像（目前 18 个 Dockerfile 无一引用它们）。
- **5 个 Rust major**：改不到，父包全是上游。反向依赖实测（`Cargo.lock v4`，
  注意 v4 的 `dependencies` 项在"同名只有一个版本"时**不带版本号**，解析时必须回查，
  否则会把 `secp256k1`/`tracing-subscriber` 误算成"无父"——我第一遍就踩了这个）：
  `curve25519-dalek 3.2.0 ← curv-kzen`、`secp256k1 0.20.3 ← curv-kzen + mpc-engine`、
  `rand 0.7.3 ← curv-kzen + mpc-engine`、`serde_with 2.3.3 ← cggmp21/cggmp21-keygen/generic-ec/key-share`、
  `tracing-subscriber 0.2.25 ← ark-relations`。
  `mpc-engine/Cargo.toml:27` 已把根因写明：`multi-party-ecdsa 0.8.1` 自身钉住
  `curv-kzen 0.9 / kzen-paillier 0.4.2 / sha2 0.9 / secp256k1 0.20`。
- **httpclient5 / lz4-java**：**是活路径**（Nacos 默认开、Kafka 压缩配了 lz4），
  覆盖写法也都已备好——httpclient5 走 `ext['httpclient5.version'] = '5.6.3'`；
  lz4 在 BOM 里**没有属性行**，所以只有两条：抬 `ext['kafka.version']`（当前 4.1.2），
  或在 `nexus-gateway/build.gradle` 显式声明 `implementation 'at.yawk.lz4:lz4-java:1.11.1'` 覆盖传递版本。
  属性名不是猜的，取自本机 Gradle 缓存里的 BOM：
  `grep -nE "<httpclient5.version>|<kafka.version>|lz4" "$GRADLE_USER_HOME/caches/modules-2/files-2.1/org.springframework.boot/spring-boot-dependencies/4.0.8/*/spring-boot-dependencies-4.0.8.pom"`
  → 第 75 行 `httpclient5 = 5.5.2`、第 120 行 `kafka = 4.1.2`，**无 lz4 行**。
  不单方推的唯一理由是**验证面**：CI 里唯一的 `services:` 是 Flyway 的 MySQL（`ci.yml:818`），
  没有任何 Nacos/Kafka，kind 冒烟明确说这些基础设施不具备（`ci.yml:665`），
  性能冒烟还反向关掉 Nacos（`performance-test.yml:164`）→ 推上去必然"全绿但未验证"。
  要闭合需要先有一次带 Nacos + Kafka 的全栈冒烟，这是排期问题不是判定问题。
- **yarn.lock 的 3 条**：告警记在**一棵没人安装的树**上。工具实际的安装路径
  `cmd-monitor/Dockerfile` 用 `npm install` 且目录里没有 `package-lock.json`（npm 不读 `yarn.lock`），
  而 registry 现取的最新值恰好就是告警要求的修复版：
  `npm view brace-expansion versions` → 1.x 末位 **1.1.21**（= Fixed Version），
  `npm view moment versions` → 2.x 末位 **2.31.0**（= Fixed Version）。
  所以手改 lock 只是把记账抹平、不改变任何产物；真正该做的是给这个工具补 lockfile +
  把 `FROM node:10-alpine` 提到本仓 Node 口径，或宣告它废弃（见 §2 附注）。
- **libpng16-16t64（10 条）**：这 10 行不是"决策"而是**基础镜像层**，10-06 那版已写明。
  唯一的仓库侧动作是在 Dockerfile 里 `apt-get install libpng16-16t64=1.6.48-1+deb13u6`
  固定补丁版——但那会把镜像构建钉死在 Debian 的一个具体发布号上，
  属"构建可复现性"评审范围，不该由一次依赖修复顺带做掉。

反向依赖的复跑片段（两把 `Cargo.lock` 都能用）：

```python
import re, collections
def load(p):
    txt = open(p, encoding='utf-8').read()
    blocks = []
    for b in txt.split('[[package]]')[1:]:
        nm = re.search(r'^name = "([^"]+)"', b, re.M)
        vr = re.search(r'^version = "([^"]+)"', b, re.M)
        if nm and vr: blocks.append((nm.group(1), vr.group(1), b))
    by_name = collections.defaultdict(list)
    for n, v, _ in blocks: by_name[n].append(v)
    parents = collections.defaultdict(set)
    for n, v, b in blocks:
        m = re.search(r'^dependencies = \[(.*?)^\]', b, re.S | re.M)
        for raw in re.findall(r'"([^"]+)"', m.group(1)) if m else []:
            parts = raw.split()
            if len(parts) > 1: parents[(parts[0], parts[1])].add(n)   # 带版本：直接配对
            else: parents[(parts[0], by_name[parts[0]][0])].add(n)     # v4 省略版本：回查唯一版本
    return parents
```

> **本节的方法论 takeaway**：`Fixed Version` 那一列只回答"上游有没有补丁"，
> 不回答"我们能不能装上"，更不回答"装了有没有用"。三者要分别取证，
> 否则台账会退化成一张版本号抄写表——而抄来的版本号本身也可能跨仓照抄出错
> （Jackson 3 线本仓 3.1.6、姊妹仓给的是 3.2.2）。



## 4. 顺带查出的两个"看不见"问题

### 4.1 告警噪声压过信号

fs 与 docker 两条腿都以 `CRITICAL,HIGH,MEDIUM,LOW` 上传 SARIF，于是 Security tab 里
躺着 870 条 open，其中 58% 是同一对 glibc 发现在 12 个镜像里的重复记账。
**真需要看的 35 条被埋在 835 条"等上游"里。** 改口径的位置就两处，已核实：
`.github/workflows/security-scan.yml:51`（fs 腿）与 `:160`（docker 腿）的 `severity:` 行，
输出分别是 `:53-54`、`:161-162`。三条路线的**代价我已经量化**（数字取自本节 §1 的同一次全量分页）：

| 路线 | 改动 | 后果 |
|---|---|---|
| A. SARIF 限到 `HIGH,CRITICAL` | 两行 `severity:` | 当前 870 条**全部停止新增上报**（本批一条 HIGH/CRITICAL 都没有；唯一的 CRITICAL 是 proxy-addr，已由 PR #50 闭合）。等于把 MEDIUM/LOW 的台账从 GitHub 搬进本文件——是取舍，不是免费收敛 |
| B. 只去掉 `LOW,UNKNOWN` | 两行 `severity:` | 剩 **532 条 MEDIUM**，仍吵；但 12 个镜像 × 同包的重复还在 |
| C. 批量 dismiss"无上游修复版本"的系统包 | 不改 workflow，改 Security tab 状态 | 直接消掉 **835 条**，MEDIUM/LOW 的可见性保留；每条要写理由 + 到期日，且 GitHub 会在下次扫描确认消失前保持 open |

我倾向 **C**（它消掉的是"确实无事可做"的那 835 条，A/B 消掉的是"以后可能要看"的），
但这条**不由我单方执行**：dismiss 是 Security tab 上的批量状态变更，属于口径决策。
现有 `.trivyignore` 24 条只覆盖镜像阻断腿，管不到 code-scanning 告警的产生。

### 4.2 同一个前端有两份 lockfile，且已经漂移

- `nexus-explorer/package-lock.json` —— CI 在 `nexus-explorer` 目录 `npm ci` 时用（ci.yml:138）
- `nexus-explorer/frontend/package-lock.json` —— CI 在 `nexus-explorer/frontend` 目录
  `npm ci` 时用（ci.yml:347-349 那个 job）

实测两者对同一依赖给出不同真值：react-router `6.30.4`（根）vs `6.30.6`（frontend）。
本 PR 把根 lockfile 提到 6.30.6，两边暂时对齐；但**为什么会同时存在两份**是结构问题——
workspace 根 lockfile 本应覆盖 frontend，重复的 frontend lockfile 意味着两条 CI 腿
可能装出两个不同的前端依赖树。要么删掉 frontend 那份改用 workspace 根，要么明确它只服务那个 job。

## 5. 复核方法（逐条可重跑）

```bash
# 全量 open（务必 --paginate，否则又是单页误判）
gh api "repos/Levango7/NexusChain/code-scanning/alerts?state=open&per_page=100" --paginate
```

字段口径（我踩过坑）：

- CVE 号在 **`rule.id`**；`rule.description` / `full_description` 里 grep CVE 会取空。
- 包名 / 现装版本 / 修复版本在 `most_recent_instance.message.text` 的
  `Package:` / `Installed Version:` / `Fixed Version:` 三行（`Fixed Version:` 为空 = 上游无修复）。
- 镜像腿的模块在 `most_recent_instance.environment`（形如 `{"module":"nexus-gateway"}`），
  仓库腿的对象在 `most_recent_instance.location.path`。
- 扫描腿用 `most_recent_instance.analysis_key` 尾段区分 `trivy-docker-scan` / `trivy-fs-scan`。
