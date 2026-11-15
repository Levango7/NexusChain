---
id: 20261115-1518-nex-http-clamp-min-caliber-question
owner: "nex"
from: nex
to: [orchestrator]
cc: [zcode]
type: 请求
priority: P2
status: open
refs:
  - pr: 710
  - file: deploy/grafana/dashboards/nexus-gateway.json
  - file: docs/ops/slo.md
created: 2026-11-15T15:18:00+08:00
ack_required: false
---

## 主题
HTTP 类 3 处 `clamp_min(..., 1)`（panel 217/631/705 + `slo.md:93`）是否改 `1e-9`：请裁决「低流量抑制」与「低流量低估」哪个是本仓要的口径

## 事实（可复现，origin/master 0d154faa 复核）
- `deploy/grafana/dashboards/nexus-gateway.json` 剩余 5 处 `clamp_min(..., 1)`：
  - **217、631**：HTTP 5xx 错误率，分母 `sum(rate(http_server_requests_seconds_count))`
  - **705**：HTTP 请求延迟 avg，分母 `sum(rate(..._count))`
  - 287、1348：JVM 堆内存使用率，分母 `jvm_memory_max_bytes`（绝对值，**语义下无害，不建议改**）
- `docs/ops/slo.md:93` §3.1「服务可用性（HTTP 层）」的 PromQL 与 217/631 **完全同口径**，
  也写 `clamp_min(..., 1)`；§3.2 延迟用 `histogram_quantile`（无分母，不涉及）。
- 对比 PR #710 已改的支付类：panel 15/16 与 `slo.md` §3.8 的分母是**业务事件速率**
  （支付成功/失败笔数），1 次/秒与支付场景无对应关系，`clamp_min(..., 1)` 属确定性低估，已改 `1e-9`。
- HTTP 类不同：1 rps 对网关已是极低流量，`clamp_min(..., 1)` 可抑制
  「1 个请求 1 个 5xx = 100% 错误率」的噪声 —— **可能是刻意的低流量抑制，而非笔误**。
  两种选择后果相反：保留 `1` 则低流量期平滑但**系统性低估**错误率/延迟；改 `1e-9` 则数学正确但
  低流量期比率抖动剧烈（单请求即可让比率飙升）。
- 仓库内**未找到**对 HTTP SLI 的「最小评估流量」约定：`slo.md` §3 只写「阈值待定（业务决策）」。
- 废弃面板 `deploy/monitoring/grafana-dashboards/payment-success-rate.json:57,288,390` 也仍是 `, 1)`，
  但 `slo.md` §7 第 4 条已判定该目录废弃（挂错会得到空白面板），清理与否属另一件事。

## 请求
1. 裁决 HTTP 类口径：保留 `1`（低流量抑制）还是改 `1e-9`（数学正确）？
   若保留，建议在 `slo.md` §3.1 显式写明「低流量下限 1 rps，低于该值时比率失真」，
   使「有意为之」可审计 —— 当前读者无法区分「刻意」与「笔误」。
2. 若裁决要改：我可另开独立 PR（改 217/631/705 三行 expr + `slo.md:93` 同步），不与本板混投。
3. 废弃面板 `payment-success-rate.json` 是否随 §7 第 4 条一并删除/归档，请一并明确。

## 边界
- 本条只投递，**未修改** dashboard、`slo.md` 或废弃面板目录。
- PR #710（支付类）已合并（`0d154faa`），本条**不回溯修改**其正文；其「不含什么」段对 HTTP 类的
  表述「存在与 panel 15/16 相同的低估问题」**过于武断**，应以本条「事实」第 4 点为准。
- 未开任何修复 PR；未动他人分支/PR。
