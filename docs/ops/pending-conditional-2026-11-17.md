# 有条件待办清单（2026-11-17 冻结）

> 本文件记录「当前环境/资源不具备，条件到位才做」的事项。每条写明**触发条件**与**冻结时状态**，
> 避免日后被误当成"还欠着没做"的动作项。条件未到不动手；到了再按触发条件启动。
>
> 对应交付：#739 / #740 / #741 / #742（均已合入 master）。

## A. 技术后续项（依赖真实环境/数据）

| # | 事项 | 触发条件（什么信号出现才做） | 冻结时状态 |
|---|---|---|---|
| A1 | A6 真实节点端到端联调 | 节点跑起来：Docker daemon 启动 + kind 集群 + nexus-core 节点在线（`NEXUS_RPC_URL` 可达） | 2026-11-17：Docker daemon 未启动，无 kind 集群、无节点、无 RPC 端点；hook 级单测 + typecheck 已覆盖逻辑，缺真实链路 |
| A2 | `rateLimitDenied` 注册 Prometheus meter + slo.md 登记 | 限流真正启用（`nexus.resilience.connector-rate-limit.enabled=true`，意味着已有真实 PSP 出口配额数据） | 2026-11-17：限流默认关闭，拒绝计数恒 0；此时注册 meter 只会产出恒 0 死序列，是本仓 `PaymentMetrics` 判定过的缺陷形态 |
| A3 | OrchestrationDashboard 8s 轮询 / WithdrawalApprovalsPage 实时化 | 有真实流量，8s 轮询的延迟/负载明显不可接受 | 2026-11-17：当前无真实外部访问，8s 轮询体量下够用；改造成本大于收益 |
| A4 | 链上图表缺口 | 有真实数据量可展示（explorer 当前仅聚合视图，无历史序列） | 2026-11-17：无真实数据，画图表无意义 |

## B. 外部依赖挂账（ops / 用户侧决定，不代办）

| # | 事项 | 触发条件 | 冻结时状态 |
|---|---|---|---|
| B1 | 0512 pre-scan 路径 B 只读通路 | ops 开通只读通路（qoder CN 兜底截止 2026-11-22） | 2026-11-17：等 ops 开通；用户已接手催办 |
| B2 | A2 开闸三件（下发 `NEX_WALLET_PLATFORM_PUBKEY` / prod 置 `NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true` / 开闸后 3 副本观察 CAS+ShedLock） | staging 集群到位 + 部署决定 | 2026-11-17：无 staging 集群，代码侧全就绪（#419） |
| B3 | prod 部署 | 集群 + `KUBE_CONFIG_PROD` + 客户侧决定 | 2026-11-17：无集群/无 kubeconfig，属客户侧决定 |
| B4 | keystore 密码搬进密码管理器 | 用户本人操作（密钥不明文、不入仓、不入聊天） | 2026-11-17：不代办 |
| B5 | 历史 worktree 剩余 31 个 | 对应 lane 的 agent 确认 / 用户拍板 | 2026-11-17：已按三重证据（PR 已合并 + tip 哈希精确匹配 + merge commit 入 master）清理 93 个；剩 31 个有未提交活工作 / 开放 PR / 未合入分支，不动 |

## C. 磁盘占用与可清理项评估（2026-11-17 勘测，仅评估未执行）

三盘整体：**C 81.7GB 可用 / E 157.8GB 可用 / F 46.6GB 可用**，均无空间告警。

| 盘 | 大头占用 | 可清理性判断 |
|---|---|---|
| C | `Users` 62.7GB、`tmp` 1.1GB、`Temp` 0.76GB、`$RECYCLE.BIN` 0.5GB | `Users` 是用户数据不碰；`tmp`/`Temp`/回收站合计约 2.4GB 属常规临时垃圾，但量小、收益低，且可能含在用的临时句柄，**本会话不动手** |
| E | `Docker` 132.7GB、`WSL` 97GB、`dev-data` 52.4GB（npm cache）、`zcode-rollout` 29.9GB、`dev-tools` 17.3GB | Docker/WSL 是虚拟磁盘与镜像层——**Docker daemon 当前未启动**，`docker system prune` 无法执行且强删会毁镜像，不碰；`dev-data` 里的 npm cache 可经 `npm cache clean` 回收一部分，但属全局开发缓存，可能多项目共用，不代办 |
| F | 本会话相关：已清 93 个 worktree（回收空间）；非本会话目录 `Nexus\Workflow`、`Agent` 等体量大但归属其他会话 | F 盘已无告警；剩余大头非本会话可清，列入 B5 拍板 |

**结论**：三盘都有充足余量（最少 F 也有 46.6GB），不存在需要紧急清理的空间压力；可清理项要么收益极低（C 临时文件 2.4GB）、要么有跨项目/跨会话风险（E 的 Docker/WSL/npm cache、F 的其他会话目录）。**本会话不执行任何磁盘删除**，如后续某盘吃紧，按上表逐项与归属方确认后再动。
