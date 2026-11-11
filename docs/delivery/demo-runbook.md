# 演示 Runbook（30 分钟沙箱演示）

> **用途**：交付方/客户在**本机或交付环境**用 30 分钟跑通「一次支付的生命周期」，
> 全程命令可复制粘贴，**不依赖外部服务**（沙箱 profile + H2 内存库 + mock 连接器）。
>
> **诚实边界**：本演示证明**网关编排与查询面**可用；**不含真实链上转账**、不含真实 L1、
> 不含物理多机。结算确认记录由 mock 连接器提供的 txHash 触发（写侧接线见 #637）。
>
> 配套材料：`docs/delivery/acceptance-checklist.md`（逐条验收）、`docs/runbooks/acceptance-e2e.md`（脚本细节）。

---

## 0. 前置（5 分钟）

| 项 | 要求 | 检查命令 |
|---|---|---|
| JDK | 17（本演示实测 17.0.20） | `java -version` |
| Python | 3.9+（体检脚本用） | `python --version` |
| Git | 任意近期版本 | `git --version` |
| 内存 | **≥4GB 可用**（Gradle daemon 默认 `-Xmx2g`，见 `gradle.properties`） | 内存不足时用 §0.1 低内存参数 |
| 端口 | 8080 空闲 | `netstat -ano | grep :8080` |
| 目录 | 仓库根（脚本需找到 `gradlew`） | `pwd` |

> 脚本会自行定位仓库根，`scripts/doctor.sh` / `scripts/acceptance-e2e.sh` 可从任意目录调用。

### 0.1 低内存构建（可选）

本机可用内存 <2GB 时，Gradle daemon 会被系统杀掉（表现为
`Gradle build daemon disappeared unexpectedly`）。改用单次客户端 JVM：

```bash
./gradlew :nexus-gateway:bootJar -x test --no-daemon --no-watch-fs \
  --no-configuration-cache -Dorg.gradle.jvmargs="-Xmx700m -XX:MaxMetaspaceSize=384m"
```

> 实测：2026-11-11 在可用内存不足 1GB 的机器上，用该参数构建成功（3m04s）。

---

## 1. 构建并启动网关（10 分钟）

```bash
# 1) 构建（产物约 227MB）
./gradlew :nexus-gateway:bootJar -x test

# 2) 启动（sandbox profile，端口 8080）
JWT_SECRET='<≥32 字节随机串>' \
NEX_SETTLEMENT_ENABLED=false \
java -jar nexus-gateway/build/libs/nexus-gateway-*.jar \
  --spring.profiles.active=sandbox --server.port=8080
```

**期望**：日志出现

```
The following 1 profile is active: "sandbox"
Tomcat started on port 8080 (http) with context path '/'
Started GatewayApplication in <N> seconds
```

> `JWT_SECRET` 是 ADMIN 端点（`verify` / `api-keys`）的签发密钥，缺省时网关会随机生成，
> 外部无法自签 ADMIN token——必须显式给（见 §2）。
>
> `NEX_SETTLEMENT_ENABLED=false`：沙箱无链、无真实热钱包地址；不显式关闭会因
> `SettlementPlatformAddressGuard` 启动即抛错（fail-closed，这是预期安全默认）。
>
> **不要用 `bootRun`**：`Tracer` 只在 bootJar 类路径（`productionRuntimeOnly`），
> 任何 profile 下 `bootRun` 都缺 Tracer，起不来（`docs/runbooks/acceptance-e2e.md` §1.1）。

---

## 2. 准备一个 VERIFIED 商户（5 分钟）

sandbox 无预置商户，且 **H2 内存库在网关重启后清空** ⇒ 每次演示都要重做这一步。

```bash
GW=http://127.0.0.1:8080
SECRET='<与上面相同的 JWT_SECRET>'

# a) 注册（公开端点）
curl -sS -X POST "$GW/api/v1/merchants/register" -H 'Content-Type: application/json' \
  -d '{"merchantName":"Acceptance Drill","email":"drill@example.test",
       "settlementAddress":"1SandboxPlatformPubkey0000000000000000000000000"}'
# ⇒ 返回 JSON 含 "id":1（记下 id）

# b) 自签 ADMIN token（HS256，与网关共用 JWT_SECRET）
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s); exp=$((now+3600))
h=$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
p=$(printf '{"sub":"drill-admin","iat":%s,"exp":%s,"roles":"ADMIN"}' "$now" "$exp" | b64url)
s=$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)
JWT="$h.$p.$s"

# c) 置为 VERIFIED（ADMIN）
curl -sS -X POST "$GW/api/v1/merchants/1/verify" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{"status":"VERIFIED"}'

# d) 签发 API Key（secret 只显示一次）
curl -sS -X POST "$GW/api/v1/merchants/1/api-keys" -H "Authorization: Bearer $JWT"
# ⇒ 复制返回里的 "apiKey"
```

**期望**：`verify` 返回 200；`api-keys` 返回 201 且含 `apiKey` / `secret`。

---

## 3. 一条命令跑完整验收（3 分钟）

```bash
NEX_GATEWAY_URL=http://127.0.0.1:8080 \
NEX_API_KEY='<上一步的 apiKey>' \
NEX_MERCHANT_ID=1 \
NEX_ACCEPTANCE_REPORT=acceptance-report.md \
bash scripts/acceptance-e2e.sh
```

**期望输出**（2026-11-11 实测）：

```
== NexusChain acceptance @ http://127.0.0.1:8080 ==
report: acceptance-report.md (PASS=4 FAIL=0)
```

报告内容形如：

| 步骤 | HTTP | 判定 | 说明 |
|---|---|---|---|
| create payment | 201 | PASS | `id=pay_0c0c1d3acc984ba3a2a9d2da8b2c2ae9` |
| query payment | 200 | PASS | 与创建返回的 id 一致 |
| settlement confirmation | 200 | PASS | 写侧已接线（`OrchestrationService.java:480`） |
| reconciliation query | 200 | PASS | `merchantId=1` |

> 两个易踩点：
> 1. **`NEX_MERCHANT_ID` 必须显式传**：支付响应不含 `merchantId`，不传则第 4 步记 `SKIP`
>    （**SKIP 不等于通过**）。
> 2. **签名要求**：每次请求的 `X-NexusChain-Timestamp` / `X-NexusChain-Nonce` 必须换新
>    （nonce ≥16 字节），否则 40106 重放拒绝。脚本已自动处理。

---

## 4. 体检交付包（2 分钟）

```bash
bash scripts/doctor.sh            # 汇总；--json 额外产出机器可读结果
```

**期望**：`PASS=12  FAIL=0  SKIP=2`，退出码 0；SKIP 两项为「缺 Cargo.lock / 缺 Trivy 扫描产物」，
属环境缺前置，不是失败。

---

## 5. 指标与告警面（3 分钟，需指标 token）

```bash
curl -sS -H "Authorization: Bearer $NEXUS_METRICS_TOKEN" http://127.0.0.1:8080/actuator/prometheus | head -n 20
```

**期望**：能看到 `nexus_*` 指标（PaymentMetrics 7 个 meter 已接线，见 #577）。
口径与 token 配置见 `docs/runbooks/metrics-auth.md`；阈值见 `docs/ops/slo.md`（阈值留空为业务决策）。

> 本步在本次演示中**未实测**（需要指标 token），如实标注。

---

## 6. 升级/回滚（**本机未实测**，仅给口径）

- 口径与步骤：`docs/runbooks/upgrade-rollback.md`；
- 本演示**不含**回滚实跑：staging/生产演练需部署环境与 ops 参与（见验收清单 C-03）；
- 沙箱可做的最小验证：停进程 → 用旧 jar 重启 → `/actuator/health` 恢复（`bootJar` 产物留档即可）。

---

## 7. 时间预算（30 分钟）

| 阶段 | 用时 | 累计 |
|---|---|---|
| 前置检查 | 5 min | 5 |
| 构建 + 启动 | 10 min | 15 |
| 商户准备 | 5 min | 20 |
| 端到端验收 | 3 min | 23 |
| 体检 | 2 min | 25 |
| 指标/告警 | 3 min | 28 |
| 收尾（停网关、归档报告） | 2 min | 30 |

> 实测参考（2026-11-11，本机）：构建 3m04s（低内存参数）、网关启动 37s、商户准备 <1min、
> 端到端验收 <30s、体检约 15s（12 项）。

---

## 8. 排障速查

| 现象 | 原因 | 处置 |
|---|---|---|
| 启动抛「结算已启用，但平台出款地址未配置」 | 未显式关闭结算 | 加 `NEX_SETTLEMENT_ENABLED=false`（沙箱/dev 必须） |
| 启动抛 `required a bean of type 'io.micrometer.tracing.Tracer'` | 用了 `bootRun` | 改 `bootJar` + `java -jar` |
| `Gradle build daemon disappeared unexpectedly` | 内存不足，daemon 被系统杀 | 用 §0.1 低内存参数 |
| `40101/40102/40103/40104` | 缺签名 / 时间戳非法或出窗 / nonce 缺失 | 用脚本自动带签名；校时 |
| `40106` | nonce 重放 | 每次换新 nonce |
| `401` | API Key 无效或商户非 VERIFIED | 重走 §2（**网关重启后必须重来**） |
| 第 4 步记 `SKIP` | 未传 `NEX_MERCHANT_ID` | 显式传 `NEX_MERCHANT_ID=1` |
| 端口 8080 被占 | 残留进程 | `netstat -ano | grep :8080` 找 PID 后结束 |
