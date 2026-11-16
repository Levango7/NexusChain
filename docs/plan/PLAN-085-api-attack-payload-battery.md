# 方案 PLAN-085：API 层真实攻击载荷电池（B-13 本机子集）

- **状态**：已交付（2026-11-16，zcode）；含**产品代码修复**（4 个异常处理器 + 1 处字段级严格反序列化 + 1 个日志中和工具方法）
- **来源**：`docs/audit/verification-gap-roi-2026-11-14.md` B-13「真实攻击形态未演练（合成拒绝≠真实攻击）」，T1 本机子集
- **性质**：验证切片 + 缺陷修复（修复由本切片实测暴露，非预设）

---

## 一、问题

既有 negative test 打的多是「缺字段 / 签名错 / 未认证」这类**合成拒绝**——它们证明「门在」，
但不证明「拿着合法凭证、把真实攻击载荷塞进字段里」时服务端行为正确。B-13 要求的是后者。

## 二、判据预登记（三条不变量）

固定一张攻击载荷表，逐条打到**真链路**
（`ApiKeyInterceptor → RequestSignatureInterceptor → PaymentController → MerchantOwnershipGuard → OrderService`），
对每条载荷断言：

1. **绝不 5xx**：形态错误必须是 4xx，不得是 500；
2. **不泄堆栈**：响应体不得含 `Caused by` / `at org.nexus.` / `\tat ` 等运行时痕迹；
3. **结构性非法不得到达业务服务**：被拒请求不得调用 `OrderService.createOrder`（防畸形数据透传下游）。

外加一条**对照**判据：**请求必须真能到控制器**——否则整套不变量测的是拦截器而非控制器。

## 三、实测结果（`ApiAttackPayloadBatteryIT`，15 例）

交付物：`nexus-gateway/src/test/java/org/nexus/gateway/security/ApiAttackPayloadBatteryIT.java`
（`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("sandbox")`，H2；只 mock 商户查询与订单服务）。
CI 归属：`@Tag("integration")` ⇒ 由 `:nexus-gateway:integrationTest` 跑，CI「Gateway 集成测试」步**阻断执行**。

| 组 | 载荷 | 修复前 | 修复后 |
|---|---|---|---|
| 基线 | 合法请求 | — | 201（且 body 里的 `merchantId` 被服务端按认证身份覆盖） |
| SQL 注入 | `' OR 1=1--` / `'; DROP TABLE …` / `1' UNION SELECT …` / `" OR ""="` / `admin'--`（打 `description`） | 201（原样落库，未见 500） | 201（不变量全过） |
| 日志伪造/CRLF | `\r\n` 伪造告警行 / `\n\nFAKE-STACKTRACE at org.nexus.Fake` / `%0d%0aInjected-Header` / NUL+BEL+ESC | 201 | 201（不变量全过） |
| 类型混淆（amount） | 数组 / 对象 / 布尔 / null / 嵌套数组 / `"0x10"` | **500** | **400** |
| 类型混淆（expiryMinutes） | `"abc"` / `3.5` / `2147483648` / `-1` / `{}` | **500**（且 `3.5`、`-1` 是 **201 静默接受**） | **400** |
| 数值边界 | `amount` = 0 / -1 / -1000000 / 0.0000000001 | **500** | **400** |
| 超大载荷 | `description` 256 KB（超 `@Size(256)`） | **500** | **400** |
| 畸形 JSON | 截断 / `null` / 顶层数组 / 空体 / 纯空白 / 重复键 | **500** | **400** |
| 伪造身份 | body 写 `merchantId=999` | 201 且被覆盖（正确） | 201 且被覆盖（钉住） |
| 重放（**签名正确**） | 同 nonce+签名原样重发第二次 | 401 + `40106 Replayed nonce` | 同左（钉住） |
| 对照 | 无 API Key / 未登记 key / 带注入串的 key | 401（interceptor 侧） | 同左 |
| 记录项 | header 值含 CRLF | 500 且兜底不外泄（MockMvc 可达形态；真实 Tomcat 在协议层更早拒） | 同左（如实记录，不断言必须 4xx） |

## 四、发现与处置（6 项）

### 4.1 已修：输入错误的 HTTP 契约整体失灵（1 处缺陷、多种表现）

**现象**：`POST /api/v1/orders` 传 `amount=0`（违反 `@Min(1)`）返回
`500 {"code":50000,"message":"Internal server error"}`。**Bean Validation 失败、畸形 JSON、
JSON 类型混淆、数值越界、超大载荷——全部落 500。** 调用方无法区分「我传错了」与
「服务端炸了」，监控与告警也会误判（把客户端错误报成服务端故障）。

**根因**：`GlobalExceptionHandler` 的 handler 集合里缺
`MethodArgumentNotValidException`（Bean Validation）与 `HttpMessageNotReadableException`
（报文不可读），于是这些**形态错误**穿过所有具体处理器、落到 `Exception` 兜底。
**同源缺陷在本文件里已被修过两次**——405（`HttpRequestMethodNotSupportedException`）与
403（`AccessDeniedException`）都留着「否则被兜底报成 500」的注释；本次把同一范式的
**400/415 一组**补齐：

| 新增处理器 | 触发形态 | 结果 |
|---|---|---|
| `MethodArgumentNotValidException` | `@Valid` 校验不过（`amount=0/-1`、`description` 超长…） | 400 `INVALID_PARAMS(40001)` |
| `HttpMessageNotReadableException` | 畸形 JSON / 类型混淆 / 数字越界 | 400 同上 |
| `MissingServletRequestParameterException` | 缺必需查询参数 | 400 同上 |
| `HttpMediaTypeNotSupportedException` | 不支持的 Content-Type | 415 同上 |

**响应只回通用文案**：Spring 默认异常消息里含**被拒的字段值**（攻击者输入的片段），
不回显——明细只写日志。

### 4.2 已修：日志伪造（log forging）面

`IllegalArgumentException` 等分支把 `e.getMessage()`（**可能含请求体片段**）直接写日志。
攻击者用 `\r\n` 就能在日志/告警检索里**伪造出新的告警行**。新增
`LogSanitizer.stripControlChars(String)`：CR/LF/TAB 转可见转义、其余 C0 与 DEL 转 `\uXXXX`、
超长明细截断到 512 字符并标 `…[truncated]`（防超大载荷灌爆日志行）。
新 handler 的日志全部经它过滤；4 例回归进 `LogSanitizerTest`。

### 4.3 已修：`expiryMinutes` 小数值被静默截断成整数

实测 `{"expiryMinutes": 3.5}` 返回 **201**，实际按 `3` 分钟算过期——**静默数据丢失**。
修法：字段级 `StrictIntegerDeserializer`（只堵**有损**路径：`3`、`3.0`、`"3"` 仍接受，
`3.5` 一律 400）+ `@Min(1)`（0/负数会生成「出生即过期」订单）。

> **⚠️ 工具坑（本轮最有价值的环境发现）**：本仓是 **Spring Boot 4.0**，HTTP 报文转换由
> **Jackson 3（`tools.jackson`）** 执行；第一版严格反序列化器按 **Jackson 2**
> （`com.fasterxml.jackson`）的 `@JsonDeserialize` + `JsonDeserializer` 写，
> **编译通过、运行时被完全忽略**（`3.5` 仍拿 201）。仓里 Jackson 2 的 `ObjectMapper`
> bean 只是遗留组件兼容件（`JacksonConfig`）。改用 Jackson 3 API
> （`tools.jackson.databind.annotation.JsonDeserialize` + `ValueDeserializer<T>` +
> `exc.InvalidFormatException.from(...)`）后才生效。
> **判据教训：Jackson 定制不能只看编译过，必须实跑一条会产生该形态的请求并断言状态码。**

### 4.4 已钉住（有意宽松）：`amount` 传数字字符串

实测 `{"amount": "100"}` 返回 201 并被解析为等值十进制 100。**判定为有意接受的宽松**：
BigDecimal 的十进制字符串解析无精度歧义，且许多 SDK 刻意用字符串传金额（避免浮点）。
用**特征化测试**钉住（`documentedLeniency_amountAsNumericString`：断言 201 + 捕获值 `compareTo(100)==0`）
⇒ 将来谁收紧标量强转，这条测试会红，**强制同步改文档与测试**。

### 4.5 未改（登记为契约决策）：其它标量强转与上界策略

- 其它字段的标量强转（如整型字段收 `1.0`）**未收紧**——全局改
  `ACCEPT_FLOAT_AS_INT=false` 是网关全字段口径变更，可能影响既有调用方；
- `expiryMinutes` **上界未设**：那是策略数字（产品决策），本切片只堵形态错误。

### 4.6 已修：本类自己撞了 ArchUnit 分层门（放在 `security` 切片会成环）

初版把电池放在 `org.nexus.gateway.security`（与其它安全测试同包），结果
`ArchitectureRulesTest > layer_dependencies`（切片无环）**红了 26 处**。定位过程：

1. 违规文本里**本类名出现 156 次**，`LogSanitizer` 0 次（⇒ 新加的 `config → util` 边
   不在任何环里，`util` 无出边、结构上不可能成环，无害）；
2. **决定性判据**：`git stash push -u` 后在同一棵树上单跑该规则 ⇒ **BUILD SUCCESSFUL**
   ⇒ 结论「是我引入的」（不是既有红、不是环境）；
3. 根因：本类需按**具体类** mock `MerchantServiceImpl`（接口 mock 会撞
   `BeanNotOfRequiredTypeException`，见 §三），于是新增 `security → service` 边，
   闭合既有长环 ⇒ 26 处。

**修法：把电池移到独立切片 `org.nexus.gateway.attack`**（无任何反向依赖 ⇒ 结构上不可能成环）。
**没有给分层门加豁免**——加豁免等于弱化门禁换取自己的方便。

> **可复用纪律**：ArchUnit 的切片规则**连测试类一起扫**。给「跨多层引用的测试」
> 选包时，优先放进**没人会依赖它的新切片**，而不是塞进语义相近的既有切片。

### 4.7 未发现（如实声明）

- **XSS 载荷**：本 API 面返回 JSON、不渲染 HTML，**未列入电池**（列了就是造缺口）；
  真正相关的反射面在响应回显，见 §4.1「响应只回通用文案」；
- **SQL 注入**：注入串打进字段后**未见 500/未见异常**——本仓持久化走 JPA 参数化，
  但**本电池不构成存储层注入结论**（只打了 `POST /orders` 的入口，未做盲注/延时注入/二阶注入）。

## 五、边界（勿夸大）

- **MockMvc 全上下文，非真实网络**：不起真实 Tomcat（header 含 CRLF 那例在真实服务器上会被
  协议层更早拒绝，故只作「记录」不作「必须 4xx」断言）；不是渗透测试；
- **单端点族群**：电池只打 `POST /api/v1/orders`（gateway 有 31 个 MVC 测试类覆盖其它端点），
  「其它端点是否同族缺陷」未逐点验证——但 §4.1 的修复在 `GlobalExceptionHandler`（**全局**生效）；
- **未覆盖**：二阶注入、盲注、并发重放（时序）、真实客户端证书面；
- **与 CI 的 ZAP baseline 不重叠**：那条是外部扫描器对网关的被动基线，本件是**带合法签名的结构化载荷**；
- cyc 成本：整组 15 例约 2–4 分钟（Spring 上下文一次装配）。

## 六、证据

- 本机实跑：`:nexus-gateway:integrationTest --tests ApiAttackPayloadBatteryIT` → **15/15 绿**；
  `:nexus-gateway:test --tests LogSanitizerTest` → 全绿（含 4 例新回归）；
- 修复前红样本原文：`[amount=string] 类型混淆必须是 4xx（实测 201）`、
  `[api-key-injection] 攻击载荷不得打成 5xx（实测 500）；body={"code":50000,"message":"Internal server error"}`；
- 夹具与断言全在仓内（测试即夹具），无需外部脚本。
