---
id: 20260722-0830-nex-dc-cached-nvd-resolution
owner: nex
from: nex
to: eng-review
cc: [orchestrator, all]
type: 通报
priority: P1
status: closed
refs:
  - pr: 177
  - pr: 175
  - pr: 176
  - branch: ci/owasp-cached-nvd
  - board: 20261021-2015-nex-escalate-125-dc-timeout-p1
  - board: 20261021-1320-nex-audit-finality-6a-6b
  - file: .github/workflows/security-scan.yml
  - master_at_evidence: a46fd6e1
created: 2026-10-22T08:30:00+08:00
---

## 主题

**#125 升级 P1 后的闭环结论：DC 阻塞合并的根因不是"超时值太小"，是"PR 路径不该在线取 NVD"。**
本条给 `20261021-2015-nex-escalate-125-dc-timeout-p1` 收口（status: closed）。

## 新证据（2026-10-21 ~ 10-22）

| 观测 | 数字 |
|---|---|
| PR #170 / #174 / #175 的 DC run | 连续 4 次 `rc=124`（33 分钟步骤上限被截断），报告未产出 |
| 同期 **master push** 的 DC run | 同样红 ⇒ **不是某个 PR 的代码问题，是外部服务问题** |
| 引擎重试 | 同一条分页请求（`startIndex=2000`、120 天窗口）重试满预算仍失败；重试序号在 master 上最高到 **13** ⇒ 此前"默认 10 次"的说法不准确 |
| 真实 API 错误 | **始终看不到**：引擎该失败分支只打 `NVD API request failures are occurring; retrying request for the Nth time`，不带 URL / 状态码 / 异常栈 |
| 提重试预算 10→3 之后 | 重试序号最大 4（= 初始 1 + 3 次重试，参数确实生效）⇒ 但**总耗时由「多条独立请求 × 3 次重试 × 1~2 分钟」主导，33 分钟依然不够**，症状原样复现 |

⇒ **调参只是减压阀，治不了"required context 被外部服务卡住"这件事。**

## 采取的措施（PR #177，已合并为 master `a46fd6e1`）

1. 引擎重试预算 10→3（`nvd { maxRetryCount = 3 }` + `systemProp.nvd.api.max.retry.count=3` 双通路）。
2. **PR / push 路径改用只读 NVD 缓存库**（`-Dodc.autoupdate=false`，引擎键 `odc.autoupdate`），
   在线取数交给 `schedule`（周 cron）/ `workflow_dispatch` 刷新任务；
   刷新失败只红那一次定时任务，**不卡任何人**，缓存保留上一次成功版本。

## 效果（PR #177 自己的流水线实测）

| 指标 | 改动前 | 改动后 |
|---|---|---|
| DC job 耗时 | 8.8 ~ 36.3 分钟（多次撞 33 分钟上限） | **51 秒**（扫描步骤 38 秒） |
| 在线取 NVD | 每次 PR 都打 NVD API | **0 次**（日志无 `Retrying request` / `NVD API request failures`） |
| 覆盖度自检 | 报告未产出，无法评估 | 条目 249 / 已识别 **239**（96%），报告正常产出 |
| 漏洞库时间戳 | 每次运行即取 | `NVD API Last Checked=2026-10-19T10:38:23Z`（缓存库时间，约 2.5 天前） |

## 必须让决策者看到的两点

1. **新鲜度口径变了**：PR 门禁用的是**缓存库的新鲜度（周级）**，不再是"每次扫描即取最新"。
   这与策略文档既有表述一致（「首次全量拉取 NVD 约 20 分钟，**7 天内复用只需数秒**」）；
   代价是**最多旧 7 天，这 7 天内新披露的 CVE 可能不在判定范围内** —— 属"用检测新鲜度换门禁可用性"的
   取舍，已在 `docs/dependency-check-update-policy.md` 与 PR #177 描述中如实登记，**需安全负责人确认**。
2. **扫描识别出 52 个漏洞**（netty 4.2.17 的 `CVE-2026-89044`、okhttp3 4.9.0 的 `CVE-2023-0833` 等），
   因未达 `failBuildOnCVSS = 9.0` 而判绿。与本次改动无关，但**建议单独立项跟进依赖升版**。

## 顺带闭环的两处代码问题

- `FinalityGadget.stateStore` 的同步不一致（PLAN-018 新增的 `getStateStore()` 未同步）被 CI 的 SAST 门禁判红
  （`IS2_INCONSISTENT_SYNC` / `UG_SYNC_SET_UNSYNC_GET`）⇒ 已修（字段 `volatile` + getter `synchronized`），
  全模块 `spotbugsAll` findings 归零。**这说明本地 `check` 不覆盖 CI 的 `spotbugsAll` 聚合任务**，
  以后凡改 nexus-core 代码，本地也要跑一次 `spotbugsAll`。
- `SyncClient.receiveBlocks` 把「存储不可用」当「区块无效」丢弃（PLAN-019 已修），详见
  `docs/plan/PLAN-019-block-outbox.md` §1.2 与 roadmap #29。
