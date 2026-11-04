# 方案 PLAN-076：metrics Bearer 鉴权的本地真实链路演练

- **状态**：已交付（2026-11-05）
- **前置**：PLAN-075（Bearer 门禁与 compose 配置，`d24ab1db`）
- **范围**：本地真实进程验证 401/200、Prometheus 带/不带 token 的 up/down 对照、运维 runbook
- **不包含**：物理多机、真实集群、K8s Operator Secret 落地、双 token 并行轮换

---

## 一、为什么需要

PLAN-075 的验证只到：单元测试、`promtool check config`、compose 占位渲染。
**「真实 Prometheus 带 token 能抓、不带会被 401」从未实证**。若不补，可能出现
「配置看起来对、实际抓不到」或「门禁实际没生效」两类静默失败。

## 二、环境矩阵（同内核单机，非物理多机）

| 组件 | 位置 | 说明 |
|---|---|---|
| PostgreSQL 16.15 | WSL2 | 库 `nexus_p074`（沿用） |
| nexus-core | WSL2 内 JDK 单进程 | `CONSENSUS_MODE=pos`、`NEXUS_METRICS_TOKEN` 已注入（仅本机演练 token，未入库） |
| Prometheus 3.15.1 | WSL `/opt/local-tools` | 分别用 `bearer_token_file`、无鉴权、错 token 三种配置抓取 |

## 三、四条断言（实测）

| # | 场景 | 实测结果 |
|---|---|---|
| 1 | 无 token GET `/actuator/prometheus` | **401** + `{"code":401,...}` + `WWW-Authenticate: Bearer realm="actuator/prometheus"` |
| 2 | 错 token | **401** |
| 3 | 对 token | **200**（36957 bytes），`nexuschain_block_height`、`nexus_finality_outbox_*` 序列在 |
| 4 | 无 token GET `/actuator/health` | **200**（探针不受影响） |

## 四、真实 Prometheus 对照（关键反向证据）

| 抓取配置 | target health | lastError | `up{job="nexus-core"}` |
|---|---|---|---|
| 无鉴权 | down | `server returned HTTP status 401` | **0** |
| 错 token | down | `server returned HTTP status 401` | **0** |
| `bearer_token_file` 正确 | **up** | 空 | **1** |

带 token + 规则文件再跑一次：target `up`、`nexuschain_block_height` 与
`nexus_finality_outbox_database_readable` 可查询、9 组 24 条规则加载正常
⇒ **metrics 门禁没有打断抓取/规则/告警链路**。

## 五、交付物

- `docs/runbooks/metrics-auth.md`：口径表、三处配置位置、轮换步骤（含已知空窗）、
  401/503 排查树；
- 本方案文档；
- CHANGELOG 条目。

## 六、发现与未发现

- **未发现配置/代码缺陷**：断言 4/4、Prometheus 对照 3/3 全部符合设计；
- 已知局限（登记，不修）：
  1. **轮换有空窗**：当前只支持单 token，轮换期间会出现短暂 401；零空窗需
     「同时接受新旧 token」的改造，属独立决策；
  2. **K8s Operator 落地未验**：`prometheusSpec.secrets` + ServiceMonitor
     authorization 的实际挂载需在真实集群验收（外部依赖）。

## 七、边界

- 同内核单机 + 真实 Prometheus 二进制，**不是物理多机、不是真实集群**；
- 只验证 core 一个服务；其余五个服务复用同一过滤器语义（PLAN-075 已有各自
  模块测试/编译与同构代码）；
- 演练脚本与日志留在仓库外；
- 未触碰真实 L1 / 桥资金 / wallet / settlement 契约。
