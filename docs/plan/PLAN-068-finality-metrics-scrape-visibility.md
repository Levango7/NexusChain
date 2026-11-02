# 方案 PLAN-068：最终性拒绝指标的抓取端可见性

- **状态**：已交付（2026-11-02）
- **前置**：PLAN-066（拒绝计数与告警，#400）、PLAN-067（9 个 reason 闭合词表，#407）、
  PLAN-058/062/063（"指标装了但没人看"同型缺陷的门禁）、PLAN-064（promtool 演练）
- **范围**：真实进程 + 真实 PostgreSQL 的抓取端实证、三个新 reason 在抓取文本中可见
  的回归门禁、告警 `reason` 标签值与枚举的契约门禁、抓取可达性的配置侧门禁
- **不包含**：真实 Prometheus/Alertmanager 集群、物理多机、真实网络分区、
  吞吐/基数基准、Grafana 看板、wallet/settlement lane

---

## 一、问题（为什么必须做）

PLAN-066 与 PLAN-067 的交付文档都各自登记了一条遗留项：

> 未做 `/actuator/prometheus` 抓取端补验

这不是"少写个测试"，而是**整条可观测链路从未被端到端验证过**：

| 事实 | 位置（交付前） |
|---|---|
| 端点声明已暴露 | `application.properties:292` `management.endpoints.web.exposure.include=health,prometheus` |
| 抓取方已按此路径配置 | `deploy/helm/charts/nexus-core/templates/servicemonitor.yaml:33`、`deploy/k8s/40-monitoring.yml:209` |
| 鉴权拦截器挂在 `/**` | `WebSecurityConfig.java:68-69`；白名单 `LOCAL_ONLY_PUBLIC_PATHS`（L45-59）**不含任何 `/actuator/*`** |
| `nexus.localonly` 默认 true | `application.properties:151`；缺 token 显式回 401（`LocalRpcInterceptor.java:57-75`） |
| 全仓 47 处 `actuator/prometheus`，`src/test` 仅 2 处**注释** | 实测 grep；**零条断言**"抓取返回 200 且含目标序列" |

两类结果都有意义，而且必须先实证再决定改不改：

- 若拦截器**生效** ⇒ 抓取恒 401 ⇒ 20 条告警永远不会触发，
  属于 PLAN-058/062「门禁从未接线」的同型缺陷，必须改白名单；
- 若拦截器**不生效** ⇒ 可达性 OK，切片收敛为"标签值在真实抓取文本中可见"的回归测试。

**本切片的第一步就是回答这个问题，不预设结论。**

## 二、实证（运行时，先做，后写代码）

### 2.1 环境边界（重要）

- WSL2 Ubuntu-24.04 上的 **PostgreSQL 16.15**，库 `nexus_p068`；
- nexus-core 以**单进程**在 Windows 侧 JDK 17.0.20 上 `:nexus-core:nexus-core:run`
  原生启动，`server.port=19585`，`nexus.localonly` 取默认值 `true`；
- **Windows 原生 PostgreSQL 不可用**：forked backend 首个连接即 `0xC0000142` 崩溃
  （既有记录），所以本切片一律走 WSL2 PG。这是**同内核单机**，不构成分布式结论。

### 2.2 可达性结论（实测，非推断）

不带任何 token 头、直接 `GET`：

| 路径 | 状态码 | 说明 |
|---|---|---|
| `/actuator/prometheus` | **200** | 抓取可达，响应体约 30 KB |
| `/actuator/health` | 200 | 但不被任何探针使用（见 §2.4） |
| `/height` | 200 | 白名单内，探针路径 |
| `/cheackTxlist?message=x` | **401** | 普通业务端点——**拦截器确实在工作** |
| `/nodeInfo` | **401** | 同上 |

⇒ 两条事实同时成立：**actuator 抓取可达**，且**鉴权没有被关掉**。
"200" 是 actuator 路由拓扑的固有属性，不是把 `localonly` 关掉换来的。

### 2.3 静态证据（与实测一致）

- Spring Boot **4.0.8** 的 `WebMvcEndpointManagementContextConfiguration`
  构造 `WebMvcEndpointHandlerMapping` 时**不调用** `setInterceptors`
  （`javap -p -c` 全量核对委托调用）；
- `WebMvcEndpointHandlerMapping.class` 与
  `AbstractWebMvcEndpointHandlerMapping.class` 字节码中**不含** `Interceptor` 字符串；
- nexus-core **没有 Spring Security**：`build.gradle:205` 只有 `spring-boot-starter-web`，
  全模块 0 处 `SecurityFilterChain | EnableWebSecurity | HttpSecurity`。

⇒ `LocalRpcInterceptor`（`HandlerInterceptor`，由 `WebMvcConfigurer` 注册）只作用于
`RequestMappingHandlerMapping` 解析出的 handler，够不到 actuator 的独立 HandlerMapping。

### 2.4 探针路径（决定 `/actuator/health` 是否要进白名单）

| 部署形态 | 文件:行 | 路径 |
|---|---|---|
| Helm nexus-core | `templates/statefulset.yaml:136,140` | `/height` (19585) |
| k8s 原生 | `deploy/k8s/20-core-statefulset.yml:126,132` | `/height` (19585) |
| docker-compose | `docker-compose.yml:104` | `localhost:19585/height` |
| docker-compose.prod | `docker-compose.prod.yml:85` | `localhost:19585/height` |

**nexus-core 的 liveness / readiness / healthcheck 全部打 `/height`**（已在白名单），
`/actuator/health` 不被任何探针使用 ⇒ 本切片**不改**它。

### 2.5 标签可见性（真实 PG，不是合成 registry）

向库里插入一条**3 字节检查点哈希**的脏行（模拟历史污染），等一个收敛周期
（`@Scheduled(fixedDelay = 5_000L)`）后抓取：

```
nexus_finality_vote_rejected_total{phase="convergence",reason="checkpoint_hash_invalid"} 1.0
```

该序列此前在抓取文本中**完全不存在**（`# HELP`/`# TYPE` 也没有），
说明：
1. actuator 抓取端确实把 Micrometer 注册的 counter 转成了 Prometheus 文本；
2. PLAN-067 的 `checkpoint_hash_invalid` 标签值**逐字出现在真实抓取文本里**；
3. `phase="convergence"` 与 `phase="admission"` 是两个独立序列（告警可按路径区分
   "库里躺着坏行"与"正在被攻击"）。

## 三、设计与取舍

### 3.1 不改白名单（这是结论，不是偷懒）

实证表明**不需要**改 `LOCAL_ONLY_PUBLIC_PATHS`：

- 加 `/actuator/prometheus` 进去是**无效修复**——拦截器本来就没作用于 actuator，
  加了只是让白名单多一条无人使用的条目，反而暗示"这里需要鉴权豁免"；
- 更糟的是它会把**错误的心智模型**固化进代码：后人看到白名单里有 actuator
  路径，会以为"actuator 是被拦截器管的、只是被豁免了"，从而在真的需要鉴权时改错地方。

所以本切片交付的是**证据 + 门禁**，不是白名单改动。

### 3.2 门禁一：真实 HTTP 抓取文本断言（`FinalityMetricsScrapeGateTest`）

为什么不是"直接查 `SimpleMeterRegistry`"：`FinalityRejectionMetricsTest` 已经查过了，
但那只证明**Micrometer 内部状态**正确。真正会失败的地方在两者之间：
端点是否暴露、路径是否被鉴权挡住、`_total` 后缀与标签顺序、文本格式。

实现要点：

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + 真实内嵌 Tomcat —— 发的是**真 HTTP**；
- 用 `@Import({WebSecurityConfig, LocalRpcInterceptor, RpcInterceptor, ...})` 精确装配
  三块（actuator 自动配置 + 拦截器 + 指标适配器），**不组件扫描整个 `org.nexus`**
  ——那会把 PG/LevelDB/P2P/genesis 拖进上下文，测试变成集成环境；
- 用 JDK `HttpClient` 而不是 `TestRestTemplate`：Boot 4 把后者迁到了
  `org.springframework.boot.resttestclient` 且不在 starter-test 传递依赖里；
  更重要的是原始 HTTP 能保证"不带 token 头"是**字面事实**（无隐式加头/重试/重定向）；
- 配置值**刻意沿用生产 `application.properties`**（只覆盖数据源），避免
  "测试配置绿、生产配置红"——`management.endpoints.web.exposure.include` 不覆盖。

三条断言（都作用在抓取文本上）：

1. 序列 `nexus_finality_vote_rejected_total{phase="admission",reason="checkpoint_hash_invalid"}`
   存在且计数 = 1；
2. 同上 `checkpoint_limit_exceeded`、`payload_too_large` 各 = 1；
3. `# TYPE nexus_finality_vote_rejected_total counter` 存在（否则 PromQL 的
   `increase()` 语义会变）。

**外加一条对照断言**：同进程 `/p068-probe-guarded` 必须返回 **401**。
没有这条，用例只能证明"抓取能通"，无法排除"有人把 `localonly` 关了"。

三个 reason 的触发路径都用**真实判定路径**，不是直接调 counter：

| reason | 触发方式 |
|---|---|
| `checkpoint_hash_invalid@admission` | `gadget.submitVote(...)` 一张 3 字节哈希的票（在注册表/验签之前被挡） |
| `checkpoint_limit_exceeded@admission` | 同一验证人同 epoch 的第 5 个新键（K=4 上限） |
| `payload_too_large@admission` | `broadcaster.onVoteReceived()` 一帧真实编码后 > 2048 字节的载荷 |

### 3.3 门禁二：告警 `reason` 标签值契约（`FinalityRejectionLabelContractTest`）

`FinalityRulesContractTest` 断的是"规则 `expr` 引用的**指标名**在 registry 里存在"，
它**不看标签值**：

```
increase(nexus_finality_vote_rejected_total{reason=~"a|b|c"}[10m]) > 0
            ↑ 名字对得上              ↑ 这里写错一个字母也全绿
```

标签值写错的后果与名字写错一样致命：序列存在、指标在涨，但 `reason=~...` 一条都
匹配不上 ⇒ 告警永远不触发。PLAN-066 把 6 个 reason 扩到 9 个、PLAN-067 又加 3 个，
每次都在改这个正则——而此前**没有任何断言**把正则和枚举绑在一起。

本测试只读 YAML、不起 Spring 上下文（毫秒级），钉三件事：

1. 规则里 `reason=~` 的**每个取值**都必须是真实枚举值（拼错即红）；
2. PLAN-067 的三个新 reason **必须真的被某条告警引用**（不允许"只进指标、永不告警"）；
3. 规则里 `phase=~` 的取值（若使用）必须是真实 `Phase`。

### 3.4 门禁三：配置侧前提（`productionConfigStillExposesPrometheus`）

即便抓取路由通，若有人把 `management.endpoints.web.exposure.include` 收缩掉
（或给 actuator 单独配 `management.server.port`），ServiceMonitor 就会抓到 404 /
连不上。这条断言直接读**生产 `application.properties`**：

- `include` 必须含 `prometheus`；
- `server.port` 必须仍是 19585（actuator 与 RPC 同端口，ServiceMonitor 用 `port: rpc`）；
- `management.server.port` **不得**出现（一旦分离，ServiceMonitor 与网络策略需同步改）。

## 四、验证

### 4.1 定向与全量

| 项目 | 结果 |
|---|---|
| `FinalityMetricsScrapeGateTest` | 2 用例 / 0 失败（真实 Tomcat + 真实 HTTP） |
| `FinalityRejectionLabelContractTest` | 4 用例 / 0 失败 |
| `org.nexus.metrics.*` + `org.nexus.command.*` + `org.nexus.consensus.finality.*` | 全绿 |
| `nexus-core` 全量 `--rerun-tasks` | **220 个测试类 / 1845 用例 / 0 失败 / 0 错误 / 19 跳过**（19 跳过为仓库既有） |
| Code Hygiene（`scripts/check-code-hygiene.py`） | 49 = 基线 49，无新增 |

### 4.2 反向对照（定点变异 → 跑用例 → 还原 → 核对 SHA-256）

脚本：`C:\Users\winge\AppData\Local\Temp\opencode\mutate-p068.py`
（仓库外，不入库）。备份目录同目录下 `p068-reverse/`。

| 组 | 变异 | 预期 | 实测 |
|---|---|---|---|
| A′ | 把 `excludePathPatterns(...)` 去掉，让拦截器**连 actuator 也拦** | 抓取变 401 | **仍绿（BUILD SUCCESSFUL）** |
| B | 抹掉 `FinalityRejectionMetrics.countVoteRejected` 的 `counter.increment()` | 3 条序列断言红 | **1 用例红（`FinalityMetricsScrapeGateTest:216`）** |
| C | 生产配置去掉 `prometheus` 暴露 | 配置断言 + 抓取断言同时红 | **2 用例红（:175 与 :150）** |
| D | 把对照端点 `/p068-probe-guarded` 加进白名单 | 401 对照断言红 | **1 用例红（:161）** |
| E | 规则里把 `checkpoint_hash_invalid` 拼成 `checkpoint_hash_invald` | 3 条标签断言红 | **3 用例红（:69 / :83 / :114）** |

还原后 4 个文件的 SHA-256 与变异前逐字一致：

```
websecurity  79bae6b8107555c6ead6b93f4f554852c772ac2f5b1352ba769bb9affcd9cb68
metrics      3caf6870252b8f03307b024178ea204a14f770f34bbb1192743e3504be773ea1
props        e355d7a136ce47a3d09b35b4360de15ba624bf6bb6c6b7b4741bde4d930b5df9
rules        284c346fab1b077280a668b602404075efa59d69e1264ea8ea5239190caed399
```

还原后 `--rerun-tasks` 重跑，2 用例 / 4 用例全绿。

#### A′ 为什么"仍绿"是有价值的结果

A′ 是把拦截器挂成"连 actuator 也拦"，按理应该让抓取变 401、断言变红。实测**没变**。
这不是用例失效，而是**再次独立证实了 §2.3 的结论**：`WebMvcConfigurer` 注册的
`HandlerInterceptor` **根本不参与** actuator 的独立 `HandlerMapping`——即便你在
`WebSecurityConfig` 里写成 `addPathPatterns("/**")` 且不排除任何路径，也拦不到
`/actuator/**`。

所以这条变异**不能**作为"我能抓住 actuator 401"的对照（那种变异在配置层做不到），
它证明的是另一件事：**想靠改 `WebSecurityConfig` 来给 actuator 加鉴权是无效的**，
要拦 actuator 必须用 `EndpointFilter` / Spring Security / 独立管理端口。
这一点对后续"要不要给 `/actuator/*` 加保护"的决策很关键，故如实记录。

真正的可达性对照由 **C**（配置侧关掉暴露）与 **D**（鉴权对照端点被放行）承担：
它们分别精确命中了"端点不存在"和"鉴权被绕过"两种会让本门禁失效的方式。

## 五、边界（勿引用为更强结论）

1. **同内核单机**：WSL2 里的 PostgreSQL 与 Windows 侧的 nexus-core 同属一台物理机，
   **不是物理多机**，不构成分布式/集群结论；
2. **未起真实 Prometheus / Alertmanager**：只证"抓取文本里序列存在且计数正确"，
   **不证**推送/路由/通知链路，也**未在真实集群触发过告警**；
3. **无 token 抓取 200 是 Boot 4 actuator 的路由拓扑事实**，不是本切片引入的暴露面，
   也不是"鉴权已完善"的证明——actuator 端点目前**没有鉴权**（若未来需要，见 §4.2 A′
   的结论：必须另选机制）；
4. **未做吞吐/基数基准**：只验标签值可见，不验高基数下的抓取开销；
5. **合成拒绝**：三个 reason 的触发是测试夹具，不代表真实发生过攻击；
6. **`checkpoint_limit_exceeded` 的触发**用 K=4 上限的真实判定路径，但
   `checkpoint_hash_invalid` 的运行时实证走的是**收敛路径**（库里预置脏行），
   不是 P2P 攻击帧；两条路径的计数语义已由 PLAN-067 的单测分别覆盖；
7. 未触碰真实 L1 / 桥资金 / wallet / settlement lane，未修改他人分支/PR。

## 六、后续（roadmap #27 剩余）

- epoch 窗口 / 规范检查点语义（阻塞于链接口）；
- 历史脏行清理（迁移切片，需可回滚 + 审计日志）；
- 真实集群告警演练（Alertmanager 路由与通知，需真实集群）；
- 吞吐/基数基准（高基数下 `phase × reason` 序列的抓取开销）；
- 物理多机 / 真实网络分区矩阵；
- **若需给 actuator 加鉴权**：必须用 `EndpointFilter` / Spring Security /
  独立 `management.server.port`，改 `WebSecurityConfig` 无效（§4.2 A′ 实证）。
