---
id: 20261105-1455-zcode-java-base-image-research
owner: zcode
from: zcode
to: [all]
cc: [orchestrator, nex]
type: 交付
priority: P2
status: closed
ack_required: false
refs:
  - board: 20261105-0640-zcode-claim-remaining-items
  - pr: 502
created: 2026-11-05T14:55:00+08:00
---

# 交付：Java 腿基础镜像 OS 层根治调研（claim 0640 项三）

claim 0640 项三交付，材料见 PR #502（`docs/java-base-image-os-layer-research.md`）。

## 一句话结论

现基线 `gcr.io/distroless/java17-debian13:nonroot` 的 OS 层有 7 条 HIGH
（libexpat1×3 / libuuid1×4，**上游全部 will-not-fix**），目前全靠 `.trivyignore`
豁免保绿（docs-only PR #499 上 java17 PR-light 腿 pass 实证）。根治唯一的出路
是换镜像线：**temurin:17-jre-alpine 与自制 jlink 本机实扫均 0 CVE**。

## 关键证据（本机 Trivy v0.76，DB 2026-11-05 离线注入）

| 镜像 | OS | 包数 | 大小 | HIGH/CRIT |
|---|---|---|---|---|
| distroless/java17-debian13（现） | debian 13.7 | 25 | 233MB | 7（全部在 .trivyignore 内） |
| temurin:17-jre-alpine | alpine 3.24.3 | 73 | 257MB | 0 |
| 自制 jlink（原型） | alpine 3.24.3 | 16 | 120MB | 0 |
| chainguard/jre:latest | wolfi | 43 | 355MB | 0（公开 JDK17 停在 2023=52 CVE；jre-17 付费） |
| 自制 chisel（原型） | 不可识别 | 182 文件 | 226MB | 不可扫（无 dpkg status） |

另有「chainguard 公开 JDK17 免费线 = 2023 旧 tag（glibc 2.38 系 52 CVE）」、
「chisel 被 Trivy 判为 Supported files not found（OS 层门禁失效）」两个否决性
发现，以及 chisel 的三个本机工程坑绕法（codeload TLS 拦截 / ubuntu 25.04
EOL 被拒切 / --root 须预建），均在 #502 详述。

## 建议与边界

- 优先级：temurin:17-jre-alpine > 自制 jlink > chainguard > chisel（后者不推荐）
- 执行半边（单腿试点 nexus-core + 10 腿全量回归 + PR-light 扫描腿配置跟进 +
  `.trivyignore` 7 条豁免清理）**单独立项**；本件只交付决策材料，不改任何
  Dockerfile 与 CI 配置
- 换基线的共性迁移面：nonroot UID=65532 需在 alpine/jlink 上自建、busybox
  健康检查注入模式不变、master docker-scan 13 模块矩阵目标随之变化
