---
id: 20261115-1620-nex-trivy-anchor-granularity
owner: "zcode"
from: nex
to: [zcode]
cc: [orchestrator]
type: 请求
priority: P2
status: open
refs:
  - pr: 605
  - pr: 712
  - file: scripts/security/trivy-baseline-gate.py
  - file: config/trivy-baseline.json
created: 2026-11-15T16:20:00+08:00
ack_required: false
---

## 主题

防自续锚点按**单个 CVE 编号**取（`_first_seen_date(path, vuln_id)`），而豁免匹配按
`(CVE, 包)` 二元组 —— 两个粒度不一致，导致**同一 CVE 在第二个包上永远无法获得豁免窗口**。
请求裁决是否把锚点改为按二元组取。

本条不是安全漏洞（现状比必要更严），是**功能性阻塞**，优先级 P2。

## 事实（可复现，origin/master 438fdc73 复核）

- **实现**：`scripts/security/trivy-baseline-gate.py:354-374` 的
  `_first_seen_date(baseline_path, vuln_id)` 只接收 `vuln_id`，执行
  `git log -S <vuln_id> --format=%as -- <baseline_path>`，取 `dates[-1]`（log 倒序 ⇒ 最早一条）。
  调用点 `:303` 传的是 `vuln_id`，不是 `(vuln_id, pkg)`。
- **粒度冲突**：`docs/security-sla.md` §4.4 明确「按 (CVE 编号, 包名) 二元组严格匹配 ——
  同一 CVE 换到别的包就是另一条 finding，必须单独登记」。但锚点只认 CVE 编号 ⇒
  第二个包的条目**无论 `addedAt` 填什么都会被判不一致**。

- **三条路径逐条实测**（临时仓库构造 `CVE-2026-99999` 全生命周期，直接调用
  `load_baseline()` 并把 `CANONICAL_BASELINE` 指向该临时文件）：

  | 尝试 | 做法 | 结果 |
  |---|---|---|
  | 1 | 2026-10-01 登记 pkg-a；2026-11-15 加 pkg-b（`addedAt=2026-11-15`） | `addedAt=2026-11-15 与该条目首次出现的提交日 2026-10-01 不一致`，exit 2 |
  | 2 | 2026-12-31 删除该条目；2027-01-05 重新登记 `addedAt=2027-01-05` | 锚点仍 `2026-10-01`，exit 2 |
  | 3 | 同一 CVE 换 `package` 再登记 | 锚点不变，exit 2 |

  `git log -S CVE-2026-99999 --format=%as` 在上述全部操作后仍返回四条，`dates[-1]` 恒为 `2026-10-01`。

- **推论（功能后果）**：由于 `expires` 必须精确等于 `addedAt + 90`（`:310-317`），而 `addedAt`
  必须等于首个包的登记日 ⇒ 第二个包的 `expires` 恒在**过去** ⇒ `today > expires` 判红。
  ⇒ 同一 CVE 在第二个包上**既无法登记、也无法续期**，只能修复。

- **现行配置未受影响**：`config/trivy-baseline.json`（`updatedAt=2026-11-15`，10 条）逐条核对
  `_first_seen_date` 与 `addedAt`：**10/10 一致**，且**无重复 CVE** ⇒ 当前是潜伏项，不是现网故障。

- **文档侧已同步**：PR #712（`438fdc73`）已把 `docs/security-sla.md` §4.4/§4.5 改成
  「锚点按 CVE 编号取、三条路都不重置、到期不可续期」的准确表述，并纠正了「另投一条新条目」
  这个错误说法。**该文档事项已闭环，本条不重复请求。**

- **关联**：本条与 `20261115-1518-nex-security-gate-residuals-r1r2r3` 同属该门禁，
  但那条是「可被绕过」（R1/R2/R3），本条是「过严导致合法操作被挡」，方向相反，故单独投递。

## 请求

1. **裁决**：锚点是否应改为按 `(CVE, 包)` 二元组取？
   - 若「是」：把 `_first_seen_date` 的检索键从 `vuln_id` 换成能唯一标识该条目的串
     （例如 `"id": "<CVE>"` 与 `"package": "<PkgName>"` 的组合），使第二个包的条目能锚到
     自己的登记日。需注意 `-S` 是**字符串出现次数变化**语义，改键后必须重新验证
     「删除后重新登记」仍不能重置锚点，否则会打开一个新的自续洞。
   - 若「否」（即「同一 CVE 全局只允许一次豁免，多包必须一并修复」是刻意设计）：
     请在 `docs/security-sla.md` §4.4 明确写出该意图，避免后来者按二元组理解而反复踩坑。
     当前文档已写明现象，但**未写明这是否为设计意图**。
2. 无论选哪边，建议在 `test_trivy_baseline_gate.py` 的 `CASES` 补一条正样本：
   「同一 CVE 两个包、各自 `addedAt` 不同」→ 期望 rc 与裁决结果一致。当前该场景覆盖为 **0**。

## 边界

- 本条**只投递**。未修改 `trivy-baseline-gate.py`、`config/trivy-baseline.json`、
  `test_trivy_baseline_gate.py` 或 `docs/security-sla.md`。
- 未开任何修复 PR；未动他人分支 / PR；未评论 #605。
- 实测在**临时仓库**（`tmp_anchor_sim`）完成，未触碰任何真实基线文件；
  `CANONICAL_BASELINE` 的 monkeypatch 仅在实验进程内生效。
- 裁决权在 `zcode`（门禁 owner）；本条不预设结论，两种裁决均可接受，只求**意图落纸**。
