# 生产部署配置前置清单（配置 `KUBE_CONFIG_PROD` 之前）

> **起因**（可复现）：`v2.77.0` / `v2.78.0` 两个 `Release & Deploy` 运行停在 `production` 环境审批约两周；
> deploy lane 答疑（板 `20261114-0930-nex-request-prod-deploy-align` / zcode @ 2026-11-14）证实：
> **本流水线从未真正部署过任何环境（staging / prod 均无）**——`gh secret list` 唯一现存 secret 是 `NVD_API_KEY`；
> `release.yml` 的凭据检查为空时走「跳过 + warning」路径（2026-09-22 起明确设计）。
>
> 本清单定义在 `KUBE_CONFIG_PROD` 被配置成有效值之前，**必须先完成**的证据——
> 不完成即配置，任何被 approve 的 release run 的 `Deploy to Production` 腿替客户跑的是真实部署，
> 含共识变更内容的版本（v2.78.0 起）会对上线节点造成不可逆动作。

## 前置项（共五项 + v2.77.0 特定前置；缺一不可）

### 前置 1 · 部署状态声明（谁先确认）

- 书面（含姓名/日期/联系方式）确认客户侧或下游环境目前**是否在跑 NexusChain**：
  版本（哪天装的）、形态（helm / compose / 手工）、节点数量与分布、谁运维、日志与监控在哪；
- 无法确认 ⇒ **本项不成立**，禁止任何人配置 `KUBE_CONFIG_PROD`。

### 前置 2 · 升级协调窗口结论（共识版本强制）

- 凡包含**共识规则变更**的版本（v2.78.0 及以后）必须有**全节点同窗切换**结论：
  - 窗口时间、参与方（各验证/出块节点）、切换顺序（或同时）、回滚方案与回滚触发条件；
  - 负责协调人姓名；参与升级的节点对白名单变更（`0x04–0x08` / `0x10–0x1a`）已逐个知会；
- 纯缺陷修复版本（无共识内容）可豁免本项，但须在工单中显式写明「无共识内容」并附证据链接（CHANGELOG 节）。

### 前置 2.1 · v2.77.0 特定前置（仅此版本）

- `v2.77.0` 归版即引入 **`SettlementPlatformAddressGuard` 启动期 fail-fast**（归版 `#496`，tag 2026-11-05，「结算地址启动 fail-fast」）——fork 点与共识无关，但前置刚性的一样；
- 当前 `deploy/helm/values-prod.yaml:69` 仍为 `CHANGE_ME_PLATFORM_SETTLEMENT_ADDRESS` 占位符，结算默认开启；
- 任何 v2.77.0 部署前**必须**显式注入真实 `NEX_SETTLEMENT_PLATFORM_ADDRESS`（或显式 `NEX_SETTLEMENT_ENABLED=false`）——否则**启动即失败**（fail-fast，非降级故障）；
- `v2.78.0` 起 prod compose 的 `NEX_SETTLEMENT_ENABLED` 透传真空补口已入版（`#510` / 归版 `#685`），**无需本条**——v2.78.0 的部署前置是前置 2（共识窗口）与前置 3（链史 pre-scan）。

### 前置 3 · 客户侧链史 pre-scan

- 在客户侧链上扫描历史区块 `body[].type`：
  - 若命中 `0x04–0x08` 或 `0x10–0x1a`，新版本节点同步到该高度会**拒绝该区块**（分叉）；
  - 命中 ⇒ 禁止直接升级；先评估回滚/重放方案、或停在上一个稳定版本（v2.77.0）冻结并调查；
- pre-scan 必须以**可复跑的命令与输出存档**（谁跑的、何时、命令是什么）。

### 前置 4 · 生产凭据归口（谁持钥、谁审批）

- 明确指定 `KUBE_CONFIG_PROD` 的**持有人与备份人**（GitHub repo/org secret）；
  任何非本清单授权人获得生产凭据即违规；
- GitHub environment `production` 的 `required_reviewers` 当前为 `Levango7`（唯一，`prevent_self_review=false`）——
  若需扩展审批人，须先完成环境保护规则变更并落板，不允许口头变更；
- secret 不落入任何笔记、文档、聊天记录；只进 GitHub Environments secrets。

### 前置 5 · 配置后记录（谁配、何时）

- 谁配置、何时配置、配置时 prod 的实况（版本/节点/数据出口）、本次启用的 deploy run 编号、
  与 v0 对应的升级窗口时间——写入本次变更单（不得落在私人聊天里）；
- 合并进 `docs/delivery/` 的任何相关 SOP 更新，附本变更记录的链接，不复制敏感信息。

## 相关事实（客观记录，2026-11-15）

- 本流水线 `deploy-prod` 的控制流为：`检查部署凭据` → 未配置 ⇒ `::warning::` + `Setup Helm / Configure kubeconfig / Helm upgrade` 全部 skipped ⇒ job 终结；
- 点击 `approve` 该路径 = 跑完 skip 链路并结束，**等同于零副作用动作**；
- 系统里尚有一个可归置的陈旧 waiting run（`#455` v2.76.0，run `41818133087`），是否驳回由决策者定；
- 真正的分叉点是「某时刻有人配置 KUBE_CONFIG_PROD」——那一刻前 1~5 项必须全部成立。

## 边界

- 本文不授予任何人配置凭据的权利；不授予跳过任何前置项的权利；
- 不作为 prod 当前状态的陈述（仓内无法证实 prod 是否存在/在跑什么）；
- 不改变 `production` environment 的审批要求；不得把本文作为审批已通过的证据。
