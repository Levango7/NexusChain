# 许可合规说明（Licensing）

> **修订说明（2026-09-17）**：本文是仓库声明盘点，不是法律意见或合规认证。
> 文件头匹配不能证明整个目录均属 LGPL；仅凭进程隔离不能判定 GPL 义务范围。
> 保留文件级许可和 MIT 组件声明，不通过新增 NOTICE 给现有代码重新许可。
>
> **状态**：Active —— 合规声明已落盘；**clean-room 重写 / 法务确认仍为待办**（见 §5）。
> **2026-10-07 更新**：接入依赖许可扫描器（报告制）后，**新发现 `mpc-engine` 传递引入
> 5 个 GPL-3.0 系 crate**——其严重性高于此前记录的 LGPL 派生（LGPL 允许闭源、GPL 不允许）。
> 详见 §5 第一条。
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
| `mpc-engine` | 本地包声明 Apache-2.0，**依赖树已无 GPL/AGPL 系 crate**（2026-10-08 GG20 退役后，见 §5 第一条）；**但 cggmp21 链路含 LGPL-3.0+ 弱传染**（`rug`/`gmp-mpfr-sys`，义务见 §5） | `Cargo.toml` + `Cargo.lock`（许可经 `scripts/check-license-policy.py` 于 crates.io 核实）；唯一密码学栈 `cggmp21` 为 MIT OR Apache-2.0 |
| `nexus-explorer`（根/前端/后端）、`nexus-devtools`、TS SDK | MIT 组件声明 | 各 `package.json`；与根许可不同不必然冲突 |
| `nexus-core/nexus-core/src/main/java/org/nexus/tools/` 内嵌 JS | 须逐文件盘点原始声明和实际交付关系 | 未证明“全部无许可”或“构建不使用”，不得如此表述 |

## 4. 注意事项（对工程操作的影响）

1. **不要给 LGPL 范围内的文件换头**：`nexus-core/nexus-core/src` 下改动文件时，
   必须保留原 LGPL 头（第 1 条义务）。若确需重写某文件为原创，应整文件重写并
   在 PR 中说明，同时更新 NOTICE/本文档与脚本白名单。
2. **新增 GPL/LGPL/AGPL 依赖或源码**：同步 NOTICE §3 与 §3 表格，否则 CI 失败。
3. **依赖许可扫描**（2026-10-07 更新）：此前 CI 只有 Trivy（漏洞）/ gitleaks（密钥）/
   OWASP Dependency-Check（漏洞，**非许可**），**没有 license 扫描器**。现已接入
   `scripts/check-license-policy.py`（CI job `依赖许可证策略（报告制）`）：
   - **npm** — ✅ 解析全部 `package-lock.json` 的 `license` 字段（离线、确定）
   - **Rust** — ✅ 解析 `Cargo.lock` + crates.io 许可，带**可提交缓存**
     `scripts/rust-license-cache.json`（离线可复现；缓存未命中会告警，提示新增依赖未核实）
   - **Java/Gradle** — ❌ **仍未覆盖**（无锁文件，需解析已解析依赖树，另行评估）
   - 当前为**报告制**（不阻断）：首扫即发现 **5 个 GPL-3.0 系 crate**，见 §5 新增决策项。

## 5. 待办（未完成，不得宣称"许可已合规"）

- [x] ~~**{新增 2026-10-07，最高优先级}决策：`mpc-engine` 的 GPL-3.0 依赖如何处置。**~~
      → **2026-10-08 已解决（选路径 B 的等价实现：退役 GG20 路径）。**
      处置：GG20 可信协调器路径（唯一触达 5 个 GPL-3.0 系 crate 的代码）整体删除，
      CGGMP21（MIT OR Apache-2.0）成为唯一门限签名路径——
      Rust 侧删 `gg20/dkg/sign/aggregate/distributed/session` 六模块与 `integration_test`，
      Java 侧删 `GrpcMpcCryptoEngine` 及 GG20 测试，proto 双副本删 6 个 GG20 RPC，
      `Cargo.toml` 移除 GG20 独用依赖（`multi-party-ecdsa`、`curv-kzen`、
      `kzen-paillier`（别名 `paillier`）、`zk-paillier`、`round-based 0.1`、
      `secp256k1 0.20`、`sha2 0.9`、`rand 0.7` 与 dev-dep `nix`）。
      实施记录：`docs/plan/PLAN-001-R2-gg20-retirement.md`。
      **核实方式（分发前仍应重跑）**：`python3 scripts/check-license-policy.py` 于
      退役后的 lockfile 报告**强传染 0**（组件 2073→1997）。
      **注意强弱之别**：cggmp21 链路仍带 LGPL-3.0+ 弱传染组件（`rug 1.30.0` /
      `gmp-mpfr-sys 1.7.1`，GMP 系）——LGPL 允许闭源使用但有声明/重链接义务，
      **与本条 GPL 处置无关，仍须按下一条 LGPL 决策单独处理**。
- [ ] **法务/合规确认**：LGPL 派生范围对外分发（SaaS 形态是否构成分发）的定性。
- [ ] **决策：保留 LGPL 派生 vs clean-room 重写**。若目标为纯 Apache-2.0 商业闭源分发，
      需对 `nexus-core/nexus-core/src`（162 文件）+ consortium 4 文件重写或替换。
- [x] ~~**依赖许可清单化**~~ → **2026-10-07 部分完成**：已接入
      `scripts/check-license-policy.py` + CI job `依赖许可证策略（报告制）`
      （npm ✅ / Rust ✅ 带缓存 / **Java ❌ 未覆盖**）。~~**转阻断的前提**是上面
      第一条 GPL 决策落地——否则 CI 会因存量 5 个 GPL 组件常红，"红"将失去
      "新引入违规"的语义。~~ → **2026-10-08 前提已达成**（强传染归零）：转阻断
      的技术条件已具备，可择机把 `--strict` 打开（转阻断前先确认弱传染 34 项
      无新增误报面，见 §6）。
- [ ] **前端许可统一**：`nexus-explorer/frontend/package.json` 的 `MIT` 与根
      Apache-2.0 对齐（或明确为独立许可）。
- [ ] **清理未标注的内嵌 JS**（`org/nexus/tools/**`）或补齐其许可信息。

## 6. 维护约定

- 本文档与 `NOTICE`、`scripts/check-license-headers.sh` 三者的白名单/声明必须同步修改。
- 每次发布前运行 `bash scripts/check-license-headers.sh` 应 0 失败；
  CI 的 `版权头合规检查 (license headers)` 步骤为强制门禁。
- **依赖许可**：新增/升级依赖后，本地跑
  `python3 scripts/check-license-policy.py --update-cache` 以刷新
  `scripts/rust-license-cache.json`（否则 CI 会报"缓存未命中"，提示有新增依赖未核实）。
  报告在 CI 工件 `license-policy-report`。**当前为报告制**；待 §5 第一条
  GPL 决策落地后，再把 `--strict` 打开转为阻断。