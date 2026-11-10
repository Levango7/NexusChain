# 端到端验收 Runbook（acceptance-e2e）

**用途**：用一条命令验证「客户拿到交付物后，一笔支付能否走完 下单 → 查询 → 结算确认 → 对账」。
它回答的是**可交付性**问题，不是单元测试覆盖率。

**脚本**：`scripts/acceptance-e2e.sh`（产出 `acceptance-report.md`）

---

## 1. 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| gateway 已启动 | `bootJar` + `java -jar`（**不能用 `bootRun`**，见 §1.1） | 必须**在仓库根**执行；gateway 子目录独立构建不可用 |
| 结算开关 | sandbox 建议 `NEX_SETTLEMENT_ENABLED=false`（见 §1.2） | 否则启动被 `SettlementPlatformAddressGuard` 拦下 |
| 商户 API Key | `verification_status=VERIFIED` | sandbox **无预置商户**，需先创建并置为 VERIFIED（§1.3） |
| 签名密钥 | sandbox 默认 `sandbox-signing-secret-do-not-use-in-prod` | 见 `nexus-gateway/src/main/resources/application-sandbox.yml:102`；prod 必须走 `NEXUS_REQUEST_SIGNING_SECRET` |
| 工具 | `curl`、`openssl` | JSON 解析脚本内用 `python`/`sed`，本机需其一 |

### 1.1 为什么不能用 `bootRun`（2026-11-08 实测）

```bash
# ✗ 起不来
./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"
```

实测失败：

```
Parameter 10 of constructor in org.nexus.gateway.service.PaymentServiceImpl
required a bean of type 'io.micrometer.tracing.Tracer' that could not be found.
```

根因：`Tracer` 由 `spring-boot-starter-zipkin` 提供，而
`nexus-gateway/build.gradle:101` 把它放在自定义 `productionRuntimeOnly` 配置里
（**只并入 bootJar 类路径，不进 `bootRun`**；该配置是为避免单测 `@MockitoBean`
冲突而刻意做的）。⇒ **任何 profile 下 `bootRun` 都缺 Tracer，一律起不来**。

可用启动方式：

```bash
# 1) 构建（约 3~5 分钟，产物约 227 MB）
./gradlew :nexus-gateway:bootJar

# 2) 启动 sandbox
JWT_SECRET='<≥32 字节随机串>' \
NEX_SETTLEMENT_ENABLED=false \
java -jar nexus-gateway/build/libs/nexus-gateway-*.jar \
  --spring.profiles.active=sandbox --server.port=8080
```

> `JWT_SECRET` 是 `verify` / `api-keys` 等 ADMIN 端点的签发密钥（HS256）。
> 缺省时网关启动期会随机生成，导致外部无法自签 ADMIN token——见 §1.3。

### 1.2 结算地址守卫

`application.yml:196` 的 `nexus.settlement.enabled` 默认 `true`，而 sandbox
**不覆盖**它、也不给 `platform-address` ⇒ 启动即抛：

```
结算已启用，但平台出款地址未配置：请设置 nexus.settlement.platform-address
（环境变量 NEX_SETTLEMENT_PLATFORM_ADDRESS）... 若本环境确实不做结算，
请显式设置 nexus.settlement.enabled=false ...
```

sandbox 无链、无真实热钱包地址，因此显式 `NEX_SETTLEMENT_ENABLED=false`。
**代价**：结算腿不执行 ⇒ 不会产生链上交易哈希，也不会创建结算确认记录。

### 1.3 准备一个 VERIFIED 商户（sandbox 无预置）

```bash
GW=http://127.0.0.1:8080

# a) 注册（公开端点）
curl -sS -X POST "$GW/api/v1/merchants/register" \
  -H 'Content-Type: application/json' \
  -d '{"merchantName":"Acceptance Drill","email":"drill@example.test",
       "settlementAddress":"1SandboxPlatformPubkey0000000000000000000000000"}'

# b) 自签 ADMIN token（与网关共用 JWT_SECRET，HS256）
#    claims: {"sub":"drill-admin","iat":<now>,"exp":<now+3600>,"roles":"ADMIN"}
JWT=<上一步生成的 token>

# c) 置为 VERIFIED
curl -sS -X POST "$GW/api/v1/merchants/<id>/verify" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"status":"VERIFIED"}'

# d) 签发 API Key（secret 只显示一次）
curl -sS -X POST "$GW/api/v1/merchants/<id>/api-keys" -H "Authorization: Bearer $JWT"
```

> **H2 内存库**：sandbox 用 `jdbc:h2:mem:nexus_sandbox`，**gateway 重启即清空**。
> 重启后必须重新走 a~d，旧 API Key 会返回 `40101 Invalid or revoked API key`。

### 1.4 结算确认写入点当前未接线（P1 缺口，非本 lane）

即使打开结算，第 3 步仍然会 404。2026-11-08 实测确认：

- `ChainSettlementConfirmationService.initiateConfirmation(paymentId, txHash)`
  （`ChainSettlementConfirmationService.java:88`）是**唯一**创建确认记录的方法；
- 该方法在 `src/main` 下**零调用**，只有 `ChainSettlementConfirmationServiceTest` 调用；
- `ChainSettlementConfirmationController` 只提供 `GET /{paymentId}`、`GET`（列表）、
  `POST /{paymentId}/retry`，**没有创建端点**；`retry` 也要求记录已存在。

⇒ `GET /api/v1/settlement-confirmations/{paymentId}` 对任何真实支付**恒 404**。
这不是验收脚本的断言写错，而是结算确认的**写侧未接线**。已按板规上报
gateway/settlement lane，本 runbook 只如实记录，不改产品代码。

## 2. 用法

```bash
# 最小用法（默认 http://localhost:8080）
NEX_API_KEY=<商户API Key> bash scripts/acceptance-e2e.sh

# 指定目标、链上地址与商户号（对账步骤需要）
NEX_GATEWAY_URL=http://localhost:8080 \
NEX_API_KEY=<key> \
NEX_SIGNING_SECRET=<secret> \
NEX_MERCHANT_ID=<merchantId> \
NEX_PAYER_ADDRESS=<payer> \
NEX_PAYEE_ADDRESS=<payee> \
bash scripts/acceptance-e2e.sh
```

退出码：`0` 全通过；`1` 有步骤失败；`2` 前置缺失。

## 3. 四步契约（逐字核对 origin/master）

| 步骤 | 方法与路径 | 契约来源 |
|---|---|---|
| 创建支付 | `POST /api/v1/payments` | `PaymentOrchestrationController.java:37,77` |
| 查询支付 | `GET /api/v1/payments/{id}` | 同文件 `getPayment` |
| 结算确认 | `GET /api/v1/settlement-confirmations/{paymentId}` | `ChainSettlementConfirmationController.java:27,45` |
| 对账差异 | `GET /api/v1/reconciliation/discrepancies/{merchantId}` | `DiscrepancyResolutionController.java:46,101` |

请求头（缺一即 401）：

```
X-NexusChain-ApiKey:    <商户 API Key>
X-NexusChain-Timestamp: <unix millis>
X-NexusChain-Nonce:     <≥16 字节唯一值>
X-NexusChain-Signature: v2:<hex(HMAC-SHA256(canonical, secret))>
```

canonical（长度前缀，按 UTF-8 字节计）：

```
NXC2|<len>:<ts>|<len>:<nonce>|<len>:<method>|<len>:<path>|<len>:<body>|
```

## 4. 失败排查

| 返回码 | 含义 | 处置 |
|---|---|---|
| 40101 | 缺签名 | 检查 `X-NexusChain-Signature` |
| 40102 | 缺/非法时间戳 | 检查 `X-NexusChain-Timestamp` 是否 unix millis |
| 40103 | 时间戳出窗（默认 180s） | 校时；本机时钟偏差过大 |
| 40104 / 40109 | 缺 nonce / nonce 过短 | nonce ≥16 字节 |
| 40105 | 服务端未配置签名密钥 | sandbox 档需 `application-sandbox.yml` 生效 |
| 40106 | nonce 重放 | 每次请求必须换 nonce |
| 40107 | 签名不匹配 | canonical 字段或 secret 不一致 |
| 40108 | 拒绝了 v1 签名 | 必须用 `v2:` 前缀 |
| 401 | API Key 无效 | 商户不存在或未 VERIFIED |
| 404（结算确认） | 无确认记录 | 写侧未接线（§1.4）：`initiateConfirmation` 无生产调用；即使结算开启也不会创建 |

## 5. 已知限制（诚实声明）

- **未包含真实链上转账**：脚本只验证网关编排与查询面；链上结算需另配 `NEX_SETTLEMENT_PLATFORM_ADDRESS` 与可用执行通道。
- **sandbox 无预置商户**：首次运行必须先准备 API Key，否则 `exit 2`；且**重启即失效**（H2 内存库）。
- **`bootRun` 不可用**：只能 `bootJar` + `java -jar`（§1.1，Tracer 只在 bootJar 类路径）。
- **sandbox 默认关闭结算**：不会产生链上交易哈希，见 §1.2。
- **结算确认写侧未接线**：第 3 步恒 404，见 §1.4；这是 P1 缺口，不是脚本问题，已上报 gateway lane。
- **对账查询需要显式 `NEX_MERCHANT_ID`**：支付响应不含该字段；未传时该步记为 `SKIP`，不等于通过。
- 本 runbook 与脚本的签名实现已用 Python 独立实现交叉验证（canonical 与 HMAC 均一致）；端到端已在**本机 sandbox** 实跑（见 §6）。

## 6. 实测记录（2026-11-08）

环境：Windows + JDK 17.0.20.8；`nexus-gateway-2.77.0.jar`，sandbox profile，
H2 内存库，端口 18080；客户端 Git Bash + curl + openssl。

商户准备（同一实例内完成）：注册 `201` → verify `200` → api-key `201`。

| 步骤 | HTTP | 判定 | 说明 |
|---|---|---|---|
| create payment | 201 | PASS | `id=pay_b9bc8088...`（真实落库） |
| query payment | 200 | PASS | 与创建返回的 id 一致 |
| settlement confirmation | 404 | FAIL | 写侧未接线（§1.4）；本次结算亦关闭 |
| reconciliation query | 200 | PASS | `NEX_MERCHANT_ID` 显式传入后通过 |

**结论**：

1. **下单 → 查询**主链路真实跑通（201 + 200，id 一致），签名 / ApiKey /
   canonical 三段实现与网关一致；
2. 结算确认的 404 有**两层原因**：本次 sandbox 关闭了结算（§1.2），且
   `initiateConfirmation` 在 `src/main` 零调用（§1.4）——**后者是产品缺口**，
   不能归因于配置；
3. 对账步骤已从 `SKIP` 修为可执行：支付响应确实不含 `merchantId`
   （`PaymentOrchestrationController.toResponse()` 的键集合里没有），
   因此脚本改为接受显式 `NEX_MERCHANT_ID`；实测 `200 PASS`，
   未传时仍记 `SKIP`，不把 `SKIP` 当通过。
