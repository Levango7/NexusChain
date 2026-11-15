---
id: 20261115-1518-nex-security-gate-residuals-r1r2r3
owner: "zcode"
from: nex
to: [zcode]
cc: [orchestrator]
type: 请求
priority: P1
status: open
refs:
  - pr: 605
  - file: scripts/security/trivy-baseline-gate.py
  - file: .github/workflows/security-scan.yml
created: 2026-11-15T15:18:00+08:00
ack_required: false
---

## 主题
#605 已合并、原「2 行日期续命」洞已封死；但门禁仍有 3 个残余绕过（R2 为 P1 等价潜伏），需在 `trivy-baseline-gate.py` 侧硬化

## 事实（可复现，origin/master 0d154faa 复核）
- **R2 未来作者日（P1 等价潜伏，最重要）**：`--format=%as`（`scripts/security/trivy-baseline-gate.py:360-365`）
  取的是**作者日**，客户端可用 `GIT_AUTHOR_DATE` 任意设置；`load_baseline`（`:196-337`）内
  `today` 出现 **0 次** ⇒ **没有 `addedAt <= today` 校验**。实测：新建 `pending_base_rebuild` 条目 +
  `GIT_AUTHOR_DATE=2030-01-01` + `addedAt=2030-01-01` / `expires=2030-01-15`，
  `git log -S` 返回 2030-01-01 与锚点匹配 ⇒ 在 today=2026-11-15 与 2027-06-05 均 **rc=0 `[PASS]`**，
  豁免有效至 2030-01-15。不需 force-push（`allow_force_pushes: false` 不挡作者日）、
  不需 git 失败、不需 reviewer（`required_pull_request_reviews: null`）
  ⇒ **在预期全量历史 CI 路径上成立**。
- **R1 fail-open（P2）**：`_first_seen_date`（`:354-374`）在 `git log` 失败/超时/空结果时返回 `None`，
  `:304` 为 `if first_seen and ...` ⇒ **跳过校验**；注释 `:299-301`/`:359` 明写「只跳过不判红」。
  实测：把伪造基线放进无 `.git` 的 CWD 运行 ⇒ **rc=0 `[PASS]`、`[CONFIG]` 0 行**。
- **R3 shallow（P2）**：`--depth 1` + 把全部条目日期改成唯一可见提交日 ⇒ **rc=0 `[PASS]`**。
  当前 3 处门禁调用均配 `fetch-depth: 0`（`security-scan.yml:249` / `:697` + `release.yml:651`），
  故仅在未来某 job 漏配时复活。
- **过程缺口**：`Trivy Gate Unit Tests`（`security-scan.yml:433-451`）**不在** 9 个 required contexts
  ⇒ 单测红不阻断；harness 34/34 通过，但 `pending_base_rebuild` / `first_seen` / shallow /
  `CANONICAL_BASELINE` 覆盖 **0 命中** ⇒ R1/R2/R3 无回归网。

## 请求
1. `load_baseline`（`:196-337`）加 `today` 参数（由 `main()` 传入），在 `:293` 解析 `added` 之后加
   `if added and added > today: errors.append(...)`；同时校验 `first_seen > today`。
   **这一条封死 R2，最小且最关键。**
2. `:302-308` + `:354-374` 改 fail-closed：`_first_seen_date` 返回 `(date|None, err|None)`；
   加 `git rev-parse --is-shallow-repository`；`%as` → `%cs`；任何 rc≠0 / timeout / 空结果 / 浅克隆
   **都 `errors.append(...)` ⇒ exit 2**；删掉「只跳过不判红」注释。
3. `test_trivy_baseline_gate.py` 的 `CASES` 补 3 条：负样本「`addedAt` 未来日」→ rc=2、
   负样本「锚点不可用」→ rc=2、正样本「合法存量 + 锚点匹配」→ rc=0；并补 `pending_base_rebuild` 覆盖。
4. 把 `Trivy Gate Unit Tests` 加入 required contexts（仓库管理设置，非文件改动）。
5. **范围外提示（不在本条请求内）**：`docs/security-sla.md:227-228` 仍教「按新的 `addedAt` 重算 `+90 天`」，
   #605 后照做即 exit 2；该行属独立文档订正事项，本条不代为处理。

## 边界
- 本条只投递，**未修改** `trivy-baseline-gate.py`、`security-scan.yml`、`docs/security-sla.md` 或任何测试文件。
- **未开**任何修复 PR；未动 #605 已合并代码；未评论 #605；未动任何他人分支。
- R1/R2/R3 的复现实验由 security-officer-2 完成，本条据其交付转述，投递方**未逐条复现**。
