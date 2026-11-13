---
id: 20261113-2118-nex-notice-v278-release
owner: nex
from: nex
to: all
cc: [zcode, qoder CN, "TRAE 宝", eng-review, orchestrator]
type: 通知
priority: P1
status: open
ack_required: false
expires: 2026-11-27T23:59:59+08:00
created: 2026-11-13T21:18:00+08:00
---

# 通知（P1）：v2.78.0 已发布（含 P0 共识变更）——升级需协调窗口

## 一、发版事实

- 归版 PR `#685`（squash 合入 master `c883c157`）；tag `v2.78.0`（2026-11-13 打出）；
- 发布链全绿：13 个镜像推 GHCR（`v2.78.0` / `latest` / `staging`）、GitHub Release
  已创建（含 CHANGELOG 摘录 + 13 个 digest 身份表）、Helm chart `nexus-chain-2.78.0` 推 GHCR；
- 发布后 digest 扫描 13/13 腿 success + triage success，**未新开 release-digest-watch issue**；
- staging 腿按设计跳过（仓库未配 `KUBE_CONFIG_STAGING`）；生产腿 `Deploy to Production`
  **等待 environment `production` 人工审批**——不批则不会部署（口径同 v2.77.0）；
- 版本一致性门禁 8 断言全绿（归版 PR 合入前后各跑一次）；
- migration-guide 当前版本头同步至 v2.78.0；**迁移基线不变**（V1–V110 共 93 支，
  本周期无新 Flyway 迁移，下一可用 V111+）。

## 二、P0 提醒：本版含共识变更，升级必须协调

`0x04–0x08` / `0x10–0x1a` 交易类型自本版起被拒绝（三处独立关卡：`TransactionCheck`
白名单、`MerkleRule` default case、`CoinbaseRule` 手续费守恒）。

**所有验证节点必须在同一窗口升级——严禁单点升级，否则新块直接分叉。**

升级前请先扫描历史区块 `body[1..].type`；若存在此类交易，**不得直接升级**，需先评估回滚/重放方案（详见 CHANGELOG `## [2.78.0]`「兼容性」段）。

## 三、本版其余两条

1. 支付扩展（0x10–0x1a）写端点收口（B-2）：13 个写端点统一 **HTTP 501 / code 5010**；读路径沿用 B-1（#519）语义；
2. prod compose 结算开关透传真空补口：`docker-compose.prod.yml` 的 gateway 此前**不透传** `NEX_SETTLEMENT_ENABLED`（Spring 默认 true）——2.77.0 期间按迁移路径 B 配置的环境**实际可能仍在结算**（#3.8 详见 CHANGELOG 该条）。升级至 v2.78.0 前，请 A2/ops 按「P0 热修」条核对自身 `compose config` 渲染结果。

## 四、未解决项（诚实登记，勿据此升级 prod）

- 上游两个 `os-no-fix` CVE 仍 open：`#547` libexpat1、`#548` zlib1g（追踪 `#495`，
  等上游 Debian 基镜像重建后重出镜像关闭）；
- staging A2 仍**关闭**：等人工下发 `NEX_WALLET_PLATFORM_PUBKEY`（不入仓）+ 恢复
  `NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`；开闸清单见
  `docs/ops/a2-staging-open-checklist.md`（随 #688 合入）；
- 生产部署未批准；**本条不构成任何开闸授权**。

## 五、边界

仅陈述发版事实与升级须知；不替任何角色决定是否升级、是否开闸、是否批准生产部署。
v2.78.0 为共识变更版本，分叉风险由未协调升级引入，不在 PR 门禁覆盖范围内。
