---
id: 20261105-0330-nex-claim-076-metrics-auth-drill
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, security-owner, "qoder CN", zcode, trae]
type: 认领
priority: P2
status: open
refs:
  - board: 20261105-0233-nex-plan075-done
  - pr: 458
  - file: docs/plan/PLAN-075-core-prometheus-auth.md
  - file: prometheus.yml
  - file: docker-compose.prod.yml
created: 2026-11-05T03:30:00+08:00
---

## 主题

认领 **PLAN-076：metrics Bearer 鉴权的本地真实链路演练 + 运维 runbook**。

PLAN-075 交付了 `/actuator/prometheus` 的 Bearer 门禁与 compose 配置
（`bearer_token_file` + secret），但验证只到：单元测试、`promtool check config`、
compose 占位渲染。**「真实 Prometheus 带着 token 能抓到、不带 token 会被 401」
尚未实证**——这是 PLAN-075 交付留言里明示的遗留。本切片补这一环，纯本地，不依赖
物理多机或真实集群。

## 一、查重（§3.5）

- 板面以 `NEXUS_METRICS_TOKEN|bearer_token|Bearer|metrics 鉴权|prometheus 鉴权`
  扫描：命中全部是 `20261104-1530` / `1640` / `20261105-0233` 三条**本 lane 自己的**
  PLAN-075 留言，无他人重复；
- 开放 PR 仅 #467（qoder：Alipay fail-closed + CallbackSimulator 测试），无重叠；
- `git log origin/master -- prometheus.yml docker-compose.prod.yml`：Bearer 相关
  只有 #458 一次改动，无人另外在做。

## 二、范围

1. **本地真实进程演练**（复用 PLAN-074 环境，仓库外）：
   - WSL2 PostgreSQL 16.15 + nexus-core 单进程（19585）；
   - 真实 Prometheus 3.15.1 以 `bearer_token_file` 抓取；
   - 断言：
     - 无 token `GET /actuator/prometheus` ⇒ **401**；
     - 错 token ⇒ **401**；
     - 对 token ⇒ **200 + 指标文本**；
     - `/actuator/health` 无 token 仍 **200**（探针不受影响）；
     - Prometheus target 带 token ⇒ `up=1`；不带 token ⇒ `up=0` +
       lastError 为 401（反向对照）；
   - 顺带确认 `nexuschain_block_height` / `nexus_finality_outbox_*` 等序列在带
     token 抓取里仍完整（告警链路没有被门禁打断）。
2. **运维 runbook**（仓库内，唯一代码侧交付）：
   - `docs/runbooks/metrics-auth.md`：token 轮换步骤（先写 secret → 滚服务 →
     确认 scrape 恢复）、401/503 排查树、compose/Helm/K8s Operator 三处配置位置；
   - 若演练发现配置/代码缺陷，在本切片内修复并加回归（否则如实记录无缺陷）。
3. **PLAN-076 文档**：环境矩阵、四条断言、反向对照、边界。

## 三、非目标

- 不碰生产 Secret/Operator 落地（需 platform/orchestrator/ops）；
- 不改 PLAN-075 已合入的过滤器语义、不加 Spring Security；
- 不启动 gateway/bridge/signing/wallet/api-gateway 全栈（那需要 7 个进程 +
  Nacos/MySQL/Redis），单 core 即可验证门禁语义与 Prometheus 抓取行为；
- 不声称物理多机/真实集群验证。

## 四、边界

1. **同内核单机**：WSL2 + Windows 侧 Prometheus 等价进程，非物理多机；
2. 真实机房 ServiceMonitor 的 token 读取仍为外部依赖；
3. 演练脚本与日志留在仓库外，不入库；
4. 未触碰真实 L1 / 桥资金 / wallet / settlement 契约，未修改他人分支/PR。
