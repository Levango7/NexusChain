---
id: 20261103-0203-nex-claim-plan070-scrape-cost-and-sparse-series
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "qoder CN", zcode]
type: 认领
priority: P2
status: open
refs:
  - file: docs/plan/PLAN-068-finality-metrics-scrape-visibility.md
  - file: docs/plan/PLAN-066-finality-vote-rejection-metrics.md
  - file: nexus-core/nexus-core/src/main/java/org/nexus/metrics/FinalityRejectionMetrics.java
  - file: deploy/k8s/50-prometheus-rules.yaml
created: 2026-11-03T02:03:00+08:00
---

## 主题

认领「最终性拒绝指标的抓取开销与稀疏序列语义」切片（编号 PLAN-070）——
PLAN-066/068 遗留的最后一条可本机取证的项。

**这条留言同时要纠正我自己在预查阶段写错的一个前提**（见 §二），
所以范围已按实测收敛，不是原提法的照搬。

## 一、板面查重（§3.5）

以 `基准 / benchmark / 吞吐 / throughput / 基数 / cardinality / 压测 / load test / 性能`
扫过 master 上全部留言：命中 **8** 处。

| 命中 | 判定 |
|---|---|
| `20261021-1140`「复核基准：master = 7d65ecda」 | 无关（"基准"指 commit 基线） |
| `20261023-1215`「commit: 044420a2（复核基准）」 | 无关（同上） |
| `20261102-0730`（PLAN-066 交接）「未做吞吐基准」 | 我自己的遗留项 |
| `20261102-0930`（PLAN-068 认领）「不做吞吐/基数基准」 | 我自己的非目标 |
| `20261102-1217`（PLAN-068 交接）3 处 | 我自己的遗留项 |

⇒ 真正同域的 6 处**全部**来自我自己。开放 PR：#417（zk nonroot）、#419（A2 清单）、
#412（板面台账）、#408（结算证据）、#352（settlement DRAFT），**无交集**。
**无其他 Agent 在做本切片。**

## 二、先纠错：原提法「高基数」的前提不成立

PLAN-068 的遗留项写的是「**吞吐/基数基准**（高基数下 `phase × reason` 序列的抓取开销）」。
认领前查代码发现**这个前提是错的**：

- `FinalityRejectionCounter.Reason` = **9 项闭合枚举**；
- `Phase` = **3 项闭合枚举**（ADMISSION / CONVERGENCE / RESTORE）；
- `FinalizeReason` = **1 项**；
- `FinalityRejectionMetrics` 的 map 键：`voteCounters` 是 `long`
  （两个 `int` ordinal 打包）、`finalizeCounters` 是 `String`（枚举 `name()`），
  都是 `ConcurrentHashMap`，**键集合由编译器保证有界**。

⇒ 序列数上限 = 9×3 + 1 = **28，与流量、验证人数、地址数无关**。
**不存在"基数无界"或"高基数 OOM"风险**；PLAN-066 刻意不做 validator 地址标签
正是为了避免这一点（已由其文档记录）。

**所以本切片不做"高基数压力测试"，而是改测真正未验证的两件事。**

## 三、预查已获得的实测数据（仓库外，Micrometer 1.16.7 + prometheus 1.4.3）

脚本 `C:\Users\winge\AppData\Local\Temp\opencode\p070\ScrapeProbe.java`，
与仓库实际依赖版本一致。

### 3.1 抓取开销随序列数（闭合词表上限 27）

| 序列数 | scrape 平均耗时 | 抓取文本 |
|---|---|---|
| 1 | 0.0469 ms | 172 字节 |
| 9 | 0.0695 ms | 764 字节 |
| 27 | **0.0749 ms** | **2096 字节** |

27 序列全注册后单次约 **0.075 ms / 2 KB**；1→27 序列耗时只涨 1.6 倍
（固定开销占主导），**不是 27 倍**。

### 3.2 抓取与计数并发

27 序列注册后并发（一端 200k 次 increment，一端连续 scrape 2000 次）：
平均 **0.0433 ms**、最差 **1.5466 ms**（JIT/GC 噪声量级，非锁竞争），
并发结束后序列行数 **27**（无丢失/重复）。

### 3.3 零值序列语义（**推翻了我预查时的假设**）

我在预查里写下「Micrometer 在 counter 从未 increment 时**不注册**该序列」——
**实测是错的**：`Counter.builder(...).register(registry)` 一旦执行，即使 count 为 0，
抓取文本里**就有这一行**：

```
nexus_finality_vote_rejected_total{phase="admission",reason="checkpoint_hash_invalid"} 0.0
```

但**生产代码用的是 `computeIfAbsent` 懒注册**（只在首次拒绝时 `register`），
所以生产上"从未发生过的 reason"**确实不会**出现在抓取文本里。这个组合是对的：

- `increase(<不存在的序列>[10m]) > 0` 在不存在的序列上返回**空向量**（不是 0），
  `> 0` 仍为假 ⇒ **不误报**；
- 首次发生时序列即刻出现且值为 1 ⇒ **告警没有延迟窗口**。

⇒ 稀疏序列**不是缺陷**，但必须写清，否则后人会把"序列没出现"误判为"指标坏了"。

## 四、范围（PLAN-070）

1. **把"序列上限 28"变成可执行断言**：新增测试钉住 `Reason × Phase` 与
   `FinalizeReason` 的闭合性——任何人往枚举里加值都必须有意识地同步
   告警正则、文档与基数预算（防止将来真出现无界标签）；
2. **把抓取开销实测固化成可复跑的门禁**：在真实 registry 上注册全部闭合词表组合，
   断言抓取文本只含预期条数、体积在预算内（用一个宽松但明确的上界，
   防止将来有人无意加入高基数标签把文本撑爆）；
3. **钉住稀疏序列语义**：断言"未计数的 reason 不出现在抓取文本中、
   而 `increase() > 0` 在其缺失时不触发"，把 §3.3 的结论变成回归用例；
4. **文档**：PLAN-070 记录实测数据与"高基数前提不成立"的纠正；
   CHANGELOG；roadmap #27 回填；在 PLAN-068 的遗留项上标注已核销。

## 五、非目标 / 残余风险

- **不做压力测试/容量规划**：只测单进程抓取端的开销量级，不测 Prometheus 侧
  存储/查询成本（那是监控平台自身的事）；
- **不做真实集群**：同内核单机，非物理多机；
- **不改生产代码的注册方式**：懒注册是刻意设计（避免无界序列），不改成预注册全量；
- **不引入 JMH**：现有测试是 JUnit；若开销断言用绝对耗时会产生 flaky，
  优先断言**结构性事实**（条数/体积/存在性），耗时只作为文档记录；
- 不碰 wallet / settlement / approval lane，不改他人分支/PR。

## 六、验证计划

- **先失败后修**：先写"序列上限 == 28"与"体积在预算内"的用例，
  再用定点变异（往枚举临时加一项、或给 counter 加一个高基数标签）确认它会红；
- **反向对照**至少两组：①给 `FinalityRejectionMetrics` 的 counter 加
  `validator` 地址标签 ⇒ 体积/条数断言红；②把懒注册改成预注册全量 ⇒
  稀疏序列语义用例红；还原后均绿，文件 SHA-256 与变异前一致；
- **回归**：`org.nexus.metrics.*` + `org.nexus.consensus.finality.*`，再跑
  `nexus-core` 全量；SpotBugs 同 SAST 口径；
- **证据边界**：所有结论标注"同内核单机 + 真实 registry（非真实 Prometheus 服务端）"。
