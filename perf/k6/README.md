# NexusChain k6 性能压测

> P2-T6: 性能压测与调优 — NexusChain v2.0.0 Phase 2 生产就绪
>
> 本目录包含 4 个核心场景的 k6 压测脚本、认证/数据工具、配置与 CI 集成。

## 目录结构

```
perf/k6/
├── README.md                  本文档
├── config.json                压测配置（环境 URL、阈值、阶段）
├── payment-create.js          场景 1：支付创建压测
├── payment-query.js           场景 2：支付查询压测
├── bridge-lock.js             场景 3：跨链桥锁定压测
├── webhook-delivery.js        场景 4：Webhook 投递压测
├── run-smoke.sh               冒烟测试脚本（CI 用）
└── utils/
    ├── auth.js                认证工具（API Key + HMAC-SHA256 签名）
    └── data.js                测试数据生成工具
```

## 1. k6 安装

### 1.1 本地安装

**macOS / Linux（推荐 Docker，避免污染主机）：**

```bash
docker pull grafana/k6:latest
alias k6='docker run --rm -i grafana/k6:latest'
```

**macOS Homebrew：**

```bash
brew install k6
```

**Linux apt（Debian/Ubuntu）：**

```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E371532A8AE254D4BBF864CDA6EB11
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update && sudo apt install k6
```

**Windows：**

```powershell
choco install k6
# 或
winget install grafana.k6
```

### 1.2 验证安装

```bash
k6 version
# k6 v0.43.x ...
```

> **要求 k6 ≥ 0.43**（脚本使用 `hmac()` 内建函数）。

## 2. 压测目标

| 场景 | 端点 | 目标 RPS | P99 延迟 | 说明 |
| --- | --- | --- | --- | --- |
| payment-create | `POST /api/v1/payments` | 1000 | < 500ms | 支付创建（含 API Key + HMAC 签名） |
| payment-query | `GET /api/v1/payments/{id}` | 2000 | < 200ms | 支付查询（先创建后查询） |
| bridge-lock | `POST /api/v1/bridge/lock` | 100 | < 2s | 跨链桥锁定（多签 + 源链确认） |
| webhook-delivery | `POST /api/v1/payments/{id}/confirm` | 500 | < 1s | Webhook 投递（模拟链上回调） |

通用阈值：错误率 `< 1%`，业务检查通过率 `> 99%`。

## 3. 运行压测

### 3.1 准备凭据

所有脚本通过 k6 `-e` 环境变量注入凭据，**不硬编码**。最小必填项：

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `API_KEY` | 商户 API Key（对应 `X-NexusChain-ApiKey`） | `perf-test-api-key` |
| `SIGNING_SECRET` | 请求签名密钥（对应 `nexus.security.requestSigningSecret`） | `perf-test-signing-secret` |
| `BASE_URL_GATEWAY` | 网关地址 | `http://localhost:8080` |
| `BASE_URL_BRIDGE` | 桥服务地址 | `http://localhost:8084` |

可选变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MERCHANT_ID` | `1` | 商户 ID |
| `NOTIFY_URL` | `https://perf.example.com/...` | Webhook 回调地址 |
| `PREFERRED_CONNECTOR` | `mock` | 首选连接器（压测默认走 mock，避免真实链上延迟） |
| `SEED_POOL_SIZE` | `200` / `100` | 预创建支付池大小（query/webhook 场景） |
| `TIMEOUT_MS` | `10000` | 单请求超时 |
| `TLS_SKIP_VERIFY` | `false` | 跳过 TLS 证书校验（自签 staging 用） |

### 3.2 运行 4 个场景

**场景 1：支付创建（目标 1000 RPS / P99 < 500ms）**

```bash
k6 run \
  -e API_KEY=$API_KEY \
  -e SIGNING_SECRET=$SIGNING_SECRET \
  -e BASE_URL_GATEWAY=http://localhost:8080 \
  perf/k6/payment-create.js
```

如需精确 RPS（k6 用 VU 控制并发，加 `--rps` 限流）：

```bash
k6 run --rps 1000 \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  -e BASE_URL_GATEWAY=http://localhost:8080 \
  perf/k6/payment-create.js
```

**场景 2：支付查询（目标 2000 RPS / P99 < 200ms）**

```bash
k6 run \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  -e BASE_URL_GATEWAY=http://localhost:8080 \
  -e SEED_POOL_SIZE=500 \
  perf/k6/payment-query.js
```

**场景 3：跨链桥锁定（目标 100 RPS / P99 < 2s）**

```bash
k6 run \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  -e BASE_URL_BRIDGE=http://localhost:8084 \
  perf/k6/bridge-lock.js
```

**场景 4：Webhook 投递（目标 500 RPS / P99 < 1s）**

```bash
k6 run \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  -e BASE_URL_GATEWAY=http://localhost:8080 \
  -e SEED_POOL_SIZE=200 \
  perf/k6/webhook-delivery.js
```

### 3.3 冒烟测试（低负载快速验证）

```bash
bash perf/k6/run-smoke.sh
```

冒烟测试用 5 VU 跑 30s，仅验证连通性与基本正确性，不强制 P99 阈值。

### 3.4 输出到 JSON / InfluxDB

**JSON 产物（CI 归档）：**

```bash
k6 run --out json=results/payment-create.json \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  perf/k6/payment-create.js
```

**InfluxDB（实时可视化）：**

```bash
k6 run --out influxdb=http://localhost:8086/k6 \
  -e API_KEY=$API_KEY -e SIGNING_SECRET=$SIGNING_SECRET \
  perf/k6/payment-create.js
```

配合 Grafana [k6 官方看板](https://grafana.com/grafana/dashboards/2587-k6-load-testing-results/) 实时观测。

## 4. 结果解读

### 4.1 关键指标

| 指标 | 含义 | 关注点 |
| --- | --- | --- |
| `http_req_duration` | 请求总耗时（含 TLS/连接/服务端） | P99 是否达标 |
| `http_req_failed` | 非 2xx/3xx 比例 | 错误率 |
| `checks` | 业务断言通过率 | 业务正确性 |
| `vus` | 活跃虚拟用户数 | 是否达到目标并发 |
| `iterations` | 完成迭代次数 | 吞吐量 |
| `http_reqs` | 总请求数 | 吞吐量 |
| `biz_success_rate` | 业务成功率（自定义） | 端到端成功 |
| `payment_create_latency` | 场景独立延迟（自定义） | 排除 seed 噪声 |

### 4.2 阈值通过/失败

k6 退出码：
- `0`：所有阈值通过
- `1`：有阈值失败或脚本错误

CI 中可直接用退出码 gating。

### 4.3 典型输出片段

```
     execution: local
        script: perf/k6/payment-create.js
        output: -
     scenarios: (100.00%) 1 scenario, 1000 max VUs, 4m30s max duration
              * default: 4 stages, ramp up 200 → 500 → 1000 → 0 over 4m30s

    http_req_duration..........: avg=187.32ms p(99)=421.54ms
    http_req_failed............: 0.23%
    checks.....................: 99.78% ✓ 23456 ✗ 51
    biz_success_rate...........: 99.74%
    payment_create_latency....: avg=187.11ms p(99)=421.31ms
```

`p(99)=421.54ms < 500ms` → 通过。

## 5. 调优建议

### 5.1 服务端（Spring Boot / JVM）

**Tomcat 线程池（`application.yml`）：**

```yaml
server:
  tomcat:
    threads:
      max: 400          # 默认 200；1000 RPS / ~200ms 平均 → 200~400
      min-spare: 50
    max-connections: 8192
    accept-count: 200
    connection-timeout: 10000
```

> 经验：`threads.max ≈ 目标 RPS × 平均延迟(s) × 安全系数(1.5)`。
> 1000 × 0.2 × 1.5 = 300，向上取 400。

**HikariCP 连接池：**

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50      # 默认 10；压测常见瓶颈
      minimum-idle: 20
      connection-timeout: 5000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 30000
```

> 经验：`maximum-pool-size ≈ threads.max × DB 使用率`。
> 支付创建每次 ~1 次 DB 写，取 `threads.max / 8` 或 50，取大者。

**JVM 参数（堆 + GC）：**

```
-Xms2g -Xmx2g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m
-XX:+ParallelRefProcEnabled
-XX:+AlwaysPreTouch
```

> 4C8G 容器建议 2~3g 堆；16G+ 可换 ZGC：`-XX:+UseZGC -Xmx8g`。

**Redis / 缓存：**

- Nonce 防重放：单实例 in-memory；多实例必须切 Redis `SET NX EX 300`。
- 限流：Redis 令牌桶，避免本地限流在多实例下放大 N 倍。

### 5.2 客户端（k6）

- `noConnectionReuse: false`（默认）— 复用连接，降低握手开销。
- 大规模压测用分布式 k6：`k6 run --out cloud`（Grafana Cloud k6）。
- 避免 VU 数过高导致客户端自身瓶颈：单机建议 VU ≤ 2000，更高用多机。

### 5.3 基础设施

- 数据库：PG 连接数 `max_connections ≥ hikari.pool × 实例数 + 20`。
- 索引：`payments(merchant_id, status, created_at)` 覆盖列表查询。
- 网关：Nginx `keepalive 256` 到 upstream；`worker_connections 16384`。
- 监控：Prometheus + Grafana，关注 `up`、`http_request_duration_seconds`、`jvm_gc_pause_seconds`。

### 5.4 调优排查清单

1. P99 高但 P50 低 → 长尾问题：查 GC 日志、DB 慢查询、连接池等待。
2. 错误率突增 → 看是否 Nonce 重放（5min 窗）、API Key 失效、DB 连接耗尽。
3. VU 上不去 → 客户端 CPU/端口耗尽，换分布式或调 `ulimit -n`。
4. RPS 达不到 → 服务端线程池 / 连接池打满，或下游连接器限速。

## 6. CI 集成

### 6.1 GitHub Actions

工作流文件：`.github/workflows/performance-test.yml`

触发：
- **PR** 到 master：自动跑冒烟测试（5 VU × 30s），失败阻塞合并。
- **手动** `workflow_dispatch`：可指定完整压测。

详见工作流内注释。

### 6.2 本地冒烟

```bash
bash perf/k6/run-smoke.sh
```

退出码 `0` 表示通过；CI 中可直接 gating。

### 6.3 结果归档

- CI 上传 JSON 产物到 artifact（保留 14 天）。
- 可选推送到 Grafana Cloud / InfluxDB 做趋势对比。

## 7. 安全注意事项

1. **凭据不进仓库**：`API_KEY` / `SIGNING_SECRET` 通过 GitHub Secrets 注入，脚本中仅读 `__ENV`。
2. **压测环境隔离**：冒烟测试默认打 `localhost:8080`；CI 中通过 `BASE_URL_GATEWAY` 指向 staging。
3. **不压生产**：完整压测仅在 staging / perf 环境运行；生产仅冒烟。
4. **签名密钥**：压测用专用密钥，不复用生产 `nexus.security.requestSigningSecret`。
5. **数据隔离**：压测商户 ID 与生产隔离（建议 `merchant_id=perf-1`）。

## 8. 故障排查

| 现象 | 可能原因 | 解决 |
| --- | --- | --- |
| `401 Missing request signature` | 未传 `SIGNING_SECRET` | 检查 `-e` 注入 |
| `401 Signature mismatch` | 密钥与服务端不一致 | 对齐 `nexus.security.requestSigningSecret` |
| `401 Replayed nonce` | Nonce 重复（极少） | k6 自动加 VU+ITER+ts 后缀，不应出现 |
| `401 Request timestamp expired` | 客户端时钟偏移 > 5min | 同步 NTP |
| 连接拒绝 | 服务未启动 / 端口错 | `curl $BASE_URL/actuator/health` |
| P99 远超目标 | 服务端线程池 / DB 池满 | 按 §5 调优 |
| k6 `hmac is not defined` | k6 版本 < 0.43 | 升级 k6 |

## 9. 维护

- 新增场景：在 `config.json` 加 stage/threshold，新建 `*.js`，复用 `utils/`。
- API 变更：同步更新 `utils/data.js` 字段与 `utils/auth.js` 头名。
- 阈值调整：改 `options.thresholds` 与 `config.json`，并在本 README §2 同步。

---

**负责人**：QA/性能工程师
**最后更新**：v2.0.0 Phase 2 — P2-T6