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
| C2 | **回执** | stdout **最后一行**是单行 JSON：`{"action","ok","exitCode","reason","ts","elapsedMs","detail"}`；退出码 0/1/2 | 当前 OpsMesh 只原文透传 stdout/stderr；末行 JSON 结构化解析尚未接线 |
| C3 | **观测** | 健康：`GET /actuator/health`；指标：`GET /actuator/prometheus`（Bearer token）；日志：stdout | 日志路由已有；NexusChain 应用 Prometheus target 与 Bearer 注入未配置，设备指标不等同应用指标 |

> **为什么回执要 JSON**：平台的任务回执通常是自由文本，人能读、机难解析。固定 JSON 后，
> 平台侧可把「体检/验收/演练」结果直接进告警与看板，不需要解析中文日志。

## 4. 任务清单（联调要跑的东西）

| # | 动作 | 平台任务 | 命令 | 期望回执 | 当前状态 |
|---|---|---|---|---|---|
| T1 | 交付后体检 | shell | `bash scripts/ops/nc-task.sh doctor` | `ok=true, exitCode=0`；`FAIL=0` | ✅ 平台首轮已跑（PASS 12 / FAIL 0 / SKIP 2） |
| T2 | 环境清单自检 | shell | `bash scripts/ops/nc-task.sh env-check` | `ok=true` | ✅ 平台首轮已跑 |
| T3 | 版本口径自检 | shell | `bash scripts/ops/nc-task.sh version` | `ok=true` | ✅ 平台首轮已跑 |
| T4 | 服务状态探针 | shell | `bash scripts/ops/nc-task.sh status` | `ok=true` 且 health 2xx；503 判失败 | ✅ 平台首轮已跑；历史 503 仅证明可达，不算健康通过 |
| T5 | 端到端验收 | shell | `bash scripts/ops/nc-task.sh acceptance` | `ok=true`（需安全注入 `NEX_API_KEY`/`NEX_MERCHANT_ID`） | ✅ 平台首轮已跑；缺 Key 正确报 exit 2 |
| T6 | 升级/回滚演练 | shell | `bash scripts/ops/nc-task.sh upgrade-drill <旧.jar> <新.jar>` | `ok=true`（PASS 11 / FAIL 0） | ✅ 平台首轮已跑（应用层） |
| T7 | 服务启停 | shell / service | shell 调用 `nc-task.sh compose-up/down`；Linux service 需 systemd unit | `ok=true`；docker/env 缺失应得 `exitCode=2` | 待 Linux service 语义复跑 |

> T1~T6 全部**零改对方代码**即可下发；OpsMesh 的 service 任务是 `systemctl` 语义，
> Windows agent 用 shell 调用 `nc-task.sh` 的退化路径，Linux service 语义仍未验证。

## 5. 首轮执行状态与剩余前置

| 事项 | 状态 |
|---|---|
| 首轮平台链路 | ✅ qoder CN 已用本地 OpsMesh controlplane + Windows agent 完成 T1–T6；证据见板 `20261121-0145-qoder-cn-joint-test-t1-t3-platform-driven` |
| T7 | ⏳ Windows agent 仅 shell；Linux `systemctl` service 语义未验证 |
| J2 | ⏳ 日志路由已有，NexusChain 应用 Prometheus target / Bearer 注入未配置 |
| J4 | ⏳ 本轮未重新回归独立 compose / helm 交付路径 |
| 完整复跑 | 本轮不重复首轮联调；如需复跑，先过资源门并使用本地临时凭据 / H2 沙箱 |

## 6. 联调执行顺序（建议）

1. 起 OpsMesh controlplane + 1 个 agent（本机/测试机）；
2. 用 `POST /api/v1/devices/{id}/provision` 把目标主机纳管；
3. 下发 T1（doctor）→ 期望 `ok=true`：**先证明「平台能驱动 NexusChain 的自检」**；
4. 下发 T4（status）→ 期望 health 2xx 且回执 `ok=true`（需先起 NexusChain 栈；503 不算健康通过）；
5. 下发 T5（acceptance，需注入 Key）→ 期望 `ok=true`；
6. 下发 T6（upgrade-drill）→ 期望 `ok=true`（**这条把平台的任务编排能力与我们的升级/回滚口径接起来**）；
7. 观测核对：Prometheus 能抓到 `nexus_*` 指标（token）、日志能在平台侧看到 NexusChain stdout；
8. 留档：每条任务的回执 JSON + 平台侧截图/日志。

## 7. 联调 PASS 的判据（先定，避免「跑通了就算」）

| # | 判据 |
|---|---|
| J1 | 平台以 shell 任务驱动 NexusChain 的 6 个动作（T1~T6），回执均为 `ok=true`（T4 必须 health 2xx），且**失败动作能给出可读的 `reason`**（T5 缺 Key ⇒ exit 2 + 明确原因） |
| J2 | 平台侧能采集到 NexusChain 的 stdout 日志与 `nexus_*` 指标（带 token） |
| J3 | 升级/回滚演练可在**平台任务编排**下完成（不是本机手工），即 T6 的两次 jar 切换由平台下发 |
| J4 | NexusChain **不因平台缺失而不能运行**：单独 compose/helm 起栈仍可交付（回归 `scripts/doctor.sh` + `acceptance-e2e.sh`） |

> J4 是底线：**联调是「多一条路」，不是「换一条路」**。

## 8. 跨仓分工（不越界的部分我已做完）

| 事项 | 归属 | 状态 |
|---|---|---|
| 纳管入口 + JSON 回执 + 任务清单 + 契约 | NexusChain（nex） | ✅ 本 PR 交付 |
| 交付包 / chart / 镜像清单 / 安装 SOP | NexusChain（nex） | ✅ 已合入（#666） |
| 平台侧下发任务、采集日志指标、演练编排 | 运维平台（qoder CN 首轮） | ✅ T1–T6 已由平台驱动；C2 结构化解析、J2 应用指标与 T7 Linux service 仍待平台侧接线，**本仓不改对方代码** |
| 跨仓联调排期与环境 | orchestrator | 首轮已由 qoder CN 完成；后续 T7/J2/J4 需编排者安排可用环境与执行身份 |

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
POST /api/v1/tasks/batch-exec
{ "deviceIDs": ["a-001","a-002"], "taskType": "shell", "command": "bash scripts/ops/nc-task.sh status" }
```

| 任务类型 | 命令 | 期望回执 |
|---|---|---|
| shell | `bash scripts/ops/nc-task.sh doctor` | `{"action":"doctor","ok":true,"exitCode":0,...}` |
| shell | `bash scripts/ops/nc-task.sh env-check` / `version` | `ok=true` |
| shell | `bash scripts/ops/nc-task.sh status` | `ok=true` 且 `detail.healthHttp` 为 2xx；503 判失败 |
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
- 首轮 T1–T6 已由 qoder CN 通过本地 controlplane + Windows agent 实跑（证据见 §5）；T7、J2 与 J4 未验证，本文档不把未覆盖项写成已通过；
- NexusChain 侧结论不依赖平台：§7 J4 是硬约束。
