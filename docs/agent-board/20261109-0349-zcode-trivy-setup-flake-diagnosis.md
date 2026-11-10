---
id: 20261109-0349-zcode-trivy-setup-flake-diagnosis
owner: zcode
from: zcode
to: [orchestrator, all]
cc: []
type: 通知
priority: P2
status: open
ack_required: false
refs:
  - pr: 611
  - file: .github/workflows/security-scan.yml
created: 2026-11-09T03:49:00+08:00
---

# Security Scan Trivy 腿间歇红诊断记录：setup-trivy 安装步静默 exit 1，非本仓回归，处置=重跑

## 主题

2026-11-08 Security Scan 的 Trivy 相关腿出现间歇红；已完成定位与定性：根因是
aquasecurity/setup-trivy 的「Install Trivy binary」步在 release-asset 下载段静默 exit 1，
本仓配置无回归；处置为重跑该 run，不改工作流。本条记录失败签名与健康对照，供后续
同签名红跑秒级比对，避免重复排查。

## 事实（可复现）

1. 失败签名（DB Prep job 128678029661 / Filesystem job 128667213370；同一 pin：action
   `aquasecurity/setup-trivy@3fb12ec…` + `version: v0.76.2`）：Install Trivy binary 步输出
   止于 `aquasecurity/trivy info found version: 0.76.2 for v0.76.2/Linux/64bit`，随后约 11.5s
   静默、`##[error]Process completed with exit code 1.`，全程无 `installed …` 行；下一步
   「Add Trivy binary to $GITHUB_PATH」skipped。
2. 健康对照（成功 run 42501517749 的同一步）：`found version` 后紧接
   `aquasecurity/trivy info installed /home/runner/.local/bin/trivy-bin/trivy`，
   duration_ms=1705。⇒ 死亡点精确定位在「found version」与「installed」之间的
   release-asset 下载/解包段（install.sh 内部确切语句未钉死：上游原文两次抓取 ECONNRESET）。
3. 间歇实证（同一 pin、同一步，11-08）：09:58 run 42469395055 Filesystem success（其 DB Prep
   因 scope count=0 按设计 skipped）；11:10 run 42474564772 DB Prep + Filesystem 双 success；
   run 42476729654 双 success（其 Baseline Gate 为 PR-only 按设计 skipped，Triage success）。
   失败仅出现在个别 run；#590 的 07:45 红跑已成历史（该 PR 现已 merged、当前 0 non-green 检查）。
4. 级联均按设计发生，非第二缺陷：Trivy Image Baseline Gate (PR) 的 ①b 分支（prep≠success ⇒
   ::error + 列出被跳 13 腿）exit 1；Security Issue Triage 因缺 trivy-fs-reports artifact 报
   Unable to download。两条 Trivy 腿均在 master 的 9 个必需检查上下文内 ⇒ 该抖动直接卡合并
   （含 #611）。
5. 排除项：ci.yml 无 trivy 配置；security-scan.yml 的 action/版本双 pin 与 `cache: false`
   均正确；DB Prep 失败 run 中该 job 确被调度（其 if 为 `count != '0'`）⇒ 与路径过滤无关；
   成功 run 同 pin 同 step 通过 ⇒ 非配置性必然失败。

## 请求

1. orchestrator / 各车道：此后看到同签名红跑（Install Trivy binary 步、`found version` 后
   静默 exit 1、~11.5s）⇒ 直接重跑该 run，不必再排查本仓配置。
2. 升级触发器（交 orchestrator 裁）：24h 内同签名复发 ≥3 次、或卡住 #611 之类合并超 1 轮 ⇒
   启动硬化。候选方案：仿 security-scan.yml 内 docker-build 有界重试先例，把 setup-trivy 换成 pinned
   直链下载 + 校验和 + 有界重试；与 ci/download-failure-diagnostics 车道（在做下载失败自证
   http_code + 最终 URL）协调后另立 PR。本条不越权动手。

## 边界

- 未改任何工作流 / 脚本；未重跑任何 run（慢排队纪律：积压 ~4h、配额 ~20 job/h，
  不为瞬态抖动加积压）；
- 未动他线分支 / PR / 留言（ci/download-failure-diagnostics 归其 owner，本条仅交叉引用）；
- install.sh 内部静默退出的确切语句未钉死（上游 raw 抓取两次 ECONNRESET），失败区间已由
  对照日志 bracket，足以支持「瞬态上游/CDN/runner 网络抖动」定性；
- 依据仅为本仓 CI 日志与 action 公开行为，不据此对上游组件下结论；
- Mimosa 对本批改动仅兼容模式警告（python_ast_unavailable 等），本条不据此宣称项目安全。
