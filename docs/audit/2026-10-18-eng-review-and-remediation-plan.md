# NexusChain 工程评审报告与分批改良计划

- **日期**：2026-10-18
- **评审基线**：本地 master `daa7b528`
- **执行分支**：`fix/eng-review`（批次①时初名 `fix/eng-review-batch1`，批次②后更名；独立工作树 `F:\Nexus\nc-wt-engreview`，未触碰其他 7 个 agent 工作树）；**2026-10-18 已 push 并开 PR [#140](https://github.com/Levango7/NexusChain/pull/140)**（推送前已 rebase 到 origin/master，含 #135–#139 的迁移棘轮门禁与 CI 调整；文中提交哈希均为 rebase 后值）
- **证据口径**：所有结论来自 7 路并行代码审计 + 逐项原文复核（file:line）；仅"审计提出、尚未本人复核"的条目会显式标注。

---

## 0. 执行摘要（TL;DR）

**这个项目的工程治理水平明显高于同类均值**：CHANGELOG 当作发布合同维护、门禁全部代码化（版本口径 / MPC 分层 / 调度锁 / 迁移号段棘轮）、"fail-closed" 是一贯哲学（宁可拒起不给占位口令）、还有 L1–L3 三级安装预演。这些是真实的、少见的好底子。

**但审计暴露了四类系统性问题**：

| # | 问题族 | 一句话 | 整体定级 |
|---|--------|--------|----------|
| A | **元数据漂移是系统性的，不是偶发** | 版本多源、部署资产 tag 与真实发布产物对不上、归版脚本存在 no-op 步骤——"写而不测、写而不装" | P1（发布/部署可信度） |
| B | **叙事与实现脱节（主要在 nexus-core）** | 对外叙述的 Groth16 实为 Schnorr、KZG 是 mock、欺诈证明未锚定、Merkle 校验失败仍写链 | P1（对外声明真实性） |
| C | **资金路径细节缺陷（gateway）** | 退款事务内出款、累计退款 check-then-act、无 UNIQUE 引用、自动出款竞态与内存台账等 6 项 | **P0**（真金白银路径） |
| D | **门禁覆盖盲区 + 豁免不对称** | composite build 逃逸 SpotBugs/JaCoCo、explorer 从未被扫描、zk 无 RustSec、豁免只在部分步骤生效 | P2 |

**本日已执行并落库（批次①+②+⑤+③+④+⑦全部收口，23 个提交；已推送 + PR [#140](https://github.com/Levango7/NexusChain/pull/140)）**：

- **批次①（4 提交）**：版本四源归版 2.73.0→2.74.0 + 版本门禁 3→7 断言（含"CHANGELOG 超前检测"，并经反向用例验证）；CI/安全扫描三处补齐；许可证门禁恒真 bug 修复（实测从"每次运行必备网络"变为"离线 0 查询"）；prod/dev compose 构建上下文 8 处修正 + JWT/MPC token fail-closed 注入（含反用例实证）；core 镜像 logs 目录与 busybox 钉版；PrometheusRule 静默失效 label 修复。
- **批次②（3 提交，发布工程收口）**：tag 政策落定**全面 v 前缀**（镜像/部署引用；chart 元数据维持纯 semver）；部署资产全链对齐 v2.74.0（k8s 静态清单 6 处 / values-prod 7/7 钉死 / chart×8）；registry 命名空间修正（ghcr.io/nexus 是从不存在的空间 → ghcr.io/levango7/nexuschain，含 kind mirror "永不命中"的 host 级修复）；版本门禁 7→8 断言（部署资产口径，反向用例验证）；bump 脚本 v 前缀；k8s-sync `--set` 单一真源化。详见 §7。
- **批次⑤（4 提交，资金路径 P0 加固）**：退款执行三段式（CAS 认领先提交 → 链上调用事务外 → 结果独立落库；链上成功后落库失败停留 EXECUTING 不再自动处置，新增扫描器告警）；自动提现出款认领协议（V108 三列 + 三段式落库 + 悬挂 fail-closed，同族"二次出款"风险一对收口）；JWT 启动守卫 require-secret（gateway/signing 双侧，prod 缺密钥拒起）。复核后推迟/豁免 3 项（reference UNIQUE、Split 幂等、Recovery 锁）已在 CHANGELOG 透明记录。详见 §7。
- **批次③+④+⑦（8 提交，2026-10-18 续作）**：部署资产完备性（prod compose 20 服务限额/探针全栈 + sync-check 真断言 + runbook 5→11 条）；文档一致性（ARCHITECTURE 对准实现、SECRET-MANAGEMENT 去版本化、bump 脚本 8 步）；构建与 CI 覆盖——**composite build 门禁真实化**（6 模块接入 SpotBugs/JaCoCo/testAll，首扫 21 条存量 findings 全闭环：代码修复 10 + 收窄豁免 11；ADR-035）＋ ci.yml 遗留疑点全修（concurrency / Setup Node 26 / artifact 名 ×4）。详见 §7。

**决策点状态**见 §8（tag 政策 / OWASP 完善 / GHCR 路径 / 报告归属 / push-PR 均已决并执行；合并按条件授权；剩余：origin 历史、下一批次选择）。

---

## 1. 评审方法

- 7 路并行审计，各有分工与证据要求：①构建与测试 ②CI/CD 与门禁 ③gateway 质量 ④core 质量 ⑤部署运维资产 ⑥SDK/前端/多语言 ⑦文档治理一致性。
- 主审对关键待修项**逐条原文核实**后才动手（例如：`check-license-policy.py:199` 恒真、`values-prod` 的 MPC 开关实际值、gateway Dockerfile 头注明确要求根上下文、`busybox:musl` 为指令中唯一浮动引用等）。
- 本地实测工具：GNU bash 5.2.21 / helm v3.14.0 / Python 3.14.3 + PyYAML / docker compose v5.6.0 / cargo-audit 0.22.2（RustSec 数据库网络不可达，zk 扫描只能在 CI 侧首次验证，故落为"观察 job"而非阻断）。
- **边界纪律**：不动其他 agent 分支/PR/worktree；不 push、不 merge origin；治理性决策（required checks 名单、tag 政策、origin 历史）只报告不擅动。

---

## 2. 项目全景

**定位**（README）：基于自研结算链的支付编排平台——区块链是结算底座，产品价值在统一支付 API、启发式路由与清结算；商业形态为"私有化交付给持牌机构"，现阶段不自持牌照、不直接运营资金。

**技术构成**：

| 层 | 组成 | 规模特征 |
|---|------|----------|
| JVM 主模块 | nexus-gateway / nexus-core / nexus-signing-service / nexus-wallet-service / nexus-bridge / nexus-api-gateway / nexus-settlement / nexus-compliance / nexus-analytics / nexus-oracle 等 | gateway 965 个 Java 文件、core 576 个 |
| composite build 模块 | consortium / settlement / compliance / analytics / oracle / api-gateway（以 includeBuild 接入） | **不参与根构建聚合门禁**（见 §4.6） |
| Rust 密码学组件 | mpc-engine（CGGMP21 gRPC 引擎）、zk-groth16-service | 独立 Cargo 工程 |
| 前端 / 工具 | nexus-explorer（TS/Node）、四语言 SDK（java / js / python / go）、devtools、demo、perf | 镜像链路与 trivy 矩阵此前不一致 |
| 部署资产 | docker-compose（dev/prod）、deploy/k8s 静态清单、deploy/helm chart、deploy/kustomize、deploy/monitoring | 多路径并存，同步检查覆盖不全 |
| 测试 | 707 个测试文件；gateway 约 2633 用例（~1m30s）、core 1512 个 @Test | CI 观测 15.9–35.4 分钟 |
| 版本 | 现归版 2.74.0（批次①前为构建 2.73.0 / CHANGELOG 2.74.0 漂移） | 版本源历史上最多时达 5 处 |

**CI 门禁现实**：8 个 required checks——Gitleaks、Trivy FS+SBOM、SAST SpotBugs、Cargo Audit (Rust deps)、Code Hygiene、k6 Smoke (PR)、OWASP Dependency-Check、Build & Test (JDK 17)。

---

## 3. 总体评价

**做得好的**（应保留并推广）：
1. 门禁代码化 + 负向用例（不是靠文档约定）；`check-version-consistency.sh` 的注释里写着每一次漂移的先例——这是 institutional memory 的正确形态。
2. fail-closed 文化：prod compose 无默认口令、插值期直接炸；signing 空 JWT 密钥时拒绝所有 `/api/**`。
3. 缺陷修复带"实测依据"（L3 复验 22 项的 CHANGELOG 条目、OWASP job 的 26 次 run 耗时统计）。
4. 诚实标注（license 脚本自述"Java 未覆盖，不假装全覆盖"）。

**四类系统性问题**：见 §0 表格。其中 **C（资金路径）是本报告第一优先级**；A 类已在批次①收口"检测能力"，B/D 需要后续批次。

---

## 4. 分领域发现

### 4.1 构建与测试

| 发现 | 证据/细节 | 状态 |
|---|---|---|
| composite build 门禁逃逸 | consortium / settlement / compliance / analytics / oracle / api-gateway 用 includeBuild 接入根构建，SpotBugs 聚合门禁不覆盖、JaCoCo 门禁对它们是空转 | **已修（批次⑦）**：6 模块接入（ADR-035）；首扫 21 条存量 findings 全闭环（修复 10 + 收窄豁免 11）；CI 首验 SAST 绿 |
| `testAll` 语义被高估 | `build.gradle:433-436` 只依赖根 `subprojects` 的 test——composite 模块与 `integration` 标签用例不在内；README 原文"全量回归测试"不准确 | **已修（批次①，README 修正）** |
| Gradle 版本引用过期 | README 写 8.5，wrapper 实际 8.14 | **已修（批次①）** |
| 无 buildSrc / 版本目录 | 依赖与版本声明散落各 build.gradle，升级面大 | **已评估（批次⑦）**：暂缓——19 个 build.gradle / 324 处版本声明，风险收益权衡后记账为独立批次候选（ADR-035 §2.3） |

### 4.2 CI/CD 与门禁

| 发现 | 证据/细节 | 状态 |
|---|---|---|
| Trivy Docker 矩阵缺 nexus-explorer | principe 注释自称"与 ci.yml 保持一致"但实际漏（ci.yml docker-build 矩阵含），explorer 镜像从未被扫描 | **已修（批次①）** |
| Trivy fs 的 SARIF/JSON 步缺 `trivyignores` | 豁免政策只在阻断步生效——已处置的 CVE 仍污染 Security tab 与分诊 JSON；Docker job 的 SARIF 早已带此参数 | **已修（批次①）** |
| Cargo Audit 仅覆盖 mpc-engine | zk-groth16-service 同属密码学 Rust 组件却无 RustSec 覆盖 | **已修（批次①，独立观察 job，不列入 required）** |
| `check-license-policy.py:199` `(update_cache or True)` 恒真 | 任何运行都会对未命中 crate 实时调用 crates.io——网络依赖 + 非确定性，与模块文档"可提交缓存、CI 离线可复现"契约冲突 | **已修（批次①，实测离线 0 查询）** |
| `check-mpc-tier-policy.sh` prod 缺 2 项硬断言 | 仅 staging 有 DISTRIBUTED / CGGMP_SIGNERS 断言；prod 静默退回非分布式也能过门禁 | **已修（批次①）** |
| `k8s-sync-check.yml` 触发面缺口 | paths 只含 deploy/k8s 与 deploy/helm；kustomize / monitoring 改动不触发"双路径漂移"校验 | **已修（批次①）** |
| OWASP Dependency-Check 治理悬空 | TEMP"永远上报"试运行 2026-10-15 已到期；且经 gh api 实证**该 job 已在 required contexts 中**（共 8 条）。若按计划改"PR 不跑"而不先撤 required，GitHub 会把"required 但永不产生状态"按 pending 处理 → 此后每个 PR 卡死 | **待决策（§8-3）** |
| ci.yml 疑似遗留项 | 审计提出：build-and-test 跑 npm 却无 setup-node、artifact 名疑似模板残留、全文无 concurrency（不取消旧运行）——**尚未逐条复核** | **已逐条复核修复（批次⑦）**：concurrency 组 + Setup Node 26 + artifact 名 ×4 全清 |

### 4.3 版本与发布工程（A 类重灾区）

**批次①前的漂移全景**（这是"多源版本 + 手工同步"模式的必然结果）：

| 位置 | 批次①前 | 说明 |
|---|---|---|
| build.gradle `version` | 2.73.0 | 单一真源声明 |
| version.properties | 2.73.0 | 双源之一 |
| README 头注 | 2.73.0 | 且有"最新条目 [2.73.0]"复述（停在旧版本） |
| CHANGELOG 最新条目 | **2.74.0** | **超前于构建版本**——旧门禁三断言对此全绿 |
| application.properties `nexus.version` | **v2.50.0** | 陈旧 23 个版本无人发现（门禁从不校验、bump 脚本会写——"写而不测"） |
| git tag / 发布镜像 | v2.73.0（release.yml 推 `${{ github.ref_name }}`） | **v 前缀** |
| deploy/k8s 静态清单镜像 tag | `.../nexuschain/<svc>:2.1.0` | **纯版本号**，与发布产物对不上 |
| values-prod / Chart appVersion | 2.50.0 | 同上 |
| 11 个子模块 version 覆盖 | 2.30.0 | 根版本之外的历史残留 |

**结论**：部署资产中的纯版本号 tag 是**死引用**（GHCR 里不存在 `:2.50.0` 这类镜像，只有 `vX.Y.Z` 形态）。这不只是洁癖问题——helm 生产安装会拉不到镜像。**已决策并执行（批次②）**：全面 v 前缀（仅镜像/部署引用；chart 元数据维持纯 semver，与 release.yml 去 v 打包口径一致），并加门禁断言 8 永久把守。见 §7。

**bump-version.sh 的两个实锤缺陷（已在批次①修复）**：
- k8s 镜像前缀写成 `ghcr.io/nexus/`，而实际是 `ghcr.io/levango7/nexuschain/` → 该步骤**从未匹配过任何行**（no-op 无数个版本）。
- 针对 `nexusVersion` 字段的 sed 是死代码（build.gradle 中已无此字段）；且脚本不更新 README 头注——四次漂移的最后一环。现为 9 步全流程。

### 4.4 部署与运维资产

| 发现 | 证据/细节 | 状态 |
|---|---|---|
| prod compose 7 处构建上下文错误 | `build: ./nexus-gateway` 等直接指向模块目录——而各 Dockerfile 头注明确要求 **repo 根上下文**（依赖根级 gradlew/settings.gradle/project() 解析）；按原写法 `docker compose build` 必失败 | **已修（批次①）** |
| prod compose 缺 JWT_SECRET / NEX_MPC_ENGINE_AUTH_TOKEN | gateway/signing 跨服务 JWT 验签、signing→mpc-engine 鉴权（引擎侧 `MPC_REQUIRE_AUTH=true`）在 prod 面板均无注入 | **已修（批次①，`:?` fail-closed + 反用例实证）** |
| JWT 空密钥是"静默降级"而非拒起 | 实测双端代码：gateway/signing 的 JwtTokenProvider 都是**告警 + 进程内随机密钥**；signing SecurityConfig 另拒绝所有 `/api/**`；gateway 侧后果是跨服务验签必然失败。启动不炸、运行才坏 | **已修（批次⑤）**：`nexus.security.jwt.require-secret` 档位（prod 双侧置 true）——密钥缺失/短于 32 字节启动即拒；dev/测试档位保留原兜底 |
| dev compose zk 上下文同病 | `context: ./zk-groth16-service` 与 zk Dockerfile 约定不符 | **已修（批次①）** |
| nexus-core 镜像缺 /app/logs | Logback `./logs/nexuscore.log` 无 profile 守卫，目录缺失即启动失败；dir-builder 阶段已建、runtime 阶段漏拷 | **已修（批次①）** |
| `busybox:musl` 浮动 tag | 指令中唯一未钉版本的基础镜像引用 | **已修（批次①，钉 1.36.1-musl）** |
| 50-prometheus-rules.yaml 缺 release label | kube-prometheus-stack 的 ruleSelector 匹配 `release: kube-prometheus-stack`——缺 label 规则被 Operator **静默忽略**（告警不在线、无任何报错）；同类文件（40-monitoring.yml、kafka-monitoring）都带，唯独此文件漏 | **已修（批次①）** |
| run-trivy-scan.sh 上下文错误 | `docker build "./$module"` 与 CI 矩阵口径不一致 | **已修（批次①）** |
| .env.prod.example 清单口径 | 自称 15 项、实列 18 项；缺 JWT_SECRET | **已修（批次①，19 项）** |
| deploy tag 未随发布对齐 | 见 §4.3 表 | **已修（批次②，全链对齐 v2.74.0 + 断言 8）** |
| SECRET-MANAGEMENT.md 自相矛盾 | 标题 (v2.50.0)，注记却写"标题中 v2.1.0 为历史标记"——注记本身过期 | **标题与注记已修（批次②）**；"版本化标题"的永久表述方案仍归批次④ |
| kustomize / monitoring 资产复核 | 触发已补齐，内容级复核未做 | 待批次③ |

### 4.5 nexus-gateway（资金路径，P0 集中区）

**P0 清单**（全部为"钱走出去"路径上的并发/事务/幂等问题）：

1. **退款事务内出款**：退款流程在数据库事务内调用渠道出款——出款是外部不可回滚操作，事务回滚/重试会造成重复出款或悬挂。→ **已修（批次⑤）**：三段式（认领先提交 / 链上事务外 / 结果独立落库）+ 卡单扫描告警；落库失败停留 EXECUTING 不自动改、不自动重试。
2. **累计退款 check-then-act**：累计退款金额校验与写入之间无原子性（并发退款可叠加超限）。→ **核验：现行代码已修复**（v2.27.0 起 `sumPendingRefundsByOrderId` 已计入 PENDING/APPROVED/EXECUTING/EXECUTED + 悲观锁）；批次⑤仅核验，未重复施工。
3. **`account_transactions.reference` 无 UNIQUE 约束**：引用同一业务单号的多笔入账/出账可重复落库，对账与幂等都依赖业务层自觉。→ **推迟（批次⑤复核）**：AutoWithdraw 补偿后同周期重试会**合法复用**同一 reference（`AUTO_WITHDRAW_日期_规则ID`），此时加唯一约束会破坏重试路径；且缺 prod 存量脏数据审计。留后续批次以"引用号加周期/尝试后缀"方案一并处理。
4. **AutoWithdraw 竞态 + 内存台账**：自动出款并发控制不足，且台账非持久化——重启即失忆。→ **并发/重复出款控制已修（批次⑤）**：V108 认领协议 + 出款事务外 + 悬挂 fail-closed；**出款台账持久化仍为遗留**（`ManualPayoutExecutor` 进程内 Map，留后续批次）。
5. **SplitService 无幂等**：分账可被重复触发。→ **复核：主线无调用方**（`executeSplits` 未接线入支付流程），批次⑤不动；**接线时必须先补幂等键**。
6. **TransactionRecoveryScheduler 无调度锁**：多副本下恢复任务并发执行（本仓调度锁门禁 `check-scheduler-lock.py` 现有 13 条豁免，此项在豁免还是漏网需列入批次⑤核对）。→ **核对：维持既有豁免论证**——正确修法是参与方描述持久化，加锁有害（门禁豁免账含理由）；批次⑤仅报告记述。

**正范本**：`RiskLimitAccrualService:89-113` 展示了本仓自己已掌握的正确写法（条件更新/原子累加），资金路径其余部分应向它看齐。

**其他**：JWT 静默降级 **已修（批次⑤，见 §4.4）**；KEK/MAK 相关组件（EncryptedFileKeyShareStore / HmacSigner）的主装配路径仍待代码级复核（审计提出；批次⑤未展开，留后续复核）。

**注意协调**：gateway 的若干问题**已被其他 agent 认领**（死安全事件清理 nc-wt-deadcode、3DS 观察者 nc-wt-read（已合并）、重放指标 nc-wt-metrics、安全审计存储 nc-wt-secaudit、风控链管理端点 nc-wt-authfix）——后续批次**不得重复施工**，以 agent-board 为准。

### 4.6 nexus-core（B 类：叙事与实现脱节）

| 发现 | 细节 |
|---|---|
| Merkle 校验失败仍写链 | `PendingBlocksManager:92-110`：Merkle 根校验失败时并未 fail-closed，块照样写入 |
| "Groth16" 实为 Schnorr | 声称的 Groth16 电路/证明实际是 Schnorr 签名路径——对外叙述与技术现实不符 |
| KZG 为 mock 实现 | 承诺数据可用性层的关键组件是占位 |
| 欺诈证明未锚定 | 欺诈证明机制未真正锚定到链上状态 |
| 0x10–0x1a 支付扩展 opcode 断层 | 支付扩展指令区无实现，链上"支付原生能力"名不副实 |
| nonce 无上界 | 账户 nonce 无溢出/上界防护 |
| DB 无版本化迁移 | 状态存储无 schema 版本与迁移机制，升级面风险 |

**处理原则建议**：自研链定位是"结算基础设施"——**要么把声明收敛到实现（改名、改文档、标注实验性），要么把实现补到声明**。两者都行，但"叙述按未来、实现按现状"的缝隙就是审计/DD 时最容易被击穿的地方。批次⑥专项处理，且这是**产品/对外口径决策**，需要你参与。

### 4.7 SDK / 前端 / 多语言 / 工具链

- 四语言 SDK（java/js/python/go）+ explorer + devtools + demo + perf：**版本与发布链路一致性**是主要风险（与主仓版本没有联动门禁）；explorer 此前从未被 Trivy 扫描（批次①已修）。
- zk-groth16-service Dockerfile：要求 repo 根上下文、基于 trixie-slim、无 USER 指令（与 L3 全栈一致化需复核）；RustSec 覆盖批次①已加观察 job。
- mpc-engine：已有 `.cargo/audit.toml` 逐项豁免 + 说明（形态正确）；`NEX_MPC_ENGINE_AUTH_TOKEN` 链路已核对。
- **复核项（批次⑦残项，2026-10-19 已闭环）**：SDK 各语言测试在 CI 的真实覆盖（三语言 step 级 success + 用例数对齐；Java 走根构建 check + codecov）、explorer 构建链 Node 版本（三处统一钉 26）、perf/demo 定位（perf 实跑有据、demo 为本地演示包）——详见 §7 批次⑦残项复核。

### 4.8 文档治理

- README：头注复述漂移**已修**（不再复述 CHANGELOG 条目内容，改由门禁断言 6 兜底）；Gradle/testAll/ci.yml 行号引用**已修**。
- `docs/migration-guide.md`：版本声明停在 v2.73.0（**已被其他 agent 认领**，本批未动——尊重 claim）。
- ARCHITECTURE 文档漂移（前次审计点名 47 / 60-61 / 82-91 / 114 行区域）→ 批次④。
- agent-board 多 agent 协作文化运转正常（本批全程遵守：独立工作树 + 不动他人认领件）。

---

## 5. 风险矩阵（汇总）

| 级别 | 条目 | 归属批次 |
|---|---|---|
| **P0** | gateway 资金路径 6 项（退款事务内出款 / 累计退款竞态 / reference 无 UNIQUE / AutoWithdraw / Split 幂等 / Recovery 锁） | **⑤ 已执行**（退款三段式、AutoWithdraw 认领协议**已修**；累计退款**核验为已修**；③推迟、⑤⑥豁免——明细见 §4.5/§7） |
| P1 | 部署 tag 与发布产物对不上（纯版本号死引用） | **已修（批次②）** |
| P1 | nexus-core 叙事脱节 7 项 | ⑥（含对外口径决策） |
| P1 | 版本多源（检测与流程均已收口：bump 脚本 v 前缀 + 全链对齐 + 断言 8） | **已修（批次②）** |
| P2 | composite build 门禁盲区 | ⑦ |
| P2 | OWASP required 治理悬空（PR 卡死风险） | §8-3 决策 |
| P2 | JWT 静默降级（启动期 fail-fast 加固） | **已修（批次⑤）** |
| P2 | ci.yml 遗留疑点（node/artifact/concurrency） | ⑦（先复核） |
| P3 | 文档一致性（ARCHITECTURE/SECRET-MANAGEMENT/runbooks） | ④ |
| P3 | SDK 版本联动、kustomize/monitoring 内容复核 | ⑦/③ |

---

## 6. 分批改良计划（批次②起）

> 每批独立成工作树/分支，遵循仓库协作约定；进入下一批前跑全部门禁。

### 批次② 发布工程收口（✅ 已执行，见 §7）
- 已统一 tag 形态为全面 `vX.Y.Z`（镜像/部署引用；chart 元数据维持纯 semver，与 release.yml 去 v 打包口径一致）。`bump-version.sh` 已改 v 前缀、静态清单/values-prod/chart 已对齐 v2.74.0、k8s-sync-check 已改单一真源推导（原"掩码"实为手写对齐常量，一并根治）+ 新增门禁断言 8 永久把守。
- 已复核 release.yml：`latest` / `staging` 移动 tag 语义与 rollback 的 v 归一逻辑（`${TARGET_VERSION#v}`）均与新政策兼容。
- 遗留（批次②续，见 §8-2）：GHCR 实拉验证需要网络窗口；`values-staging` 移动 tag 与分发路径复核可在有网环境补做。

### 批次③ 部署资产完备性（✅ 已执行，见 §7）
- prod compose：`depends_on: condition`、healthcheck 完备性、重启策略、资源限制逐服务过一遍。
- kustomize / overlays 内容级复核；monitoring 告警规则与 runbook 链接核对。
- k8s-sync-check 从"掩码后 diff"升为"关键字段显式断言集"。
- 验收：kind 起栈（可选）+ 渲染 diff + 断言集全绿。

### 批次④ 文档一致性（✅ 已执行，见 §7）
- ARCHITECTURE 对准实现；SECRET-MANAGEMENT 标题/注记语义定稿（建议标题改为"当前版本见 README"的永久表述，终结每版必改）；runbooks 更新；与 migration-guide 认领者协调接口。
- 验收：文档中所有版本/组件数声明可机械校验或有出处。

### 批次⑤ gateway 资金路径加固（✅ 已执行，见 §7）
- 逐项执行结果：①退款事务边界 **已修**（三段式：CAS 认领先提交 / 链上事务外 / 结果独立落库 + 卡单扫描告警）；②累计退款 **核验为已修**（v2.27.0 现行代码已含 EXECUTING/EXECUTED 计入 + 悲观锁，本批仅核验、未重复施工）；③reference UNIQUE **推迟**（补偿后同周期重试会合法复用同 reference，加约束会破坏重试路径；留"加周期/尝试后缀"后续方案）；④AutoWithdraw **已修**并发/重复出款面（V108 认领协议；出款台账持久化仍留后续批次）；⑤Split 幂等 **不动**（主线无调用方，接线时必须先补）；⑥Recovery 调度锁 **维持豁免**（正确修法是参与方描述持久化，加锁有害）。
- JWT 启动期 fail-fast **已修**：`nexus.security.jwt.require-secret`（prod 双侧置 true；dev/测试档位行为不变）。
- 验收实测：定向测试（gateway refund / fundreport / security / wiring 上下文 + signing）全绿；门禁 scheduler-lock / code-hygiene / license-headers / packaging 全绿；每项独立提交、可单独 revert（见 §7）。

### 批次⑥ nexus-core 诚信化（**依赖对外口径决策**）
- Merkle 失败 fail-closed；nonce 上界；DB schema 版本化。
- Groth16/KZG/欺诈证明：按"收敛声明"或"补齐实现"两条路线之一推进（建议先出一页路线文档再动代码）。
- 验收：叙实一致（文档与代码能被同一句话描述）。

### 批次⑦ 构建与 CI 覆盖（✅ 已执行，见 §7）
- composite build 纳入 SpotBugs/JaCoCo/testAll（或明确豁免并写入 ADR）。
- ci.yml 遗留疑点逐条复核修复；加 concurrency 组（取消过期运行，缩短反馈时间）。
- buildSrc/version catalog 重构评估。
- 验收：门禁对 composite 模块产出真实报告；CI 时长下降可测量。

### 批次⑧（可选）治理收尾
- OWASP required 决策执行（去/留 + 文档同步）。
- origin/master 与本地 master 的历史关系整理（需你拍板，见 §8-4）。
- 本报告入库 `docs/audit/` + agent-board 留言。

---

## 7. 执行明细与验证证据

**批次①提交（分支 `fix/eng-review`，工作树 `F:\Nexus\nc-wt-engreview`；批次①时为 `fix/eng-review-batch1`，批次②后更名）**：

| 提交 | 内容 |
|---|---|
| `2e9e34eb` | fix(ci)：trivy fs SARIF/JSON 补豁免、Docker 矩阵补 explorer、zk 观察 job |
| `a9414aa3` | fix(gates)：license 恒真修复（默认离线）+ MPC prod 断言 + k8s-sync paths |
| `d1474436` | fix(deploy)：8 处构建上下文、JWT/MPC token fail-closed、core logs、busybox 钉版、PrometheusRule label、trivy 脚本、.env.prod.example |
| `e27aaae4` | chore(release)：四源归版 2.74.0 + 门禁 3→7 断言 + bump 脚本修复 + README/CHANGELOG |

**验证记录（全部当场复跑通过）**：
- `bash scripts/check-version-consistency.sh` → 7 断言全绿；**反向用例**：人为把 CHANGELOG 改成 2.75.0 → 断言 5 红；把 README 复述改成 2.60.0 → 断言 6 红（证明新门禁真能抓住这批漂移）。
- `bash scripts/check-mpc-tier-policy.sh` → 9 项断言全绿（新增 prod DISTRIBUTED / SIGNERS）。
- `python scripts/check-license-policy.py` → 1997 组件、**0 次网络查询**（修复前每次运行必备网络）、1 项诚实标注的未命中（rustls@0.23.46，维护者用 `--update-cache` 刷新即可）。
- `docker compose -f docker-compose.yml config -q` → 通过；`docker-compose.prod.yml` + 完整 dummy env → 通过；**反用例**：去掉 JWT_SECRET → 插值期立即报错拒起（fail-closed 生效）。
- PyYAML 解析全部改动 YAML 通过；`bash -n` 全部触动脚本通过。
- 与 `ci/dc-nvd-timeout` 分支（唯一重叠文件 security-scan.yml）逐 hunk 比对：**改区不重叠，可自动合并**。

### 批次②执行明细（发布工程收口）

**提交（3 个，同分支）**：

| 提交 | 内容 |
|---|---|
| `a43e6d4b` | fix(deploy)：部署资产 tag 对齐 v2.74.0（6 静态清单 + values-prod 7/7 钉死 + chart×8 + SECRET-MANAGEMENT） |
| `d51d8834` | fix(deploy)：registry 命名空间修正（6 子 chart values + kind mirror host 级 + 4 份文档） |
| `0ae0cd07` | fix(gates)：版本门禁断言 8 + bump 脚本 v 前缀 + k8s-sync `--set` 单一真源化 + CHANGELOG |

**政策例外说明**：v 前缀适用于**镜像/部署引用**；Chart.yaml 的 version/appVersion 维持纯 semver——release.yml `helm package --version "${CHART_VERSION#v}"` 显式去 v，属既定设计，强行 v 化会与发布链打架。

**验证记录（全部当场复跑通过）**：
- 门禁 **8 断言全绿**；**反向用例**：k8s tag→2.1.0 / values-prod tag→2.50.0 / 子 chart version→2.73.0 三个漂移全部正确 FAIL（随即恢复绿）；
- `bump-version.sh` 端到端夹具：9 步 → 9.9.9，k8s/values 得 `v9.9.9`、chart 得 `9.9.9`，0 个 `.bak` 残留，归版后门禁直接绿（bump ↔ 门禁闭环）；
- `helm lint` umbrella + 7 子 chart 全绿；`helm template` 全量渲染 7 个镜像与静态清单逐字符串一致（`ghcr.io/levango7/nexuschain/<svc>:v2.74.0`）；
- PyYAML 多文档解析 23 个改动 YAML 通过；`bash -n` 通过。

### 批次⑤执行明细（资金路径 P0 加固）

**提交（4 个，同分支）**：

| 提交 | 内容 |
|---|---|
| `56103804` | fix(refund)：退款执行三段式——认领先提交 / 链上事务外 / 结果独立落库 + 卡单扫描告警 |
| `ae1043ab` | fix(fundreport)：自动提现出款认领协议——扣账先提交 / 出款事务外 / 结果独立落库（V108） |
| `7475c232` | fix(security)：JWT 启动守卫 require-secret（gateway + signing-service） |
| `fca847f7` | docs(changelog)：批次⑤条目 + agent-board 认领留言 |

> 注：表中为 rebase 到 origin/master（含 #135–#139）后的提交哈希，与 `origin/fix/eng-review` / PR #140 一致。

**验证记录（全部当场复跑通过）**：
- 定向测试：`:nexus-gateway:test` 过滤 `refund.*` + `fundreport.*` + `JwtTokenProviderTest` + `WiringEffectivenessGuardTest`（dev 上下文反射校验出款装配仍成立）+ `:nexus-signing-service:test` 新守卫测试类 → **BUILD SUCCESSFUL**（61 项 gateway 定向用例全绿）。
- 期间暴露并修复 3 处：`ClaimOutcome` record 静态工厂与访问器同名（编译期拦截）；`InOrder + atLeastOnce` 时序断言语义不确定（改为事件序列表确定性断言）；signing `application-prod.yml` 命中 `.gitignore` 安全护栏（`**/src/main/resources/application-prod.yml`）→ 该文件零凭据且 bridge/gateway/wallet 已有 3 个受跟踪先例，按先例 `-f` 纳入（"改用环境变量注入"为可选替代，留后续统一）。
- 门禁：`check-scheduler-lock.py`（37 个 @Scheduled 全部带锁/在账，新扫描器带 ShedLock）、`check-code-hygiene.py`（0 新增违规）、`check-license-headers.sh`、`check-packaging-consistency.py` 全绿。
- 新增迁移 `V108` 复核：号段从顶部 V107 顺取（提交前二次复核 master 与工作树两处顶部均为 V107，无撞号）。

### 批次③执行明细（部署资产完备性）

**提交（2 个 + 1 笔合规修正，同分支）**：

| 提交 | 内容 |
|---|---|
| `8eacae1b` | fix(deploy)：compose prod 20 服务资源限制 + 6 处 healthcheck 补齐（sentinel 留白注明）+ core restart + sentinel 端口 8858 + redis maxmemory |
| `c782039a` | fix(deploy)：k8s-sync-check replicas 真校验（注释与实现口径对齐）+ alerts runbook 5→11 条补全 6 条排障手册 |
| `60c57dc9` | chore(board)：1900 留言移出功能 PR（§4 合规，改经专用 PR #141 逐字节重投，blob 校验一致） |

**验证记录（全部当场复跑通过）**：
- `docker compose --env-file <dummy> -f docker-compose.prod.yml config --quiet` → exit 0；PyYAML 逐服务审计表 20×limits / 19×healthcheck / 20×restart；
- 探针真机实测：debian:trixie-slim（mpc/zk 运行阶段镜像）`/dev/tcp` 开端口 exit=0 / 闭端口 refused；prom/prometheus:v2.50.0 与 grafana/grafana:10.4.0 exec 形 HTTP 探针 exit=0 且端到端 healthy；redis 实启 `INFO memory` 确认 maxmemory=536870912 生效（CONFIG 被 rename-command 禁用，以 INFO 验证）；
- k8s-sync-check 改造后 **workflow_dispatch 实测 run `39128097756` + PR 事件 run `39128100422` 双绿**（该 workflow paths 不含 .github/，PR 推送不自动触发，显式 dispatch 取实测；新 replicas 校验首次真实执行）；抽取渲染 step `bash -n` 通过、workflow YAML 解析通过、runbook 围栏配平（18 个）；
- 留白（诚实标注）：sentinel 探针（镜像不在本环境源白名单、工具链不可实测，compose 内就地注明待补）；kind 起栈（本机无 kind，以 compose config + 真容器探针替代）；
- kustomize/overlays + monitoring 内容级复核结论：**设计有意、无需变更**（未发现漂移面）。

### 批次④执行明细（文档一致性）

**提交（2 个，同分支）**：

| 提交 | 内容 |
|---|---|
| `db900ab6` | docs：ARCHITECTURE 对准实现 + SECRET-MANAGEMENT 标题去版本化 + bump 脚本 9→8 步重排 |
| （本执行明细同批） | docs(changelog+plan)：批次③④条目 + 本记录 |

**验证记录（全部当场复跑通过）**：
- bump 夹具：独立 worktree（detached HEAD）实测 8 步全链 → 9.9.9，7 项探针全中（build.gradle / k8s `v9.9.9` / values-prod `v9.9.9` / Chart `9.9.9` / application.properties / version.properties / README 头注），SECRET-MANAGEMENT.md 零改动（去版本化步骤确已除名），0 个 `.bak` 残留；
- `check-version-consistency.sh` 8 断言复跑全绿（CHANGELOG 102 条无重复、不超前）；
- 修正声明均给出出处：settings.gradle 注记（SDK 移除）/ application.yml + SigningApprovalService（审批存储）/ docker-compose.prod.yml 注释 + ADR-033（DB 族）；架构图修正行行宽逐行测量（= 边框宽 71，未破坏 ASCII 框）；Spring Boot 4.0.8 / Gradle 8.14 复核为真、未改。

### 批次⑦执行明细（构建与 CI 覆盖）

**提交（4 个，同分支）**：

| 提交 | 内容 |
|---|---|
| `d2196985` | fix(ci)：concurrency 组 + build-and-test Setup Node 26 + artifact 模板残留 ×4 + composite `:check` 翻闸 + composite JaCoCo 报告步骤/codecov 扩展 |
| `ef0896c9` | fix(build)：composite SpotBugs 接入（共享脚本 ×6 模块 + spotbugsAll/testAll 聚合扩展）+ 首扫 21 条 findings 全闭环 + security-scan timeout 25 + README + ADR-035 |
| （本执行明细同批） | docs(changelog+plan)：批次⑦条目 + 本记录 |
| （残项复核补记，2026-10-19） | docs：批次⑦残项复核——SDK 覆盖 / explorer Node / perf-demo 三项闭环（§4 L173 复核项） |
| （合并期，2026-10-19） | Merge origin/master（含 #142）：concurrency 与 #142 等价实现归一（保留 #142 版）；其 k8s-sync / performance-test / security-scan 三处同批次一并进入本分支 |

**验证记录（全部当场复跑通过）**：
- composite 模块 check 本地实测（settlement/compliance/analytics/oracle/api-gateway 五模块 `:check`）→ **BUILD SUCCESSFUL 3m53s**；jacoco XML 五份落盘（46KB–273KB）——compliance/analytics/api-gateway 的 0.50 门禁"已配置但 CI 只跑 test"（写而不测），翻闸后进入真实执行；
- SpotBugs 接入首扫暴露 **21 条存量 findings**（此前 SAST 门禁对 composite 完全盲区——空转自证），全部就地闭环：
  - **代码修复 10 条（真缺陷）**：HexBytes 补 `hashCode`（equals 契约破损）；ChainCache 构造器改私有 `doPut/doPutAll` 消除构造期虚分派（`ChainCacheWrapper(T/Collection)` 构造必 NPE 的埋雷——子类锁字段尚未初始化）、`evict` 直连私有 `remove`；InMemoryStateTree 改 `Arrays.equals`（语义等价、免分配）；PeersCache 改 `rand.nextInt(n)`（`Math.abs(Integer.MIN_VALUE)` 负索引 + 取模偏置）；TransactionPool `skip((long) page * size)`（int 溢出）；TestCommunicationTool try-with-resources（异常路径流泄漏）；FileSNAllocator 校验 `raf.read` 短读；AggregatedPriceOracle DCL 字段 `volatile`（安全发布）；
  - **收窄豁免 11 条（逐条论证入 spotbugs-exclude-filter.xml）**：本仓自定义 `Cloneable<T>` 约定接口 ×6、Start.consensusEngine 未来共识引擎守卫 ×1、net.Util 节点连通性探测 socket ×2、AES256CTR 套件语义（无生产调用点）×2；
- 夹具（独立 worktree）全量复跑：`spotbugsAll` **BUILD SUCCESSFUL 5m57s**，8/8 composite 报告 `<BugInstance>` 计数 = 0；随后 consortium 三模块 + oracle `:check`（测试 + 覆盖率校验）全绿（1m28s / 1m06s）；
- 主树复验：`spotbugsAll` BUILD SUCCESSFUL（与夹具逐字节同输入、构建缓存命中直接复用同一分析结果），主树 8/8 报告 total=0 点名复核；
- **ci.yml 疑点逐条复核**（评审"尚未逐条复核"项闭环）：concurrency 全仓此前缺失（**合并期归一**：origin/master 的 #142 以 `event_name == 'pull_request'` 的等价实现覆盖 4 个 workflow，语义等价且面更广——本分支合并消重、保留 #142 版本）；build-and-test 4 处 npm 无 setup-node（已补 Node 26，与 engines `>=24 <27` 及 Explorer/Hardhat job 一致）；artifact 名 `-${{ matrix.os }}` 残留 4 处（job 无 matrix、恒空串带尾横线，已清）；引用面核验：download-artifact 仅 security-scan 用 `trivy-fs-reports`（改名无联动）；
- `testAll` 扩展与 spotbugsAll 同机制（`gradle.includedBuild().task()`，后者已实跑验证）；README 口径同步；**ADR-035** 落档（含 Gradle composite 三处实证陷阱：`--dry-run` 对 includeBuild 任务不生效、嵌套闭包 owner 链接收者陷阱、includedBuild 任务引用语法）；
- **buildSrc/version catalog 重构评估**（范围"评估"项）：19 个 `build.gradle`、显式版本声明 324 处（boot 4.0.8 重复 10 处）——收益/风险权衡后**暂缓**，记账为独立批次候选（理由见 ADR-035 §2.3）；
- 门禁：PyYAML 解析 ci.yml（8 jobs）/ security-scan.yml（11 jobs）通过；`check-code-hygiene.py` 无新增违规（49/49 基线）；`check-license-headers.sh` 通过；
- **残项复核（§4 L173 三项，2026-10-19 补做闭环）**：① **SDK 四语言真实覆盖**——Go 13 用例（`go test ./nexus/`）/ Python 15（`python3 tests/test_nexus_sdk.py`）/ TS 8（`npm run test:ts`）三 step 在最终 HEAD run `39140973458` 全 success，且用例数与 ci.yml 注释（Go 13 / Python 15 / TS 8）逐一对齐；Java SDK 20 个测试文件走根构建 check + codecov（files 含 nexus-sdk/java）；② **explorer 构建链 Node**——explorer job / build-and-test / Hardhat 三处统一钉 26；③ **perf/demo 定位**——perf/k6（4 场景 + 容量基线 + HMAC 认证工具）经 performance-test.yml 真实接线，本 PR 冒烟为**实跑**（执行态日志 `##[notice]…执行 k6 冒烟实跑` + bridge 探针 warning + 服务栈启动 245s，run `39140973534`；不相关 PR 按设计 skip 记 success 防悬空 required check），capacity 基线首跑被 staging secrets 阻塞（roadmap C2 在案）；demo/ 为独立本地演示包（express mock + 七步流程脚本，ARCHITECTURE L406 为使用示例），无 CI 引用、非集成证据——无需变更；
- CI 实证：Security Scan run `39139292023` **success**（SAST - SpotBugs + FindSecBugs job 7m32s 全绿——6 个 composite 模块报告经 gate 实测 0 findings）；concurrency 取消行为与最终 HEAD 的 Pipeline/Security Scan 全套 run 号见 PR #140 评论区（W10 三提交推送的 Pipeline run `39139292026` 的最终处置亦记录于此）。

---

## 8. 需要你拍板的决策点

1. ~~tag 格式统一方向~~ **已决并执行**：全面 `vX.Y.Z`（镜像/部署引用）；chart 元数据维持纯 semver。见 §7（批次②明细）。
2. ~~GHCR 实拉验证窗口~~ **已查明并定策（2026-10-19）**：v2.74.0 镜像**从未发布**——GHCR 现存最高 v2.73.0、release 运行史无 v2.74.0 tag（批次②对齐的 v2.74.0 部署资产属**待发布目标**）；package 匿名可读、manifest 直拉可用、NJU 镜像站同 digest 备援——**无需额外凭据**。前置链：PR #140 合并 → master 切 tag `v2.74.0`（触发 Release & Deploy：13 镜像构建 + GitHub Release + helm publish + staging/prod 部署，v2.73.0 同链先例全 success）→ 逐镜像实拉/digest 验证并记录。
3. ~~OWASP Dependency-Check 去留~~ **已决并执行（2026-10-19）：完善（转正）**——「永远上报」试运行评估通过（26 次 run：min 2.1 / 中位 6.0 / max 20.4 分钟；失败可归因；缓存未挤爆），同仓库 PR 恒真跑定档为最终语义、保留 required；TEMP 标记全部清理；评估表与历史注记写入 `docs/dependency-check-update-policy.md`「转正结论」节；顺带修复 Dependabot 配置（自 v2.24.0 引入以来 0 PR——`auto-merge` 非 schema 键 + User 账号下无效团队 reviewers，已修复），SCA 三件套（DC / Trivy / Dependabot）分工成文。
4. **origin/master 历史**：本地 master 领先 611 个提交，origin/master 疑似 2 提交扁平孤儿历史（merge-base 为空）。这决定后续 push/PR 的基线与策略——需要你说明现状或授权处理。
5. ~~本报告与后续批次产物的归属~~ **已决并执行**：报告已收入 `docs/audit/2026-10-18-eng-review-and-remediation-plan.md`（随 PR #140）；agent-board 认领留言随提交入库（`docs/agent-board/20261018-1900-eng-review-money-path-p0.md`）。
6. ~~推送与 PR~~ **已决并执行**：批次①+②+⑤ 共 **11 个提交**已 push（`origin/fix/eng-review`）并开 **PR #140**（`fix/eng-review` → `master`；推送前已 rebase 到 origin/master，提交哈希以 §7/§9 为准）。后续批次③/④/⑦ 将在同一分支/PR 续作（用户已确认继续执行）。**合并授权更新（2026-10-19）**：确认无冲突且前置具备即合并；执行序＝OWASP 转正/修复与残项复核落库 → CI 全绿 → 合并 #140/#141 → 切 tag `v2.74.0` → GHCR 实拉验证。

---

## 9. 附录：复现步骤

```bash
cd F:/Nexus/nc-wt-engreview   # 批次①+②+⑤工作树（分支 fix/eng-review）

# 1. 版本门禁（8 断言：含部署资产 tag 口径）
bash scripts/check-version-consistency.sh

# 2. MPC 分层门禁（9 项断言）
bash scripts/check-mpc-tier-policy.sh

# 3. 许可证扫描（离线模式，不应有任何网络请求）
python scripts/check-license-policy.py --out-dir build/license-report

# 4. compose 校验
docker compose -f docker-compose.yml config -q
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q   # 缺任一必需变量应报错拒起

# 5. 门禁反向用例（可选）：把 CHANGELOG 最新条目改成更高版本 → 步骤 1 应红

# 6. 批次⑤定向测试（gateway 资金路径 + signing JWT 守卫）
./gradlew.bat :nexus-gateway:test --tests "org.nexus.gateway.refund.*" --tests "org.nexus.gateway.fundreport.*" --tests "org.nexus.gateway.security.JwtTokenProviderTest" --tests "org.nexus.gateway.wiring.WiringEffectivenessGuardTest" :nexus-signing-service:test --tests "org.nexus.signing.config.JwtTokenProviderTest"

# 7. 批次⑤门禁
python scripts/check-scheduler-lock.py && python scripts/check-code-hygiene.py
```

**提交列表（批次①+②+⑤，共 11 个；已推送 + PR #140，rebase 到 origin/master 后哈希）**：`git log --oneline origin/master..HEAD` → `fca847f7 / 7475c232 / ae1043ab / 56103804 / 81e0c2b9 / 9c9724e5 / 85902206 / 36801697 / 0712270d / 089420de / 4df54ec7`。
