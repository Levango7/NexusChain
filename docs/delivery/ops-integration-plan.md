# 与运维平台联调方案（NexusChain × OpsMesh 类平台）

> **一句话**：OpsMesh（或同类运维平台）作为**运维控制面**纳管 NexusChain 的交付与运行；
> NexusChain 仍是**自包含运行**（自己的链 + 网关 + 清结算），**不把任何平台当运行时基座**。
>
> **本文的定位**：把「两个一起测」拆成**可执行的接口契约 + 任务清单 + 验收判据**，
> 让联调**零改对方代码**即可进行；需要对方新增能力的地方单列并请 owner 立项。

---

## 1. 角色划分（先把「底座」这个词说清楚）

| 角色 | 归属 | 说明 |
|---|---|---|
| **运行时基座** | NexusChain 自带 | 链（nexus-core）、网关、清结算、签名、钱包、桥——**不依赖外部平台** |
| **运维控制面** | OpsMesh（或同类） | 设备纳管、任务下发（shell/service/file）、日志采集、指标抓取、告警、升级/回滚编排 |
| **交付面** | 双方 | NexusChain 侧出**交付包**（chart + 镜像清单 + 依赖清单 + SOP）；平台侧消费它完成安装/升级 |

> **为什么不做运行时耦合**：平台是运维态，链/支付是运行态；把运行态绑到平台会让客户多一个
> 运行时强依赖（平台挂了业务也得停），与「私有化交付持牌机构」的定位相悖。
> 平台只作为**可选的运维面**存在 ⇒ 客户不用它也能装、能跑、能验收。

## 2. 联调架构（文字版）

```
┌──────────────────────────────────────────────────────────────────┐
│ 运维控制面（OpsMesh controlplane）                                │
│   · POST /api/v1/devices/{id}/provision   设备纳管                │
│   · POST /api/v1/tasks   {type: shell|service|file, command}      │
│   · 任务回执 / 日志采集（Loki·ES） / Prometheus 抓取 / 告警        │
└───────────────┬──────────────────────────────────────────────────┘
                │ gRPC（mTLS）+ HMAC，agent 拉任务
┌───────────────▼──────────────────────────────────────────────────┐
│ 纳管设备（agent 所在主机）                                        │
│   · NexusChain compose 栈 / helm release                          │
│   · 交付包与脚本：scripts/doctor.sh · acceptance-e2e.sh ·         │
│     upgrade-rollback-drill.sh · ops/nc-task.sh                   │
│   · 观测面：/actuator/health（探针）· /actuator/prometheus（token）│
│     · 日志：stdout                                                  │
└──────────────────────────────────────────────────────────────────┘
```

## 3. 接口契约（三条，跨平台中立）

| # | 契约 | 本仓实现 | 平台侧要求 |
|---|---|---|---|
| C1 | **任务下发** | 被调用方：`bash scripts/ops/nc-task.sh <action>` | 以 shell 任务下发即可（OpsMesh：`POST /api/v1/tasks`，`type=shell`） |
| C2 | **回执** | stdout **最后一行**是单行 JSON：`{"action","ok","exitCode","reason","ts","elapsedMs","detail"}`；退出码 0/1/2 | 采集 stdout 并按 JSON 解析；失败原因看 `reason` |
| C3 | **观测** | 健康：`GET /actuator/health`；指标：`GET /actuator/prometheus`（Bearer token）；日志：stdout | 探针打 health；Prometheus 抓 metrics（带 token）；日志采集 stdout |

> **为什么回执要 JSON**：平台的任务回执通常是自由文本，人能读、机难解析。固定 JSON 后，
> 平台侧可把「体检/验收/演练」结果直接进告警与看板，不需要解析中文日志。

## 4. 任务清单（联调要跑的东西）

| # | 动作 | 平台任务 | 命令 | 期望回执 | 当前状态 |
|---|---|---|---|---|---|
| T1 | 交付后体检 | shell | `bash scripts/ops/nc-task.sh doctor` | `ok=true, exitCode=0`；`FAIL=0` | ✅ 本机已实测（PASS 12 / FAIL 0 / SKIP 2） |
| T2 | 环境清单自检 | shell | `bash scripts/ops/nc-task.sh env-check` | `ok=true` | ✅ 本机已实测 |
| T3 | 版本口径自检 | shell | `bash scripts/ops/nc-task.sh version` | `ok=true` | ✅ 本机已实测 |
| T4 | 服务状态探针 | shell | `bash scripts/ops/nc-task.sh status` | 服务可达时 `ok=true` 且 `detail.healthHttp!=000` | ✅ 本机已实测（不可达时正确报 exit 1） |
| T5 | 端到端验收 | shell | `bash scripts/ops/nc-task.sh acceptance` | `ok=true`（需环境注入 `NEX_API_KEY`/`NEX_MERCHANT_ID`） | ✅ 本机已实测（缺 Key 正确报 exit 2） |
| T6 | 升级/回滚演练 | shell | `bash scripts/ops/nc-task.sh upgrade-drill <旧.jar> <新.jar>` | `ok=true`（PASS 11 / FAIL 0） | ✅ 本机已实测（应用层） |
| T7 | 服务启停 | service | `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d / down` | 平台侧 svc 任务；**平台不支持 compose ⇒ 退化为 T8 的 shell 任务** | 待联调 |

> T1~T6 全部**零改对方代码**即可下发；T7 只取决于平台是否支持容器编排的 svc 语义，
> 不支持就用 shell 任务包一层（`nc-task.sh` 已预留扩展点）。

## 5. 环境准备与**当前阻塞**

| 事项 | 需要 | 现状 |
|---|---|---|
| 跑 OpsMesh 控制面 | Docker（或 Linux + systemd 形态） | ❌ **本机 Docker daemon 未起**（`npipe:////./pipe/dockerDesktopLinuxEngine` 不存在） |
| 同机跑两套栈的内存 | 官方文档口径 L3 需 ~16GB 可用 | ❌ 本机可用内存 1~3GB（另有非本车道进程占约 6GB） |
| NexusChain 侧前置 | 交付包 + 19 个强制变量 + 镜像 | ✅ 已备齐（`scripts/build-delivery-bundle.sh`、`docs/delivery/offline-install.md`） |

⇒ **今天无法实跑联调**。可先做的事：契约（§3）、任务清单（§4）、纳管入口（`scripts/ops/nc-task.sh`）已就位；
**一旦有可用 Docker 主机**，按 §6 顺序即可开跑。

## 6. 联调执行顺序（建议）

1. 起 OpsMesh controlplane + 1 个 agent（本机/测试机）；
2. 用 `POST /api/v1/devices/{id}/provision` 把目标主机纳管；
3. 下发 T1（doctor）→ 期望 `ok=true`：**先证明「平台能驱动 NexusChain 的自检」**；
4. 下发 T4（status）→ 期望 health 可达（需先起 NexusChain 栈）；
5. 下发 T5（acceptance，需注入 Key）→ 期望 `ok=true`；
6. 下发 T6（upgrade-drill）→ 期望 `ok=true`（**这条把平台的任务编排能力与我们的升级/回滚口径接起来**）；
7. 观测核对：Prometheus 能抓到 `nexus_*` 指标（token）、日志能在平台侧看到 NexusChain stdout；
8. 留档：每条任务的回执 JSON + 平台侧截图/日志。

## 7. 联调 PASS 的判据（先定，避免「跑通了就算」）

| # | 判据 |
|---|---|
| J1 | 平台以 shell 任务驱动 NexusChain 的 6 个动作（T1~T6），回执均为 `ok=true`，且**失败动作能给出可读的 `reason`**（T5 缺 Key ⇒ exit 2 + 明确原因） |
| J2 | 平台侧能采集到 NexusChain 的 stdout 日志与 `nexus_*` 指标（带 token） |
| J3 | 升级/回滚演练可在**平台任务编排**下完成（不是本机手工），即 T6 的两次 jar 切换由平台下发 |
| J4 | NexusChain **不因平台缺失而不能运行**：单独 compose/helm 起栈仍可交付（回归 `scripts/doctor.sh` + `acceptance-e2e.sh`） |

> J4 是底线：**联调是「多一条路」，不是「换一条路」**。

## 8. 跨仓分工（不越界的部分我已做完）

| 事项 | 归属 | 状态 |
|---|---|---|
| 纳管入口 + JSON 回执 + 任务清单 + 契约 | NexusChain（nex） | ✅ 本 PR 交付 |
| 交付包 / chart / 镜像清单 / 安装 SOP | NexusChain（nex） | ✅ 已合入（#666） |
| 平台侧下发任务、采集日志指标、演练编排 | 运维平台 | 需对方按 §3 契约接入，**本仓不改对方代码** |
| 跨仓联调排期与环境 | orchestrator | **待指派单点 owner**（本条请求） |

### 8.1 跨仓接入包（给未来 owner 的可执行清单，无需重新推导）

**平台侧需要做的（以 OpsMesh 公开 API 为例）**

```bash
# ① 设备纳管
POST /api/v1/devices/{id}/provision

# ② 下发任务（body 字段与其 api-reference.md 一致）
POST /api/v1/tasks
{ "agentID": "a-xxx", "type": "shell",
  "command": "bash scripts/ops/nc-task.sh doctor",
  "maxRetries": 3, "approvalRequired": false }

# ③ 批量下发（多台设备同一动作）
POST /api/v1/tasks/batch
{ "targets": ["a-001","a-002"], "type": "shell", "command": "bash scripts/ops/nc-task.sh status" }
```

| 任务类型 | 命令 | 期望回执 |
|---|---|---|
| shell | `bash scripts/ops/nc-task.sh doctor` | `{"action":"doctor","ok":true,"exitCode":0,...}` |
| shell | `bash scripts/ops/nc-task.sh env-check` / `version` | `ok=true` |
| shell | `bash scripts/ops/nc-task.sh status` | `ok=true` 且 `detail.healthHttp != "000"` |
| shell | `bash scripts/ops/nc-task.sh acceptance`（需注入 `NEX_API_KEY`/`NEX_MERCHANT_ID`） | `ok=true` |
| shell | `bash scripts/ops/nc-task.sh upgrade-drill <旧.jar> <新.jar>` | `ok=true` |
| shell / service | `bash scripts/ops/nc-task.sh compose-up` / `compose-down`（需 Docker + `.env.prod`） | `ok=true`；docker 不可用时 `exitCode=2` + 明确原因 |

**NexusChain 侧要提供给平台的东西**：交付包（`charts/`、`images.lock`、`jars/`、`scripts/`、`evidence/`）+
`.env.prod.example`（19 个强制变量）+ 上面这份动作表。

**联调验收**：按 §7 的 J1~J4（J4 是底线：不依赖平台也能交付）。

**跨仓协作方式（按对方仓的板规，非我臆造）**：

- OpsMesh 仓的并发协调板规（`docs/COORDINATION-BOARD.md`）规定：唯一活日志是 `docs/CLAIMS.md`，
  `docs/INBOX-<线名>.md` 只收**定向**消息，线名册为 `kilo / workbuddy / qoder / ZCode / trae`；
  历史 `INBOX-LEVANGO.md` 是**只读档案**。
- ⇒ **`nex` 不在对方线名册内**，我方**不自投**（新建 `INBOX-NEX.md` 等于在对方仓插入一条未登记的线，
  可能触发其 `scripts/coord-gate.py` 门禁）；正确路径是**由编排者指派对方线内 owner**，
  由该 owner 在其线内按协议 CLAIM/接入。

## 9. 边界

- 本方案**不要求**任何特定平台：契约（§3）是平台中立的，换平台只需重写「谁调谁」；
- 本次**未修改任何其他仓库**，未在对方仓库投评论，未代签对方板面；
- 联调**尚未实跑**（Docker daemon 未起 + 内存不足），本文档不把「未跑」写成「已跑」；
- NexusChain 侧结论不依赖平台：§7 J4 是硬约束。
