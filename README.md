# NexusChain

NexusChain 是一个**基于自研区块链的支付编排平台（Payment Orchestration Platform）**。它在自研结算链之上构建统一支付网关、跨链桥、交易所钱包与清结算/合规/分析中间服务层，面向中小电商与 SaaS 提供多渠道、多链的支付受理能力。

> **定位**：区块链是底层结算基础设施，不是产品本身。产品价值在于统一支付 API、启发式路由与清结算。
>
> **商业化路径（2026-10-03 定夺）**：本项目现阶段不持有支付牌照、不直接运营资金；
> 商业形态为**私有化软件交付给持牌机构**（需要支付系统、有资质与实力的集团），
> 沙箱/仿真态对外展示，生产资金操作由持牌交付方在其合规主体下运行。

> **版本口径（2026-09-28 第三次修复漂移，CI 门禁化）**：构建侧**双源**为根 `build.gradle` 的 `version`
> 与 `nexus-core/nexus-core/src/main/resources/version.properties` 的 `versionNumber`（当前均为 `2.74.0`；
> 发版时**两处都要改**——v2.50.2 曾因 version.properties 滞后导致 jar 名错位）；
> 发布说明单一来源为 [CHANGELOG](CHANGELOG.md)（最新条目以 CHANGELOG 顶部为准；
> 本头注不复述具体条目内容——历次漂移中有两次源于此处复述与现实脱节）。
> 本头注的准确性由 CI 门禁 `scripts/check-version-consistency.sh` 保护：
> build.gradle / version.properties / 本头注三处任一失配将直接导致 CI 失败。

## 快速开始

### 环境要求

- JDK 17+（nexus-consortium 亦已升级至 17）
- Gradle 8.14（随 wrapper 提供）
- 可选：Docker + docker-compose（一键起全栈）
- 可选：Rust toolchain（编译 mpc-engine，需 C 编译器）

### 构建环境要求（Rust / mpc-engine）

`mpc-engine` 为 Rust gRPC MPC 密码学引擎，`cargo check` / `cargo build` 需要以下环境之一：

- **MinGW 工具链（Windows 默认 GNU）**：安装 MinGW-w64，确保 `gcc.exe` 与 `dlltool.exe` 在 `PATH` 中。Rust 默认 `x86_64-pc-windows-gnu` target 依赖二者。
- **MSVC 工具链（推荐 Windows 生产环境）**：安装 Visual Studio Build Tools（含 C++ 桌面工作负载），并切换 Rust 默认 target：
  ```bash
  rustup target add x86_64-pc-windows-msvc
  rustup default stable-x86_64-pc-windows-msvc
  ```
  此路径不依赖 `gcc` / `dlltool`，与 NexusChain Java 侧同享 MSVC ABI。
- **Linux / macOS**：系统 `gcc` / `clang` 即可，无需额外配置。

> 若仅构建 Java 侧（`gradle build`），无需 Rust 环境。Rust 仅 `mpc-engine` 模块需要。

### 本地构建与测试

```bash
# 聚合测试（根构建全部 Java 子项目 test + composite build 模块 check（含覆盖率验证）；integration 标签用例不含）
./gradlew testAll

# 仅网关（沙箱模式，零外部依赖）
# 注意：须在仓库根目录执行——nexus-gateway/ 内的独立构建不可用
# （gateway build.gradle 引用根构建子项目 :nexus-common / :nexus-sdk:java）
./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"
```

### Docker 一键启动

```bash
docker-compose up -d
```

启动网关 + 链节点 + 签名服务 + 钱包服务 + 桥，以及 Nacos / Sentinel / Seata / Zipkin 基础设施。

### 本地联调（容器化 Postgres + 原生 core）

core 的持久化层绑定 Postgres 方言，真机联调的正确姿势是 **Docker 起 Postgres、core 走 local profile 原生运行**。一键脚本会自检 Docker 引擎（未启动时自动拉起 Docker Desktop）并幂等保证 `127.0.0.1:55432` 上有健康的 PG（已有健康容器则复用，不重复创建）：

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1        # 仅确保 PG 就绪
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-up.ps1 -StartCore   # PG 就绪后前台起 core

# Linux / CI（原生 Docker daemon）
./scripts/dev-pg-up.sh
START_CORE=1 ./scripts/dev-pg-up.sh

# 停掉 compose 管理的 PG（数据卷保留）
powershell -ExecutionPolicy Bypass -File scripts\dev-pg-down.ps1
```

等价的手动流程：`docker compose up -d nexus-pgsql` 起库，再 `gradlew :nexus-core:nexus-core:run --args="--spring.profiles.active=local"` 跑 core（凭据与端口见 `nexus-core/nexus-core/src/main/resources/application-local.properties`，与 `docker-compose.yml` 的 `nexus-pgsql` 服务严格一致）。

## 模块清单

| 模块 | 职责 | 成熟度 |
|------|------|--------|
| `nexus-core` | 结算链节点：共识（DPoS 默认 / PoS 可选）、P2P、存储、RPC、合约引擎 | 较完整（PoS 基础层 + NexFinality 最终性层已实现（v2.21.0 修复测试签名长度问题，49 个测试全部通过），多节点共识 2026-08 真机验证） |
| `nexus-gateway` | 商户支付网关：订单、路由、Webhook、清结算/风控/合规关卡接入 | 较完整 |
| `nexus-bridge` | 跨链桥：锁定/铸造/销毁/解锁状态机、Relayer 网络、流动性管理 | 核心完整，Solana/Avalanche 适配器已交付（含测试），其余外部链为骨架 |
| `nexus-consortium` | 联盟链/侧链：完整 PoA 链、国密 SM2/3/4 | 完整 |
| `nexus-settlement` | 清结算：复式账本、对账、风控规则、资金归集 | 完整（库形态，由 gateway 内嵌，不独立部署，见下注） |
| `nexus-compliance` | 合规：KYC、AML 筛查、DID、信誉评分 | 完整（库形态，由 gateway 内嵌，不独立部署） |
| `nexus-analytics` | 数据分析：交易图谱、监控告警、统计、导出 | 完整（库形态，由 gateway 内嵌，不独立部署） |
| `nexus-oracle` | 预言机：多源价格聚合、链上治理、可验证随机数 | 完整（库形态，由 gateway 内嵌，不独立部署；治理执行已修复：GOV-P0-01 事件源白名单 + 审计日志持久化） |
| `nexus-signing-service` | 签名服务：交易签名编排、MPC 传输层 | 编排完整，MPC 真实 CGGMP21 门限签名（GG20 已退役 2026-10-08，见下「MPC 多方签名」） |
| `nexus-wallet-service` | 钱包服务：白名单、冷热托管、审批流 | 白名单/审批完整，托管为模拟 |
| `mpc-engine` | Rust gRPC MPC 密码学引擎 | **真实 CGGMP21 门限 ECDSA（`cggmp21` 0.6.3）；GG20 路径已退役，依赖树无 GPL** |
| `nexus-explorer` | React + TS 区块浏览器 | 六个数据面全部接入 core RPC（区块/交易/地址/合约/跨链/节点状态）；**已容器化**（单容器 BFF 同源托管，见成熟度声明） |
| `nexus-sdk` | 四语言 SDK：Java 全能力；TS/Python/Go 真实 RPC 契约实现（链/钱包/交易查询、跨链桥、地址校验、提交走 wallet-service） | **四语言全部可用**（2026-09-01 v2.2.0 补真，36 用例全绿，见 nexus-sdk/README） |
| `nexus-rpc-doc` | RPC API 文档 | 参考 |

> **注（2026-08-29 审计核实）**：`nexus-settlement` / `nexus-compliance` / `nexus-analytics` / `nexus-oracle`
> 均为**库模块**（各自 build.gradle 中 `bootJar.enabled=false`，注释明确"不产出 Spring Boot fat jar"），
> 由 `nexus-gateway` 内嵌消费，**不提供独立部署形态**（docker-compose / Helm charts 中无对应服务属预期设计）。
> 如未来需独立部署（如合规审计独立进程），需另行改造为可执行应用。

## 成熟度声明（重要）

为避免"宣称能力 >> 实际能力"，以下组件的真实状态如实标注。**v2.0.0-rc1 已完成 Phase 5 真实化改造**，各组件状态如下：

### 区块浏览器（nexus-explorer）

- **状态（2026-10-12 复核）：六个数据面全部真实接入，可容器化部署；仍未做链上实时订阅与图表可视化。**
- **已具备**：
  - 页面/路由 9 条，全部消费真实后端端点：区块列表与详情、交易列表与详情、地址（余额/交易数）、
    **合约列表与详情**（`/contracts`、`/contracts/:addr`）、**跨链交易追踪**（`/crosschain`，含状态筛选）、
    节点状态（首页页头）、支付编排控制台（`/orchestration`，需商户凭证）。
  - 契约纪律：`types/` 与后端字段**逐字对齐且标注来源**，`api/client.ts` 对每个端点声明必需字段
    （缺字段抛契约错误，而非渲染 `undefined`）；路由可达性由 `routes.test.tsx` 守卫（页面不可达即红）。
  - 测试与门禁：页面级测试 + 组件测试 + 构建产物校验（设计令牌注入/工具类生成）+ 对比度校验（WCAG AA）。
  - **部署形态**：`nexus-explorer/Dockerfile`（多阶段，单容器 = Express BFF 同源托管前端产物 + `/api/**`），
    已接入 dev/prod compose 与发布链（`release.yml` / `ci.yml` 镜像矩阵）。
- **未具备（诚实声明）**：链上状态实时订阅（当前为 10s 轮询）、图表可视化、监控告警前端、
  K8s/Helm 部署形态（`deploy/helm/charts/` 无 explorer 子 chart，`deploy/k8s/` 无静态清单——
  两者须同步落地，否则 `k8s-sync-check` 会判定双路径漂移）、合约 `abi` 数据源（core 不返回，UI 显式说明）。
- **历史**：2026-08-27 曾标注「MVP 骨架（约 40 个前端源文件）」；该口径在 2026-09-16 数据接入审查
  与 2026-10-12 合约/跨链页补齐后均已过期，本段为最新口径。

### MPC 多方签名（CGGMP21 门限 ECDSA；GG20 已退役 2026-10-08）

> **GG20 退役完成（PLAN-001-R2，2026-10-08）**：GG20 可信协调器路径整体删除
> （Rust `mpc-engine/src/{gg20,dkg,sign,aggregate,distributed,session}.rs` 与
> Java `GrpcMpcCryptoEngine`，以及 proto 的 Dkg/Sign/Aggregate/Relay*/DistStatus
> 六个 RPC）——CGGMP21 是**唯一**的门限签名路径。随之一并消除的还有 GPL-3.0 系
> 依赖（`multi-party-ecdsa`/`centipede`/`bulletproof-kzen`/`round-based@0.1`/
> `zk-paillier`），`mpc-engine` 依赖树现为 MIT/Apache 系。设计与实施记录见
> [docs/plan/PLAN-001-gg20-retirement.md](docs/plan/PLAN-001-gg20-retirement.md)。

> **默认运行态声明（2026-10-08 代码取证）**：`mpc.engine.cggmp-enabled` 默认 **false**
> 时**没有任何真实引擎**——`ColdWalletMultiSigService.selectActiveEngine()` 返回 null，
> 冷钱包转账降级为 **FROZEN skeleton 记账流程**（`FROZEN-` 占位份额，非真实签名，
> 仅供本地/沙箱跑通流程）。真实门限签名要求 `cggmp-enabled=true` + 3 端点集群
> （见下「生产路径」）。与 GG20 时代的差异：那时 `false` 会回退到可信协调器路径，
> 该回退已随 GG20 消失——现在是 fail-closed 的「要么真实、要么明确的不真实」。

> **传输层默认态补充（2026-09-29 代码取证）**：除引擎路径外，P2P 份额传输默认也是
> **进程内**实现：`application.yml` 中 `mpc.transport.real-grpc-enabled` 默认 **false**，
> 此时 `GrpcMpcTransportStub` 回落到 `InMemoryMpcTransport`（跨方消息不离开本进程）。
> 真实 gRPC+mTLS 传输需显式开启（`deploy/helm/values-prod.yaml` 置 `"true"`）。
> 因此"多引擎分布式签名"的完整验证要求 `cggmp-enabled` 与 `real-grpc-enabled` **两者同时为 true**。
> 基础配置默认关闭 CGGMP21，不代表所有部署均未启用；实际能力需核对部署覆盖值和端到端测试。
>
> **生产路径已显式启用分布式 CGGMP21（P0-1，2026-09-29 代码取证）**：
> `deploy/helm/values-prod.yaml`（signing-service）注入 `NEX_MPC_ENGINE_DISTRIBUTED=true`
> + `NEX_MPC_ENGINE_CGGMP_ENABLED=true` + `NEX_MPC_ENGINE_CGGMP_SIGNERS=0,1`（2-of-3）
> + `NEX_MPC_ENGINE_TLS_OVERRIDE_AUTHORITY=localhost`；engine endpoints 指向 3 个
> mpc-engine StatefulSet 逐 Pod headless DNS，mTLS 证书经 `nexus-mpc-certs` Secret 注入。
> 该路径下：`distributed-mode=true` 强制 endpoints ≥3 且 partyIndex 超界 fail-closed
> （抛异常，不回退端点 0；`MpcEngineRouter.java:192-205,294-305`）；份额加密驻留各引擎
> 进程（NXC1 信封），signing-service 不持有任何份额。生产路径 2-of-3 真实签名 E2E
> （含份额隔离断言：三节点 keyshare.bin SHA-256 两两不同、无 GG20 快照残留）见
> `CggmpMpcE2EClusterTest#cggmpE2EProductionPath`，由 `mpc-java-cluster-e2e` CI job
> 无条件回归（CI job `mpc-java-cluster-e2e`）。
> 旧路径退役已于 2026-10-08 实施完成（PLAN-001-R2）：
> [docs/plan/PLAN-001-gg20-retirement.md](docs/plan/PLAN-001-gg20-retirement.md)。

> **MPC 分层口径（2026-10-02 决策；2026-10-08 两轮更新：GG20 退役 + staging 升级）**：
> 默认关闭**不是缺陷，是有意的三级阶梯**——
> ① **dev/默认**（application.yml）：`real-grpc-enabled=false` + `cggmp-enabled=false`，
> InMemoryMpcTransport + **无真实引擎（FROZEN skeleton 记账）**（零外部依赖，单测/本地开发用）；
> ② **staging**：**2026-10-08 起升级为全分布式 CGGMP21**（transport + distributed +
> cggmp-enabled 全开，与 prod 同口径同阈值）——此前只开传输层、协议层无真实引擎；
> ③ **prod**：transport + distributed-mode + cggmp-enabled 全开，全分布式 CGGMP21 2-of-3。
> **keyshare 供给（两级共同前置）**：钱包要能签名，必须先跑一次 DKG 仪式
> （keygen→aux→assemble；份额落在各引擎进程的 `MPC_ENGINE_SESSION_DIR`，**Java 侧不持份额**）：
> `python3 scripts/mpc-wallet-ceremony.py --wallet <walletId> ...`（加 `--sign-probe` 可当场验签）。
> 未跑仪式的钱包在签名时以 `key_share missing` fail-closed——这是设计，不是缺陷。
> 该阶梯由 CI 门禁 `scripts/check-mpc-tier-policy.sh`（k8s-sync-check workflow）固化：
> staging 不得静默退回进程内传输或 skeleton，prod 不得静默退回非分布式/进程内
> （staging 的 CGGMP21 开关已由 WARN 升为**硬断言**）。

- **冷钱包 HTTP 入口（2026-10-09 新增）**：此前 CGGMP21 的 DKG 仪式与冷钱包多签编排
  **没有任何 HTTP 入口**（`MpcService.generateKeyShare` 与 `ColdWalletMultiSigService` 的
  init/sign/broadcast 只有测试调用方——业务链"没接出去"）。现由 `MpcColdWalletController`
  接出（`/api/v1/mpc/**`，鉴权：建钱包 `ROLE_ADMIN` / 转账三类 `ROLE_SIGNER` / 查询 `ROLE_READ`）：

  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/api/v1/mpc/wallets` | 建钱包 = 跑 DKG 仪式（keygen→aux→assemble），登记聚合公钥；幂等；集群不可用 → 409 fail-closed |
  | GET | `/api/v1/mpc/wallets/{walletId}` | 查钱包 |
  | POST | `/api/v1/mpc/cold-wallet/transfers` | 受理转账（审批法定数 + 地址白名单校验）；集群不可用 → **503**（不降级 skeleton） |
  | POST | `/api/v1/mpc/cold-wallet/transfers/{sid}/sign` | MPC 签名（引擎内产出 r‖s 并内部验签） |
  | POST | `/api/v1/mpc/cold-wallet/transfers/{sid}/broadcast` | 聚合 + 上链广播，返回 txHash |
  | GET | `/api/v1/mpc/cold-wallet/transfers/{sid}` | 状态/失败原因/链上哈希 |

- **审计与对账查询入口（2026-10-11 新增/补全）**：资金操作全部写审计（`audit_events` 表，
  append-only），并提供只读查询面（`ROLE_MERCHANT` / `ROLE_ADMIN`）：

  | 方法 | 路径 | 说明 |
  |---|---|---|
  | GET | `/api/v1/audit/events/merchant/{merchantId}` | 按商户查审计（倒序） |
  | GET | `/api/v1/audit/events/action/{action}` | 按动作查（如 `CHANNEL_REFUND_EXECUTED` / `AUTO_WITHDRAW_EXECUTED`） |
  | GET | `/api/v1/audit/events/window?from=&to=` | 按时间窗查（ISO-8601） |
  | GET | `/api/v1/audit/events?page=&size=` | 分页视图（size 夹 1..200） |

  **写入侧覆盖四条资金主路径**：支付确认 / 退款（审批·执行·失败）/ 对账调账（加·扣·失败）/
  自动出款（执行·失败已退回·无出款账户被拒）/ 渠道退款（微信按 `transaction_id`、
  支付宝按 `trade_no` 原路退回）。**审计为旁路**：写失败绝不阻断资金流程。

- **出款账户（结算账户）管理入口（2026-10-11 补齐）**：自动提现的闸门是"**无生效出款账户就拒绝扣账**"
  （v2.62.0），但绑定入口此前缺失 ⇒ 该功能实际不可用。现补上（**一律 `ROLE_ADMIN`**——
  出款去向是最敏感的资金出口配置）：

  | 方法 | 路径 | 说明 |
  |---|---|---|
  | POST | `/api/v1/payout-accounts` | 绑定出款账户（进 `PENDING_VERIFY`，需核验） |
  | GET | `/api/v1/payout-accounts/{merchantId}` | 列出该商户账户（含待核验/停用） |
  | POST | `/api/v1/payout-accounts/{id}/activate` | 核验通过 → 生效（方可作为出款去向） |
  | POST | `/api/v1/payout-accounts/{id}/disable` | 停用（留痕，不物理删除） |

  **合规护栏（服务层强制，端点原样透传原因）**：`PLATFORM_SETTLEMENT` 必须持牌主体
  （`complianceEntity=true`）——平台方不得以自有账户收商户款并对外出款（**二清红线**）；
  户名必须与商户主体一致（防绑他人账户）。

  **keyshare 供给两条等价路径**：① 业务侧走上面 `POST /wallets`（经 Java 驱动引擎跑仪式）；
  ② 集群运维侧走 `scripts/mpc-wallet-ceremony.py`（不经 signing-service，纯 grpcurl）。
  两者会话 ID 同源（`cw-` + SHA-256(walletId)/16），可混用。
- **业务链密码学闭环 E2E（2026-10-09 升级为真实广播）**：`CggmpMpcE2EClusterTest#coldWalletBusinessChainE2E`
  在真实 3 引擎集群上跑 DKG 编排 → 受理 → MPC 签名 → **真实 HTTP 广播**（`POST /sendTransaction`），
  且由"链节点"侧用聚合公钥**独立验签**——验签不过即拒上链（`code=5000`）。
  于是"业务链跑通"蕴含"MPC 签名可被链节点验签"（真链上打包/共识仍需真实节点环境）。
  该用例同时是"签名必须绑定 `SHA-256(msg)` 原摘要"的回归门禁（见 CHANGELOG v2.54.1）。
- **本地沙箱（docker-compose）**：3 个引擎以 **PartyConfig 真 mTLS** 装配（配置由
  `scripts/gen-mpc-engine-configs.sh` 生成，证书 `scripts/gen-mpc-certs.sh`）+ 份额落盘卷；
  signing-service 侧 CGGMP21 全开。端到端实证（2026-10-08 本机）：三引擎 mTLS → 仪式
  （keygen/aux/assemble，聚合公钥三方一致）→ 2-of-3 签名 + 引擎侧验签 `valid=true`
  → **重启签名方进程后仅凭磁盘份额再签成功**（日志 `cggmp share loaded from disk`）
  → 三引擎 `keyshare.bin` 同尺寸但 SHA-256 两两不同（份额隔离）。
- **Rust `mpc-engine`**：CGGMP21（LFDT-Lockness `cggmp21` 0.6.3，Kudelski 审计）实现**真实 t-of-n 门限 ECDSA**（keygen → aux_info（Paillier）→ sign，产出可被标准 secp256k1 验证的签名）；驱动线程 actor 模型，协议消息经协调器**字节管道**中转（协调器不理解、不落盘、不修改消息，不含任何份额）。
- **Java MPC 传输层**：`GrpcMpcTransportStub` + `MpcTransportGrpcServer` 实现**真实 gRPC over HTTP/2** 传输，支持 P2P 消息路由。
- **份额隔离（诚实声明，2026-10-08）**：CGGMP21 路径下各引擎进程独立持有份额（NXC1 信封 AES-256-GCM 加密落盘），signing-service 不持有任何份额；GG20 时代「全部 n 方份额驻留同一进程、协调器可跨方提取任意份额」的缺陷**随该路径代码一并消失**——不再是「访问控制靠单进程信任边界」。
  **残余诚实声明**：`cggmp-enabled=false`（dev/默认/staging 现状）时没有真实引擎，冷钱包走 FROZEN skeleton 记账（占位份额、非真实签名）；份额的 ZK 范围证明校验未接入 Java 聚合层（`MpcSignatureAggregator` TODO，依赖最终 ECDSA 整体验签兜底）；落盘密钥轮换未实现版本化（`load_storage_key_for_version` 当前忽略版本号，单密钥运行）。
- **编译状态**：已完成编译验证（`mpc-engine/target/release/mpc-engine` 产物存在；README 早期"未编译"声明过时，2026-08-27 核实）。
- **传输安全**：gRPC mTLS 已实现（MPC-P0-02 修复，use-plaintext 默认 false）。

### ZK 证明（方案 C 真实 Groth16 已启用，详见 CHANGELOG v2.40.0）

- **真实 Groth16（方案 C，已启用）**：`zk-groth16-service`（Rust arkworks，BN254 双线性配对）提供**真实配对验证**；Java `DefaultZkProofSystem` 默认远程对接（fail-closed：远程不可用 prove 抛异常 / verify 返回 false），本地 Schnorr 仅作未配置远程服务时的降级路径。
- **witness BigInteger 化（A1-R3）**：prove/verify 全链路 `long[]` → `BigInteger[]`，256 位状态根无精度损失（ZK-P2-01 关闭）。
- **可信设置仪式（A1-R4）**：`scripts/zk-setup-ceremony.sh` 离线仪式（generate 导出 pk/vk hex → import 导入 + SHA256 校验 + 指纹比对），安全要求见 `docs/zk-setup-ceremony.md`。
- **回归自动化（A1-R5）**：`scripts/zk-e2e.sh` HTTP E2E（setup→prove→verify + bench 阈值）与 `cargo test` 已纳入 CI 门禁。
- **历史遗留（v2.0.0-rc1 起，仍有效）**：无远程服务时的本地 Schnorr 降级路径**不具备通用电路 ZK 安全属性**，仅用于逻辑流程验证；halo2 / Plonk 后端仍冻结。

### L2 Rollup（v2.0.0-rc1 真实化）

- **Optimistic 路线**：欺诈证明单步验证为真实逻辑（批次/挑战/bond 状态已 PG 持久化——`JdbcFraudProofStateStore`，重启不丢失；L1 交互默认内存模拟 `real-l1-enabled=false`——"锚定到真实 L1 状态"未完成；Phase 5 仅覆盖双节点共识/同步，real-L1 真机验证仍未交付，桥的存款/提款/已提交根仍为内存态，见"状态执行接线"专项 [PLAN-014](docs/plan/PLAN-014-state-execution-wiring.md)）。
- **ZK 路线**：依赖上述 Schnorr 证明系统，不具备 ZK 安全属性。
- **支付扩展（0x10–0x1a）**：类型枚举、`GAS_TABLE` 与 `BasicRule` 单规则预检已存在；但完整区块/RPC 路径受 `TransactionCheck.TransactionFormatCheck` 的 `type<=15` 门禁约束，0x10–0x1a 会返回 `Type check error`，**当前不可上链**。`PaymentTransactionProcessor.processTransaction` 仅测试调用；0x19/0x1a（DID 注册、订阅授权）分支仅日志、无状态写入。执行接线与"可上链"共识变更均未完成（见 [PLAN-014](docs/plan/PLAN-014-state-execution-wiring.md)）。
- **L2-L1 真实化**：Hardhat L1 测试环境 + `L2Bridge.sol` Solidity 合约已就绪，但端到端测试因 Hardhat EDR 不兼容跳过（未完成真实 L1 节点验证）。

### 治理执行（真实化完成，2026-08）

- **`GovernanceExecutionDispatcher`**：监听 `ProposalStatusChangedEvent`，按提案类型分发（软件升级/资金支出/验证者集变更）。
- **执行流程真实**：解析 payload → 校验 → 执行 → 审计日志（JPA 持久化）→ 发布事件 → 状态回写。
- **安全已补**：事件源白名单认证、审计日志持久化、软件升级 Nacos 配置发布（Open API）、转账哈希真实计算（fail-closed，无 `hashCode()` 占位）。

### PoS 共识（NexFinality 最终性已实现（v2.21.0 测试修复后全部通过），2026-08）

- 基础层闭环（ADR-029 审计基线）：权益加权出块提案、区块 8 步校验（真实 Ed25519 验签）、质押管理、罚没、验证者注册、出块奖励、P2P 同步。
- **NexFinality 最终性层（ADR-030/031）已实现并真机闭环**：BFT 双轮投票、2/3 质押权重判定、双签证据惩罚链、验证者集变更走治理（VALIDATOR_SET_CHANGE）、BLS 接口层（M3 阻塞项：blst 库绑定待环境解锁）。
- **最终性状态持久化（PLAN-015 Phase 6A，2026-10-21）**：生产/local 下 `JdbcFinalityStateStore` 将投票与最终化检查点写入 PG（`finality_votes` / `finality_checkpoints`）；双节点强制重启后旧 epoch 仍 `FINALIZED`、权重一致。同主机双进程验收边界、多副本缓存一致性与故障注入缺口见 [PLAN-015](docs/plan/PLAN-015-finality-persistence-resilience.md)。
- **最终性故障注入首批（PLAN-016 Phase 6B，2026-10-21）**：持久化顺序改为“先写库、后改内存”，修复 `recordVote`/`markFinalized` 失败导致的内存与数据库分叉；`FinalityCoordinator` 增加有界重试队列并隔离 `NewBlockEvent` 异常，最终性表短时不可用时区块不再被 `pendingBlock` 60 秒超时拖住。双节点实测故障 21.6 秒后 epoch 534–544 全部补齐。边界见 [PLAN-016](docs/plan/PLAN-016-finality-fault-injection.md)。
- **PG/AccountDB 短时不可用降级（PLAN-017 Phase 6C 第一切片，2026-10-21）**：账户读取改为 fail-closed（数据库故障不再伪装成“新账户”），proposer 在账户库不可读时安全暂停；`/consensus/status` 与 P2P 状态握手改用内存链头，新增 `/consensus/storage-status` 降级观测、DBCP2 5 秒快速失败与异步区块采纳异常隔离。真实 PG 停止 43.9 秒验收中状态接口保持 HTTP 200、日志 ERROR 为 0、恢复后 A/B 高度 3549/3549、最终性检查点无缺口。**PG 停止期间链停止出块，本切片不等于 PG 高可用或主备切换**；边界见 [PLAN-017](docs/plan/PLAN-017-account-store-degradation.md)。
- **最终性持久化闭环（PLAN-018 Phase 6C 第二切片，2026-10-22）**：`finality_votes` 增列 `signature`/`public_key`（Ed25519 签名随票落库），重启后能恢复完整 Vote 对象并**自行补最终化**（不再需要新票）；新增持久化 outbox 表 `finality_vote_outbox` 与重试中枢 `FinalityOutboxManager`（内存队列 + 落库意图，跨进程重启存活）；**接收侧独立重试**——收到远端票写库失败不再蒸发，而是由接收方自己补写（旧行为下发送方并不会重发）；新增 `GET /consensus/finality-outbox` 观测待补写票数。**边界**：数据库整体不可用时只剩内存队列兜底（进程崩溃仍丢票），多副本最终性缓存一致性未解决，迁移前的老数据没有签名。详见 [PLAN-018](docs/plan/PLAN-018-finality-persistence.md)。
- **持久化区块 outbox（PLAN-019 Phase 6C 第三切片，2026-10-22）**：新增 `pending_block_outbox` 表与 `PendingBlockOutbox` 抽象，待补写区块连同写入意图跨进程存活；接收侧不再把「存储不可用」当「区块无效」丢弃（改交 `PendingBlocksManager` 走重试/outbox 通道）；新增 `GET /consensus/block-outbox` 观测端点。真实 PG 验收：账户表不可用 31.5 秒时收到的区块被**持久化捕获**（`databasePending=1`），恢复后 outbox 自动清零、链继续推进。**边界**：「写库失败」不由本 outbox 兜底（被 `RDBMSBlockChainImpl` 吞成 `false`，由 `StateDB` 的确认轮次补写），数据库整体不可用时只剩内存队列兜底。详见 [PLAN-019](docs/plan/PLAN-019-block-outbox.md)。
- **最终性视图跨节点收敛（PLAN-021 Phase 6C 第五切片，2026-10-22）**：gadget 的最终性内存视图原本只在启动时装载一次 ⇒ 启动后看不到对端投的票（3 节点验收实测同一 epoch 三节点分别报 3000/2000/1000，且永远无法本地补最终化）。现在每 5 秒把库里的新票/新终局拉进本地视图，**合并必须过与提交路径同一套绑定校验**、去重、单调；3 节点验收里三节点视图收敛为**一致 3000/3000**，掉线节点重启后完全追平。顺带修掉 DB 重放路径**没把质押额灌进 StakingService**的 bug（导致节点"知道有这些验证人却算不出权重"⇒ 总权重偏小⇒ 永远达不到 2/3 阈值）。**边界**：收敛只在**共享存储**拓扑下成立，独立存储部署仍需跨节点事实传播机制。详见 [PLAN-021](docs/plan/PLAN-021-finality-view-convergence.md)。
- **3 节点节点级故障容忍（PLAN-020 Phase 6C 第四切片，2026-10-22）**：单主机 3 验证人（**非多机**）——杀掉 1/3 验证人后链继续出块、最终性不中断（2/3 阈值），掉线节点重启后完全追平并重新参与投票，最终性检查点全程缺口 0。顺带修掉一个真 bug：`ValidatorSetPersistence.replay()` 在 `RowCallbackHandler` 里多调 `rs.next()` 导致**验证人集隔行丢失**，重启节点认不全 proposer ⇒ 报 "unknown proposer" ⇒ **永久卡在缺口高度无法采纳对端区块**（已修 + 回归测试）。**边界**：不是多机高可用（无独立存储/网络分区/断电），多副本最终性视图仍不一致（各节点报告的权重进度不同）。详见 [PLAN-020](docs/plan/PLAN-020-node-failure-tolerance.md)。
- **多节点共识全链路**（PLAN-001~013b）：验证人 P2P 同步、单 proposer 轮换、共享链幂等写、分叉重组——双节点真机 51/52 交替出块 + epoch 最终化 100%。

### L2 Rollup（ZK 真实化进展，2026-08）

- **ZK 真实 Groth16 全链路**（方案 C）：zk-groth16-service（Rust arkworks，BN254 配对验证）+ Java 对接（fail-closed）+ Rollup 状态转换电路桥接 + setup 持久化/可信设置仪式外部注入 + MOCK 证明默认拒绝。
- **L2-L1 端到端**：Hardhat 并行冲突已修复（testAll 首次全绿）。

### 资产承载限制

上述组件在达到生产级安全属性前，**不应用于承载真实资产**。详见 [安全审计报告](docs/audit/v2.0.0-rc1-security-audit.md)。

## 研究层冻结与解冻（ADR-001）

依据 [ADR-001](docs/adr/ADR-001-research-layer-freeze.md)，研究层模块经历「冻结 → Phase 5 真实化 → 条件解冻」过程：

- **mpc-engine**：**已解冻**（真实 CGGMP21 门限 ECDSA——keygen/aux/sign 全链路 + 驱动线程 actor + 字节管道协调器；原「可信协调器持有全量份额」限制随 GG20 路径退役消失，2026-10-08）
- **nexus-core L2 ZK 证明系统**：**已解冻**（方案 C 已实现：`zk-groth16-service`（Rust arkworks，BN254 配对验证）+ Java fail-closed 对接；本地 Schnorr 仅作未配置远程服务时的降级路径）
- **nexus-core L2 L1 合约交互**：**条件解冻**（Hardhat 环境就绪，EDR 兼容性待解决）
- **nexus-oracle 治理执行**：**已解冻**（GOV-P0-01 事件源白名单 + 审计日志持久化已修复）
- **halo2 / Plonk 后端**：**仍冻结**，降级为 Groth16（实际 Schnorr）

ADR-001 状态：**Resolved**（2026-08-10）。

## 历史改动摘要（v2.30.0，前端i18n+组件测试+覆盖率门禁+nexus-core异常处理）

> 注：以下为 v2.30.0 的历史摘要，最新变更见 [CHANGELOG](CHANGELOG.md)（当前版本以本文件开头「版本口径」头注为准，此处不重复硬编码版本号——正文硬编码旧版本号是第三次漂移的直接成因）。

本次发布聚焦前端 i18n 文本替换、前端组件测试、覆盖率门禁提升和 nexus-core 异常处理规范化：

| 改进项 | 范围 | 结果 |
|--------|------|------|
| 前端i18n文本替换 | 7个页面 | HomePage/Settings/OrchestrationDashboard/BlockDetailPage/TxDetailPage/AddressPage 硬编码中文替换为 useTranslation() 调用 |
| 前端组件测试 | 4个组件/57用例 | Button(15)+Card(14)+Modal(13)+Badge(15) 组件测试，总计63个测试全部通过 |
| 覆盖率门禁提升 | nexus-gateway | JaCoCo minimum 0.25→0.30，当前覆盖率0.6528远超门禁 |
| nexus-core异常处理规范化 | 约60文件/143处 | catch(Exception e)替换为具类型捕获（RuntimeException/IOException/JsonProcessingException/DecoderException/GeneralSecurityException/CryptoException等），编译通过 |

详细变更见 [CHANGELOG](CHANGELOG.md) v2.30.0 节。

## 安全审计

v2.0.0-rc1 候选版本已完成安全审计，审计报告见 [docs/audit/v2.0.0-rc1-security-audit.md](docs/audit/v2.0.0-rc1-security-audit.md)。

**审计发现汇总**：

| 级别 | 数量 | 已修复 | 未修复 |
|------|------|--------|--------|
| P0（严重） | 8 | 8 | 0 |
| P1（高危） | 8 | 8 | 0 |
| P2（中危） | 15 | 10 | 5（已记录/降级） |

**发布建议（限定于该批次修复完成度）**：v2.1.0 已完成全部 P0/P1 修复，可作为**生产候选版本**用于功能与集成验证。
这**不等于**可承载真实资产——资产承载的前置条件见上文[资产承载限制](#资产承载限制)，该限制仍然有效，
两者不矛盾：前者讲"能不能部署验证"，后者讲"能不能放真钱"。

## 许可证

**分模块声明（整仓不是单一许可）**：

| 范围 | 许可 |
|------|------|
| 根 [LICENSE](LICENSE) —— gateway / bridge / signing-service / wallet-service / api-gateway / sdk（Java·TS·Python·Go）/ settlement / compliance / analytics / oracle / mpc-engine / zk-groth16-service / explorer / devtools | Apache License 2.0 |
| `nexus-core/nexus-core/src/**`、`nexus-consortium/consortium/src/**`（4 文件） | **GNU LGPL-3.0-or-later**（上游 `java-nexuscore` 派生；许可全文见 [`nexus-core/LICENSE`](nexus-core/LICENSE)） |

对外交付 / 商业化售卖 / 尽调前请先阅读 [NOTICE](NOTICE) 与 [docs/licensing.md](docs/licensing.md)
（后者含**未完成**的合规待办：法务确认、LGPL 范围保留 vs clean-room 重写决策、依赖许可清单化）。
CI 门禁：`scripts/check-license-headers.sh` —— 新增越界的 GPL/LGPL/AGPL 头文件会使构建失败。

## 文档

- [PRD](PRD.md) — 产品需求
- [ARCHITECTURE](ARCHITECTURE.md) — 架构与模块地图
- [CHANGELOG](CHANGELOG.md) — 版本变更与勘误
- [安全审计报告 v2.0.0-rc1](docs/audit/v2.0.0-rc1-security-audit.md) — Phase 5 安全审计
- [docs/adr/ADR-020-version-strategy.md](docs/adr/ADR-020-version-strategy.md) — 版本治理策略
- [docs/adr/ADR-001-research-layer-freeze.md](docs/adr/ADR-001-research-layer-freeze.md) — 研究层冻结与解冻决策
