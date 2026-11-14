---
id: 20261115-0523-nex-erratum-prod-align
owner: nex
from: nex
to: all
cc: [zcode, Levango7, orchestrator]
type: 勘误
priority: P1
status: open
ack_required: false
refs:
  - board: 20261115-0130-nex-notice-prod-align-findings
  - board: 20261114-0930-nex-request-prod-deploy-align
  - pr: 699
  - pr: 698
  - pr: 702
created: 2026-11-15T05:23:00+08:00
---

# 勘误：`#699` 决策矩阵两处前提错误（nex 自勘误）

## 勘误对象

`20261115-0130-nex-notice-prod-align-findings.md`（#699，nex 发布）中「**v2.77.0 无共识变更可安全先批（顺带修『迁移路径 B 环境实际可能仍在结算』）——顺序 v2.77.0 → v2.78.0，不共批**」一句。两处前提错误：

### 更正 A｜prod compose `NEX_SETTLEMENT_ENABLED` 透传修复属于 v2.78.0，不是 v2.77.0

修复落于 `docker-compose.prod.yml gateway environment`，归版即入 **v2.78.0**（CHANGELOG `## [2.78.0] - 2026-11-13` 节；归版标题即「结算开关透传真空补口」，`#685` 的 `c883c157`；修复本身 `#510` / 通知 `#512` 均晚于 v2.77.0 tag（2026-11-05 tag）。**⇒ 批 v2.77.0 关闭不了「path-B 环境可能仍在结算」。**

### 更正 B｜v2.77.0 不是「安全版本」：它引入启动期 fail-fast

v2.77.0 归版即引入 **`SettlementPlatformAddressGuard`（`@PostConstruct`，早于调度器注册）**（归版 `#496`，「结算地址启动 fail-fast」）。当前 `deploy/helm/values-prod.yaml:69` 地址仍是 `CHANGE_ME_PLATFORM_SETTLEMENT_ADDRESS` 占位、结算默认开启 ⇒ **任何 v2.77.0 真部署在不注入地址或不显式写 `NEX_SETTLEMENT_ENABLED=false` 的情况下启动即失败。**

## 修正后的口径（不影响「当下 approve 零副作用」）

- approve 任一/两个 waiting run 今天都只产 skip 链，**仍零副作用**——这一点不变；
- 若按序批准：理由换成「**按版本次序走**」（成本为零），**不是**「先批 v2.77.0 更安全」——它自带启动 fail-fast 前置，不是干净版本；
- 若只批一个：**v2.78.0 单批在内容上已覆盖 path-B 修复**；两个版本各自部署前置：**v2.77.0：结算地址/开关前置**；**v2.78.0：共识窗口 + 链史 pre-scan**（前置清单 `docs/delivery/prod-config-prerequisites.md`）；
- 按 #699 发布前勘误声明：本条优先于 #699 中前 2 处表述。

## 联动更新

- 前置清单已补 **前置 2.1 · v2.77.0 特定前置（仅此版本）**——PR `#702`（仅该文件）；

## 边界

- 本条仅更正 #699 中一句前提错误；不改结论已坐实的部分（三问答疑结论、双重验证、skip-chain 语义、配置前置纪律）；
- 本条本身不构成任何部署或授权；approve 与否仍是你的决定。
