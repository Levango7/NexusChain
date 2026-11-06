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

- 真发现 **19 条**：🔴 5 · 🟠 10 · 🟡 4
- 被否证 / 降级 **12 条**（含审计方自身前两轮的 2 处误判）
- 被否证那一栏与发现栏同等重要：**它决定了修复预算不浪费**。

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

## 七、建议修复顺序（按"级别 × 改动量"排）

1. **A3 签名面契约对齐** + 修正那条钉死漂移的测试（前端 `auth.ts` 一行 + 测试断言）
2. **A1 dev 档自洽**：dev profile 显式声明 `nexus.settlement.enabled`（或补 `platform-address` 占位），让 `clone → run` 成立
3. **A4 / B8 / B9 三个孤儿事件接线**：补消费者（或若确认不需要，删掉事件与发布点并在注释说明——**不留"看起来有告警"的假象**）
4. **A5 清算失败重试触发**：加 `@Scheduled` + ShedLock（照 `retryFailedSagas` 的样子）+ 失败告警
5. **B5 / B7 / B10 前端错误态收口**：统一 `ApiError` 之外一律走 i18n 文案；补页面级重试；补 `path="*"` 兜底路由
6. **A2 鉴权开关收口**：prod 显式置 false（或把默认翻转并在过渡期用 env 打开）
7. **C4 加一条"存在监听器"的架构断言**（ArchUnit 或上下文内查 `ApplicationListener`），防止同类断链复发
8. **B1 / B2 / B3 / B4 / B6**：按运维面逐个收口

---

## 八、边界声明

- 本报告**未修改任何产品代码**；所有结论均给出 `文件:行号` 或可执行命令。
- 未做：真实 L1/L2 链交互、MPC 密码学正确性验证、性能压测、K8s 集群部署复现、Mimosa 深度安全扫描
  （后两者属独立工作项，本报告不以其为结论依据）。
- 审计环境限制（已排除出结论）：本次使用的 in-app 浏览器**无法访问宿主机的非本页源端口**，
  故页面上出现的 `Failed to fetch` 属审计环境限制，**不作为产品缺陷**；本文所有前端结论均由
  源码与渲染结果独立可证。
- `nexus-oracle` 等 ADR-001 冻结模块的判定**只针对一致性**，不对其功能完备性提要求。
