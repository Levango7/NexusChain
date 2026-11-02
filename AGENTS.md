# AGENTS.md — NexusChain 多 Agent 入场索引

> **性质**：给新加入的 Agent 的「一页入口」，不是规范本体。规范以
> `docs/agent-board/README.md`、`README.md` 与 CI 门禁为准；本页在冲突时让位。
>
> **快照时间**：2026-11-02（master `35dfbf6c`）。分工与在途 PR 变化很快，
> 以 `docs/agent-board/` 最新留言为准；本页只负责把「先读什么、怎么开工、
> 哪里不能碰」说清楚。

## 1. 项目是什么

NexusChain 是**以自研结算链为底座的支付编排平台**：统一支付 API、多链/多渠道路由、
清结算/合规/分析。区块链是结算基础设施，不是产品本身。

- **商业化口径**：现阶段不持牌照、不直接运营资金；软件私有化交付给持牌机构/有资质的集团，
  沙箱态对外展示。
- **资产边界**：未达到生产级安全属性前，不应用于承载真实资产（见 README「资产承载限制」）。
- **当前版本**：`2.75.0`（2026-10-26）。构建侧双源：根 `build.gradle` 的 `version` 与
  `nexus-core/nexus-core/src/main/resources/version.properties` 的 `versionNumber`，发版两处都要改。

## 2. 先读什么（按顺序）

1. `README.md` — 模块清单、成熟度声明、MPC/桥/L2/ZK 的真实状态与诚实边界。
2. `ARCHITECTURE.md` — 模块地图、共识/治理/签名审批/L2 分层。
3. `PRD.md` — 产品定位、MVP 能力、API 生命周期。
4. `CHANGELOG.md` — 版本合同；最新条目以文件顶部为准，不要复述旧版本号。
5. `docs/agent-board/README.md` — **多 Agent 协作规则，开工前必读**。
6. `docs/roadmap-next.md` / `docs/roadmap-unresolved.md` — 前方规划与历史问题台账。

## 3. 构建与测试（最小集）

环境：JDK 17+、Gradle 8.14（wrapper 自带）；Docker 可选；Rust 仅 `mpc-engine` 需要。

```bash
# 聚合测试（根构建 Java 子项目 + composite build；不含 integration 标签）
./gradlew testAll

# 网关沙箱模式（必须在仓库根执行；gateway 子目录内独立构建不可用）
./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"

# 一键全栈
docker-compose up -d

# Windows 本地 PG + 原生 core
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1 -StartCore
```

CI 必检项以 branch protection 为准（当前含 Gitleaks、Trivy、SAST、Cargo Audit、
Code Hygiene、k6 Smoke、OWASP Dependency-Check、Build & Test，以及 MPC 集群回归）。
**不要**用 `--admin`、跳过 hook 或关闭必检项来换合并速度。

## 4. 工作树与主树（最容易踩的坑）

- `F:/Nexus/NexusChain` 是**共享主树**，多个 Agent 共用；它经常停在旧基点
  （2026-11-02 时落后 origin 16 个提交，且带 3 条未跟踪留言）。
- **不要**在主树切分支、rebase、reset、clean。需要开工时从 `origin/master` 新建 worktree：

```bash
git -C F:/Nexus/NexusChain fetch origin --prune
git -C F:/Nexus/NexusChain worktree add -b <type>/<slug> F:/Nexus/nc-wt-<slug> origin/master
```

- 已有 worktree 属于其他 Agent（`nc-wt-a2off`、`nc-wt-acpgate`、`nc-wt-idem` 等），
  **不要进入、不要清理、不要复用它们的分支**。
- 两条已登记的孤儿分支：`feat/governance-audit-visibility`（`nc-wt-govaudit`）、
  `feat/plan-042-total-storage-loss`（`nc-wt-p6c`）；是否续做由板面声明，不由新人处置。

## 5. 留言板：唯一的跨 Agent 协作介质

规则全文见 `docs/agent-board/README.md`，这里只列必须记住的六条：

1. **一留言一文件**，文件名 `YYYYMMDD-HHMM-<agent>-<slug>.md`，目录 `docs/agent-board/`。
2. **投出即整篇不可变**（含 front-matter）。完成/勘误/撤回都发**新留言**，用 `refs: - board: <id>` 关联。
3. **`owner` 必填且写具体 Agent 名**，不能写角色或 GitHub 账号——所有 PR 作者都是 `Levango7`，账号无法区分 Agent。
4. **只执行 `to` 包含自己的留言**；`to: all` 视为包含所有人，任何人可认领，认领要快。
5. **留言只走 docs-only PR**；功能 PR 的 diff 里不得出现 `docs/agent-board/`。
6. **发现别人的问题 → 投留言给 owner，不动手**；不评论、不推送、不合并他人 PR。

签收门禁（2026-10-31 起）：`ack_required: true` + `expires: <ISO-8601>` 的 P0/P1 留言，
逾期且无人以新留言引用签收 ⇒ `code-hygiene` 必检 job 判红。

## 6. 当前分工（2026-11-02 快照，待各 owner 校正）

> 这是 `20261102-0118-trae-claim-project-status-baseline` 整理的读法，
> 不是任命；以板面回帖和实际 PR 为准。

| 身份 | 当前域 | 在途 |
|---|---|---|
| **nex** | consensus / finality / 补票 / 指标门禁；结算 S1–S3；多项裁决 | #352（S3，DRAFT）；PLAN-066 已实现（#400） |
| **zcode** | gateway 审批/执行腿、SDK 词表、ADR-036、signTransfer 幂等键 | ADR-036 勘误已合（#395/#397）；幂等键 ADR 草案 |
| **qoder CN** | wallet/signing fail-closed 收敛、deploy 渲染门禁、板面签收门禁 | #390 已合（`0386b8af`） |
| **codearts agent** | Flyway 迁移号段 / DB 文档 / README 成熟度核对 | 无明显在途（#379/#380 已闭环） |
| **eng-review** | 全仓审计、资金路径 P0 批次、发布收口 | 自 10-19 后无明显板面动作 |
| **orchestrator** | 指派、裁决、外部前置 | 不直接写代码 |
| **TRAE 宝** | 项目现状/分工索引、roadmap 快照（docs-only） | 0118 已交接（#401，在途） |

历史参与者：**opencode** 在 PLAN 文档里留下 `C:\Users\winge\AppData\Local\Temp\opencode\...`
与 `F:\Agent\opencode\workspace\...` 证据；**WorkBuddy** 在 10-27 发现平台地址占位符类 P0，
被 zcode 复核确认。板面当前没有它们的独立署名，**只能确认做过，不能确认还在做**。

## 7. 当前闸门与待办（快照）

已完成（本轮）：ADR-036 勘误（#395/#397）、退款平台地址 fail-closed（#398/#399）、
PLAN-066 实现（#400）、0118 现状基线（#394）。

1. **#352（S3）**：依赖 #347 已合（10-29），可 rebase/去 DRAFT；法币 `payee=null` 政策仍未决。
2. **结算 0320**：`DefaultClearingEngine:63` 仍是 `PLATFORM_HOT_WALLET` 默认值 +
   `:67-68` 空值二次回退；仓库内零注入，已投 P2 通知给 nex，尚未回帖/修复。
3. **0247/0249 未送达**：只在共享主树工作区，master 上看不到；0248 已由 #359/#364 兑现、
   #388 明确可销账。0249 是法币收款方政策请求，不送达则裁决不启动。
4. **staging A2**：A2 开着但 `NEX_WALLET_PLATFORM_PUBKEY` 未下发 ⇒ 当前 master 部署即
   提现腿 100% fail-closed；需要公钥值 + 「先关后开」的决定。
5. **A3 风控口径**：限额按自然日 vs 滚动 24h，需产品拍板。

## 8. 资金语义口径（改代码前先对齐）

- 只有 `SigningResponses.declaresNotExecuted`（5000/4000/4030）才可落终态 **FAILED**
  （= 确定未动账，可安全重试）。
- 5001/5002/5003、`null`、未知码、超时/异常 ⇒ **EXECUTED_UNVERIFIED**（wallet）/
  **EXECUTION_UNKNOWN**（gateway），非终态、禁自动重放、交人工核对。
- 同一响应在「异常腿 / 信封腿 / 直签腿」必须给出同一结论；改一处要检查其余两条腿。
- 平台地址未配置 ⇒ 执行前 `not configured` fail-closed，不得回退占位符；wallet（10-27）
  与退款（#398）已落地，结算 0320 仍待办。

## 9. 硬边界

- ❌ 不修改他人分支、不向他人 PR 推送提交、不替他人合并/关闭 PR。
- ❌ 不在他人 PR 下擅自评论（对外发声需本人授权）；跨 Agent 结论走留言板。
- ❌ 不把密钥、token、真实账户写进仓库；配置默认值不得是「看起来能跑」的占位符。
- ❌ 不在主树做破坏性 git 操作；不清理他人 worktree。
- ✅ 唯一允许的介入方式：**投留言给该事项的 owner**，并在「边界」段写明自己没做什么。
