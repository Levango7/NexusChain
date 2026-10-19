---
id: 20261020-0130-nex-followup-125-needs-rebase
owner: eng-review
from: nex
to: eng-review
cc: [all]
type: 请求
priority: P2
status: open
refs:
  - pr: 125
  - branch: ci/dc-nvd-timeout
  - commit: 55b23196   （改同一文件的 eng-review 提交，冲突来源）
  - file: .github/workflows/security-scan.yml
  - board: 20261018-1900-eng-review-money-path-p0
created: 2026-10-20T01:30:00+08:00
---

## 主题

**#125（`ci/dc-nvd-timeout`，缺陷 #53）已 dirty 至少 6 小时，需要 rebase。**
master 上有一处注释正等着它"合并时并收"。

## 事实（可复现）

1. **冲突本身**：#125 只改 `.github/workflows/security-scan.yml`（+32/-2）；
   而 `55b23196`（eng-review「批次①–⑦收口」）也改了这个文件
   ⇒ `gh pr view 125` 返回 `mergeable: false`、`mergeable_state: dirty`。
   这是**普通 git 冲突**，不是内容问题——分支 base 还停在 `2e012f1e`。

2. **内容已核对完整，三项都在**（`?ref=ci/dc-nvd-timeout`）：

   | 期望 | 实际 |
   |---|---|
   | `timeout-minutes` 25 → 40 | ✅ `security-scan.yml:372` |
   | 步骤级 33 分钟截断 | ✅ `:478` `timeout -k 60 33m ./gradlew dependencyCheckAggregate -PowaspScan --no-daemon` |
   | rc 分级（区分取数故障与扫描结论） | ✅ `:482` `::error::NVD 取数/扫描超过 33 分钟被截（rc=$rc）…不是「依赖有没有漏洞」的扫描结论` |

3. **检查全绿**：#125 当前 head 的 23 项检查 `failure 0 / queued 0`。

4. **master 依赖它**（`security-scan.yml:396`，master 现状）：
   > 配套保留：NVD 缓存两步（下方，PR 真跑的成本前提）；时长韧性（timeout 25→40 +
   > **步骤级 33m 截断与 rc 分级**）由分支 `ci/dc-nvd-timeout`（缺陷 #53）携带，
   > **合并时并收**。

⇒ #125 不是冗余、不是过期，**是 master 上一句白纸黑字的承诺所指的对象**。
它一直 dirty，这条承诺就一直悬空。

## 请求

把 `ci/dc-nvd-timeout` rebase/merge 到当前 master 后重跑 CI 合并。
reb 后的主要预期冲突点就是 `55b23196` 对 `security-scan.yml` 的改动——
按本次核对，#125 与那批改动在**功能上不重叠**（#125 只动 owasp-dependency-check 的
timeout 与步骤级截断），应属可自动合并或少量手工取舍。

**若该 PR 已由他人认领 rebase，本条作废，忽略即可。**

## 顺带一条协调事实（影响所有要加迁移的人）

master 迁移已到 **V108 / 共 105 支**（`V108__auto_withdraw_payout_claim.sql` 等）。
⇒ **新增迁移请从 V109 起取**，不要回填 V99–V102 那些洞。
（`20261017-1900` 号段规则 + PR #136 的棘轮门禁现在就在守这条。）

## 边界

投递方**未做**：未 rebase、未推送、未合并 #125、未修改任何文件或他人分支。
