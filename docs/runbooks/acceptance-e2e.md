# 端到端验收 Runbook（acceptance-e2e）

**用途**：用一条命令验证「客户拿到交付物后，一笔支付能否走完 下单 → 查询 → 结算确认 → 对账」。
它回答的是**可交付性**问题，不是单元测试覆盖率。

**脚本**：`scripts/acceptance-e2e.sh`（产出 `acceptance-report.md`）

---

## 1. 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| gateway 已启动 | `./gradlew :nexus-gateway:bootRun --args="--spring.profiles.active=sandbox"` | 必须**在仓库根**执行；gateway 子目录独立构建不可用 |
| 商户 API Key | `verification_status=VERIFIED` | sandbox **无预置商户**，需先创建并置为 VERIFIED |
| 签名密钥 | sandbox 默认 `sandbox-signing-secret-do-not-use-in-prod` | 见 `nexus-gateway/src/main/resources/application-sandbox.yml:102`；prod 必须走 `NEXUS_REQUEST_SIGNING_SECRET` |
| 工具 | `curl`、`openssl`、`python` | python 仅用于解析 JSON |

## 2. 用法

```bash
# 最小用法（默认 http://localhost:8080）
NEX_API_KEY=<商户API Key> bash scripts/acceptance-e2e.sh

# 指定目标与链上地址
NEX_GATEWAY_URL=http://localhost:8080 \
NEX_API_KEY=<key> \
NEX_SIGNING_SECRET=<secret> \
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
| 404（结算确认） | 无结算记录 | 支付可能未走到结算腿，检查结算开关与平台地址 |

## 5. 已知限制（诚实声明）

- **未包含真实链上转账**：脚本只验证网关编排与查询面；链上结算需另配 `NEX_SETTLEMENT_PLATFORM_ADDRESS` 与可用执行通道。
- **sandbox 无预置商户**：首次运行必须先准备 API Key，否则 `exit 2`。
- **对账查询依赖响应回填 `merchantId`**：若响应不含该字段，该步记为 `SKIP`，不等于通过。
- 本 runbook 与脚本的签名实现已用 Python 独立实现交叉验证（canonical 与 HMAC 均一致）；**端到端实跑仍需真实商户凭据**，未在 CI 中执行。
