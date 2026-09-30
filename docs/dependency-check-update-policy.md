# OWASP Dependency-Check Action 版本更新策略

## 概述

本文档定义了 GitHub Actions 中 OWASP Dependency-Check Action 引用版本的更新策略、审批流程与责任人，旨在防范供应链攻击风险。

## 当前版本

| 项目 | 值 |
|------|-----|
| Action 仓库 | `dependency-check/Dependency-Check_Action` |
| 版本号 | `1.1.0` |
| Commit SHA | `75ba02d6183445fe0761d26e836bde58b1560600` |
| 发布日期 | 2021-04-28 |
| 引用位置 | `.github/workflows/security-scan.yml` |

> ⚠️ **实际执行方式已变更（2026-09-10）**：该 job **已弃用本 Action**，改为直接
> `docker run owasp/dependency-check:latest`（原因：Action 的 `others` input 在
> `action.yml` 里以单引号拼接，含空格的整串会作为**一个 argv** 传给 CLI，导致
> `--failOnCVSS`/`--disableNodeAudit`/`--disableYarnAudit` 全部失效，详见
> workflow 内注释）。
> 故上表仅约束「若回退使用 Action」的场景。当前该 job 的供应链风险点是
> **`owasp/dependency-check:latest` 为浮动 tag、未钉 digest**（每次运行可能拉到
> 不同的引擎版本，门禁结果不可复现）——属**已知未修缺口**，见
> `docs/audit/2026-09-29-ci-gate-and-mpc-default-findings.md` §10。

## 引用规则

**强制要求**：必须使用完整的 40 位 commit SHA 引用，**禁止**使用 `@main`、`@latest` 或 `@<tag>` 等浮动引用。

```yaml
# ✅ 正确：钉版本 sha
uses: dependency-check/Dependency-Check_Action@75ba02d6183445fe0761d26e836bde58b1560600

# ❌ 错误：浮动引用（供应链攻击风险）
uses: dependency-check/Dependency-Check_Action@main
uses: dependency-check/Dependency-Check_Action@latest
uses: dependency-check/Dependency-Check_Action@1.1.0
```

## 更新频率

- **常规更新**：每季度一次（Q1/Q2/Q3/Q4 末月）
- **紧急更新**：当发现以下情况时立即更新
  - 上游发布安全修复版本
  - 当前版本被报告存在漏洞
  - CI 流水线因 action 兼容性问题失败

## 更新审批流程

1. **发起**：开发工程师发起 PR，更新 `security-scan.yml` 中的 commit SHA
2. **验证**：PR 中必须包含以下信息
   - 新版本号与发布日期
   - 新 commit SHA（40 位十六进制）
   - 变更内容摘要（changelog diff）
   - 已验证 CI 流水线通过
3. **审批**：安全负责人（Security Owner）审核并批准 PR
4. **合并**：审批通过后合并到主分支
5. **记录**：更新本表格中的"当前版本"信息

## 责任人

| 角色 | 职责 | 默认人员 |
|------|------|---------|
| 发起人 | 发起版本更新 PR | 开发工程师 |
| 审核人 | 审核更新 PR 的安全性与必要性 | 安全负责人 |
| 执行人 | 合并 PR 并验证 CI | DevOps 工程师 |

## 验证清单

更新版本时，执行以下验证：

- [ ] 新 commit SHA 来自官方仓库 `dependency-check/Dependency-Check_Action`
- [ ] SHA 对应的 tag 为稳定 release（非 pre-release）
- [ ] 已阅读 release changelog，确认无破坏性变更
- [ ] CI 流水线（`security-scan.yml`）执行通过
- [ ] OWASP 报告正常生成（HTML + JSON）
- [ ] 本文档已同步更新

## NVD API Key 配置（✅ 已完成，job 已恢复阻断语义）

### 现状（2026-10-01）

| 项目 | 值 |
|------|-----|
| secret | `NVD_API_KEY`（仓库 Actions secrets，2026-09-30 创建） |
| job 阻断性 | **阻断**（`continue-on-error` 已于 2026-10-01 摘除） |
| 实测证据 | run `36784537663`（workflow_dispatch）：22:31:36Z → 22:51:48Z，**20 分 12 秒**，`success` |
| 漏洞库状态 | 报告 `scanInfo.dataSource` → `NVD API Last Checked = 2026-09-30T22:51:36Z`（engine 13.0.0） |

**判定依据**：缺 key 时该 job 约 5~7 秒即 `exit 1`（下方守卫），而本次运行 **20 分 12 秒**
且报告里留有 NVD 拉取时间戳 —— 两面互证：**key 有效，且确实在用 NVD 库**
（不是同名不同源的库）。

该守卫**保留不动**（刻意设计，不是缺陷）：与其静默跳过让人以为扫描过了，不如明确报错
并给出申请地址。

```bash
if [[ -z "$NVD_API_KEY" ]]; then
  echo "::error::缺少 NVD_API_KEY secret——NVD 已强制 API key，匿名无法拉取漏洞库。"
  exit 1
fi
```

### ⚠️ 覆盖度实测：本 job 目前是「空心绿」（最重要的限制，勿高估）

同一份 run `36784537663` 报告的结构：

| 指标 | 实测值 |
|------|--------|
| `dependencies` 条目数 | 54 |
| 构成（`.js` / `.json` / `.jar`） | 37 / 15 / 2 |
| **取得 CPE（`packages`）的条目** | **0 / 54** |
| `vulnerabilities` 条数 | 0 |
| JVM 侧 | 仅 2 个 `gradle-wrapper.jar`；**完全未解析 Gradle 依赖图** |

**含义**：本 job 当前只做**文件级识别**，没有取得任何可比对 NVD 的包坐标，因此对依赖
漏洞的**实际检测力为零** —— 它的绿 ≠ 依赖无漏洞。JVM 依赖（jackson/spring 等）的真实
覆盖由 **Trivy 镜像扫描的 `Java (jar)` 层**承担（run `36780102329` 实测：`nexus-core`
镜像由「Java (jar) Total: 4 (HIGH: 4)」转为 success）。

为防误读，`security-scan.yml` 已增补**「扫描覆盖度自检」**步骤：每次运行都把上述数字
（含 `NVD API Last Checked`）打印到日志与 Job Summary；覆盖度为零时发 `::warning::`
但**不失败** —— 扫描器能力不足是**待升级项**，不应表现成「门禁抓到漏洞」。

### 升级为「真覆盖」的前置条件（未实施）

实施前，本 job 只应被视为「NVD 库连通性 + 提交物内归档文件」的**浅门禁**。二选一：

1. **引入官方 Gradle 插件** `org.owasp.dependencycheck`：构建期用
   `dependencyCheckAnalyze` 解析真实依赖图，结果带坐标 → CPE 匹配才有对象。
   代价：需把 `NVD_API_KEY` 以 Gradle 属性注入、CI 时长增加、插件版本纳入本策略纳管。
2. **维持现状，把 JVM 覆盖显式挂在 Trivy**：在 `docs/security-sla.md` 写明
   「JVM 依赖漏洞以 Trivy 镜像扫描为准，OWASP DC 仅作 NVD 连通性与归档文件兜底」。

### 命中 CRITICAL（CVSS≥9）时的处置

`--failOnCVSS=9` 一旦触发，job 变红并阻断 workflow。处置顺序：

1. 取 artifact `owasp-dependency-check-report`（HTML/JSON）定位条目；
2. 能修则升版依赖（最低可用修复版本，不跨大版本）；
3. 确属**误报 / 无利用路径**，再写 `dependency-check-suppressions.xml`（置于扫描根目录，
   DC 自动加载），附证据与**到期日**；
4. **不要用 `continue-on-error` 兜底** —— 那等于关闭门禁。

### 为什么必须配 key

NVD 自 **2023-12** 起强制要求 API key，**匿名访问返回 403**。
`--nvdApiDelay` 只是限速参数，**无 key 连 403 都过不去**
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
| Trivy **镜像**（`trivy-image-*`） | **是（当前唯一真实门禁）** | 扫描打包进镜像的 jar，输出 `Java (jar)` 台账；PR #15 的 jackson `2.21.7/3.1.7` 修复即在该层由红转绿（run `36780102329`）。 |
| OWASP DC（本 job） | **否（当前）** | 见上「覆盖度实测」：CPE 命中 0/54，未解析依赖图。 |

**结论**：JVM 依赖漏洞目前**完全依赖 Trivy 镜像扫描**；未被打进任何镜像的依赖
（如仅测试期使用的库）**当前无人覆盖** —— 这正是「升级为真覆盖」的理由。

### 常见误解（避免走弯路）

- ❌ 「加 `--nvdApiDelay` 就能匿名跑」—— 该参数只控制请求频率，不能绕过鉴权
- ❌ 「用企业邮箱才能申请」—— 个人邮箱即可，免费
- ❌ 「不配 key 就等于没扫描」—— 见上，Trivy 镜像扫描仍在覆盖 JVM 依赖
- ❌ 「OWASP DC 绿了 = 依赖没漏洞」—— 当前它 CPE 命中 0/54，绿只代表
  **没识别出可比对的组件**，不代表没有漏洞
- ❌ 「Trivy fs 覆盖 Gradle 依赖」—— 仓库无 `gradle.lockfile`/`pom.xml`，
  fs 侧并不覆盖 Gradle；JVM 覆盖只存在于**镜像扫描**层
- ❌ 「摘了 `continue-on-error` 就是强门禁」—— **阻断语义 ≠ 检测能力**，
  见上「覆盖度实测」

## 相关文档

- `docs/security-sla.md` — 安全漏洞 SLA 策略
- `.github/workflows/security-scan.yml` — 安全扫描流水线
- [OWASP Dependency-Check 官方仓库](https://github.com/dependency-check/Dependency-Check_Action)
- [NVD API Key 申请](https://nvd.nist.gov/developers/request-an-api-key)