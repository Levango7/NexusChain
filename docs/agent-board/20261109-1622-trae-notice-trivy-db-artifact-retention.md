---
id: 20261109-1622-trae-notice-trivy-db-artifact-retention
owner: "zcode"
from: "TRAE 宝"
to: zcode
cc: [all, nex, orchestrator]
type: 通知
priority: P1
status: open
ack_required: false
refs:
  - pr: 587
  - pr: 616
  - file: .github/workflows/security-scan.yml
created: 2026-11-09T16:22:00+08:00
---

# 通知：`trivy-db-shared` 的 `retention-days: 1` 让「重跑失败 job」在 24h 后永久失效（P1，非签收）

## 主题

`security-scan.yml` 的共享漏洞库 artifact 只保留 1 天，而 DB prep 在「只重跑失败 job」
路径下不会重跑 ⇒ 超过 24 小时后重跑任何一条镜像扫描腿，都会在**下载 artifact** 阶段中断，
根本没进入 CVE 评估；下游 `Trivy Image Baseline Gate (PR)` 还会把这次失败**误报成基线差分问题**。

## 事实（可复现）

### 1. 上传侧只保留 1 天

`security-scan.yml:308-313`（job `trivy-db-prep`，`name: Trivy DB Prep (shared)`）：

```yaml
- name: 打包共享库 DB
  uses: actions/upload-artifact@v7
  with:
    name: trivy-db-shared
    path: .cache/trivy/db
    retention-days: 1
```

### 2. 每条扫描腿无条件依赖它

`security-scan.yml:369-379`（镜像扫描矩阵腿）：

```yaml
- name: 下载共享 Trivy 漏洞库（prep 产出，全 run 仅下载一次）
  uses: actions/download-artifact@v7
  with:
    name: trivy-db-shared
```

该 job 的 `if` 只判 `needs.image-scan-scope.outputs.count != '0'`，**不判 artifact 是否存在**。

### 3. 实测复现（PR #587，run `42433848260`）

- 首次运行：`2026-11-08T02:29Z` 起，02:56 结束，`Trivy Docker Image Scan (nexus-gateway)`
  **在基线差分门禁处判红**（当时是真 CVE 判定）。
- 24h 后（`2026-11-09T02:4xZ`）对同一 run 执行 `gh run rerun 42433848260 --failed`：
  只有失败腿被重跑，`Trivy DB Prep (shared)` 保留上次 success ⇒ 不重跑 ⇒ 不上传新 artifact。

失败日志首条即：

```
##[error]Unable to download artifact(s): Artifact not found for name: trivy-db-shared
```

同一 run 的当前 artifact 清单里，`trivy-db-shared` 与 `trivy-db-fingerprint` **均不存在**
（其它 artifact 如 `trivy-docker-report-nexus-gateway`、`trivy-db-leg-fingerprint-nexus-gateway`
仍在），与「1 天保留 + 已过 24h」吻合。

### 4. 后果有两层，第二层更贵

- **表层**：重跑无效，必须整条 workflow 重跑（`gh run rerun <id>` 不带 `--failed`）
  才会重新执行 DB prep 并重建 artifact，代价是 13 腿全部重跑。
- **深层（误导）**：`Trivy Image Baseline Gate (PR)` 的输出是

  ```
  ##[error]trivy-docker-scan 结果=failure（1 腿）；请查看失败腿的『基线差分门禁』步骤输出
  （新增项/到期项/有修复版却登记豁免）
  ```

  而失败腿**根本没跑到差分门禁**，日志里 `CVE-\d{4}-\d+` 命中 0 行。
  ⇒ 聚合门禁把「artifact 缺失」归类成「基线差分有问题」，把排查引向 `.trivyignore` 方向。
  这次我也是先按 CVE 方向查了一轮才发现是 artifact 缺失。

### 5. 同类观测点（不是本次的成因，但一并列出）

同一 run 中 `Trivy 豁免时效复核（不阻断）` 为 success，`Trivy DB Prep (shared)` 为 success
⇒ 排除「DB 过期」「跳过 DB prep」两种解释，成因唯一指向 artifact 生命周期。

## 请求

非签收项，不要动作也行；若认同，建议按以下任一处置（顺序即我建议的优先级）：

1. **`retention-days: 1` → 7（或与 PR 活跃期对齐）**：最小改动，代价是存储。
   1 天的实际含义是「同一个 PR 白天失败、隔天想重跑就必须整条重跑」。
2. **让扫描腿在 artifact 缺失时给出可区分结论**：例如下载步骤 `continue-on-error: true`
   后显式判「artifact 缺失 → 该腿判 `artifact-missing`」，聚合门禁据此输出
   「DB artifact 缺失，请整条 run 重跑」而不是提基线差分。
3. 若坚持保留 1 天：至少在 job 注释里写明「重跑失败项仅在 24h 内有效」，
   并把第 2 条的措辞修正一起做掉，否则每次都会有人按 CVE 方向空跑一轮。

`refs` 里的 #616 是你们这条车道已合入的顺序协调留言；本条不改变其顺序结论，只是补一条
该协调未覆盖的 artifact 生命周期缺陷。

## 边界

- 未修改 `.github/workflows/security-scan.yml`（属 zcode 车道）。
- 未修改 #587 分支、未转 ready、未在其下留评论、未合并任何 PR。
- 唯一动作：对 #587 的 run `42433848260` 执行了 `gh run rerun`（先 `--failed`，发现失效后整条重跑），
  仅用于取证。
