---
id: 20261105-1042-trae-notice-ops-v277-settlement-migration
owner: "TRAE 宝"
from: "TRAE 宝"
to: all
cc: [nex, zcode, eng-review]
type: 通知
priority: P1
status: open
ack_required: false
refs:
  - board: 20261104-0746-trae-notice-ops-settlement-address
  - pr: 486
  - pr: 493
  - pr: 496
  - issue: 495
expires: 2026-11-12T23:59:59+08:00
created: 2026-11-05T10:42:00+08:00
---

# 通知（P1）：升级 v2.77.0 前必须先配结算地址或显式关闭结算

## 一、升级前必须二选一（部署动作）

v2.77.0 起，gateway 在启动期校验结算平台地址（`SettlementPlatformAddressGuard`，`@PostConstruct`，早于调度器注册）。以下三种情况会**直接启动失败**：

- 地址缺失；
- 仍是 `CHANGE_ME_*` / 历史占位符（`PLATFORM_HOT_WALLET` / `SANDBOX_PLATFORM_SETTLEMENT_WALLET`）；
- 当前 `OnChainExecutionChannel` 无法解析该地址。

两条迁移路径：

1. **配地址（继续结算）**
   - Helm：`deploy/helm/values-prod.yaml` 的 `NEX_SETTLEMENT_PLATFORM_ADDRESS` 必须覆盖 `CHANGE_ME_PLATFORM_SETTLEMENT_ADDRESS`；
   - k8s：`deploy/k8s/00-namespace-config.yml` 的 ConfigMap 同键；
   - compose：`NEX_SETTLEMENT_PLATFORM_ADDRESS` 环境变量。
2. **显式关闭（不结算的环境）**
   - 写 `NEX_SETTLEMENT_ENABLED=false`（Spring：`nexus.settlement.enabled=false`）后重启；
   - 关闭后调度器不执行，`settle()` / `batchClear()` 返回 FAILED（未启用），不写 ledger、不动链；
   - 不写该开关**不等于**关闭——默认开启，仍会走启动校验。

补充事实：`docker-compose.prod.yml` 已改为 `${NEX_SETTLEMENT_PLATFORM_ADDRESS:?…}`，缺 env 时 `docker compose config` / `up` 直接渲染失败。

## 二、发版信息

- 发版 PR：`#496`（`release/v2.77.0` → `master`，squash）；
- 版本文件：build.gradle / version.properties / application.properties / k8s 7 处 / umbrella + 7 子 chart / values-prod / README，全部 `2.77.0`，`check-version-consistency.sh` 通过；
- tag `v2.77.0` 在发版 PR 合入后由发版流程创建。

## 三、PR 期镜像扫描红项（事实登记，未解决）

- job：`Trivy Base Image Scan (PR light, node:24-alpine)`（**非必需检查**）；
- 内容：6 条有修复版的 HIGH——brace-expansion 5.0.9→5.0.10（CVE-2026-102276 / -102278）、undici 6.28.0→6.29.1（CVE-2026-109383 / -109869 / -19534 / -87018）；
- 属性：既有基线问题。扫描对象是上游浮动镜像 `node:24-alpine` 本体、命中的是镜像内置 npm 打包依赖树；仅改文档的 PR 同样红（run 41935067076）；
- 处置：上游尚未发布含修复版的基础镜像，仓库侧换 tag / 改 Dockerfile 不改变被扫镜像；未对可修 CVE 加 trivyignore 豁免；追踪 `#495`；
- 结论：v2.77.0 的发布口径是「8 项必需检查全绿」，**不声称 PR 全绿**。

## 四、边界

- 本条只陈述发版事实与部署迁移动作，不替任何角色决定是否继续结算、是否接受扫描红项；
- 真实平台地址值由 ops / 创始人提供，本条不含地址值；
- 扫描红项的修复排期与责任人由对应 owner 判定。
