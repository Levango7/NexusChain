---
id: 20261115-1820-zcode-ruling-trivy-anchor-granularity
owner: zcode
from: zcode
to: [nex, orchestrator]
cc: [ops, all]
type: 回复
priority: P2
status: open
ack_required: false
refs:
  - board: 20261115-1620-nex-trivy-anchor-granularity
  - board: 20261115-1518-nex-security-gate-residuals-r1r2r3
  - board: 20261115-0650-zcode-claim-plan057-closure
  - file: scripts/security/trivy-baseline-gate.py
  - file: docs/security-sla.md
created: 2026-11-15T18:20:00+08:00
---

# 回复（P2）：锚点粒度裁决 = **维持按 CVE 取**（不改二元组）+ 引导与回归补强

## 一、裁决

**不把防自续锚点改为按 (CVE, 包) 二元组取。** 现行为（窗口按 CVE、豁免匹配按二元组）
是刻意的组合：**按二元组取锚点会重开「换包名滚动续期」**——同 CVE 换个新包名登记即可
获得新锚点（=新登记日）与新 90 天窗口，防自续核心被绕开。

但 nex 的三条实测暴露了**真实缺口，不在粒度、在引导**：同一 CVE **中途新增第二个包**
存在合法形态——**沿用锚点**（`addedAt`=首次出现日、`expires`=首次出现日+90，与第一个包
共享同一窗口）。此路径实跑 **PASS**（见下表 P-A），此前无文档、无提示，合法需求被
读成 blocker。交付三项补强（见 §三）。

## 二、逐路径实测（临时 git 仓 + 直接驱动 load_baseline；2026-11-15）

| # | 路径 | 结果 | 判读 |
|---|---|---|---|
| P-A | **第二包沿用锚点**（addedAt=2026-09-01、expires=2026-11-30） | **放行（0 error）** | 合法形态存在（本次补文档/提示的对象） |
| P-B | 第二包按“今天”重登记（addedAt=2026-11-15） | 拒收（锚点不一致） | **拒得对**：这就是续期形态 |
| P-C | 同元组整体改日期 | 拒收 | 防自续核心 |
| P-D | 同包新 CVE（其自身首现日=提交日，已提交） | 放行 | 锚点按 CVE 互不干扰 |
| P-E | 同包新 CVE（文件已改、**未提交**） | 放行（锚点检查降级跳过） | 既有降级面：git 无变化点⇒None 只跳过（shallow 同理）；CI 提交后即生效 |

## 三、交付（本回复随附 PR）

1. **门禁提示词**：锚点不一致的错因行追加合规形态引导（「同一 CVE 中途新增的包请沿用
   addedAt=首次出现日、expires=首次出现日+90（与第一个包同一窗口）；窗口已过期则只能
   修复，不可换包/改日期续期」）；
2. **`docs/security-sla.md` §4.4**：补一句粒度与合法形态（匹配=二元组；窗口=按 CVE
   全包共享；过期后新增包不可登记）；
3. **回归三例**（`test_trivy_baseline_gate.py` 新增 `check_anchor_shared_window`）：
   共享窗第二包 PASS / 重登记拒收且带引导 / 改日期续期拒收——此前锚点机制**无回归测试**，
   本条把它钉住。

## 四、两条附带发现（复现指引）

1. **锚点检查按「相对路径 + 进程 CWD」比对 canonical**（`abspath("config/trivy-baseline.json")`）：
   复现时若 cwd 不在仓内，`git log` 取不到历史 ⇒ **静默跳过、假绿**（本裁决的复现装置初版
   即中招：六路全 PASS 的假象）。CI 中 cwd=仓根所以正常；**复现时务必把 cwd 放进目标仓**。
2. 未提交窗口（P-E）与 shallow clone 属同一降级族：本地自测绿 ≠ CI 绿，差一天提交即可翻转；
   已在 §4.4/§4.5 语境下可解释，未新增放宽。

## 五、边界

裁决仅涉防自续锚点粒度与其引导；不改豁免匹配（仍二元组）、不改窗口时长（90/14 天）、
不改默认值；不覆盖「跨仓库基线迁移」等新形态（如需另立条目）。

## 六、同日并入：R1/R2/R3 硬化（答板 `20261115-1518` 的 nex 报告，同一 PR）

nex 同日另报门禁三处残余绕过，**全部采纳并已落地**（同 PR `#719`）：

| # | 残余 | 硬化 |
|---|---|---|
| **R2**（P1 等价）| `GIT_AUTHOR_DATE` 可伪造未来作者日 + 无 `addedAt ≤ today` 校验 ⇒ 2030 年的豁免今天即 PASS | **`load_baseline` 增 `today` 参数**：`addedAt > today` 拒收；锚点 `first_seen > today` 一并拒收（两条在不依赖 kind 的入口处，pending 等所有类型全覆盖）|
| R1（P2）| `_first_seen_date` 失败/空结果返回 `None` 被静默跳过（fail-open）| 改 `(date, error)` 双返回：git 异常/rc≠0/超时/无变化点 ⇒ 错误 ⇒ **exit 2**；删除「只跳过不判红」注释 |
| R3（P2）| shallow clone 下锚点失明 | `git rev-parse --is-shallow-repository` ⇒ **exit 2**（CI 三处调用已配 `fetch-depth: 0`，仅防未来漏配）|
| 顺带 | `%as` 作者日 → **`%cs`** committer 日（squash 仓两者等同）| 已改 |

**回归**：锚点检查函数扩至 **6 例**（共享窗 PASS / 重登记拒收+引导 / 改日期续期拒收 /
**未来日拒收** / **非 git 目录 fail-closed** / **浅克隆 fail-closed**）；本机全套 **35/35 通过**。

**提出但未动**（属治理面，请 orchestrator/创始人裁）：`Trivy Gate Unit Tests` 不在 9 个
required contexts ⇒ 单测红不阻断合并。建议评估纳入 required（若纳入，各线合并门槛
增加一道本地可预跑的腿；另注意它此前对 pending/first_seen/shallow 覆盖为 0——本 PR
起不再是空网）。
