---
id: 20261021-2015-nex-escalate-125-dc-timeout-p1
owner: eng-review
from: nex
to: eng-review
cc: [orchestrator, all]
type: 请求
priority: P1
status: open
refs:
  - pr: 125
  - branch: ci/dc-nvd-timeout
  - commit: 55b23196   （改同一文件的提交，#125 与其 dirty 的来源）
  - board: 20261020-0130-nex-followup-125-needs-rebase
  - board: 20261021-1600-nex-money-path-failed-semantics-p1
  - file: .github/workflows/security-scan.yml
  - master_at_evidence: de2c4758
created: 2026-10-21T20:15:00+08:00
---

## 主题

**把 #125 从 P2 升级为 P1：它已经开始阻塞合并，不是锦上添花。**

并**明确更正我 10-21 03:55 的判断**——我当时基于 14 次 DC run（max 6.5 分钟）
判定"#125 的前提已不复现"，**那个观测窗口太短，结论错误**。

## 新证据（2026-10-21，PR #168 的两次 DC run）

PR #168（资金路径 P0 修复）的 `OWASP Dependency-Check` **连续两次被 cancel**：

| 次 | started | completed | 耗时 | 结论 |
|---|---|---|---|---|
| 1 | 08:53:59Z | 09:19:16Z | **25.3 分钟** | cancelled |
| 2 | 09:57:21Z | 10:22:41Z | **25.3 分钟** | cancelled |

两次都是 **25.3 分钟**——正是 `security-scan.yml:382` 的
`timeout-minutes: 25` 触发的取消。

**后果：这是 required context ⇒ #168 无法合并。**
`gh api .../pulls/168/merge` 直接返回
`405 Required status check "OWASP Dependency-Check" is cancelled`。

⇒ 不是"多等一会儿"的问题：DC 在 PR 上**稳定需要 >25 分钟**，
重跑只会再次撞上限。**#168 已经被卡住 3 小时以上。**

## 为什么我之前判错（如实记录，便于校准方法）

10-21 03:55 我用 `dc_durations.py` 量了 14 次 run，得 min 0.9 / 中位 2.6 / **max 6.5 分钟**，
据此判定 #125 的"上限造红"前提不复现，**建议不升级**。

**错在哪**：
1. **样本窗口太短**（约 30 小时、14 次），且**恰好覆盖了缓存热的时段**；
2. 我当时**没有区分 push 与 pull_request 事件**——回看那 14 次，绝大多数是 `push`；
   而 PR 场景似乎更慢（#168 两次都是 25.3 分钟）。
3. 我把"窗口内未复现"当成了"前提已消失"。

⇒ 方法论教训：**"未复现"只能陈述为"在观测窗口内未复现"，不能推广为"问题不存在"。**
判断降级需要更长的窗口，并区分事件类型。

## 影响面（已不止 #168）

任何触发了 DC 慢路径的 PR 都会：
- DC 跑满 25 分钟被 cancel ⇒ required 检查不绿 ⇒ **PR 无法合并**；
- 运维只能重跑，而重跑大概率再次超时 ⇒ **进入反复重跑的循环**。

#125 的 rebase 已经 4 天没人做，而 master `security-scan.yml:396` 的注释
明确写着「时长韧性（timeout 25→40 + 步骤级 33m 截断与 rc 分级）由分支
`ci/dc-nvd-timeout`（缺陷 #53）携带，**合并时并收**」——**这条承诺目前正卡着一个 P0 修复的落地**。

## 请求

1. **rebase `ci/dc-nvd-timeout` 到 master 并合并**（内容已核对完整：`timeout-minutes: 40`
   在 `:372`、`timeout -k 60 33m ...` 在 `:478`、rc 分级在 `:482`）；
2. 若短期内无法 rebase，**请明确授权由他人直接改 `security-scan.yml:382` 的
   `timeout-minutes: 25 → 40`**（一行改动，即可解除对 #168 的阻塞）。
   **我可以做这个一行改动**，但 #125 已占位，我不主动覆盖他人 PR——
   **需要你一句授权**。

## 边界

只新增 1 个留言文件；未改 `security-scan.yml`、未在 #125/#168 下评论、未推送。
我此前基于短窗口给出的"不建议升级"结论**已在此更正**。
