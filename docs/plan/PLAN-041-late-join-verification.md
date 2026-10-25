# 方案 PLAN-041：「晚加入节点卡 height 1」条目核销——实证与证据归档（roadmap #27）

- **状态**：已交付（2026-10-26）；本机两进程实证，预登记判据全部给出结论
- **性质**：验证切片——**本产品代码零改动**；goal 是确认 roadmap #27
  「本机三节点里'晚加入节点'仍会因验证人集未同步卡在 height 1」在
  PLAN-030（全表回放）+ F2/PLAN-029（unknown-proposer 挂起重试）之后是否仍然成立
- **前置**：PLAN-030（grpc 全表回放）、PLAN-032（rest 分发）、PLAN-001 切片 4
  （enforce 全库重置后验证人集回放实证——同方向的间接证据）

---

## 一、背景与假设

roadmap #27 的「仍未做」清单登记了一条：`本机三节点里"晚加入节点"仍会因
验证人集未同步卡在 height 1`。该条目写入于 PLAN-030 交付**之前**。

修复后的预期机制（掰开来）：
1. **unknown-proposer 不再丢块**：`PendingBlocksManager` 收到
   `unknown proposer (validator set not synchronized)` 时按"等验证人集同步后重试"
   挂起，而不是视为坏块丢弃；
2. **全表回放**：`SyncManager.onGetStatus` 末尾把注册表全量回发给咨询方，
   晚加入节点一轮状态咨询内补齐集合 → 内部那个被挂起的 height-1 块放行。

**待证伪的假设**：以上两手叠加后，空库晚加入节点**仍会永久**卡在 height 1。

## 二、实验设计（预登记判据）

拓扑（边界如实标注在 §五，先看结果）：

| 节点 | 角色 | 存储 | 关键参数 |
|---|---|---|---|
| A | 先跑（mining） | 独立 PG 库 `nexuschain` | `min-validators-to-mine=1`、interval 3s、priv=aa.. |
| D | 晚加入（空库启动，mining） | 独立 PG 库 `nexuschain_d` | `min-validators-to-mine=2`（防在不知 A 时单独开分叉）、bootstraps→A、priv=bb.. |

流程：A 单独预热出块到 height ≥ 60 → T0 起 D（**全新空库 + 空 LevelDB 缓存**，绝不预置）→
采样 12×20s：A/D 高度、D 日志 `unknown proposer` 累计、D 库 `validators_synced` 行数。

**预登记判定**（先定标准再跑实验，防"事后找糖吃"）：

1. D 在启动后 60~120s 内把至少 1 个**对端**验证人加入注册表（日志 `Validator-set add ... registered=true` 或 `validators_synced` ≥2 行）；
2. D 高度越过 1 并对 A 形成持续增长（不中断、不回退）；
3. `unknown proposer` 必须**可恢复**——总数有限、且在拿到验证人集后不再增长（区分"挂起重试"与"永久卡死"）。

## 三、结果（2026-10-26，第三次运行有效；前两次为**实验环境问题**，如实记录）

### 3.1 结论：**假设被证伪——不会永久卡住**

| 采样（T0=04:34:07 起 D） | A | D | D `unknown proposer` 累计 | D `validators_synced` 行数 |
|---|---|---|---|---|
| t+20s | 86 | -1（启动中） | 0 | 0 |
| t+40s | 97 | 0 | 1 | 1（自注册） |
| t+60s | 103 | 0 | 3 | 1 |
| t+80s | 108 | 0 | 5 | 1 |
| t+100s | 113 | 0 | 5 | 1 |
| **t+120s** | 136 | **136** | 5 | **2（+A）** |
| t+140s | 178 | 178 | 5 | 2 |
| ... → t+240s | 384 | 384 | 5 | 2 |
| 终结（04:38:20） | 385 | **385** | **5（不再增长）** | 2 |

三条判据：

1. ✅ **D 拿到 A 的验证人集**（04:36:03.582，
   `Validator-set add: address=nv3gC5mGzFjtv2ouzw3Yd3vQYbfys3tD8 registered=true (via P2P)`；
   `...UANv9` 是 D 自己，`nv3...` 是 A）——约 T0+116s；耗时构成：
   D 进程启动 ~36s + D 状态咨询周期 ~30s×2（统计 = D 自己的同步节奏决定）。
2. ✅ **D 追平并与 A 同步前进**：加集合瞬间 0→136 全量补块落库，
   之后逐块跟随到 385/385。
3. ✅ **`unknown proposer` 有限且可恢复**（5 次，全部在 height 1、全部发生在拿到集合之前）：
   日志原文自证语义——
   `PendingBlocks: unknown proposer (validator set not synchronized) at height 1; block will be retried after validator-set sync (height=1)`，
   拿到集合后该块放行，**不再新增**。

回放链路两侧日志均可见（`1 validators` → `2 validators`、
`1 signed payloads verbatim, 0 legacy rebuilt`），且 A/B 互发的历史载荷被按 nonce 幂等
去重（`dropped (replay): ... nonce ... <= lastSeen ...`）——PLAN-030/PLAN-001
切片 4 的机制在运行。

### 3.2 前两次测试运行（环境问题，如实记录、不进结论）

| 轮次 | 现象 | 根因（取证：D 日志 BindException + netstat） |
|---|---|---|
| Run 1（04:16） | D 启动即挂，全程 D=-1 | `PeerServer` 绑定 `0.0.0.0:9239` 失败——**Windows 本机动**
| Run 2（04:24） | 同上 | **态端口范围是 1024–15000**（`netsh int ipv4 show dynamicport tcp`）， 9239 落在源端口池里，被**别的进程的一次出站连接**抢先占用（netstat 实测 `192.168.10.201:9239 -> 112.28.173.82:443 ESTABLISHED`）|
| 修正 | D 换 **19339**（同样验证空、仍在供数） | 与运行产品代码无关，纯属本机实验坐标的瞬态碰撞 |

### 3.3 沿革确认（为什么"桩"语义变了）

F2（PLAN-029）关联改动之后，`unknown proposer` 的挂点是
`PendingBlocksManager:152`——块**入队等验证人集同步后重试**，而不是坏块丢弃；
#36/#195 修复让挂起块在重启后也能从 outbox 循环捞回；PLAN-030 的全表回放保证
"集合必达"。三件事叠加 ⇒ 「永久卡 height 1」从**机制上不再可能**，
本次实机把该机制重新点了一遍火。

## 四、结论与处置

- **roadmap #27 「晚加入节点卡 height 1」条目：核销为「不再成立」**，
  证据=本节表 3.1 与 `F:\Agent\opencode\workspace\pl041-latejoin.log` /
  `C:\Users\winge\AppData\Local\Temp\opencode\mm\pl041-{A,D}.log`；
- **不改产品代码**（实证路径与 PLAN-030/029/019/#195 的既有机制一致）；
- `docs/roadmap-unresolved.md` #27 行更新 + 治理记录新增一行；
- CHANGELOG 摘要同步。

## 五、边界（勿夸大）

1. **本机同内核两进程**（非跨内核/非物理多机） + 两个独立 PG 库；
   bootstraps→127.0.0.1；不含网络分区/断电/硬件故障；
2. D 拿到集合的耗时（本实验 ~116s）由 **D 自己的 30s 状态咨询周期 + 启动耗时**决定，
   不是回放链路的延迟；>64 验证人会被 `MAX_VALIDATORS_PER_REPLAY` 截断（PLAN-030 已如实上限）；
3. D 在拿到集合前**正常挂起的是 height-1 的同步块**（可恢复），不是"链停摆"——
   A 侧的出块全程未受影响；
4. `min-validators-to-mine=2` 是集群既有默认（regression 同配），防止晚加入节点
   在不知 A 时单独开分叉——不是本切片新增的约束；
5. **不覆盖**：多机拓扑下的组合故障（C 下线 + D 同时新加入）、拜占庭式伪造验证人集
   （信任模型见 PLAN-001 边界陈述——enforce 默认 false，运维灰度）。
