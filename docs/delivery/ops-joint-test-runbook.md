# 联调开跑手册（NexusChain × OpsMesh 纳管联调）

> **定位**：把「联调重启（受限）」从口径变成**可照单执行的开跑清单**。本手册不重复推导，
> 只按既有资产逐条给出：前置自检 → 交付包消费选择 → 平台侧接入 → 执行顺序 → PASS 判据 → 留档。
>
> **来源**（全部为仓库内既有事实，未复跑）：
> 板 `20261113-2021-qoder-cn-ruling-business-3q-answers`（Q3=联调做，重启受限，前置两项）、
> 板 `20261113-1254-qoder-cn-ruling-joint-test-deferred`（缺口登记与重启动作）、
> `docs/delivery/ops-integration-plan.md` §3~§8.1（契约 / 任务清单 / 执行顺序 / 判据 / 接入包）、
> `scripts/ops/nc-task.sh`（被纳管统一入口）、`docs/delivery/offline-install.md`（交付包与 SOP）。
>
> **诚实边界**：联调**尚未实跑**（本手册是开跑清单，不是结果记录）；jar 裸进程整链已实测
> PASS 6 / FAIL 0 / SKIP 0（SOP §2.1），镜像 tar 路径**未入整链实测**（SOP §10-4、
> 板 `20261113-2021` 事实 5），联调顺带补测并记入本手册 §9 记录表。

---

## 1. 开跑前置（两项，缺一不开跑）

### 1.1 用户侧：OpsMesh 线内执行身份（不可代办）

- OpsMesh 仓协调板规的线名册 = `kilo / workbuddy / qoder / ZCode / trae`，**nex 不在册**，
  本方不自投（新建未登记线会触发对方 `scripts/coord-gate.py`，见接入包 §8.1 末段）；
- 正确路径：**由编排者/用户指派对方线内 owner**，该 owner 按对方协议 CLAIM 后执行本手册；
- 指派前本手册对用户先做「机器侧环境自检」（§1.2）即可，不依赖 owner 到位。

### 1.2 机器侧：联调主机环境

| 事项 | 判据 | 机器不足时的失败形态 |
|---|---|---|
| Docker | `docker info` 成功（compose-up 动作内置同一自检） | `nc-task.sh compose-up` 回执 `exitCode=2` + `docker 不可用（未安装或 daemon 未起）` |
| 内存 | 板 `20261113-2021` 口径 **≥8GB 可用**（1640 §四-2）；接入包 §5 另记官方 L3 需 ~16GB（两套栈同机），取宽 | 无脚本判据，起栈前 `free -h` / 任务管理器人工确认 |
| 镜像 | 能拉 `ghcr.io/levango7/nexuschain/*`，或改用离线包 `docker load` | 拉取失败按 SOP §9 排查（核对 `images.lock` 19 条） |
| 连通性 | 控制面 ↔ 纳管设备（agent）可达；验证接口不依赖外网 | gRPC 通道不通属对方侧环境，按其文档排查，本仓不改对方代码 |

> 参考阻塞事实（nex 本机，2026-11-12 记录，引用不复跑）：Docker daemon 未起、可用内存 1~3GB
> ——因此本手册要求在**另选的联调主机**上执行（1640 §四-2）。

---

## 2. 交付包消费选择（双出，二选一执行，包内两种都在）

### 2.1 生成（在已有 19 个镜像的构建机上）

```bash
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle --with-images --with-jars
```

- 两 flag 独立（`scripts/build-delivery-bundle.sh:38-39`），可同趟出（SOP §1.1 与 :62 同趟先例）；
- 缺镜像时脚本**如实 skip 并标注包不完整**（`:225-244`）——**SKIP 不等于通过**，按清单补齐再运。

### 2.2 消费方式 A：允许导入第三方镜像（tar 主路径）

```bash
cd delivery-bundle && sha256sum -c SHA256SUMS          # 任一 mismatch 即停止
for f in images/*.tar; do docker load -i "$f"; done     # 逐镜像导入
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

- 镜像清单 `images.lock` 19 条（compose 路径取 `compose:` 来源行）；
- **本路径未入整链实测**（SOP §10-4）：本次联调即为补测场景，结果记入 §9 记录表后再对外宣称。

### 2.3 消费方式 B：客户环境禁容器（jar 裸进程主路径，非回退）

```bash
# 1) 起网关（文件型 H2；生产换 MySQL/PG 连接串）
JWT_SECRET='<≥32 字节随机串>' NEX_SETTLEMENT_ENABLED=false \
java -jar jars/nexus-gateway-<版本>.jar \
  --spring.profiles.active=sandbox --server.port=8080 \
  --spring.jpa.hibernate.ddl-auto=update \
  --spring.datasource.url='jdbc:h2:file:/var/lib/nexus/gateway;MODE=MySQL'
# 2) 体检与验收直接用包内脚本
bash scripts/doctor.sh
NEX_API_KEY=<key> NEX_MERCHANT_ID=<id> bash scripts/acceptance-e2e.sh
```

- 原文见 SOP §4.3；`JWT_SECRET` 必须显式给（缺省随机生成 ⇒ 外部自签 token 403）；
- 本机整链已实测 PASS 6 / FAIL 0 / SKIP 0（SOP §2.1）。

---

## 3. NexusChain 栈装配后的强制变量

```bash
cp .env.prod.example .env.prod   # 19 个强制变量模板，逐项填值
bash scripts/check-prod-env.sh   # 断言清单覆盖 compose 全部 ${VAR:?}；不通过即停止
```

- `nc-task.sh env-check` 即上述脚本的纳管化入口；
- 端到端验收另需 `NEX_API_KEY`（VERIFIED 商户）/ `NEX_MERCHANT_ID`（demo-runbook §2）。

---

## 4. 平台侧接入（以 OpsMesh 公开 API 为例，形状见接入包 §8.1）

```bash
# ① 设备纳管
POST /api/v1/devices/{id}/provision

# ② 下发任务
POST /api/v1/tasks
{ "agentID": "a-xxx", "type": "shell",
  "command": "bash scripts/ops/nc-task.sh doctor",
  "maxRetries": 3, "approvalRequired": false }

# ③ 批量下发
POST /api/v1/tasks/batch
{ "targets": ["a-001","a-002"], "type": "shell", "command": "bash scripts/ops/nc-task.sh status" }
```

**回执契约（C2）**：stdout **最后一行**是单行 JSON
`{"action","ok","exitCode","reason","ts","elapsedMs","detail"}`；退出码 `0/1/2`
（0=PASS，1=动作跑过但不通过，2=用法/环境不满足）。平台侧按 JSON 解析并采集，不解析中文日志。

---

## 5. 执行顺序与每步判据（按接入包 §6）

| 步骤 | 平台下发命令 | 期望回执 | 失败先看 |
|---|---|---|---|
| T1 体检 | `bash scripts/ops/nc-task.sh doctor` | `ok=true`，`FAIL=0` | stdout 尾部 40 行；SKIP 项看说明 |
| T2 变量 | `bash scripts/ops/nc-task.sh env-check` | `ok=true` | `FAIL: 缺少以下强制变量` |
| T3 版本 | `bash scripts/ops/nc-task.sh version` | `ok=true` | 版本口径漂移清单 |
| T4 探针 | `bash scripts/ops/nc-task.sh status` | `ok=true` 且 `detail.healthHttp != "000"` | 先起栈；`NEX_GATEWAY_URL` 默认 `http://127.0.0.1:8080` |
| T5 验收 | `bash scripts/ops/nc-task.sh acceptance` | `ok=true`；缺 Key 应得 `exitCode=2` + 明确 `reason` | J1 要求失败也可读——恰好用它验证 |
| T6 演练 | `bash scripts/ops/nc-task.sh upgrade-drill <旧.jar> <新.jar>` | `ok=true`（应用层 PASS 11 / FAIL 0 口径） | 两个 jar 路径与版本 |
| T7 启停 | `bash scripts/ops/nc-task.sh compose-up` / `compose-down`（或平台 svc 任务等价） | `ok=true`；docker/env 缺失应得 `exitCode=2` | `NEX_ENV_FILE`（默认 `.env.prod`） |

> T7 若平台不支持容器编排 svc 语义 ⇒ 用 shell 任务包一层调用 `nc-task.sh compose-up` 即可
> （接入包 §4 已预留此退化路径）。T1~T6 零改对方代码可下发。

---

## 6. 观测核对（J2）

```bash
curl -sS http://<host>:8080/actuator/health
curl -sS -H "Authorization: Bearer $NEXUS_METRICS_TOKEN" http://<host>:8080/actuator/prometheus
```

- 期望 200 + `nexus_*` 指标（22 个）；无 token / 错 token 一律 401（fail-closed，SOP §7）；
- 日志：平台侧采集 NexusChain stdout（`nc-task.sh` 不打印任何凭据）。

---

## 7. PASS 判据（接入包 §7，先定后跑）

| # | 判据 | 留档物 |
|---|---|---|
| J1 | T1~T6 由平台 shell 任务驱动，回执均 `ok=true`；失败动作给出可读 `reason`（T5 缺 Key ⇒ exit 2） | 每步回执 JSON 原文 |
| J2 | 平台侧采集到 stdout 日志与 `nexus_*` 指标（带 token） | 平台侧截图/日志查询链接 |
| J3 | 升级/回滚演练由**平台任务编排**完成（非本机手工） | T6 两次回执 + 平台任务 ID |
| J4 | **不依赖平台也能交付**：单独 compose/helm 起栈回归 `scripts/doctor.sh` + `scripts/acceptance-e2e.sh` | 两条回归的回执 |

> J4 是底线：联调是「多一条路」，不是「换一条路」。

---

## 8. 镜像 tar 整链实测记录位（补事实 5 的证据缺口）

SOP §10-4 标注「真断网机未实测 / compose·helm 路径未实测」。本次联调若走 §2.2 的 tar 路径
（或后续断网机复测），按下表逐项填并随 PR/板帖留档；**填齐前对 tar 路径继续标注「未宣称」**。

| 日期 | 主机/环境 | 步骤 | 结果 | 证据位置 |
|---|---|---|---|---|
| （待填） | （待填） | `sha256sum -c SHA256SUMS` | （待填） | （待填） |
| （待填） | （待填） | `docker load images/*.tar`（19 条核对） | （待填） | （待填） |
| （待填） | （待填） | `docker compose ... up -d` 起栈 | （待填） | （待填） |
| （待填） | （待填） | `nc-task.sh doctor` / `status` / `acceptance` | （待填） | （待填） |
| （待填） | （待填） | 平台侧 T1~T6 下发与回执 | （待填） | （待填） |

---

## 9. 失败分诊与边界

- **exit 2**（用法/环境不满足）：先看 `reason`——缺 `NEX_API_KEY` / docker 不可用 / 缺 env 文件 /
  缺 `upgrade-drill` 两参数；补齐后原任务重下发即可（动作幂等或只读）。
- **exit 1**（动作已跑但不通过）：看 stdout 尾部（doctor 40 行 / 演练 60 行）；
  自检类失败按对应 runbook（`docs/runbooks/*.md`）处置。
- **边界**：不改对方仓、不自投对方名册线、不把「等对方」写成「我方进度」；
  联调结果以回执 JSON 为准，不以「看起来跑通了」为准；J4 回归必须单独跑一次并留档。

---

## 10. 一页口令清单（抄给 owner）

```bash
# 机器自检
docker info && free -h
# 交付包（构建机上双出）
bash scripts/build-delivery-bundle.sh --out /tmp/delivery-bundle --with-images --with-jars
# 目标机消费（二选一）
cd delivery-bundle && sha256sum -c SHA256SUMS && for f in images/*.tar; do docker load -i "$f"; done
# 或 jar 路径：见 §2.3
# 装配后自检
bash scripts/check-prod-env.sh
bash scripts/ops/nc-task.sh doctor
bash scripts/ops/nc-task.sh status
NEX_API_KEY=<key> NEX_MERCHANT_ID=<id> bash scripts/ops/nc-task.sh acceptance
bash scripts/ops/nc-task.sh upgrade-drill <旧.jar> <新.jar>
```
