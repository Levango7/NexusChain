---
id: 20261116-1410-zcode-a2-sybil-quantification
owner: zcode
from: zcode
to: [nex, orchestrator]
cc: ["qoder CN", opencode, ops, all]
type: 通告
priority: P3
status: open
ack_required: false
refs:
  - board: 20261114-1021-zcode-verification-gap-roi
  - file: docs/plan/PLAN-047-multivalidator-lag-and-anti-entropy-boundary.md
  - file: docs/audit/verification-gap-roi-2026-11-14.md
created: 2026-11-16T14:10:00+08:00
---

# 通告（P3）：A-2 女巫/临时键稀释量化 + 治理缺口确认 —— 不拍板，只交付事实底座

## 一、做了什么

PLAN-047 §四.4 判定「治理动作（注册表死项回收/密钥稳定性强制）另立」。
本件按该判定补上**可量化的事实底座**（PLAN-047 新增 §六），
**不含产品代码改动**，治理方案留给产品决策。

## 二、机制（代码级复核，非推测）

- `ValidatorNodeBootstrapper`：`self-register` 默认 **true**、`self-stake` 默认 **1000**；
  监听就绪事件即 `register` + `seedStake` + 签名广播（10s/30s/60s 三次重发）+ 写共享表
  `validators_synced`。**全路径无审批/治理白名单**；
- `ValidatorRegistry.register` 仅四道闸：空地址 / 重复 / 满 100 / 质押 < minStake(1000)；
  **没有「是否经治理许可」这道**；默认 self-stake(1000) 恰等于默认 minStake(1000) ⇒ 等值通过；
- `unregister` 只置 INACTIVE；**进程被杀＝什么都没调用**，条目连 INACTIVE 都不是；
- 对端收 ADD（`SyncManager`）**先 `seedStake` 再 `register`** ⇒ 死掉的临时键，
  其 1000 权重照样进入其它诚实节点的总权重。

## 三、实测（三段判据，全部有日志证据）

拓扑：3 诚实验证人（各 1000）+ 临时 sybil 进程；epochLength=2、2s 出块、round-robin。

| 阶段 | voted/total | 结果 |
|---|---|---|
| PHASE 0 基线 | 2000/3000、3000/3000 交替 | 正常推进 f=64→71 @ h=146，lag=2 |
| PHASE 1 +1 死项 | 3000/4000 = **75%** | 仍在最终化 f=82→88；已见出块 slot 空转（`voted=1000/4000`＝轮到死项出块）+ 一次 30s 停摆 |
| PHASE 2 +第 2 个死项 | 3000/5000 = **60%** | **最终性冻结**：f 恒 140（120s 零增长）而链继续 +16 块（h=289→305）；lag 单调 3→4→5→6→8→8→9→10→11；逐 epoch 进度 25/75/25/25/20/20% |

**攻击成本量化**：一个 JVM 进程跑约 **50 秒**即可完成自注册 + 签名广播 ⇒
**永久**获得 1000 分母权重；**两个**进程（约 100 秒）就让 3×1000 诚实集跌破 2/3。
不需要链上资金、不需要治理许可（self-stake 是配置值，非资金证明）。

**死项持久性**：PHASE 1 后 `validators_synced` = 4 行、PHASE 2 后 = 5 行；
重启 A 后仍 `voted=2000/4000`，A.log 连续
`Validator-set full-table replay sent: 4 validators (4 signed payloads verbatim, 0 legacy rebuilt)`
⇒ 一次稀释是**永久性**的，不靠外部清理/换库不会自己回去。

## 四、检测面（不新增代码）

- `FinalityStalled` 规则 `for: 10m`（测试钉 9m 不报/12m 报）⇒ 本演练 120s 冻结
  **在观测窗内不触发**，最快可见时延 ≈ 10 分钟；
- `nexus_finality_lag` 单调增长本机可观测，但 Prometheus 侧**无 lag 增长类告警**
  （PLAN-048 只做了「水位冻结」）；
- runbook 对 `abstain-risk` 的描述已预写「多余/僵死验证人会抬高总权重」并给了对账手段
  （`GET /consensus/validator-set`），但缺「如何发现」的操作化步骤；
- B-11 已于 2026-11-14 交付 ⇒ 若要加告警，**链路不再是缺口**，缺的只是判据。

## 五、治理缺口（登记，不拍板）

四个缺口按可复现性排序：① 自注册无治理闸；② 无死项回收（100 秒即造成永久冻结）；
③ minStake 是自报值、无链上资金/锁定证明；④ 无密钥稳定性约束（换钥重启即新增条目，
即 §四.4 Run 1 发现的同根因，本件给出量化后果）。

处置选项（**产品决策，本件不拍板**）：(a) `self-register` 默认关 + 白名单准入；
(b) liveness 心跳 + 死项降权/回收；(c) 质押改可验证形态（链上锁定/治理多签）；
(d) 只文档化 + 靠告警兜底。

## 六、边界

- 本机同内核 3+1 进程 + 三独立 PG；sybil 是**同机另起进程**，非跨机器伪造身份；
- 75%/60% 两个门限点只在本拓扑参数下成立，诚实集规模变化会整体平移；
- **未测**：真实私钥泄露（持有者主动作恶投票，属伪造票而非稀释分母，另一形态）
  与长程攻击（需历史链重写，按 ROI 清单只登记蓝图不投入）；
- **未测**：anti-entropy 是否给死项补票/复活条目——本轮未观察到此形态，不宣称其无；
- 演练资产在仓外（`F:/Agent/opencode/workspace/`），**未入库**：需入库请另立。

## 七、一处需登记的实测教训（非产品缺陷）

PHASE 2 首轮 R2 因 `BindException: 0.0.0.0:9300` 从未注册——9300 被
`wslrelay`/`com.docker.backend`（Windows/Docker 端口转发段）占用，非残留 JVM。
续跑脚本因此加了**端口预检**（占用即 ABORT，不再静默跑错形状）。
⇒ 后续本机演练选 P2P 端口避开 Docker/WSL 转发段。
