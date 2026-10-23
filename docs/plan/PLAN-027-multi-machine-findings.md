# 验收 PLAN-027：多机拓扑（跨三套内核）——补票跨边界实证 + 四项真发现

> 用户要求：多机必须做，用目前有限的资源做。本切片在**一台物理机**上构造了
> 三个**不同内核/网络栈**的节点（Windows 宿主 / WSL2 VM / Docker VM），
> 网络经真实 NAT/桥接（非 loopback）。诚实口径：**虚拟化边界多机，不是物理多机**；
> 无硬件故障注入。本切片无产品代码改动，产出为实证 + 缺陷清单。

## 一、环境

| 节点 | 运行时 | 存储 | P2P 宣告地址 | 互联路径 |
|------|--------|------|--------------|----------|
| A | Windows 原生 JVM（Corretto 17） | Windows 便携 PostgreSQL 16.9 :55433 | `host.docker.internal:9235` | 被动接受 B/C 外连 |
| B | WSL2 Ubuntu-24.04 JVM（systemd 托管） | WSL 内 PostgreSQL 16.15 :5432 | `172.17.218.229:9236` | 主动连 A（/etc/hosts 把 host.docker.internal 指到宿主 vEthernet IP） |
| C | Docker 容器（temurin 17，docker-desktop VM） | 容器内 PostgreSQL 16（mm-pg-c，独立库） | `localhost:9237`（宿主发布端口回进容器） | 主动连 A（host.docker.internal） |

配置：`min-validators-to-mine=2`、`block-interval=3s`、`epoch-length=2`、
`anti-entropy enabled/5s`。判定口径与 PLAN-025 一致：直接查各节点**自己的库**。

连通性实测（决定宣告地址的依据）：容器→宿主只能走 `host.docker.internal`
（`172.17.208.1` 与 LAN IP 均被防火墙挡）；WSL→宿主两个 IP 都通；
docker 发布端口在 WSL-vEthernet 方向被防火墙挡、回环方向通。

## 二、跨边界补票实证（PLAN-025 机制在真实网络边界下成立）

C 是迟到节点（独立库、从 epoch 54 才参与投票）。集群稳定出块后观测 C 的恢复轨迹
（C 的 checkpoint 数 / 水位 epoch，对照 A）：

```text
t+30s   C=71/86   A=86/86
t+60s   C=73/88   A=88/88
t+90s   C=74/89   A=89/89
t+120s  C=76/91   A=91/91
t+160s  C=81/96   A=96/96
t+320s  C=87/102  A=102/102
```

- **水位完全一致**：C 的 max epoch 与 A 每次采样都相同（86/88/89/91/96/102）；
- **checkpoint 持续追赶**：65 → 87，缺口由 anti-entropy 逐步回填；
- **票库证据**：C 库里 A 的票 **1..102 全程在**（含 C 不在线期间的）、B 的票 1..91
  （B 的票经 A 的补票应答捎带回推）；
- A 累计服务补票请求 **526 次**（跨 WSL/Docker 两个网络边界）。

结论：PLAN-025 的补票机制**不依赖同主机假设**，在跨 VM/跨内核的独立存储拓扑下同样工作。

## 三、四项真发现（多机实验的核心价值）

### 发现 1（P1）：非 distroless Linux 上节点启动卡死 —— `org.nexus.ipc.Fifo`

`Fifo` 是 `ApplicationRunner`，且 `isLinuxSystem()` 为真时执行
`new FileReader(fifo)`（`Fifo.java:135`）——Linux 上打开 FIFO 读端会**阻塞等待写端**。
该阻塞发生在 `SpringApplication.callRunners()` 内，**先于 ApplicationReadyEvent**：

- 后果链：`ValidatorNodeBootstrapper`（监听 ready 事件）永不执行 ⇒ 不自注册、
  不广播验证人集、最终性协调器不装填 ⇒ 不出块。线程栈实证：main 卡在
  `Fifo.initFifo` → `callRunners`。
- 影响面：任何**带 mkfifo 的 Linux**（WSL/Ubuntu/完整发行版）都会卡死；
  生产 core 镜像（distroless，无 mkfifo）走 `catch(IOException)` 优雅降级
  （`Fifo.java:104` 注释即此场景）**恰好幸免**。
- 修复方向：FIFO 读循环移到守护线程 / 加 `nexus.ipc.enabled` 开关；
  或 ready 事件不依赖 runner 返回。
- 实验侧绕行：令 `mkfifo` 不可用（与生产同路径），节点即正常。

### 发现 2（F1）：验证人集广播不中继 —— 星型拓扑缺 C 的验证人

广播只发给**直连对端**，A 不把 C 的 validator-set 转发给 B ⇒ B 的注册表里
永远没有 C ⇒ C 提议的块在 B 处 `unknown proposer (validator set not synchronized)`
无法落库（日志实证）。全网认识全部验证人依赖"全联通"，而引导配置只保证星型。

### 发现 3（F2）：出块节点没有主动追块通道

`SyncClient.getStatus()`（30s 拉取对端高度并补块）开头就是
`if (enableMining) return;` —— **出块节点永远不主动按高度补块**，只靠被动接收广播。
错过广播的块永久缺失：B 停在 162，对端已在 164，重启后依旧 162
（日志：`require blocks 162..164` → 收到 3 块 → 因发现 2 无法落库，恶性循环）。

### 发现 4（F3）：提议人轮转没有 view-change —— 单点卡住全链停摆

round-robin 按高度固定映射提议人：C 下线后轮到 C 的 slot 无人出块；
B 落后又被"落后抑制"（`isBehindPeerChain`）锁死出块时，轮到 B 的 slot 同样无人出块。
**没有 round 递增/换人机制**，A 每秒重复 `Selected proposer aKURTT... at height 165`
无限等待（日志实证），全网停摆且不能自愈（C 回来也不解，因 165 的提议人仍是 B）。

> 这四条组合出 **plan-027 停机链**：C 故障 ⇒ 轮转卡 C 的 slot（F3）⇒ B 错过新块
> ⇒ B 被抑制且成为后续 slot 的提议人（F2 无法追块）⇒ 永久停链。
> 停机期间**补票无意义**（没有新 epoch 可补）——即出块活性是最终性事实传播的前置。
> 注：roadmap #30 声称的"杀 1/3 验证人链继续"是在**共享存储**拓扑下实测的；
> 独立存储 + 跨边界下不成立，#30 需按此修正边界。

### 恢复实证（绕行 F1 后）

人工给 B 的库补插 C 的验证人行（正是 F1 缺口的注脚：本应由 P2P 同步完成）并重启 B：

```text
B: 162 → 171+（补齐 pending 块后恢复出块）
B 的 finality_checkpoints：79/79 → 84/84（与 A 完全一致）
C：65/80 → 持续追赶中（见 §二）
```

## 四、明确边界（勿夸大）

- 同一台物理机、三个内核边界（Windows/WSL2 VM/docker-desktop VM），**非物理多机**；
- 无硬件故障、无真实机房网络分区（容器断网属进程级隔离）；
- C↔B 直连被宿主防火墙挡（已实测），拓扑为 A 居中的星型 + 各节点与 A 双向；
  每节点掌握 self+A 两票 = 2000/3000 ≥ 2/3，最终性成立；B↔C 票不互通；
- 补票的 on/off 对照结论沿用 PLAN-025（同一机制层，同口径），本切片只做 on 组实证。

## 五、后续顺序

1. 修发现 1（Fifo Linux 启动卡死）—— P1，独立小切片；
2. 修发现 4（F3 轮转 view-change / 超时换人）—— 活性根基；
3. 修发现 2+3（验证人集中继 + 出块节点主动追块）；
4. 四项修完后重跑本多机实验，完成"停 C 60s → 恢复 → 补票追平"的完整闭环。
