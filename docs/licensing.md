# 许可合规说明（Licensing）

> **修订说明（2026-09-17）**：本文是仓库声明盘点，不是法律意见或合规认证。
> 文件头匹配不能证明整个目录均属 LGPL；仅凭进程隔离不能判定 GPL 义务范围。
> 保留文件级许可和 MIT 组件声明，不通过新增 NOTICE 给现有代码重新许可。
>
> **状态**：Active —— 合规声明已落盘；**clean-room 重写 / 法务确认仍为待办**（见 §5）
> **原则**：结论全部附文件与行号证据，可复核。本文档只陈述仓库内可取证的事实，不构成法律意见。

---

## 1. 问题（发现时的原状）

| 事实 | 证据 |
|---|---|
| 根许可为 Apache-2.0 | `LICENSE:1-3`（`Apache License / Version 2.0, January 2004`） |
| 但 `nexus-core` 携带 LGPL-3.0 全文 | `nexus-core/LICENSE:1-4`（`GNU LESSER GENERAL PUBLIC LICENSE / Version 3, 29 June 2007` / `Copyright (c) [2016] [ <ether.camp> ]`） |
| 且 166 个 Java 源文件带 LGPL-3.0-or-later 文件头 | `grep -rl "GNU Lesser General Public License" --include=*.java` = 166（`nexus-core/nexus-core/src` 162 + `nexus-consortium/consortium/src` 4）；示例：`nexus-core/nexus-core/src/main/java/org/nexus/core/BlockChainOptional.java:1-17` |
| 且源码含上游项目名 `java-nexuscore` | 163 个 `.java` 文件命中（同上示例 `:3`） |
| README 曾仅声明 Apache-2.0，且对"派生自 LGPL 项目"零披露 | 修复前 `README.md` 许可证章节只有 `[Apache License 2.0](LICENSE)`；全仓搜索 `LGPL` / `fork` / `衍生` / `java-nexuscore` 在 README/PRD/ARCHITECTURE 均无命中 |

**结论**：整仓单一声明 Apache-2.0 与仓库内实际存在的 LGPL 派生内容冲突。
LGPL-3.0 对分发方有源码提供、许可保留与修改标注义务，
该缺口在**对外交付、商业化售卖、客户尽调**场景属法律风险（非工程缺陷）。

## 2. 已落地的修复（本次）

| 变更 | 文件 |
|---|---|
| 新增 NOTICE：按模块划分许可（Apache-2.0 / LGPL-3.0-or-later）+ 上游派生披露 + 分发义务摘要 | `NOTICE`（新建） |
| README 许可证章节改为分模块声明并指向本文档 | `README.md` 许可证章节 |
| CI 门禁：声明存在性 + 越界 GPL/LGPL/AGPL 头防护 | `scripts/check-license-headers.sh`（新建）+ `.github/workflows/ci.yml` 步骤 |

脚本行为（可本地运行：`bash scripts/check-license-headers.sh`）：

1. 断言 `nexus-core/LICENSE` 含 `GNU LESSER GENERAL PUBLIC LICENSE`；
2. 断言 `README.md` / `NOTICE` 含 `LGPL-3.0` 声明字样（防止声明被误删）；
3. 扫描 `git ls-files '*.java'`，任何带 `GNU (LESSER )?GENERAL PUBLIC LICENSE` /
   `AGPL` 头的文件，若不在**白名单前缀**内则失败并打印文件清单；
4. 打印各白名单前缀下的命中计数（供人工复核是否与本文档一致）。

白名单前缀（与 NOTICE §2 严格一致）：

```
nexus-core/nexus-core/src/
nexus-consortium/consortium/src/
```

## 3. 许可范围清单（当前）

| 路径 | 许可 | 依据 |
|---|---|---|
| 根 `LICENSE` | Apache-2.0 文本；不覆盖更具体的文件/组件声明 | 根许可 |
| `nexus-core/nexus-core/src/` 中匹配文件头的 162 个 Java 文件 | LGPL-3.0-or-later 文件头 | 文件头 + `nexus-core/LICENSE`；不据此判整个目录 |
| `nexus-consortium/consortium/src/` 中匹配文件头的 4 个 Java 文件 | LGPL-3.0-or-later 文件头 | 保留声明，来源/结合关系待核实 |
| `mpc-engine` | 本地包声明 Apache-2.0；manifest 注释记录 GPL 依赖 | `Cargo.toml`；分发义务须结合解析依赖与交付形式审查 |
| `nexus-explorer`（根/前端/后端）、`nexus-devtools`、TS SDK | MIT 组件声明 | 各 `package.json`；与根许可不同不必然冲突 |
| `nexus-core/nexus-core/src/main/java/org/nexus/tools/` 内嵌 JS | 须逐文件盘点原始声明和实际交付关系 | 未证明“全部无许可”或“构建不使用”，不得如此表述 |

## 4. 注意事项（对工程操作的影响）

1. **不要给 LGPL 范围内的文件换头**：`nexus-core/nexus-core/src` 下改动文件时，
   必须保留原 LGPL 头（第 1 条义务）。若确需重写某文件为原创，应整文件重写并
   在 PR 中说明，同时更新 NOTICE/本文档与脚本白名单。
2. **新增 GPL/LGPL/AGPL 依赖或源码**：同步 NOTICE §3 与 §3 表格，否则 CI 失败。
3. **依赖许可扫描尚未接入 CI**：当前 CI 只有 Trivy（漏洞）/ gitleaks（密钥）/
   OWASP Dependency-Check（漏洞，非阻断），**没有 license 扫描器**
   （如 license-checker / FOSSA / ScanCode）。已列入待办 §5。

## 5. 待办（未完成，不得宣称"许可已合规"）

- [ ] **法务/合规确认**：LGPL 派生范围对外分发（SaaS 形态是否构成分发）的定性。
- [ ] **决策：保留 LGPL 派生 vs clean-room 重写**。若目标为纯 Apache-2.0 商业闭源分发，
      需对 `nexus-core/nexus-core/src`（162 文件）+ consortium 4 文件重写或替换。
- [ ] **依赖许可清单化**：产出 SBOM（Trivy 已能出 CycloneDX，见
      `.github/workflows/security-scan.yml`）+ 许可归集，接入 CI 门禁。
- [ ] **前端许可统一**：`nexus-explorer/frontend/package.json` 的 `MIT` 与根
      Apache-2.0 对齐（或明确为独立许可）。
- [ ] **清理未标注的内嵌 JS**（`org/nexus/tools/**`）或补齐其许可信息。

## 6. 维护约定

- 本文档与 `NOTICE`、`scripts/check-license-headers.sh` 三者的白名单/声明必须同步修改。
- 每次发布前运行 `bash scripts/check-license-headers.sh` 应 0 失败；
  CI 的 `版权头合规检查 (license headers)` 步骤为强制门禁。