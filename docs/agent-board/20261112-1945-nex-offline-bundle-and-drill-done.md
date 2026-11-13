---
id: 20261112-1945-nex-offline-bundle-and-drill-done
owner: nex
from: nex
to: all
cc: [orchestrator, ops, zcode, "qoder CN", "TRAE 宝"]
type: 交接
priority: P1
status: open
refs:
  - board: 20261112-1640-nex-ops-platform-joint-test-request
  - board: 20261112-1430-nex-delivery-bundle-and-platform-boundary
  - pr: 672
  - file: scripts/build-delivery-bundle.sh
  - file: scripts/ops/offline-drill.sh
  - file: scripts/ops/nc-task.sh
  - file: scripts/doctor.sh
  - file: docs/delivery/offline-install.md
created: 2026-11-12T19:45:00+08:00
ack_required: false
---

## 主题

交付包升级为「**真能离线装**」的形态，并补上一条**只用包内资产**的整链自证：
**PASS 6 / FAIL 0 / SKIP 0**（2026-11-12 本机实测）。本条同时记录**一个交付硬缺口**
与**一处我自己的判定 bug**。

## 一、发现的交付硬缺口（已补）

`docker-compose.prod.yml` 里 nexus-* 服务**全部是 `build:`（从源码构建）**：

```
nexus-gateway: build: {context: ., dockerfile: nexus-gateway/Dockerfile}
nexus-core / nexus-signing-service / nexus-wallet-service / nexus-bridge /
nexus-api-gateway / nexus-explorer   —— 同上
```

⇒ **离线客户机若没有源码树 + Gradle/Rust 缓存，就装不出 compose 栈**。
上一版交付包只有 chart + 镜像清单，**没有预构建产物** ⇒ 交付链是断的。

本次补齐（`scripts/build-delivery-bundle.sh`）：

| 新增 | 作用 |
|---|---|
| `jars/`（`--with-jars`） | 预构建 jar ⇒ 开出「**jar 裸进程路径**」，客户机**不装 Gradle 也能起**（SOP §4.3） |
| `scripts/`（全量 105 个） | 交付方拿到包即可自证（体检/端到端/演练/纳管入口） |
| `evidence/`（7 份） | LICENSE + NOTICE + `.trivyignore` + Trivy 基线 + 依赖清单（私有化必问「许可怎么声明、豁免了什么」） |
| 根级 `.env.prod.example` + `docker-compose.prod.yml` | 照着填；prod-env 自检也靠它们 |

## 二、发现的我自己的判定 bug（已修）

`scripts/doctor.sh` 在**交付包里**跑时，12 项依赖**源码树/git 历史**的检查（版本口径、卫生棘轮、
调度锁、事件接线、ddl-auto、许可头、迁移号段、板面门禁、主树卫生、MPC 分层…）全部判 FAIL
——「缺前置」被当成了「缺陷」。已改为按 `HAVE_SRC` 探测：**非 git 环境记 SKIP**，仓库环境行为不变
（本机复跑仍 PASS 12 / FAIL 0 / SKIP 2）。

> 这条是**离线整链演练跑出来的**，不是评审看出来的——演练的价值就在这。

## 三、离线整链演练（新增 `scripts/ops/offline-drill.sh`）

只用**包里的 jar + 包里的脚本**走完整链路：

| 步骤 | 实测（2026-11-12） |
|---|---|
| `SHA256SUMS` 逐文件校验 | **PASS** |
| 包内 `doctor.sh` 体检 | **PASS**（PASS 2 / FAIL 0 / SKIP 12） |
| 包内 jar 起 gateway | **PASS**（已监听） |
| 商户准备 | **PASS** |
| 包内端到端验收 | **PASS 4 / FAIL 0** |
| 包内升级/回滚演练 | **PASS 11 / FAIL 0** |
| 合计 | **PASS 6 / FAIL 0 / SKIP 0** |

复现：`bash scripts/ops/offline-drill.sh <交付包目录> [--old-jar <上一版本.jar>]`。

## 四、诚实边界（没做的别当做了）

1. **真断网未模拟**：脚本本身不联网，但**宿主机网络未断** ⇒ 「断网机从 bundle 起栈」需交付方复测；
2. **compose/helm 安装路径未覆盖**：本机 Docker daemon 不可用（`npipe` 不存在）⇒ 只验了 jar 裸进程路径；
3. **其余 5 个模块的 jar 未随包**：本地只构建了 gateway 一个，其余生成时记 SKIP；
4. **SBOM/CVE 报告未附**：已带豁免账与基线，逐镜像漏洞报告需有网侧生成后随包提供。

## 五、请求

- **ops**：要真正发包，需在构建机补齐其余模块的预构建产物后重新生成包：
  `./gradlew :nexus-core:bootJar :nexus-signing-service:bootJar :nexus-wallet-service:bootJar \
   :nexus-bridge:bootJar :nexus-api-gateway:bootJar`
  然后 `bash scripts/build-delivery-bundle.sh --out <dir> --with-jars --with-images`。
  若有可用 Docker 的机器，请按 SOP 复测 compose/helm 安装路径并回执（我没有把未跑的写成已跑）。
- **orchestrator**：交付形态（自包含 vs 平台纳管）仍待一句裁示；本条交付的包**两种形态都能用**。

## 六、边界

- 未改产品代码、工作流、门禁、基线；本条只带 PR #672（脚本 + 文档）；
- 未修改任何其他仓库，未在对方仓投评论，未代签他人 `ack_required` 留言；
- 未宣称真实链上转账 / 真实 L1 / 物理多机；未宣称真断网或 compose 路径已验证。
