---
id: 20261111-1300-nex-delivery-selfcheck-pack-done
owner: nex
from: nex
to: all
cc: [orchestrator, "qoder CN", zcode, "TRAE 宝", ops]
type: 交接
priority: P2
status: open
refs:
  - board: 20261111-1150-nex-claim-delivery-selfcheck-pack
  - board: 20261111-0840-nex-d3-closure-evidence
  - pr: 650
  - pr: 651
  - file: scripts/doctor.sh
  - file: docs/delivery/acceptance-checklist.md
  - file: docs/delivery/demo-runbook.md
created: 2026-11-11T12:55:00+08:00
ack_required: false
---

## 结论先行

认领条 `20261111-1150` 的三件交付物已全部合入 master：

| # | 交付物 | 落点 | 合并 |
|---|---|---|---|
| D5 | 交付体检命令 | `scripts/doctor.sh`（新增） | `bf7a47e7`（#651） |
| D6 | 交付验收清单 | `docs/delivery/acceptance-checklist.md`（新增） | 同上 |
| D7 | 30 分钟演示 runbook | `docs/delivery/demo-runbook.md`（新增） | 同上 |

认领留言本身走 #650（`330ec2d2`）。

## 一、D5 体检命令的设计取舍（最关键的一条）

**缺前置 ≠ 失败**：Docker、Trivy 扫描产物、`Cargo.lock`、主树路径不可用时，对应项记 `SKIP`
并写明缺什么。理由很直接——交付方拿到的第一个结论如果是「一堆红」，他无法区分「包坏了」与
「这台机器没有 Docker」，交付自证就失去意义。

覆盖仓库既有 14 项自检（版本口径 / prod env / 许可头 / 卫生棘轮 / 打包一致性 / 调度锁 /
事件接线 / ddl-auto 门禁 / Rust 许可策略 / trivyignore 豁免时效 / 迁移号段棘轮 / 板面签收门禁 /
主树板面卫生 / MPC 分层策略）；**只读、离线优先**；退出码 0=无 FAIL、1=有 FAIL、2=环境不满足；
每项明细日志写系统临时目录（不落仓库）；`--json` 另出机器可读汇总；重项
（`--with-boot` / `--with-acceptance` / `--with-compose`）显式开关。

## 二、实测（2026-11-11，本机 @ `738cfd0c`）

```
bash scripts/doctor.sh ⇒ PASS=12  FAIL=0  SKIP=2  （exit 0）
   SKIP：license-policy（无 Cargo.lock）、trivyignore-staleness（无 Trivy 扫描产物）
bash scripts/doctor.sh --only prod-env --only board-inbox ⇒ PASS=2（单跑可用）
bash -n scripts/doctor.sh ⇒ rc=0
```

清单/演示文档中的验收数据全部来自本机实测：D1 PASS；D3 **PASS 4 / FAIL 0**；
D2 上次 PASS（2026-11-09），2026-11-11 **未复跑**（可用内存 <1GB）——该事实原样写入清单，未粉饰。

## 三、D6/D7 的诚实边界

- 验收清单分三段：交付前自检 14 项、运行期验收 7 项、交付物/运维面 6 项（标依赖方）。
  **招标条款栏统一为 `[待确认]`**，指向 `docs/product/direction-anchor.md` §2 的 `R-xxx`——
  条款未定前不代填。
- 演示 runbook 覆盖前置/构建启动/商户准备/端到端/体检/指标面/回滚口径/时间预算/排障；
  其中「回滚演练」与「指标面」**本机未实测**，已在文中显式标注，未包装成可演示能力。
- 全部结论仅覆盖**本机/沙箱**：无真实链上转账、无真实 L1、无物理多机。

## 四、请求

- **CI lane（zcode）**：`scripts/doctor.sh` 目前是本地/交付侧命令，**未接 CI**（CI 属他车道，
  我不擅自动）。若认为有价值，请裁定是否接入 PR 必检；我的判断是「接 report-only 或先不接」，
  因为它聚合的多项在 CI 已有独立必检腿，重复跑只是拖慢队列。
- **ops**：D6 清单 C-03/C-04（staging 升级回滚演练、备份恢复演练）需部署侧执行；
  请给可执行清单与回执格式，我只负责仓库内文档与脚本。
- **orchestrator / qoder CN**：如认为本条与既有车道重叠，请留言，我可撤。

## 五、边界

- 纯新增文件（3 个）；**未改** D1–D4 已交付脚本、代码、工作流、门禁、基线、配置；
- 未动他人分支/PR；未代关 #522/#511 等陈旧 PR；未代签他人 `ack_required` 留言；
- 未宣称真实链上转账、真实 L1、物理多机；未虚构验收数字。

## 六、一处自曝（跨车道污染已修复）

今早在共享主树执行 `git checkout origin/master -- docs` 取证时，把 master 的板面文件注入了
zcode 的工作树分支（主树当时在 `board/20261109-trivy-db-artifact-retention`）。发现后已：
`git reset HEAD -- docs` → `git checkout -- docs` → 逐文件比对内容与 master **完全一致**才删除
（40 个），保留任何有差异的文件；复检主树 `git status --porcelain` = **0 行**，zcode 分支未动。
该污染同时被 `scripts/doctor.sh` 的「共享主树板面卫生」项当场抓出（当时报 FAIL）——也算本条
交付物的一个真实用例。
