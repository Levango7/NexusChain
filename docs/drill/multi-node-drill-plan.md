# 多机故障演练方案（E3 准备半边）

> **状态**：准备件（资源到位即可跑）。执行前提为物理/云多机环境（等 ops/创始人排期）。
> **收账映射**：roadmap `C7`（PLAN-008 真机 registered 取证）/ `#27`（最终性故障注入剩余矩阵）/ `#29`（区块写库失败——物理机断电与网络分区）/ `#30`（节点级故障容忍——多机停机-恢复闭环重跑）。
> **认领链**：板 20261105-0640（认领）→ 本文件 + `scripts/drill/` 骨架。
> **finality/共识口径**：§4 的判据为**候选口径**，cc nex 复核（nex 为共识 lane owner）；复核前不得作为通过/失败裁决依据。

---

## 1. 目的与边界

**目的**：把 roadmap 四项挂账（C7/#27/#29/#30）从「本机虚拟化边界已验」推进到「多机+可控分区+PG 高可用已验」，并把演练做成**任何人可重跑**的资产（对照 09-14 全栈演练的教训：资产留仓外即不可复现）。

**边界（本件只做准备）**：

- 不申请资源、不定排期（人决项）；
- 不改任何共识/服务/存储代码（场景判据涉及代码行为时只读引用行号）；
- finality 判据口径由 nex 复核后冻结；
- 不宣称已验证——本方案交付的是「资源到位即可跑」的方案+脚本+证据格式。

**已知前置事实**（决定本方案形状）：

| 事实 | 来源 | 影响 |
|------|------|------|
| 同主机 3 节点停机-恢复闭环已验（杀 1/3 → 追平，缺口 0） | PLAN-020 / PLAN-027 §六 | S1 是**重跑**而非首验，价值在 Tier-2 |
| 共享存储拓扑成立、独立存储+跨内核拓扑有 F1/F2/F3 缺陷（已修于 PLAN-028/029/030） | PLAN-027 §三 | Tier-1/Tier-2 必须用**独立存储**才有增量 |
| 物理磁盘损坏数据丢失已在本机验（PLAN-042：20s 自注册、T0+51s 追平 0→256） | PLAN-042 | S4 同样是 Tier-2 重跑 |
| PG 高可用（主备切换）仓内**从未做过** | roadmap #28「仍非 PG 高可用」 | S3 是**新增能力**，风险最高 |
| 09-14 全栈演练资产留在仓外 `F:\Nexus\build\` 已丢失 | roadmap #25 取证 | 本方案的脚本必须入仓 |

## 2. 拓扑分层

| Tier | 形态 | 存储 | 分区手段 | 现状 | 用途 |
|------|------|------|---------|------|------|
| **0** | 本机 3 进程（19585/6/7 + 9235/6/7） | 共享 PG | 不支持 | 已有 `scripts/dev-cluster-3nodes.sh` | 回归基线、脚本自测 |
| **1** | 单机 docker 3 容器 | 每节点独立 PG 容器 | `docker network disconnect` | 本方案新增骨架 | 独立存储边界、分区注入最低成本形态 |
| **2** | 3 VM/物理机 + 各自 PG（PG 主 + 1 流复制备） | 独立 PG，主备 | iptables / 拔网线 | **需 ops 资源** | 本方案主目标（C7/#27/#29/#30 收账） |

Tier-2 主机角色：

| 角色 | 进程 | 数据 | 网络 |
|------|------|------|------|
| node-A | nexus-core（19585 / p2p 9235） | PG-A（主） | 与 B/C 互通 |
| node-B | nexus-core（19586 / p2p 9236） | PG-B（独立；S3 前不参与复制） | 与 A/C 互通 |
| node-C | nexus-core（19587 / p2p 9237） | PG-C（PG-A 的流复制备，S3 用） | 与 A/B 互通 |

> 三节点固定验证人密钥沿用 `dev-cluster-3nodes.sh` 的 a/b/c 私钥常量（仅演练用；生产密钥不经本文件）。

## 3. 场景清单

每个场景结构：**注入 → 观测窗 → 恢复 → 追平校验 → 判定**。证据落盘格式见 §5。

### S1 停机-恢复闭环重跑（收 #30；PLAN-027 §六的多机版）

- **注入**：`kill -STOP` 或直接 `kill` node-C 60s（对照 PLAN-027 用的是停 C）。
- **观测窗**：注入后每 5s 采 A/B/C 的 `/height` + finality epoch。
- **通过判据（候选）**：
  1. 停机期间 A/B 继续出块（height 单调增）、最终性不中断（FINALIZED epoch 连续）；
  2. C 恢复后**完全追平**：T_recover+N 内 C.height == A.height（N 由 nex 定标，候选 10 min）；
  3. 全窗 ERROR 日志计数 == 0（已知良性 WARN 除外，白名单见 §5）；
  4. 检查点无缺口或缺口经补票完全闭合（对 PLAN-025 anti-entropy 的多机验证）。
- **失败判据**：C 卡 `unknown proposer` 不可恢复；A/B 停摆（F3 回归）；补票不闭合。
- **回归锚点**：`ValidatorSetPersistence.replay()` 隔行丢失 bug（PLAN-020 修）——若 C 重启报 unknown proposer 持续，第一怀疑对象。

### S2 网络分区（收 #27 剩余矩阵首项 + #29/#30）

- **注入**：Tier-1 `docker network disconnect`；Tier-2 iptables 规则隔离 C（A-B 仍互通）。
- **形态**：3 节点 → {A,B} + {C}（少数派 1/3）。
- **通过判据（候选，**nex 复核重点**）**：
  1. 分区期间 C **不出块不最终化**（少数派不得伪造最终化）；
  2. A/B（2/3）继续出块与最终化（2f+1 活性）；
  3. C 的最终性视图**不被分区伪造票污染**（对 PLAN-065 验签 + PLAN-067 拒绝计数器的真分区验证：`nexus_finality_vote_rejected_total` 在分区/恢复窗内可增长但不得有伪造类 reason）；
  4. 恢复后 C 经 0x5B 补票 + anti-entropy 追平，历史 epoch 缺口闭合；
  5. 分区窗结束时 `nexus_finality_vote_rejected_total{reason}` 无 `checkpoint_limit_exceeded` / `payload_too_large` 洪水（对 PLAN-067 K=4 上限与 2048B 载荷上限的实证价值验证）。
- **失败判据**：A/B 停摆；C 在分区内假最终化；恢复后缺口不闭合（对照 PLAN-025 实验关闭补票时的历史 0 恢复）。

### S3 PG 主备切换（#28 收口的第一步）

- **前置**：PG-C 配为 PG-A 的同步流复制备（`synchronous_commit=remote_apply` 或 `remote_write`，由 nex/ops 定）。
- **注入**：停 PG-A → `pg_ctl promote` PG-C → node-A 指向 PG-C（连刺切换）。
- **通过判据（候选）**：
  1. 切换窗内 node-A 的 `/consensus/storage-status` 降级可读（PLAN-017 已交付的降级观测）；
  2. 切换后 node-A 恢复写库，链继续出块（对照 #28「PG 停止期间链停止出块」的既有事实——切换本身允许暂停，但不得永久卡死）；
  3. 最终性协调器恢复后无双重最终化（同一 epoch 两次 FINALIZED）；
  4. 无数据丢失 claims 需在同步复制级别下成立（远程事务序一致）。
- **失败判据**：切换后 node-A 起不来（迁移/连接池配置）；最终性视图分裂。
- **风险声明**：仓内首次，若失败属**发现**而非回归——该场景的产出优先级是证据与缺口清单，不是通过率。

### S4 数据丢失重放（收 #30 物理/全毁维度）

- **注入**：node-C 的 PG 库 + LevelDB cache-dir 全毁（`rm -rf` + 库 DROP），不人工种子，重启 node-C。
- **通过判据（候选）**：对齐 PLAN-042 同主机实证：20s 内自注册、全表回放认回验证人集、追块一次补齐、检查点经补票重建并与 A 收敛。
- **增量价值**：Tier-2 的磁盘真实不可用（物理坏盘 / 卷卸载），排除同机文件系统缓存带来的乐观。

### S5 C7 真机 registered 取证（收 C7）

- **目的**：PLAN-008 疑点「真机验证人广播 registered=false」。
- **操作**：Tier-2 起来后在 S1 基线窗内抓三节点日志的 `Validator-set add .* registered=` 行，统计 true/false 分布 + 各节点 `validators_synced` 表行数。
- **通过判据**：三节点互相收到全部对端验证人且 registered=true（对照 dev-cluster-verify 的同主机口径）；若真机出现 registered=false，落证据并升级 nex（属共识语义，不在本件裁决）。

## 4. Finality / 共识口径（**cc nex 复核**）

以下判据用于场景通过/失败裁定，**nex 复核冻结前仅作草稿**：

| # | 口径条目 | 依据 | 复核点 |
|---|---------|------|--------|
| F1 | 「最终化」= 3/3 节点对同一 epoch 报 FINALIZED 且视图一致（metrics/接口三源一致） | PLAN-021 三节点验收口径 | 是否要求严格 3/3 还是 2/3 一致即可 |
| F2 | 少数派分区允许暂停出块/最终化，禁止伪造最终化（分区恢复后可追平） | 2f+1 容错语义 | 暂停的合法时长窗口 |
| F3 | 恢复追平 = C.height 追上 A.height 且 finality epoch 缺口经补票完全闭合 | PLAN-025 补票 + PLAN-042 追块 | 追平时限（候选 10 min）与「完全闭合」的度量接口 |
| F4 | 补票窗口外远古 epoch 不补为**预期行为**（非缺陷） | PLAN-025 实验结论 | 复核确认多机下同样预期 |
| F5 | 拒绝计数器增长合法 reason 集合（分区/恢复窗内）= 去重/并发/限额类；**伪造类 reason 出现即判失败** | PLAN-065/066/067 | reason 枚举闭合性 |

> nex 复核方式：本文件 PR + 板面通知；复核意见直接改本表并标日期/依据。

## 5. 证据格式与采集规范

```
evidence/<run-id>/                     # run-id = YYYYmmdd-HHMM-tierN
├── run-meta.json                      # 拓扑/tier/脚本版本/起止时间/操作人
├── s1-stop-restart/
│   ├── timeline.tsv                   # t  A.h  B.h  C.h  A.epoch  B.epoch  C.epoch（5s 粒度）
│   ├── metrics-a/<t>.txt              # /actuator/prometheus 快照（重点序列见下）
│   ├── finality.json                  # 各节点 finality epoch 视图快照
│   ├── pg-counts.txt                  # votes/checkpoints/outbox 行数
│   ├── rejected-reasons.json          # nexus_finality_vote_rejected_total 按 reason 聚合
│   ├── logs/node-c.log                # 受控节点全日志（其余节点 tail -2000）
│   └── report.md                      # 场景判定：PASS/FAIL + 判据逐条 ✓/✗ + 证据指针
├── s2-partition/ ...                  # 同构
└── summary.md                         # 全场景汇总 + 缺口清单（roadmap 回写素材）
```

重点 metrics 序列（对 §3 判据）：`nexus_finality_vote_rejected_total`、`nexus_finality_finalize_rejected_total`、`nexus_finality_outbox_*`（PLAN-073）、`nexus_block_persistence_*`（PLAN-040）。

良性 WARN 白名单（不计 ERROR 判据）：`unknown proposer`（首窗口）、`block will be retried after validator-set sync`、`Validator-set add.*registered=false`（S5 单独统计）。

**证据不可篡改约束**：timeline/metrics 落盘后只追加不改写；判定失败的证据**必须保留**（不得因重跑通过而覆盖失败轮——失败轮证据是 roadmap 回写素材）。

## 6. 自动化骨架（`scripts/drill/`）

| 脚本 | 职责 | Tier-0 | Tier-1 | Tier-2 |
|------|------|--------|--------|--------|
| `drill-common.sh` | 端点/证据/轮询公共库 | ✓ | ✓ | ✓ |
| `drill-node-up.sh` | 单节点拉起（复用 dev-cluster-3nodes 参数形状） | ✓ | docker 变体 | ssh 变体 |
| `drill-evidence.sh` | 快照采集（timeline/metrics/finality/pg） | ✓ | ✓ | ✓ |
| `drill-scenarios.sh` | 场景执行器（s1..s5 子命令 + `all`） | s1/s5 可跑 | s1/s2/s5 | 全（s3 需 PG 备） |

骨架粒度：Tier-0 的 S1 端到端可跑（本机自测用）；Tier-1/2 的执行适配层以 `--dry-run` 验证编排正确性（注入命令打印不执行），等资源到位后去掉开关。

## 7. 资源需求（给 ops/创始人）

- Tier-2 最小：3 台 VM（2C4G 起，Ubuntu 22.04+，互通内网，可 iptables）或 3 台物理机；
- PG 流复制备（S3）：PG-C 预装同版本 PG（与 PG-A 同大版本，仓内开发用 16.x）；
- 出网：拉镜像/gradle 依赖（首跑）；之后可纯内网；
- 人力：演练操作 1 人 + nex 侧口径复核（异步即可）。

## 8. 不做（明确排除）

- 不做完整拜占庭矩阵其余输入（女巫/长程/私钥泄露）——超出 #27 剩余矩阵首项，排期属 nex；
- 不做 Prometheus 服务端成本/容量基准（PLAN-070 已证序列有界，不重复）；
- 不做真机断电（物理机电源操作不在脚本化范围，如需以 S2 分区+ S4 数据丢失近似替代并在 report 标注近似边界）；
- 不改代码——本件发现的所有代码缺口一律回写 roadmap，由对应 lane owner 认领。

---

*本方案为 E3 准备半边交付物；执行半边（资源到位后的实跑+report）单独立项。*
