---
id: 20261104-0852-nex-claim-plan074-local-alert-drill
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "qoder CN", zcode]
type: 认领
priority: P2
status: open
refs:
  - board: 20261104-0614-nex-plan073-done
  - board: 20261104-0351-nex-claim-plan073-outbox-observability
  - file: docs/plan/PLAN-073-finality-outbox-observability.md
  - file: deploy/k8s/50-prometheus-rules.yaml
  - file: deploy/k8s/tests/prometheus-rules.test.yaml
  - file: .github/workflows/k8s-sync-check.yml
  - file: scripts/extract-prometheus-rules.py
created: 2026-11-04T08:52:00+08:00
---

## 主题

认领 **PLAN-074：本地真实链路告警演练**（`finality_vote_outbox` 指标 → 真实
Prometheus 抓取 → 真实 Alertmanager 送达），并把 PLAN-073 只做到 H2+mock 的
两个语义缺口拿到**真实 PostgreSQL** 上取证。

用户指示「本地环境能做多少做多少」，本切片就是这句话的字面执行：
把 roadmap #27 里原先标注「**需 Prometheus/Alertmanager**、**需真实集群**」的
告警演练项，用**本机三进程**闭环。**同内核单机，不是物理多机。**

## 一、为什么现在能做了（PLAN-073 时的判断已过期）

PLAN-073 交付留言 §五「后续」原文：

> 真实集群告警演练（需 Prometheus/Alertmanager）

当时的隐含前提是「服务端二进制本机没有、且不下载」。这个前提**可以拆除**：

| 能力 | 状态（2026-11-04 实测） |
|---|---|
| PostgreSQL 16 | WSL2 Ubuntu-24.04 `/usr/lib/postgresql/16`，服务已起（5432），库 `nexus_p074` + 角色 `nexus` 已建 |
| Prometheus 3.15.1 | 已从 GitHub releases 下载（106.5 MB，sha256 `6a726885…`），WSL `/opt/local-tools`，`--version` 正常（与 promtool 同版本线） |
| Alertmanager 0.34.2 | 已下载（37.5 MB，sha256 `17ee14ac…`），`--version` 正常 |
| nexus-core | WSL 内 JDK 17.0.20.1 + Gradle（`/mnt/f/Nexus/nc-wt-p67`），**编译验证中** |
| CI 手法复用 | `.github/workflows/k8s-sync-check.yml:337-356` 已有「下载 + sha256sum -c + 固定版本」的成熟写法，可照搬 |

⇒ 告警演练**不再需要真实集群**：一个 Prometheus + 一个 Alertmanager + 一个
nexus-core，都在本机即可端到端验证「指标真的被 Prometheus 抓到、规则真的
求值为 firing、告警真的到达 Alertmanager」。

## 二、四条要验证的事实（PLAN-073 未做到的）

| # | 事实 | 为什么 PLAN-073 没做到 |
|---|---|---|
| 1 | 真实 PG 下 `database_pending` **精确** | PLAN-073 用 H2 + mock store 证明 `count(*)`；真实 PG 的 `SELECT count(*)` 行为、JDBC 列类型映射（BIGINT→Long）未验 |
| 2 | 真实 PG 停机后 `database_readable == 0` 且 `database_pending` **NaN** | PLAN-073 用 `FaultyStore`/mock status 证明；真实 PG 停机路径（连接失败异常是否被 `status()` 收敛为 `readable=false`）未验 |
| 3 | 抓取文本里 `nexus_finality_outbox_*` 的**真实形状**（`# TYPE`、标签键、`_total` 后缀） | PLAN-073 只有 `FinalityRulesContractTest` 的名字级断言 + promtool；promtool 吃的是合成序列，不是真实抓取文本 |
| 4 | 告警**真的触发并送达**（Prometheus firing → Alertmanager） | PLAN-073 只做 promtool 静态/演练；**没有任何一次真实求值与投递** |

## 三、范围

### 3.1 取证（仓库外，不引入新测试依赖）

- WSL2 PG `nexus_p074` + WSL 内 JDK 单进程 `:nexus-core:nexus-core:run`
  （`nexus.finality.store=jdbc`、`server.port=19585`、JWT app-id/secret 显式注入）；
- 真实抓取 `/actuator/prometheus`，断言上面 1/2/3 三条；
- Prometheus scrape 配置指向该端口 + 加载真实规则文件
  （`scripts/extract-prometheus-rules.py` 抽出的 `rules.yaml`）；
- Alertmanager 起一个最小配置（routes 全量到一个 webhook/日志文件），
  Prometheus alerting 指向它；制造积压/停机 ⇒ 断言 Prometheus `/api/v1/alerts`
  有 firing、Alertmanager 收到；
- 脚本与日志全部留在 `C:\Users\winge\AppData\Local\Temp\opencode\` 与
  `/tmp/`，**不入仓**。

### 3.2 可进 CI 的门禁（本切片唯一代码侧交付）

补一条 PLAN-073 漏掉的**引用形状契约**：counter 必须以 `increase()`/`rate()`
引用，不能裸比较。理由：counter 是单调累计值，裸 `== / >` 比较在进程重启后
（counter 归零）会**永远不触发**或误触发；PLAN-073 的
`increase(nexus_finality_outbox_dropped_total[10m]) > 0` 是对的，但这条规则
目前没有任何门禁守着——新告警写成 `dropped_total > 0` 会一路绿灯。

落到 `FinalityRulesContractTest`（或同包新测试）：遍历 `nexuschain.finality`
与 `nexuschain.block-adoption` 两组 expr，凡引用 `*_total` 名字的，其表达式
必须包含 `increase(` 或 `rate(`（含 `reset()` 变体可按需放行）。

### 3.3 文档

`docs/plan/PLAN-074-local-alert-drill.md`：环境矩阵、手法、四条事实的实测
记录、失效模式（如 Prometheus 15s scrape 与 30s 采样的组合语义）、边界。

## 四、非目标

- **不做物理多机 / 网络分区**：本机三进程，`localhost` 互连，明确不宣称
  分布式/集群结论；
- **不改告警阈值**：演练只验证链路，不动 `for`/表达式（除非演练抓到真实缺陷，
  单独回报）；
- **不动 production Prometheus/Alertmanager 部署**：
  `deploy/monitoring/`、`deploy/k8s/50-prometheus-rules.yaml` 的既有内容只读；
- 不动 wallet / settlement / bridge / mpc lane。

## 五、边界（勿夸大）

1. **同内核单机**：WSL2 + Windows 侧 JDK/Prometheus/Alertmanager，本机进程互连；
2. **不是真实机房**：无真实 ServiceMonitor、无 kube-prometheus-stack、无多副本
  抓取拓扑；Prometheus 是自己下载的社区二进制，不是集群内的那个；
3. **采样 30s + scrape 15s** 的组合会让 `for` 的语义与生产略有差异
   （本机可调 scrape 间隔对齐生产，仍在文档里如实登记）；
4. 不宣称「告警已在真实集群触发过」；
5. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 六、验证计划

- **反向对照**：把 3.2 的门禁做成会红——在规则文件里临时把
  `increase(nexus_finality_outbox_dropped_total[10m])` 改成裸
  `nexus_finality_outbox_dropped_total > 0` ⇒ 新门禁必须精确报出「哪条告警、
  哪个指标、期望哪种引用」；还原后恢复绿且 SHA-256 一致；
- **正向**：`:nexus-core:nexus-core:test` 全量 + `spotbugsMain` +
  `check-code-hygiene.py`；
- **演练**：promtool v3.15.1（`check rules` + `test rules`）与 3.1 的真实链路
  都要留记录，两者都过才算数。
