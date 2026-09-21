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

## NVD API Key 配置（当前缺失，导致该 job 非阻断）

### 现状

`security-scan.yml` 的 `owasp-dependency-check` job 目前 **`continue-on-error: true`**
（非阻断），原因是**仓库未配置 `NVD_API_KEY` secret**。

该 job 在缺 key 时**快速失败并给出明确指引**（约 5 秒，不会白跑几分钟）：

```bash
if [[ -z "$NVD_API_KEY" ]]; then
  echo "::error::缺少 NVD_API_KEY secret——NVD 已强制 API key，匿名无法拉取漏洞库。"
  exit 1
fi
```

这是**刻意的设计**，不是缺陷：与其静默跳过让人以为扫描过了，
不如明确报错并给出申请地址。`continue-on-error` 仅保证它不阻断流水线。

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
   届时可评估是否摘除 `continue-on-error` 恢复阻断语义
   （建议先观察若干次绿色运行再摘，避免立刻变成新的长期红项）

### key 到位前的替代覆盖

Trivy 的文件系统与镜像扫描（`trivy-fs-sbom`、`trivy-image-*`）
**仍覆盖 Gradle 依赖漏洞**，唯一来源差异是 OWASP 使用的 NVD CPE 数据库。
故 key 到位前以 Trivy 结果为准，安全覆盖不存在空窗。

### 常见误解（避免走弯路）

- ❌ 「加 `--nvdApiDelay` 就能匿名跑」—— 该参数只控制请求频率，不能绕过鉴权
- ❌ 「用企业邮箱才能申请」—— 个人邮箱即可，免费
- ❌ 「不配 key 就等于没扫描」—— 见上，Trivy 仍在覆盖依赖漏洞

## 相关文档

- `docs/security-sla.md` — 安全漏洞 SLA 策略
- `.github/workflows/security-scan.yml` — 安全扫描流水线
- [OWASP Dependency-Check 官方仓库](https://github.com/dependency-check/Dependency-Check_Action)
- [NVD API Key 申请](https://nvd.nist.gov/developers/request-an-api-key)