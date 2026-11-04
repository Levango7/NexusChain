# 方案 PLAN-074：本地真实链路告警演练（Prometheus/Alertmanager 端到端 + 真实 PG 取证）

- **状态**：已交付（2026-11-04）
- **前置**：PLAN-073（outbox 可观测性 + 4 条告警，`#439`）
- **范围**：在**本机三进程**上把 PLAN-073 只做到单测/promtool 的四条事实
  全部拿到真实链路证据，并补一条可进 CI 的引用形状门禁
- **不包含**：物理多机、真实机房 Prometheus/Alertmanager、kube-prometheus-stack、
  多副本抓取拓扑、告警阈值调整、生产部署改动

---

## 一、为什么这个切片现在能做

PLAN-073 交付留言 §五把「真实集群告警演练」登记为受限于 **Prometheus/Alertmanager**。
该前提在本地**可以拆除**（用户指示「本地环境能做多少做多少」）：

| 能力 | 取得方式 |
|---|---|
| PostgreSQL 16.15 | WSL2 Ubuntu-24.04 已装，`service postgresql start` 即用 |
| Prometheus **3.15.1** | GitHub releases 下载（106.5 MB，sha256 `6a726885…`），与 promtool 同版本线 |
| Alertmanager **0.34.2** | GitHub releases 下载（37.5 MB，sha256 `17ee14ac…`） |
| JDK/Gradle | WSL 内 JDK 17.0.20.1 + Gradle，仓库与 `GRADLE_USER_HOME` 均挂载可达 |

⇒ 一个 Prometheus + 一个 Alertmanager + 一个 nexus-core，都在本机即可验证
「指标真的被抓到、规则真的求值、告警真的送达」。**不需要真实集群。**

## 二、环境矩阵（同内核单机，非物理多机）

| 组件 | 位置 | 端口 | 备注 |
|---|---|---|---|
| PostgreSQL 16.15 | WSL2 Ubuntu-24.04 | 5432 | 库 `nexus_p074`，角色 `nexus` |
| nexus-core | WSL2 内 JDK 单进程 `:nexus-core:nexus-core:run` | 19585 | `CONSENSUS_MODE=pos`、`FINALITY_STORE=jdbc`、`DATA_SOURCE_URL=jdbc:postgresql://localhost:5432/nexus_p074`、JWT app-id/secret 显式注入 |
| Prometheus 3.15.1 | WSL `/opt/local-tools` | 9097 | scrape 15s、`rules.yaml` 由 `scripts/extract-prometheus-rules.py` 从 CRD 抽出 |
| Alertmanager 0.34.2 | WSL `/opt/local-tools` | 9098 web / 9099 cluster | webhook → 本地 python 接收器 9094 |
| webhook 接收器 | WSL python3 | 9094 | 把每次投递原样落盘 `hooks/hook-NNN.json` |

**端口选择教训**：`9090-9091` 被 Windows 侧 Docker 容器
`opsmesh-controlplane-1` 占用（`docker ps` 可见），最初 Prometheus 起不来就是这个原因；
Alertmanager 默认 gossip 端口与 webhook 9094 冲突。改 9097/9098/9099/9094 后全部就绪。

## 三、四条事实的实测（对应 PLAN-073 未做到的部分）

### 3.1 真实 PG 下积压数**精确**（`#439` 只做到 H2 + mock）

往 `finality_vote_outbox` 插 100 行（`ON CONFLICT DO NOTHING`，`INSERT 0 100`），
等一个 30s 采样周期后：

```
nexus_finality_outbox_database_pending 100.0      # 旧实现会报 64
nexus_finality_outbox_database_readable 1.0
```

Prometheus 侧同样拿到 100（`value=1e+02`）。`SELECT count(*)` 在真实
PostgreSQL 上的 BIGINT→Long 映射正确。

### 3.2 真实 PG 停机后 `readable=0` + `database_pending=NaN`

`service postgresql stop`，等一个采样周期后抓取：

```
nexus_finality_outbox_database_pending NaN        # 不再是冒充的 0
nexus_finality_outbox_database_readable 0.0
```

⇒ PLAN-073 修的语义缺陷在**真实连接失败路径**上成立（此前只有 `FaultyStore`/mock）。

### 3.3 真实抓取文本形状

9 条序列全部就位且形状符合契约：`# TYPE gauge` / `# TYPE counter`、
counter 带 `_total` 后缀、`nexus_finality_outbox_pending{origin="local"}` /
`{origin="received"}` 两条序列。promtool 吃的是合成序列，这不是它验过的东西。

### 3.4 告警真实触发 + 真实送达 Alertmanager（PLAN-073 完全没做的一环）

Prometheus `targets` `health=up`，真实求值后：

| 告警 | Prometheus 状态 | Alertmanager | webhook 落盘 |
|---|---|---|---|
| `FinalityOutboxBacklog`（100 行积压，for 10m） | **firing** `value=1e+02` | **active** | `hook-006.json` `status=firing`，25 次投递 |
| `FinalityOutboxUnreadable`（PG 停机，for 5m） | **firing** | **active** | `hook-091.json` `status=firing`，4 次投递 |
| `FinalityStalled` / `BlockchainCoreStalled`（既有告警，作为链路对照） | firing | active | 各 25 次 |

另有一个**语义切换**实证：PG 停机后 `FinalityOutboxBacklog` 自动转为非 firing
（`database_pending` 变 NaN ⇒ 表达式不成立），同时 `Unreadable` 亮起——
这正是 `readable` 字段该有的行为。

## 四、CI 侧交付：counter 引用形状门禁

PLAN-073 写的 `increase(nexus_finality_outbox_dropped_total[10m]) > 0` 是对的，
但**没有任何门禁保证以后的人记得**。counter 是单调累计值，裸比较
（`dropped_total > 0`）在进程重启（counter 归零）后**永远不触发**。

新增 `FinalityRulesCounterUsageTest`：遍历两个告警组的 expr，凡引用
`nexus_*_total` 的，其引用点必须位于 `increase(` 或 `rate(` 内部；违规时报出
「哪条告警组、哪条告警、expr 原文」。

**实现上的一次真实返工**：第一版自己在测试里按行扫规则文件，产生三个假阳性——
① 把 `description` 里给人看的指标名当引用；② 把组级 `#` 注释行当引用；
③ 把组级说明块算进下一条 alert（因此 `FinalityOutboxUnreadable` 名下报出
`vote_rejected_total`）。改为复用 `PrometheusRulesFixture.groupExprLines(group)`
（与 PLAN-062/063 同一套「只取 expr 字段」的提取规则，仅额外保留 alert 名）后干净。

**反向对照**（精确命中，非「红了就行」）：

- 变异：`increase(nexus_finality_outbox_dropped_total[10m]) > 0`
  → `nexus_finality_outbox_dropped_total > 0`
- 红：断言失败，报告**仅**一行违规——
  `nexuschain.finality / FinalityOutboxDropping：counter nexus_finality_outbox_dropped_total 未包 increase()/rate()（expr 原文：nexus_finality_outbox_dropped_total > 0）`
  （同批次无其他 alert 行 ⇒ 没有连带假阳性）
- 还原：BUILD SUCCESSFUL，规则文件 SHA-256 与变异前逐字一致
  `29F80F9E784E42B1F151574796AED409EE781C183E9B55F48BEDD0DE845F1A74`

## 五、本地门禁

- `:nexus-core:nexus-core:test` 定向：`FinalityRulesCounterUsageTest` /
  `FinalityRulesContractTest` / `BlockPersistenceRulesContractTest` 全绿；
- promtool v3.15.1：`check rules` 24 rules SUCCESS + `test rules` 全部演练 SUCCESS
  （PLAN-073 已交付，本切片复跑确认未破坏）。

## 六、边界（勿引用为更强结论）

1. **同内核单机**：WSL2 + Windows 侧 JDK/Prometheus/Alertmanager 本机进程互连，
   **不是物理多机**，不构成分布式/集群结论；
2. **不是真实机房**：Prometheus/Alertmanager 是自己下载的社区二进制，
   不是集群内 kube-prometheus-stack 的那套；无 ServiceMonitor、无多副本抓取拓扑；
3. **不宣称「告警已在真实集群触发过」**；
4. scrape 15s + 应用采样 30s 的组合，与生产（ServiceMonitor 默认 30s/15s）可能有差异，
   本文件的数值按本机 15s/30s 记录；
5. **未改任何告警阈值**（演练只验链路；若抓到真实缺陷会单独回报）；
6. 实验脚本、日志、webhook 落盘全部留在仓库外
   （`C:\Users\winge\AppData\Local\Temp\opencode\p074\`、`/tmp/`、`/opt/local-tools/`）；
7. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 七、后续

- 若要进一步逼近生产：给 localonly=true 的抓取加鉴权（PLAN-068 已证明拦截器
  够不到 actuator，需要 EndpointFilter/Spring Security，属独立切片）；
- 多副本抓取拓扑、网络分区：仍需物理多机，维持登记；
- retention / 规范检查点语义：维持登记（需产品定义 / 链接口）。
