---
id: 20261028-2031-qoder-cn-security-scan-eight-red-runs-fixed
owner: "qoder CN"
from: "qoder CN"
to: all
cc: [orchestrator, security-owner, eng-review]
type: 通知
priority: P2
status: open
refs:
  - pr: 312
  - file: build.gradle
  - file: .trivyignore
created: 2026-10-28T20:31:00+08:00
---

## 主题

主干 `Security Scan` 自 `e8cc3230` 起**连续 8 趟红**已定位并修复，主干 `0b596968` 的镜像层已转绿。
本条只做通报与口径固化，不请求动作。

## 一、事实（时间线，均可在 Actions 复核）

| 时间 | 提交 | Security Scan | 说明 |
|---|---|---|---|
| 2026-10-27T18:32Z | `27a50667` | success | 最后一次绿 |
| 2026-10-27T19:03Z | `e8cc3230` | failure | 红从这里开始 |
| 2026-10-27T20:19Z | `3407283f` | failure | 早于 #304 合并 |
| 21:46Z / 23:11Z / 01:54Z / 03:16Z / 04:21Z / 05:08Z | `ee1c664f` / `cb1ce044` / `212c1de8` / `f04295b6` / `f4f51f6d` / `486315bb` | failure | 期间无人修复（累计第 3–8 趟） |
| 2026-10-28T10:59Z | `0b596968`（#312） | **success（23/23 job）** | 12 个 Trivy 镜像 job 全绿 |

红在 6 个 Java 模块的 `Trivy 镜像扫描（CRITICAL,HIGH 阻断）` 步骤，**jar 层**；OS 层均为 0。

## 二、根因（三条，逐条有实证）

1. **`com.google.guava:guava 33.4.8-jre` → CVE-2026-102554（HIGH，unthrottled memory allocation）**，
   Trivy 修复版 `33.7.2`（Guava 只发 `-jre`/`-android` 变体，裸版本号不存在，故目标是 `33.7.2-jre`）。
   受影响模块：api-gateway / bridge / gateway / signing-service / wallet-service。
2. **`org.xerial.snappy:snappy-java 1.1.10.7` → CVE-2026-108106（DoS）/ CVE-2026-93451（buffer overflow）**，
   修复版 `1.1.10.9`。仅 `nexus-gateway`；来源 `spring-kafka → org.apache.kafka:kafka-clients:4.1.2`
   （kafka 的传递依赖，Boot BOM 不管）。
3. **`nacos-client-3.1.1.jar` 内嵌（shaded）guava `33.3.1-android`**：类全部 relocated 到
   `com.alibaba.nacos.shaded.com.google.common.*`（原始 `com/google/common` 下 **0 个类**，本地 `jar tf` 实证）。
   **上游无可用修复**：nacos-client `3.2.4` / `3.3.0-RC` 仍内嵌 `33.5.0-android`（同样 < 33.7.2），
   Spring Cloud Alibaba 2025.1.0.0 亦锁 3.1.1 ⇒ 升 nacos 清不掉，只能单条豁免。

## 三、修复（#312 → `0b596968`，6 文件 +59/−5）

- 四处同步 `guavaVersion` → `33.7.2-jre`：根 `build.gradle`、`nexus-core`、`nexus-sdk/java`、`nexus-consortium/config.gradle`；
- 根 `resolutionStrategy.eachDependency` 定点抬 `snappy-java` → `1.1.10.9`（与既有 lz4 覆盖同一条来源链，同补丁线不跨 minor）；
- `.trivyignore` 新增 `CVE-2026-102554` **一条**，注释写明：只覆盖 nacos 内嵌的 relocated 副本、
  删除条件（nacos 内嵌 ≥ 33.7.2 或不再内嵌）、以及回归防线（自家 guava 回退仍会被 OWASP Dependency-Check
  这条必检拦下，本豁免不会把它静默掉）；
- CHANGELOG 同步记录。

**主干绿证**：`Security Scan` run `40676464372` overall success，23/23 job；
`CI/CD Pipeline` run `40676464425` overall success，20/20 job。

## 四、给后续 agent 的两条口径

1. **PR 阶段的 `Trivy Docker Image Scan` 是 `skipping`**（既有盲区）：PR 全绿**不代表**镜像安全，
   依赖/基础镜像类改动要看**合并后 master 的 Security Scan**。本次连红 8 趟正是发生在这条盲区里。
2. `.trivyignore` 的 `trivyignore-staleness` 只复核 OS 层基础镜像；本次新增的条目属 **jar 层**，
   会在该 job 里被列为 B 类「作用域可能失效」提示——**预期行为，不阻断**，不要据此删条目。

## 五、边界

- 只改依赖版本、一条 resolutionStrategy 规则与一条 `.trivyignore` 条目；**未改任何 workflow / 仓库设置**；
- 未动他人分支与 PR；`.trivyignore` 既有 OS 层条目一条未删。
