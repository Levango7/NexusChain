# kind 告警端到端演练 runbook（B-11）

- **成文**：2026-11-15（zcode）；**首次成功执行**：2026-11-14 夜（本机）
- **对应缺口**：`docs/roadmap-unresolved.md` #27 清单 B 组「真实集群告警未触发」
- **判据三分账（先读这条）**：
  - ✅ **已验**：25 条 nexus 规则（`deploy/k8s/50-prometheus-rules.yaml` **原样**）在真 K8s 集群（kind，真控制面/真 Operator/真 Prometheus）完成 抓取 → 评估 → firing → Alertmanager 路由 → webhook 投递，且以 **HTTP 200** 收束；
  - 🟡 **边界**：单节点虚拟化边界（kind 容器内集群，非生产多 AZ）；指标源为**静态合成**（不是应用进程真指标）；Alertmanager 接收器为演练 sink（非 Slack/邮件真实渠道）；Prometheus 评估/抓取 15s（演练提速口径，非生产 30s）；
  - ⬜ **未覆盖**：生产集群实际告警触发（生产环境当前不存在——见 prod 三问取证）；真实应用指标（nexus-gateway 等进程在集群内产生指标）的告警链路。

## 一、资产清单

| 文件 | 作用 |
|---|---|
| `deploy/scripts/kind-alert-drill.sh` | 一键脚本：`up` / `verify` / `logs` / `down`（幂等；本机环境坑全部内化，见 §四） |
| `deploy/monitoring/kind-alert-drill/values-kind-alert-drill.yaml` | kube-prometheus-stack 最小演练 values（单副本/无 PVC/无 Grafana/无内置默认规则/镜像源覆盖） |
| `deploy/monitoring/kind-alert-drill/drill-sink.yaml` | webhook 接收端（nginx，`POST /alert` → 200 收据 + access log 证据面） |
| `deploy/monitoring/kind-alert-drill/drill-metrics.yaml` | 静态合成指标源（`nexus_finality_abstain_risk=1`、`nexus_block_outbox_readable=0`）+ ServiceMonitor |
| `deploy/monitoring/kind-alert-drill/am-drill.yaml` | Alertmanager 演练配置（直补给 secret，绕开 chart 默认合并怪癖） |
| `deploy/k8s/50-prometheus-rules.yaml` | **原样装载**的 25 条 nexus 规则（不改一行——这是保真度的关键） |

## 二、前置

| 工具 | 本机实测版本 | 说明 |
|---|---|---|
| Docker Desktop | Server 29.9.3 | kind 依赖；本机曾两次中途崩溃（见 §四-2） |
| kind | 0.31.0 | 单节点默认集群即可（多节点拓扑属 `deploy/kind/` 的另一用途） |
| helm | v3.14.0 | |
| kubectl | 1.3x | |
| openssl | Git Bash 自带 | 自签 operator 所需 tls-secret |

## 三、复现（三步）

```bash
# 1) 装齐并等待收敛（含镜像预拉与全部补丁；受限网络先离线下载图表 tgz 并以 CHART_TGZ 指定，
#    下载命令见脚本头部注释）
CHART_TGZ=F:/tmp/kps-93.3.0.tgz deploy/scripts/kind-alert-drill.sh up

# 2) 约 6-8 分钟后（告警 for: 5m）读三层证据
deploy/scripts/kind-alert-drill.sh verify

# 3) 用完销毁（释放本机资源）
deploy/scripts/kind-alert-drill.sh down
```

## 四、三层证据（2026-11-14 首次成功原文，逐字）

**L1 · Prometheus 侧（`/api/v1/alerts`）**：

```
BlockAdoptionAbstainRisk  firing | since 2026-11-14T18:49:51Z
BlockOutboxUnreadable     firing | since 2026-11-14T18:49:59Z
```

**L2 · Alertmanager 侧（`/api/v2/alerts`）**：

```
BlockOutboxUnreadable     active receivers: ['drill-sink']
BlockAdoptionAbstainRisk  active receivers: ['drill-sink']
```

**L3 · sink 收据（access log）**：

```
10.244.0.11 - - [14/Nov/2026:19:04:50 +0000] "POST /alert HTTP/1.1" 200 15 "-" "Alertmanager/0.35.0" "-"
10.244.0.11 - - [14/Nov/2026:19:04:58 +0000] "POST /alert HTTP/1.1" 200 15 "-" "Alertmanager/0.35.0" "-"
```

> 两条告警由合成指标源（`nexus_finality_abstain_risk=1` / `nexus_block_outbox_readable=0`）触发，
> 表达式语义此前已由 promtool 38 用例验证（PLAN-064）；本演练补的是**真集群运行时链路**。

## 五、本机环境坑清单（10 类，全部已内化进脚本/values）

| # | 形态（实测证据） | 修法 |
|---|---|---|
| 1 | github.com 443 持续瞬断：图表 tgz 5 连超时 | 经 `ghproxy.net` 反代离线下载 + `CHART_TGZ` 本地安装位 |
| 2 | Docker Desktop 中途崩溃 ×2（npipe 消失 / API 端口拒连） | 脚本幂等；节点 `docker start` 20s 恢复；重跑 `up` 即续 |
| 3 | registry.k8s.io 外部拉取被断：kube-state-metrics `ImagePullBackOff` **7 小时+** | values 覆盖为 `m.daocloud.io/registry.k8s.io/...` |
| 4 | chart 镜像为 registry/repository **两段拼接**：只改一段渲染出 `registry.k8s.io/m.daocloud.io/...` 无效引用（certgen 与 ksm **两次同型**） | 两段都改（values 内已注释警示） |
| 5 | docker.io 节点侧不通：`nginx:1.27-alpine` 节点内 `crictl pull` 失败 | 节点内 `ctr pull m.daocloud.io/docker.io/library/...` 后 `ctr i tag` 回原名（脚本 prepull 自动做） |
| 6 | helm client-wait 死于 VM 颠簸窗：`context deadline exceeded`（apply 却成功） | `--wait=false` + 脚本自行轮询就绪 |
| 7 | admission webhooks 关闭后 operator 仍挂 `tls-secret`：`FailedMount` 131 分钟（secret 不存在）；补上后**键名不匹配再崩**（要 `cert`/`key`/`ca`，参数 `--web.cert-file=/cert/cert`） | 脚本自签补齐（键名按实测） |
| 8 | chart 默认 config 与自定义合并产生 `route.receiver="null"`：operator 报 `undefined receiver "null" used in route` 拒绝收敛 | AM 配置 secret 以 `am-drill.yaml` 直补 |
| 9 | Prometheus **v3** 对抓取 Content-Type 严判：nginx 无后缀文件以 `application/octet-stream` 供出被拒（`no fallback_scrape_protocol`）；operator 0.95.2 CRD **又不支持** `fallbackScrapeProtocol` 字段 | 静态源改用 `.txt` 后缀（nginx mime 即 `text/plain`） |
| 10 | 静态站对 POST 默认 405：Alertmanager 会持续重试而非收束 | sink 加 `/alert` 显式 200 收据端点 |

## 六、边界与后续

- 本演练证明的是「**规则文件 → 真集群运行时 → 通知投递**」链路；**不**证明生产集群的抓取目标存在性、真实应用指标语义、通知渠道（Slack/邮件）配置正确性——这些归生产部署与告警渠道配置单独立项；
- 「真实应用指标接入」的下一步（不在本次范围）：把 nexus-gateway/core 等进程部署进 kind（镜像已在 GHCR，含 `nexus-dev` 值的 Helm chart）再复用本 runbook 的 verify；
- 与 `docs/roadmap-unresolved.md` #27-B「真实集群告警未触发」条目的闭环关系见该文件治理记录（2026-11-15 行）。
