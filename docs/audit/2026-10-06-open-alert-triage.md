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

> 附：那两份 `tools/**` 下的 yarn.lock 属于**运维小工具**，实测
> `git grep -l "org/nexus/tools" -- '*.gradle' '*.yml' '*.sh' '*.json'` **零命中**——
> 没有任何 Gradle/CI 脚本引用它们，所以 3 条相关告警（moment / brace-expansion）
> 既不影响构建产物也进不了镜像；真正待议的是"JS 工具为什么放在 Java 源码树里"这个形态问题。

## 3. 逐族处置（只列有上游修复版本的 35 条）

> **处置进度（2026-10-06 同日更新）**：加粗的 4 行已落地——react-router/-dom 由**本 PR** 治，
> c3p0 / commons-lang3 / x/crypto 由 **PR #54** 治（都在来源查清之后动手）。
> 余下 12 行是"能治但需要决策"或"仓库改不到"，每行都写了前置条件，不再只是挂着编号。

| 包 | 现装 | 修复版 | 位置 | 条 | 处置与理由 |
|---|---|---|---|---|---|
| **react-router-dom** | 6.30.4 | **6.30.6** | `nexus-explorer/package-lock.json` | 1 | **本 PR 已升**（`frontend/package.json` 声明 `^6.26.0`，属范围内补丁） |
| react-router | 6.30.4 | 7.18.0 | 根 + `frontend/` 两份 lockfile | 4 | 已随之到 6.30.6；CVE-2026-53669/53666 要 **v7** 才闭合 → 大版本迁移，另案 |
| @remix-run/router | 1.23.3 | 1.23.4 | 根 lockfile | — | 随 react-router 6.30.6 联动，非独立告警 |
| libpng16-16t64 | 1.6.48-1+deb13u5 | deb13u6 | 10 个镜像 | 10 | **仓库代码改不到**：基础镜像层。要么等 distroless/`debian13` 出 u6，要么在 Dockerfile 里显式 `apt-get install libpng16-16t64=...` 并纳入构建可复现性评审 |
| **com.mchange:c3p0** | 0.12.0 | 0.14.0 | core、gateway 镜像 | 2 | **已由 PR #54 抬到 0.14.0**。来源已查清：`nexus-core/nexus-core/build.gradle:265` **显式声明**（接在他们 0.9.5.4→0.12.0 的既有修复注释后面续追）。实测源码对 `com.mchange` **零 import**——它是 `org.quartz-scheduler:quartz` 的运行时数据源池供给，**不是可删的未使用依赖**（删了是运行时炸，不是编译期炸） |
| **commons-lang3** | 3.12.0 | 3.18.0 | core、api-gateway | 2 | **已由 PR #54 治根**。来源：全仓三处声明、**两个真值**——`nexus-core/nexus-core/build.gradle:159` 与 `nexus-api-gateway/build.gradle:107` 硬编码 `3.12.0`，而 `nexus-gateway/build.gradle:138` 是无版本声明、由 Boot 4.0.8 BOM 解析成 **3.19.0**。改法是两处去掉硬编码交 BOM（与 gateway 同口径），不是再钉一个新字面量。实测两棵树解析 3.19.0、`3.12.0` 归零 |
| httpclient5 | 5.5.2 | 5.6.3 | gateway | 1 | **留决策**：BOM 管的——实测依赖树 `…alibaba.nacos:nacos-client:3.1.1 → httpclient5:5.4.4 -> 5.5.2`，且 `spring-boot-dependencies:4.0.8` 有 `httpclient5:5.5.2 (c)` 约束。抬到 5.6.3 等于让 nacos 客户端跑在 Boot 未测过的 HTTP 客户端上，属 BOM 层决策 |
| at.yawk.lz4:lz4-java | 1.10.1 | 1.11.1 | gateway | 1 | **留决策**：来源是 `spring-kafka:4.0.7 → kafka-clients:4.1.2 → at.yawk.lz4:lz4-java:1.10.1`，即 Kafka 客户端的传递依赖。要动就得动 kafka-clients（同 BOM 层），单点 force 一个 lz4 会把 kafka 放进未测组合 |
| qs | 6.15.3 | 6.16.0 | demo + explorer lockfile | 4 | express 写死 `"qs": "~6.15.1"`，6.16.0 **不在其范围内** → 只能 `overrides` 覆盖上游约束。这属于"我方改写库作者声明的约束"，需拍板，本次不做 |
| **golang.org/x/crypto** | 0.55.0 | 0.56.0 | `nexus-sdk/go/go.mod` | 2 | **已由 PR #54 升**。代价如实记：x/crypto v0.56.0 自身 `go.mod` 声明 `go=1.26.0`，`go get` 因此把本模块 go 指令 1.25.0 → **1.26.0**；而 CI 的 `SDK Go regression` **没有任何 setup-go**（`git grep setup-go` 零命中），用 runner 预装 Go，低版本会走 `GOTOOLCHAIN=auto` 现场下载。同包那条 `GO-2026-5932` 仍**无修复版本**，升完还在 |
| brace-expansion | 1.1.20 | 1.1.21 | `…/org/nexus/tools/yarn.lock`、`…/tools/cmd-monitor/yarn.lock` | 2 | **留决策**：手改 yarn.lock 需 yarn 重新解析（不是改数字）。这两份属运维小工具、无任何 Gradle/CI 引用（见 §2 附注），不进构建也不进镜像，所以优先级低于上面几项；真正的待议点是"JS 工具放在 Java 源码树里"这个形态 |
| moment | 2.30.1 | 2.31.0 | `…/tools/cmd-monitor/yarn.lock` | 1 | 同上 |
| serde_with | 2.3.3 | 3.21.0 | `mpc-engine/Cargo.lock` | 1 | **major**，牵 cggmp21 依赖树 |
| tracing-subscriber | 0.2.25 | 0.3.20 | `zk-groth16-service/Cargo.lock` | 1 | major（LOW） |
| rand | 0.7.3 | 0.8.6 / 0.9.3 / 0.10.1 | `mpc-engine/Cargo.lock` | 1 | major，密码学路径，须与上游 crate 一起评估（LOW） |
| curve25519-dalek | 3.2.0 | 4.1.3 | `mpc-engine/Cargo.lock` | 1 | major，历史上伴随**常量时间/校验点**语义变化，绝不能只改数字（LOW） |
| secp256k1 | 0.20.3 | 0.22.2 / 0.23.5 / 0.24.2 | `mpc-engine/Cargo.lock` | 1 | major，同上 |

余下 **835 条没有上游修复版本**（47 个不同包，主体是 `libc6` 252 / `libc-bin` 252 /
`zlib1g` 24 / `libuuid1` 24 / `libstdc++6` 24 / `libgcc-s1` 24 / `gcc-14-base` 24 /
`libexpat1` 20 / …）。仓库内**没有可操作动作**：等 Debian/distroless 出补丁，或按
`.trivyignore` 的既有口径「仅豁免上游声明不修/无可用修复版本的系统包」逐条登记。

## 4. 顺带查出的两个"看不见"问题

### 4.1 告警噪声压过信号

fs 与 docker 两条腿都以 `CRITICAL,HIGH,MEDIUM,LOW` 上传 SARIF，于是 Security tab 里
躺着 870 条 open，其中 58% 是同一对 glibc 发现在 12 个镜像里的重复记账。
**真需要看的 35 条被埋在 835 条"等上游"里。** 两种收敛路线（需拍板，不宜由工具侧单方面改）：

- SARIF 上传限到 `HIGH,CRITICAL`（MEDIUM/LOW 只进 JSON 分诊、不进 code-scanning）；或
- 对确认"无上游修复版本"的系统包条目做带理由 + 到期日的批量 dismiss。

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
