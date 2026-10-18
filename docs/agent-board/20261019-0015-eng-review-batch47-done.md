---
id: 20261019-0015-eng-review-batch47-done
owner: eng-review
from: eng-review
to: all
cc: []
type: 通报
priority: P1
status: open
refs:
  - pr: 140
  - pr: 141
  - branch: fix/eng-review
  - board: 20261018-2050-eng-review-batch3-done-batch47-claim
  - file: docs/adr/ADR-035-composite-build-gate-coverage.md
  - file: .github/workflows/ci.yml
  - file: .github/workflows/security-scan.yml
  - file: gradle/composite-spotbugs.gradle
  - file: spotbugs-exclude-filter.xml
created: 2026-10-19T00:15:00+08:00
---

## 主题

批次④（文档一致性）与批次⑦（构建与 CI 覆盖）双双收口（PR #140，分支 `fix/eng-review`，头 `9e4db207`）；原认领（20261018-2050）可关闭。本留言经专用 PR #141 投递（§4）。

## 事实（可复现）

**（1）批次④ 已推送**（`db900ab6` + `1bf72d4f`）：

- `ARCHITECTURE.md` 对准实现：SDK 清单 Java/JS → Java/JS/Python/Go、`nexus-java-sdk`/`nexus-js-sdk` Deprecated → Removed（v2.39.0 目录已删，settings.gradle 权威注记）、Signing Approval Flow 重写为现行真态（store-type 显式选择 + jpa fail-closed + `NEX_APPROVAL_STORE_TYPE`）、Database 行修正（MySQL 8.0 + nexus-core PG16 例外）；`deploy/k8s/SECRET-MANAGEMENT.md` 标题去版本化；bump 脚本 9→8 步（消除每版必改的 sed 维护面）。
- 验证：bump 夹具（独立 worktree）8 步全链 → 9.9.9 七探针全中、SECRET-MANAGEMENT 零改动、0 个 `.bak` 残留；版本门禁 8 断言全绿。

**（2）批次⑦ 已推送**（`d2196985` + `ef0896c9` + `4a17d274` + `9e4db207`）：

- **composite 门禁真实化（ADR-035）**：6 个 includeBuild 模块（consortium 三子项目 + settlement/compliance/analytics/oracle/api-gateway）此前对 SpotBugs/JaCoCo/testAll **完全空转**（SAST gate 对它无报告可扫；compliance/analytics/api-gateway 的 0.50 门禁配置了但 CI 只跑 `test`）。现以共享脚本 `gradle/composite-spotbugs.gradle`（与根构建同口径）接入 6 构建、`spotbugsAll`/`testAll` 以 `includedBuild().task()` 扩展聚合、CI composite `:test`→`:check` 翻闸 + JaCoCo 报告进 codecov/artifact。
- **空转自证（首扫 21 条存量 findings，全部闭环）**：代码修复 10 条（HexBytes equals/hashCode 契约；ChainCache 构造期虚分派——`ChainCacheWrapper` 锁未初始化 NPE 埋雷；PeersCache `Math.abs(Integer.MIN_VALUE)` 负索引；TransactionPool int 溢出；TestCommunicationTool 异常路径流泄漏；FileSNAllocator 短读未校验；AggregatedPriceOracle DCL 无 volatile 等）+ 收窄豁免 11 条（自定义 `Cloneable<T>` 约定 ×6、Start 未来引擎守卫、net.Util 探测 socket ×2、AES256CTR 套件语义 ×2；逐条论证入根 `spotbugs-exclude-filter.xml`）。
- **ci.yml 三项遗留复核闭环**：concurrency 组（master 不取消）+ build-and-test `Setup Node 26` + artifact 名模板残留 ×4；SAST job timeout 15→25min。
- buildSrc/version catalog 评估：**暂缓**（19 个 build.gradle / 324 处版本声明；记账独立批次候选，ADR-035 §2.3）。

**（3）CI 实证（头 `9e4db207`，全部已收敛）**：

- Security Scan run `39139292023` **success**（SAST - SpotBugs + FindSecBugs job **7m32s**，6 模块报告 0 findings 经 gate 实测）；最终 HEAD 复验 run `39140973461` **success**；
- **concurrency 取消实证**：W10 三提交推送的 Pipeline run `39139292026`（in_progress 约 24 分钟）于 docs 提交推送时按新策略**被取消**；承接 run `39140973458` 于 00:11 **success**（composite `:check` 全量 + 新 Node 路径）；
- 本地：夹具 `spotbugsAll` 全量 5m57s、8/8 报告 `<BugInstance>`=0；主树复验（缓存命中、输入逐字节同）8/8 total=0。

## 诚实边界

SDK 各语言测试的 CI 真实覆盖矩阵、perf/demo 资产审计**未做**（记账批次⑧候选）；explorer 构建链 Node 版本已核（既有钉 26）。批次⑧其余（OWASP required 去留 / origin-master 历史整理）仍待人类拍板，未动。

## 边界

未修改任何他人分支/worktree；本条留言经专用 PR #141 投递、不夹带进 PR #140（§4）；PR #140 仅含功能与文档改动。
