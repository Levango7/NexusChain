# 交付验收清单（Delivery Acceptance Checklist）

> **用途**：私有化交付方/客户在验收时逐条勾选；每条都给出**可执行命令**、**期望结果**与
> **当前实测结果**。招标/客户条款编号栏统一指向方向锚点 `docs/product/direction-anchor.md` §2 的
> `R-xxx`（条款尚未确认者标 `[待确认]`，本清单**不代填**条款）。
>
> **实测基线**：2026-11-11，`origin/master` @ `738cfd0c`（本仓），Windows + JDK 17 + Git Bash。
> **诚实边界**：全部结论来自**沙箱/本机**（sandbox profile + H2 内存库 + mock 连接器），
> **不含真实链上转账、真实 L1、物理多机**；生产/ staging 项已单独标注依赖方。

---

## 0. 一条命令拿总体结论

```bash
bash scripts/doctor.sh          # 体检汇总；--json 额外产出机器可读结果
```

| 项 | 期望 | 2026-11-11 实测 |
|---|---|---|
| 退出码 | `0`（无 FAIL） | **0** |
| PASS / FAIL / SKIP | FAIL 必须为 0 | **PASS 12 / FAIL 0 / SKIP 2** |
| SKIP 项含义 | 缺前置（无 Cargo.lock / 无 Trivy 产物），**不等于通过** | license-policy、trivyignore-staleness |

> 体检是**只读**的：不改工作区、不启动服务、不联网。重项（dev 启动 / 端到端 / compose）
> 需显式开关：`--with-boot` / `--with-acceptance` / `--with-compose`。

---

## 1. 交付前自检（离线，交付方本机可跑）

| ID | 验收项 | 命令 | 期望 | 2026-11-11 实测 | 招标条款 |
|---|---|---|---|---|---|
| A-01 | 版本口径一致（build.gradle / version.properties / README / CHANGELOG 四处） | `bash scripts/check-version-consistency.sh` | rc=0 | **PASS** | `[待确认]` |
| A-02 | prod env 强制变量清单与 compose 一致 | `bash scripts/check-prod-env.sh` | rc=0，输出 `OK: .env.prod.example 覆盖全部 19 个强制变量` | **PASS** | `[待确认]` |
| A-03 | 许可头与许可声明一致（LGPL 分模块声明） | `bash scripts/check-license-headers.sh` | rc=0 | **PASS** | `[待确认]` |
| A-04 | 代码卫生棘轮（无新增违规） | `python scripts/check-code-hygiene.py` | rc=0 | **PASS** | `[待确认]` |
| A-05 | 打包一致性（产物/版本口径） | `python scripts/check-packaging-consistency.py` | rc=0 | **PASS** | `[待确认]` |
| A-06 | 调度锁签名守护（ShedLock） | `python scripts/check-scheduler-lock.py` | rc=0 | **PASS** | `[待确认]` |
| A-07 | 事件接线（发布↔监听双向闭合 + 豁免账） | `python scripts/check-event-wiring.py` | rc=0 | **PASS** | `[待确认]` |
| A-08 | 生产档禁止 `ddl-auto: update`（白名单门禁） | `python scripts/check-ddl-auto-gate.py` | rc=0 | **PASS** | `[待确认]` |
| A-09 | Rust 依赖许可策略 | `python scripts/check-license-policy.py` | rc=0 | **SKIP**（无 `Cargo.lock`，未取 Rust 子工程） | `[待确认]` |
| A-10 | `.trivyignore` 豁免时效复核 | `python scripts/check-trivyignore-staleness.py --ignore .trivyignore --scan LABEL=JSON` | rc=0 | **SKIP**（本地无 Trivy 扫描产物；CI 内为必跑） | `[待确认]` |
| A-11 | 迁移号段棘轮（对照 `origin/master`） | `python scripts/check-migration-version-ratchet.py --base origin/master` | rc=0 | **PASS** | `[待确认]` |
| A-12 | 留言板签收门禁（无逾期未签收必读项） | `python scripts/check-agent-board-inbox.py` | rc=0 | **PASS** | `[待确认]` |
| A-13 | 共享主树板面卫生（无「写了没提交」的留言） | `bash scripts/check-main-tree-board-hygiene.sh` | rc=0 | **PASS** | `[待确认]` |
| A-14 | MPC 分层策略 | `bash scripts/check-mpc-tier-policy.sh` | rc=0 | **PASS** | `[待确认]` |

---

## 2. 运行期验收（沙箱，本机可跑）

| ID | 验收项 | 命令 | 期望 | 2026-11-11 实测 | 边界 |
|---|---|---|---|---|---|
| B-01 | dev 档裸启动（无任何结算 env） | `bash scripts/smoke-dev-boot.sh` | `PASS` 三行 + exit 0 | **上次 PASS**（2026-11-09 @ `e301552a`）；2026-11-11 未复跑（本机可用内存 <1GB，bootRun 的 2GB Gradle daemon 起不来） | 不含结算腿；dev 档结算按 #563 语义关闭 |
| B-02 | 端到端：下单→查询→结算确认→对账 | `NEX_API_KEY=… NEX_MERCHANT_ID=… bash scripts/acceptance-e2e.sh` | `PASS 4 / FAIL 0`，rc=0 | **PASS 4 / FAIL 0**（2026-11-11 @ `4d96da86`） | sandbox + H2 + mock；**无真实链上转账** |
| B-03 | 支付主链路自洽 | 同上，create/query 两步 | 201 / 200 且 id 一致 | **PASS**（`id=pay_0c0c1d3acc984ba3a2a9d2da8b2c2ae9`） | 签名/ApiKey/canonical 三段实现一致 |
| B-04 | 结算确认记录可查 | 同上，第 3 步 | 200 | **PASS**（写侧由 #637 接线，`OrchestrationService.java:480`） | 触发条件为 **txHash 非空**；sandbox 由 mock 连接器提供 txHash |
| B-05 | 对账查询可执行 | 同上，第 4 步 | 200 | **PASS**（`merchantId=1`） | 支付响应**不含** `merchantId`，必须显式传 `NEX_MERCHANT_ID`，否则该步记 `SKIP`（SKIP ≠ 通过） |
| B-06 | 端到端报告可复现 | `NEX_ACCEPTANCE_REPORT=<path> bash scripts/acceptance-e2e.sh` | 生成含每步 HTTP/判定/说明的报告 | **PASS**（脚本生成） | 报告默认落在当前目录，交付时建议显式指定路径 |
| B-07 | 告警/指标面可取 | 见 `docs/runbooks/metrics-auth.md` | `/actuator/prometheus` 需 token | **未实测**（本次体检未覆盖，需指标 token） | 指标鉴权见该 runbook |

---

## 3. 交付物形态与运维面（依赖部署方，非仓库可自证）

| ID | 事项 | 依赖方 | 仓库侧状态 | 备注 |
|---|---|---|---|---|
| C-01 | 结算出款地址下发（`NEX_SETTLEMENT_PLATFORM_ADDRESS`） | ops | 注入位已在（#486），**值待填** | 未配置时启动即 fail-closed（`SettlementPlatformAddressGuard`） |
| C-02 | prod 结算开关（`NEX_SETTLEMENT_ENABLED`） | ops | compose 已显式传值 | dev/sandbox 默认关闭 |
| C-03 | 升级/回滚演练 | ops | **仓内沙盒演练已实跑**：`scripts/upgrade-rollback-drill.sh`（2026-11-11，v2.76.0→v2.77.0→回滚，**PASS 11 / FAIL 0**，见 `docs/runbooks/upgrade-rollback.md` §十一） | **staging/集群路径仍未演练**（`helm rollback` / `rollout undo` 需集群）；
      运行手册 §九 3 已同步订正 |
| C-04 | 备份与恢复演练 | ops | 脚本在 `scripts/` 与 `docs/deploy/` | 生产通路结构性阻塞见板 `20261110-1844` |
| C-05 | 多副本 CAS / ShedLock 观察 | ops | 代码侧已具备（副本数 3 的渲染源 `deploy/helm/values-prod.yaml`） | 需真实部署环境观察数据 |
| C-06 | 监控告警阈值落地 | ops/eng-review | SLO 草案在 `docs/ops/slo.md` | **阈值留空为业务决策**，未虚构数字 |

---

## 4. 明确的未覆盖项（诚实声明）

1. **真实链上转账**：本清单全部结论来自沙箱/本机；`NEX_SETTLEMENT_PLATFORM_ADDRESS` 与可用执行通道
   未配置时不会产生链上交易。
2. **真实 L1 / 物理多机 / 网络分区 / 断电**：不在本清单范围，见 `docs/roadmap-unresolved.md`
   的 #27/#30 条目（长期项）。
3. **生产通道**：C-01~C-06 需部署侧参与，仓库侧只能给出「代码就绪 + 口径文档」。
4. **招标条款映射**：第 1/2 节的「招标条款」栏统一为 `[待确认]`，待
   `docs/product/direction-anchor.md` §1/§2 由产品/商务补齐后回填。

---

## 5. 勾选表（交付现场用）

```
[ ] A-01..A-14  体检脚本全绿          证据：doctor.sh 输出（PASS/FAIL/SKIP 与 rc）
[ ] B-01        dev 档裸启动          证据：smoke-dev-boot.sh 输出
[ ] B-02..B-06  端到端四步            证据：acceptance-report（含每步 HTTP 与判定）
[ ] B-07        指标/告警面可取        证据：/actuator/prometheus 抓取片段
[ ] C-01..C-06  部署侧事项            证据：ops 提供的配置/演练记录
```
