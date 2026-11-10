# 生产就绪度审计报告（基础 → 高级）

- **审计日期**：2026-11-07
- **基线**：`8667f138d303dafccba2ef15a2e2e79dc12485ec`（`origin/master`，2026-11-06T14:49:27+08:00，`ci(security): 锁定 trivy v0.76.2…(#544)`）
- **审计方**：zcode（板面认领 PR #558）
- **性质**：只读审计 + 独立可复现证据。**未修改任何产品代码**；修复走独立 PR。
- **方法约束**：所有检索一律 `git grep`（只搜已跟踪文件）——根级 `grep -r` 会扫进 `build/`、`node_modules` 产生假象。

---

## 一、结论摘要

平台**基础层是健康的**：构建双绿、prod 档 schema 纪律正确（全档 `ddl-auto: validate` + Flyway 权威）、
认证在运行时确实收紧（12 个资金/管理端点未认证一律 401）、Feign 与 RestTemplate 超时齐备、
提现状态契约单一定义无漂移、settlement/analytics 库模块的实体表全被消费方迁移覆盖。

**问题集中在"链路末端没接上"**：告警/通知事件发布了但无人消费、失败重试方法存在但无生产入口、
dev 档不自洽导致开箱起不来、前端错误态与契约面收口不足。

- 真发现 **23 条**：🔴 5 · 🟠 14 · 🟡 4（含 §七 在修复过程中追加的 4 条）
- 被否证 / 降级 **12 条**（含审计方自身前两轮的 2 处误判）
- 被否证那一栏与发现栏同等重要：**它决定了修复预算不浪费**。
- 已修复并推 PR **4 个**（覆盖 6 条 🔴/🟠 + 3 条追加发现，见 §九）；仍待处置项同见 §九。
- **Mimosa 深度扫描已完成并逐条分诊**（§十）：37 条命中，**0 条可确证为可利用缺口**；
  该扫描自报 `inconclusive` / `completeness: partial` / `principals: 0` ⇒ **不据此宣称项目安全**。
  另分诊出 4 条低优先清理/加固项（M1–M4）。

---

## 二、🔴 高优先级发现

### A1 dev 档开箱起不来（`SettlementPlatformAddressGuard` 启动期 fail-closed）

```
$ JAVA_HOME=<jdk17> ./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=dev"
java.lang.IllegalStateException: 结算已启用，但平台出款地址未配置：请设置配置项
nexus.settlement.platform-address（环境变量 NEX_SETTLEMENT_PLATFORM_ADDRESS）…
若本环境确实不做结算，请显式设置 nexus.settlement.enabled=false 后重启。
```
根因：`nexus-gateway/src/main/resources/application.yml` `nexus.settlement.enabled: ${NEX_SETTLEMENT_ENABLED:true}`，
**dev 档未覆盖**，靠 `docker-compose.yml` 传 env 兜住。守卫语义正确，缺的是 profile 自洽。
影响：`clone → run` 不成立；新成员/CI 之外的本地验证全部卡在第一分钟。
复现：上述命令（`--server.port=18081` 避开 8080 占用；本机 8080 被其它项目占用）。

### A2 兼容期鉴权开关默认 `true`，prod 无覆盖

| 位置 | 键 | 取值 |
|---|---|---|
| `nexus-api-gateway/src/main/resources/application.yml:91` | `nexus.api-gateway.auth.legacy-headers-enabled` | `${NEX_AUTH_LEGACY_HEADERS:true}` |
| `nexus-api-gateway/src/main/resources/application.yml:94` | `nexus.api-gateway.auth.signature-legacy-enabled` | `${NEX_SIGNATURE_LEGACY_ENABLED:true}` |
| `nexus-gateway/src/main/resources/application.yml:322` | `nexus.security.signature-legacy-enabled` | `${NEXUS_SIGNATURE_LEGACY_ENABLED:true}` |

`nexus-api-gateway` **没有 prod profile 文件**；`nexus-gateway/application-prod.yml`（108 行）未覆盖该键。
代码注释自述「兼容期结束后将 … 置 false 关闭」⇒ **兼容期从未结束**。
门的有效性有反向测试（`AuthenticationFilterTest:389` 断言 `=false` 时必须拒绝）⇒ 只是默认放行。

### A3 前后端签名面契约漂移，且测试把窄口径钉死

- 后端要求 HMAC 的路径 **5 个前缀**：`/api/v1/payments/**`、`/api/v1/refunds/**`、`/api/v1/orders/**`、
  `/api/v2/payments/**`、`/api/v2/orders/**`（`nexus-gateway/.../config/WebConfig.java:135-143`）
- 前端只实现 **1 个**：`PROTECTED_PATH_PREFIX = "/api/v1/payments"`（`nexus-explorer/frontend/src/api/auth.ts:37`）
- 漏签：`/api/v1/refunds`、`/api/v1/orders`、全部 `/api/v2/**`
- 🔴 `nexus-explorer/frontend/src/__tests__/auth/authV2.test.ts:52`
  「isProtectedPath **仍**只保护 `/api/v1/payments/**`」——**把漂移写成了断言**：
  将来按后端口径改宽，测试反而红。

当前无活调用（前端生产代码未调 refunds/orders），属**潜伏缺口**；但测试锁定使修复会"撞测试"。

### A4 `AccountLinkageFailedEvent` 发布但全仓零消费者

- 发布：`nexus-gateway/.../event/PaymentEventListener.java:135`（支付确认 → 商户余额入账失败）、
  `:172`（退款 → 余额扣减失败）
- 全仓 19 个监听器逐一核对：**无人消费该事件，也无人监听 `PaymentEvent` 基类**（泛化兜底不存在）
- 发布方注释自述（`:73-78`、`:191`）「通过发布告警事件使运维/补偿系统能**自动感知并处理**账户联动异常」
- 加重：`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` ⇒ 入账失败发生在事务提交后，
  **无法回滚订单**。用户已付款、订单已确认、商户余额没加，唯一兜底就是该事件的消费者——而它不存在。

### A5 清算入账失败的唯一重试入口无生产调用方

- `ClearingSettlementService.processClearingBatch` 逐笔 catch → 置 `BookingStatus.FAILED` + 计数（`:99-101`）✅ 有留痕
- 唯一重试方法 `retryFailedRecords(batchNo)`（`:166`）**只有测试调用方**；该类**无 `@Scheduled`**；
  生产侧唯一调用者 `ClearingSettlementListener` 只调 `processClearingBatch`
- `failCount>0` 只有一条 `log.info`（`:109`）；listener 的 catch 只 `log.error`（`:46`）
- 同仓对比：bridge 侧 `BridgeSagaCoordinator.retryFailedSagas()` **有 ShedLock 保护的定时重试**
  （`ShedLockConfig:18` 在案）⇒ **同仓同问题，bridge 做了、clearing 漏做**
- ⇒ 清算入账失败会**永久停在 FAILED**：无自动重试、无告警、无人工入口。

---

## 三、🟠 中优先级发现

| # | 发现 | 关键证据 |
|---|---|---|
| B1 | **prod 防重放存储默认内存** | `nexus-gateway/application-prod.yml:105` `replay-store: ${NEXUS_REPLAY_STORE:memory}`；`RedisReplayNonceStore` 为 `@ConditionalOnProperty(havingValue="redis", matchIfMissing=false)` ⇒ 多实例 prod 下 nonce 不共享，防重放被绕过 |
| B2 | **3DS 模拟挑战默认开、全仓零配置面** | `ThreeDsServer.java:44`（字段初值 `true`）+ `:58 @Value("${nexus.3ds.simulated-challenge-enabled:true}")`；`git grep -- '*.yml'` **0 命中**；注释自述「生产环境必须设置为 false 并对接真实 ACS」 |
| B3 | **compose 10 处硬编码 `container_name:`** | 同仓两 worktree 无法并存跑栈（实测 `Conflict. The container name "/nexus-pgsql" is already in use`）；该容器属已删除工作树 `nc-wt-p5` ⇒ **孤儿容器 Up 6h 阻塞全栈** |
| B4 | **116 个 MySQL 迁移 / 0 个回滚脚本** | 唯一下发过的回滚脚本是 `migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.rollback.sql`（PG 侧 1 个）。与 `consortium/application.yml:19` 自述「生产环境 schema 受控、可审计、**可回滚**」冲突 |
| B5 | **前端错误态直接透传 `err.message`** | 同型 6 处：`HomePage:71`、`AddressPage:38`、`BlockDetailPage:37`、`ContractPage:41`、`ContractsPage:40`、`CrossChainPage:59`。写法 `err instanceof Error ? err.message : t("...loadFailed")`——而网络失败（`TypeError`）、超时（`TimeoutError`）、JSON 解析失败**全是 `Error` 实例** ⇒ i18n 兜底**实际不可达**。i18n 键都已建好（`common.unknownError` 等）⇒ **接线问题，非缺文案** |
| B6 | **首页失败时 10 秒无限轮询、无退避无停止** | `HomePage:83-85` `setInterval(fetchData, 10000)`，失败后不降频；每轮打 3 个端点。`client.ts` 注释自述「10s 轮询会堆积请求」——**超时加了，退避没加** |
| B7 | **页面级错误态无重试入口；空态原语已被删除** | `ErrorBoundary:49-67` 有 `common.retry` 按钮（原语存在），但页面级失败**没有**重试：首页实测 `buttons: []`（0 个按钮）。`ui/index.ts:6-7` 自述「移除 `Table` 与 `Skeleton` 导出——两者零引用」⇒ 无共享空态/加载态原语 |
| B8 | **`DunningNotificationEvent` 零消费者 ⇒ 催收通知从未发出** | `DunningManager:86` 发布；注释自述「每次动作都发布…**监听器可据此发送**」；全仓引用只有发布方 + 事件类 |
| B9 | **`SlaBreachEvent` 零消费者 ⇒ SLA 违约告警无接收方** | `SlaMonitorService:68` 发布；引用只有发布方 + 事件类 + 测试（测试**只 verify 发布了**） |
| B10 | **无 404 兜底路由 ⇒ 未知路径白屏** | `App.tsx:40-52` 共 11 条 `<Route>`，无 `path="*"`；实测 `/changes` 与 `/definitely-not-a-route-xyz` 均 `bodyChars=0`、`mainExists=false`、`navLinks=[]` ⇒ 连顶部导航都没有，用户被困 |

---

## 四、🟡 低优先级发现

| # | 发现 | 证据 |
|---|---|---|
| C1 | **`nexus-oracle` 是唯一 `ddl-auto: update`（基档）+ 零 Flyway 迁移的模块** | `nexus-oracle/application.yml:22`；无 `resources/db/migration`；有 `Dockerfile`（可部署件）。实体 `GovernanceAuditLogEntry` 声明 `@Table(name="governance_audit_log")`，全仓 `.sql`（含 PG）**零命中**，源码自认：`GovernanceAuditLog.java:55`「全部 Flyway 迁移里零命中」、`:123`「无建表迁移」。**注**：`nexus-oracle/README.md` 首屏已自述「当前为**接口骨架**…以 TODO 标注待填充点」，符合 ADR-001「仅维护诚实声明」政策 ⇒ 降级为一致性问题 |
| C2 | **容器内构建在本机网络不可复现** | `npm ci` 打 `registry.npmjs.org` → `EIDLETIMEOUT`；daocloud 镜像 `TLS handshake timeout`。历史演练亦靠自备 `Dockerfile.java-drill + aliyun init.gradle` 绕过 |
| C3 | **gateway 默认下游地址 `http://localhost:8080`** | `nexus-gateway/application.yml:230`（consortium rpc-url）、`:382`（webhook callback）、`:408`（checkout base-url）。本机 8080 被他项目占用，实测网关每 30s 打外来服务的 404 |
| C4 | **测试只断言"事件发出了"，从不断言"有人处理"** | `SlaMonitorServiceTest` / `EscrowServiceImplTest` / `AlertEngineTest` 等大量 `verify(eventPublisher).publishEvent(any(XxxEvent.class))` ⇒ **发布者–消费者断链永远测不出来**。「覆盖率 100% ≠ 行为被钉住」的又一实例 |

---

## 五、被否证 / 降级的假设（同等重要）

| 原假设 | 判定 | 依据 |
|---|---|---|
| 测试信号不可信（skip 泛滥 / 恒真断言） | ✅ 否证 | `@Test`=8103、`@Disabled`=**1**（理由正当）、`@Ignore`=0、`assertTrue(true)`=0；25 处 `assumeTrue` 全为环境门控（Hardhat/Docker/zk-service/PRIVATE_KEY）。抽查 4 个"0 断言"文件全是启发式误报（ArchUnit `@ArchTest`、`@SpringBootTest` 上下文即断言） |
| 实体 ↔ 迁移大面积漂移 | ✅ 否证 | 实体 **128** vs MySQL 迁移表 **124**，基本对齐；settlement/analytics 的 5 张表全部在 gateway 迁移里 |
| 提现状态跨模块口径漂移 | ✅ 否证 | SDK 单一定义 9 值枚举；`WithdrawalRequestEntity:26` 自述「复用 SDK DTO」+`:63` 直接用同一枚举 + `@Enumerated(STRING)` ⇒ 加枚举值免迁移；`EXECUTING` 的 CAS 抢占修复在案 |
| nexus-core 22 个 `@Scheduled` 无 ShedLock = 缺陷 | ✅ 否证 | `PosMiningScheduler:132` 双守卫（`isPosMode()`+`isEnableMining()`）、`Miner:163` 有 `isEnableMining()`、`FinalityOutboxManager:156` 异常包 try/catch。这些是**节点本地态**（挖矿/内存池/UTXO/merkle/P2P），每实例各跑**才正确** |
| `MpcSignatureAggregator` 不验 ZK proof = P0 缺口 | ✅ 否证（已声明边界） | `ADR-001`（Resolved 2026-08-10）明文冻结研究层并要求「仅维护诚实声明」；类 javadoc 直言 TODO 并指向 ADR；引擎侧 `mpc-engine/src/cggmp.rs`/`cggmp_state.rs` 是**真实 CGGMP21**（Paillier aux_info 在案）⇒ 协议内生 ZK 在 Rust 侧；Java 侧对最终签名做 BouncyCastle 公钥验签 |
| 14 处 `new RestTemplate()` 无超时 = 缺陷 | ✅ 否证 | 逐文件核验：除 `RestTemplateConfig` 自身（`@Bean` 内，正当）与 `HttpPspConnector`（7 参构造器收共享 bean）外，其余全是显式标注「**测试用兼容构造器**」的测试缝，且每个文件都有 `@Autowired` 生产构造器注入共享 bean（`RestTemplateConfig` 提供 connect 5s / read 30s 池化实例） |
| Feign 无超时 | ✅ 否证 | gateway `application.yml:68-73`、signing `:212-218`、wallet `:170-176` 均 `connect-timeout: 3000` / `read-timeout: 10000` |
| `HttpOnChainExecutionClient` 无超时（提现执行线程永久挂起） | ✅ 否证 | 该文件显式 `factory.setConnectTimeout(5_000)` / `setReadTimeout(30_000)`，注释原文即该风险（2026-09-10 质量审查 Top4 修复） |
| `wechat/alipay.sandbox` 同键多默认值（7 类 4 种默认）= 缺陷 | ✅ 否证（降级潜伏） | base yml 已显式 `${NEX_PSP_WECHAT_SANDBOX:false}`（`:358`）与 `${NEX_PSP_ALIPAY_SANDBOX:false}`（`:369`）中和 |
| 签名审批默认不落库 `use-database:false` = 缺口 | ✅ 否证 | v2.38.0 已引入 `nexus.approval.store-type` 三态（memory/file/jpa）+ JPA + `@Version`，prod 显式 `NEX_APPROVAL_STORE_TYPE=jpa` ⇒ 已收口旧账 |
| prod 的 PG 迁移集"起不来" | ✅ 已闭历史 | 2026-10-12 对齐 MySQL 8；现 prod 全档 `ddl-auto: validate` + Flyway 权威 |
| 全栈演练 6 项发现（目录预建 / Nacos 装配 / Seata / Flyway / Tracer / init.sh） | ✅ 已修 | gateway/signing/wallet/bridge 的 Dockerfile 均有 `dir-builder`+`mkdir`+`chown 65532`；`bootstrap.yml` 已删除、nacos 块迁 `application.yml:21-27`；4 模块改用 `spring-boot-starter-flyway`；gateway/bridge 用 `productionRuntimeOnly 'spring-boot-starter-zipkin'`；signing/wallet/bridge 的 dev 档补齐 Seata `autoconfigure.exclude` |

---

## 六、方法学备忘（本会话实际踩到的假象，供后续审计复用）

同一个根因（Git Bash + CRLF + `git grep` 语义）在本会话造成 **7 次测量假象**：

1. `grep -oE '…`?…'` 中**反引号在双引号里被 shell 当命令替换** ⇒ 正则被吞 ⇒ 「配置键命中 0」。
2. `--include=*.yml` **未加引号被路径展开**成 `--include=docker-compose.yml` ⇒ 「yml 里一个键都没有」。
3. 根级 `grep -r .` 会扫进 `build/`（同键在 `nexus-gateway/build/resources/main/application.yml` 有副本）与 `node_modules` ⇒ 改用 `git grep`。**结论：本仓一律用 `git grep`。**
4. `git grep -h` 会去掉文件名，**使其后的 `grep -v '/src/test/'` 空转成 no-op** ⇒ 测试文件混入"生产"统计。
5. `comm` 比较两个列表时若一侧带 `\r`，**同一项会同时出现在两列**（实际发生）。⇒ 两侧都要 `tr -d '\r'`。
6. `tr -d '( '` 不去 CR，必须显式 `tr -d '\r'`。
7. 可复现脚本：`F:/tmp/nc-evt-orphans.py`（`python -I nc-evt-orphans.py <repo>`，计算 `ApplicationEvent` 传递闭包 + 发布/消费集合差）。

**另**：事件"孤儿"机械口径（23）**会高报**，已证明三种假阳性形态——① Spring 按可赋值类型派发，
子类事件被父类监听器消费（`AccountBalanceNegativeEvent extends AccountBalanceChangedEvent`）；
② 事件溯源记录 ≠ Spring 通知（`SigningCancelledEvent` 由 `SagaCoordinator:375` append 到事件存储）；
③ JPA 实体被误认为事件（`AlertEvent` 不 extends `ApplicationEvent`，走 `AlertNotifier` 三出口）。
⇒ 逐条核验后真孤儿收缩到 **3 个**（A4、B8、B9）。

---

## 七、追加发现（修复过程中新产生 / 被暴露，2026-11-07 当日）

以下 4 条**不在** §二~§四 的初始 19 条里。它们是按 §八 的顺序做修复时、靠"**动手前先证伪**"这条纪律
主动暴露出来的；记在这里是因为它们比原始发现更能说明**"接线即激活缺陷"**这一类风险。

### D1 🟠 `retryFailedRecords` 未按 `recordType` 收窄 —— 接线那一下就会变成真实动账缺陷

- 位置：`nexus-gateway/src/main/java/org/nexus/gateway/clearing/ClearingSettlementService.java`
  （原取集 `recordRepository.findByBatchNoAndBookingStatus(batchNo, FAILED)`）
- 事实：`clearing_settlement_record` 是**一表四型**（`CLEARING` / `SETTLEMENT_TRANSFER` /
  `RECON_ADJUST` / `SUSPENSE_WRITEOFF`）且**共享同一 batchNo**
  （`SettlementTransferService:118 record.setBatchNo(batchNo)` 用同一批次号落库）。
  原重试取集**不带类型过滤** ⇒ 会把同批次的划拨/调账/核销记录一并当清算入账调
  `AccountService#creditOnClearing`，两种坏结果：
  - 对 `direction=DEBIT` 的划拨记录 ⇒ **反向动账**（该扣的变成加）；
  - 对 `transferReference` 为空的行 ⇒ 幂等键退化为 `CLR_<batchNo>_<merchantId>`，
    与合法清算入账**同一个键** ⇒ 被幂等检查跳过却仍置 `BOOKED` ⇒ **假 BOOKED**。
- 为什么此前没爆：**该路径只有测试调用方**——也就是 A5 本身。做 A5 的接线动作，恰恰就是激活它的那一下。
- 处置：已在 PR #566 中**先收窄 `recordType=CLEARING`，再接定时重试**；并用断言钉住
  「无类型过滤的旧查询不得再被调用」。

### D2 🟠 `200 + null` 响应 ⇒ 三态全空 ⇒ 只剩 header 的空白页

- 位置：`ContractPage` / `AddressPage`（改写中丢过一次 `if (!data) setError(notFound)`，被本仓既有测试逮住）；
  以及 `BlockDetailPage` / `TxDetailPage`（**原本就没有**该分支）
- 事实：后端可能以 `200 + null` 表示"查无此资源"。若页面只在 `catch` 里置错误态，则
  `loading=false`、`error=null`、`data=null` ⇒ `DetailPageLayout` 既不渲染 loading 也不渲染 error
  ⇒ **只剩一个 header**（用户看到的是"半张空页"）。
- 处置：PR #568 四个详情页统一补 `setError(data ? null : t("xx.notFound"))`。

### D3 🟠 错误语义错配：把"后端没起来"报成"地址/合约不存在"

- 位置：`AddressPage` / `ContractPage` 原实现
  `api.getX(...).catch(err => { console.error(...); return null })` 之后再统一 `setError(t("xx.notFound"))`
- 事实：**所有**异常（网络不可达 / 超时 / 5xx）都被吞成 `null`，用户看到"未找到地址"
  ⇒ 会去怀疑自己输错了地址，而真实原因是服务不可达。属 §三 B5 同族，但比 B5 更重：
  **不是文案语言问题，是含义错了**。
- 处置：PR #568 按 `ApiError.status === 404` 分流，其余走 `displayErrorMessage`。

### D4 🟠 失败时 10 秒无限轮询、无退避

- 位置：`HomePage`（`setInterval(fetchData, 10000)`）
- 事实：进入错误态后仍每 10s 打 **3 个端点**（blocks / tx / status）持续施压，对用户毫无收益。
  `api/client.ts` 自己的注释已点过"10s 轮询会堆积请求"——**当时只补了 8s 超时，没补退避**。
- 处置：PR #568 进入错误态后退避到 60s，恢复正常后自动回到 10s。

---

## 八、建议修复顺序（按"级别 × 改动量"排）

1. **A3 签名面契约对齐** + 修正那条钉死漂移的测试（前端 `auth.ts` 一行 + 测试断言）
2. **A1 dev 档自洽**：dev profile 显式声明 `nexus.settlement.enabled`（或补 `platform-address` 占位），让 `clone → run` 成立
3. **A4 / B8 / B9 三个孤儿事件接线**：补消费者（或若确认不需要，删掉事件与发布点并在注释说明——**不留"看起来有告警"的假象**）
4. **A5 清算失败重试触发**：加 `@Scheduled` + ShedLock（照 `retryFailedSagas` 的样子）+ 失败告警
5. **B5 / B7 / B10 前端错误态收口**：统一 `ApiError` 之外一律走 i18n 文案；补页面级重试；补 `path="*"` 兜底路由
6. **A2 鉴权开关收口**：prod 显式置 false（或把默认翻转并在过渡期用 env 打开）
7. **C4 加一条"存在监听器"的架构断言**（ArchUnit 或上下文内查 `ApplicationListener`），防止同类断链复发
8. **B1 / B2 / B3 / B4 / B6**：按运维面逐个收口

---

## 九、修复落地进度（2026-11-07 当日）

| PR | 覆盖 | 验证 |
|---|---|---|
| #563 | A3 签名面对齐（含把钉死漂移的测试改正）+ A1 dev 档显式关闭结算 | `npm run check` exit 0（196 用例）；清空 env 后 `Started GatewayApplication in 70.877s` + `/actuator/health`=200 |
| #565 | A4 / B8 / B9 三个孤儿告警事件接线到既有告警出口（新增 `DomainAlertEventSink`，**不改既有类**） | `BUILD SUCCESSFUL`；XML 核对 `tests=7 skipped=0 failures=0 errors=0` |
| #566 | A5 清算失败自动重试 + **D1 收窄修复** | `BUILD SUCCESSFUL`；XML 核对 8+3 用例全过；本仓 `check-code-hygiene`（49→49 无新增）与 `check-scheduler-lock`（带锁）本地跑过 |
| #568 | B5 / B7 / B10 错误态收口 + **D2 / D3 / D4** | prettier / eslint / **vitest 20 文件 206 用例** / build / verify:build / verify:contrast(70 组) **全过**；`path="*"` 与错误态均**真浏览器实测** |
| #579 | 板面认领 §十 M1–M4 卫生项处置（查重零冲突，边界写清） | 前置查重：`git grep` 板面零他人认领；三文件在 `8667f138..31ee7696` 间无人改动 |
| #580 | M1–M3 就地标注（`FileUtil` 整类零调用方 + 接线警告 / `CopyrightUtil` 一次性工具禁调用 / MPC 脚本测试夹具凭据声明） | **+42 −0 纯注释 + 1 个 `@Deprecated`**；`compileJava` BUILD SUCCESSFUL；**`spotbugsMain` = 0 findings**；`bash -n` rc=0；卫生棘轮 49→49 |

**仍待处置**：A2（鉴权开关 prod 收口）、B1（replay-store 默认内存）、B2（3DS 模拟默认开）、
B3（compose 硬编码 container_name）、B4（0 回滚脚本）、C1（nexus-oracle 一致性）、C4（同族架构断言）。

### 勘误与后续（诚实记录）

1. **M1 订正**：§十 初版写「`recursiveDelete` 零调用方」，复核后是**整个 `FileUtil` 类**零调用方
   （`git grep -c 'FileUtil\.'` = 0 个文件，`recursiveList` 同样无使用者）。#580 的 javadoc 按订正后的口径写。
2. **审计基线 ≠ 当前 master**：#562 的审计基线是 `8667f138`；执行期间 `origin/master` 已前移到
   **`31ee7696`**（`git fetch origin master` rc=0，2026-11-08T01:34:39+08:00，
   `feat(devtools): 新增端到端验收脚本与 runbook (#575)`）。
   复核：`git diff --stat 8667f138..31ee7696 --` §十 M1–M3 涉及的三个文件 ⇒ **空输出**，未被他人改动。
3. **与板面新条目的边界**：`20261107-1120-nex-claim-delivery-readiness`（nex 认领「交付就绪工程」）
   明确自述「**不是再修一遍 bug**」，落点为 `.env.prod.example` / `docker-compose.prod.yml` /
   交付脚本（`check-prod-env.sh`、`smoke-dev-boot.sh`、`acceptance-e2e.sh`）/ `upgrade-rollback.md` / `slo.md`。
   与本报告的审计修复线**零文件交集**；其 D2 反而依赖本线 #563 合并（未合并则显式 SKIP）。
   ⇒ 本条**不与之冲突**，也**不代其认领**。
4. 因此 §十 的 M1–M4 处置**只做注释/注解级**（#580），**不做删除**：删除属破坏性 API 变更，
   且与 nex 的交付面存在交接关系（清理类变更宜在其交付验收跑通后再立单独立项）。
5. **A2 / B2 / B1（文档侧）已由 #588（`410496dc`）收口**（nexus-gateway 模块）：prod 显式
   `signature-legacy-enabled: false`；3DS `simulated-challenge-enabled` base 默认改 false、
   dev/sandbox 显式 true；B1 当时处置为 values-prod 注释化（默认值未动，理由为
   `RedisReplayNonceStore` fail-closed 权衡）+ helm 注入示例。
6. **2026-11-10 本批收口（审计 §九 剩余项）**：
   - **A2 补齐 nexus-api-gateway 侧**：新增 `nexus-api-gateway/src/main/resources/application-prod.yml`
     显式 `legacy-headers-enabled: false` / `signature-legacy-enabled: false`（该模块此前**无**
     prod profile 文件；旧 `X-Nexus-*` 头仓内零生产方——仅 `AuthenticationFilter` 自身与其测试引用，
     在途客户端 `demo/run-demo.js` / `WebConfig` / `WebhookController` / `DeveloperPortalService` /
     `PaymentEventListener` 均发 `X-NexusChain-*` 新头；反向测试 `AuthenticationFilterTest:389`
     断言 `=false` 时必须拒绝）。
   - **B1 收口**：`deploy/helm/values-prod.yaml` `nexus-gateway.extraEnv` 显式
     `NEXUS_REPLAY_STORE: "redis"`（本文件是多副本 prod 渲染源，replicaCount=3；fail-closed
     权衡与前置条件写入同段注释）。
   - **B4**：`consortium/application.yml` 「可回滚」自述订正为「受控、可审计」+ 真实回滚口径
     （本模块迁移集破坏性 DDL 实测 grep 零命中；PG 侧高危迁移回滚脚本
     `migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.rollback.sql` 1 个）。
   - **C1**：新增 `scripts/check-ddl-auto-gate.py`（property 行精确匹配 + 8 例自测 + allowed 集
     = `nexus-gateway/application-dev.yml` 与 `nexus-oracle/application.yml`），ci.yml 代码卫生段
     接线；变异探针（在途新模块置 `ddl-auto: update`）实测红、移除后复绿。
7. **本批后仍未处置**：B3（compose 硬编码 `container_name`）、C4（同族架构断言）。

---

## 十、Mimosa 深度扫描结果与逐条分诊

扫描：`scan-job-mw5qrscc-0e0eabce5bcc3285`（depth=deep）→
`scan-2026-11-07T01-48-14.904Z-6641a11e0a8b`，seal `sha256:d97d2086…`，
落盘 `C:\Users\winge\.mimosa\security-scans\project-a7d16cd6922957236e07fe01\`。

### 先看扫描器对自己的评价（这决定了结果能用来断言什么）

| 字段 | 值 |
|---|---|
| `runStatus` | **inconclusive** |
| `completeness` | **partial** |
| `verdictEffect` | **none** |
| `evidenceBoundary` | `static_only_no_runtime_execution` |
| `gaps` | 「部分分析阶段未能完整覆盖」 |
| source | 2889 文件全部解析（`truncated:false`、`readFailures:0`、`parseFailures:0`）⇒ **文件级覆盖完整** |
| threatModel | `entryPoints:22`、**`principals: 0`**、`authorizationSurfaces:15` |
| pathAnalysis | 1234 functions / **1617 callEdges**（对 2889 文件而言调用图很薄）、traces 4 |

**`principals: 0` 是关键**：扫描器没有建立身份模型（0 个主体/角色）。
这直接解释了 4 条 HIGH 业务逻辑候选为何全是 **inconclusive** ——
没有身份模型就**无法观察到**所有权/租户绑定，"未观察到 guard"只等于"没看到"，不等于"没有"。
其 proof gaps 也自认「尚未展开下游 service/repository 的实现」「object-id 流向追踪被截断」。

### 37 条发现逐组分诊（每条都读到代码为止）

| 组 | 条数 | 判定 | 代码依据 |
|---|---|---|---|
| 硬编码凭据 | 6 | **误报** | 4 个 `HEADER_API_KEY` / `LEGACY_HEADER_API_KEY` / `auth.ts API_KEY` / k6 `HEADER_API_KEY` 的值全是 **HTTP 头名** `"X-NexusChain-ApiKey"` / `"X-Nexus-Api-Key"`，注释原文即「API Key **请求头名**」；k6 脚本的真实凭据来自 `__ENV.API_KEY`。扫描器按变量名 `*API_KEY*` 判定，未看值 |
| 过时 TLS | 2 | **误报（且方向相反）** | `MutualTlsContext:79-81` 是 `SSLContext.getInstance("TLSv1.3")` **优先**、仅 `NoSuchAlgorithmException` 时退 `TLSv1.2` —— 这是明确的现代配置；把 `"TLSv1.2"` 字面量读成了"允许过时版本" |
| 不安全反序列化 | 1 | **误报（已加固）** | `SerializableUtil:185-193` 已有 `MAX_DESERIALIZED_SIZE` 上限 + **`ois.setObjectInputFilter(DESERIALIZATION_FILTER)`** 白名单；方法注释自标「**不推荐使用**，保留仅为兼容未迁移的调用方」 |
| 弱加密算法 | 3 | **误报** | `user-tracker/index.js:9` 与 `:132` 命中的字面量其实是 **RIPEMD160**（`crypto.createHash('ripemd160')`）——**公钥→地址派生的标准环节**（比特币/以太坊同构），不用于完整性保护；第 3 条在 `demo/` 内 |
| 加密强度不足 | 1 | **误报** | `SandboxCallbackCertInitializer` 是 `@Profile("sandbox")`（沙盒专用，prod 不加载），且 `RSA_KEY_SIZE = 2048`（标准强度） |
| 反射加载类 | 1 | **误报** | `FastByteComparisons:78` 的 `Class.forName(UNSAFE_COMPARER_NAME)` 类名是**编译期常量**、非外部输入，属该工具（ES/BitcoinJ 同名实现）的标准写法 |
| 路径穿越 | 13 | **误报（操作者即信任边界）** | 8 条在 `scripts/*.py`（本仓 CI 自用）、3 条在 `nexus-devtools/src/cli.ts`（开发者 CLI，路径来自操作者自己的参数）、1 条 `CopyrightUtil:74`、1 条 `FileUtil:70` —— **全部不被任何服务端代码调用**。其中 `FileUtil.recursiveDelete` 经核**零调用方**（除自递归），`CopyrightUtil` 唯一调用点指向作者本机的硬编码绝对路径 |
| SSRF | 5 | **误报** | `demo/run-demo.js ×2`（演示脚本）；`nexus-sdk/python/{client.py:81,wallet.py:83}` 是**客户端 SDK**——URL 由 SDK 调用方自己配置，**调用方即信任边界**（任何 HTTP 客户端库都如此），且端点形状对齐 `nexus-core JsonRpcController`；`scripts/check-license-policy.py:170` 为 CI 取仓库自身 URL |
| 跨文件污点 SSRF | 1 | **误报** | `cmd-monitor/send-service-alert.js:6` 的端点是**硬编码常量** `https://dysmsapi.aliyuncs.com`（阿里云短信），**不是 env 派生 URL**；env/参数喂的是 AccessKey（运维脚本常态） |
| 业务逻辑 IDOR/BOLA 候选 | 4 | **覆盖边界产物（inconclusive）** | 4 条全在**公开只读面**：`demo/server.js:135/141` 与 `nexus-explorer/backend/src/index.ts:151/206`（`/api/tx/:hash`、`/api/account/:address`、`/api/contracts/:addr`）。这些是**区块浏览器的公开链上数据**（`AccountInfo` 只有 `address/balance/txCount`）——链上数据**没有所有权概念**，且该 BFF 本就无鉴权、靠 CORS 白名单收敛（见 `nexus-explorer/backend/src/index.ts:58-79`）。叠加 `principals: 0`，此处"未观察到绑定"无判别力 |

⇒ **37 条中 0 条可确证为可利用缺口**（与我在同类项目的经验一致：这类扫描在本仓的命中率主要是**模式匹配误报**）。

### 但分诊出 4 条「低优先清理/加固项」（不进 §二~§四，属卫生）

| # | 项 | 依据 |
|---|---|---|
| M1 | `FileUtil.recursiveDelete` 是**零调用方**的递归删除工具（带 `setWritable(true)` 后删除），留着是future footgun | `git grep recursiveDelete` 仅命中定义与自递归 |
| M2 | `CopyrightUtil` 是残留开发工具：唯一调用点写死作者机器路径 `C:\Users\Administrator\IdeaProjects\...\Start.java` | `CopyrightUtil:50` |
| M3 | `mpc-engine/scripts/start-mpc-cluster.sh:286` 硬编码 `MPC_AUTH_TOKEN="nexus-mpc-test-token"`。值本身自述 test、脚本监听 `127.0.0.1`、且同段设 `MPC_REQUIRE_TLS=true` ⇒ 属**测试夹具**，但建议在脚本头显式声明「仅测试环境，禁止用于生产」，防误用 | 该脚本 280-292 行 |
| M4 | `SerializableUtil` 仍保留 Java 原生反序列化路径（虽已白名单 + 大小限制 + 标记 deprecated） | 见上表 |

### 依赖面（与既有门禁的关系）

扫描报告的依赖段为离线公告库命中 3 包 / 6 条 advisory（未在 `findings.json` 内附明细）。
**本仓在依赖面已有更强且钉版本的门禁**：`Trivy Filesystem Scan + SBOM`、`OWASP Dependency-Check`、
`Cargo Audit`（两条）、`依赖许可证策略` 均为 CI 必过检查。⇒ 依赖结论**以既有门禁为准**，本扫描的该段不作为增量证据。

### 边界声明（不得越界解读）

- 本扫描 `verdictEffect: none`、`runStatus: inconclusive`、`evidenceBoundary: static_only_no_runtime_execution`
  ⇒ **本文不据此宣称项目安全**，也不宣称"无漏洞"；只能说"在静态视角下、对上述 37 条命中逐条读码后未发现可利用项"。
- `principals: 0` 与 `callEdges: 1617`（对 2889 文件）表明身份模型与调用图都不完整
  ⇒ 业务逻辑类与跨模块数据流类结论**天然不完整**，若要覆盖该面需补充**运行时**跨主体访问验证（本轮未做）。
- 未做：动态/运行时验证、渗透测试、真实 L1/L2 链交互、MPC 密码学正确性证明、性能压测。

---

## 十一、边界声明

- **审计结论部分未经修改产品代码即完成**；所有结论均给出 `文件:行号` 或可执行命令。
  修复在独立 PR 中进行（§九：本报告自身所在的 PR 只加文档）。
- 未做：真实 L1/L2 链交互、MPC 密码学正确性验证、性能压测、K8s 集群部署复现、Mimosa 深度安全扫描
  （后两者属独立工作项，本报告不以其为结论依据）。
- 审计环境限制（已排除出结论）：本次使用的 in-app 浏览器**无法访问宿主机的非本页源端口**，
  故页面上出现的 `Failed to fetch` 属审计环境限制，**不作为产品缺陷**；本文所有前端结论均由
  源码与渲染结果独立可证。
- `nexus-oracle` 等 ADR-001 冻结模块的判定**只针对一致性**，不对其功能完备性提要求。
