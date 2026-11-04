# Runbook：`/actuator/prometheus` Bearer 鉴权（PLAN-075/076）

- **适用**：nexus-core / nexus-gateway / nexus-bridge / nexus-signing-service /
  nexus-wallet-service / nexus-api-gateway
- **不适用**：`/actuator/health`（保持公开给探针）；nexus-consortium（未暴露 metrics）

## 一、口径（先看这个，避免误判）

| 情况 | HTTP | 含义 |
|---|---|---|
| Authorization 正确且 token 已配置 | 200 | 正常抓取 |
| token 未配置（env 缺失） | **503** | fail-closed：部署漏配 `NEXUS_METRICS_TOKEN` |
| token 缺失/错误 | **401** | `WWW-Authenticate: Bearer realm="actuator/prometheus"` |
| `/actuator/health` 任意情况 | 200 | 探针不受影响，**这是预期** |

## 二、配置位置（三处必须同值）

1. **应用 env**：`NEXUS_METRICS_TOKEN`
   - Helm：共享 `nexus-secrets` 的 `NEXUS_METRICS_TOKEN`
     （core 显式 `secretKeyRef` + `optional: true`；其余服务经 `envFrom`）；
   - compose：`docker-compose.prod.yml` 六个服务 env + top-level secret
     `nexus-metrics-token: environment: NEXUS_METRICS_TOKEN`；
2. **Prometheus**：
   - compose：`prometheus.yml` 六个 job 的
     `bearer_token_file: /run/secrets/nexus-metrics-token`；
   - K8s：`prometheusSpec.secrets: [<token secret>]` +
     ServiceMonitor `authorization: {type: Bearer, credentialsFile: /etc/prometheus/secrets/<...>/<key>}`；
3. 两处值不一致时表现为 **Prometheus `up=0` + lastError=401**（见 §四）。

## 三、轮换步骤（如实：当前不支持双 token 并行）

1. 生成新 token（≥32 字节随机；不进仓库、不进镜像、不进 CI 日志）；
2. **先**更新 Prometheus 侧引用（token 文件/Secret 内容会立即生效于下次 reload/抓取）；
3. **再**滚动重启各 Spring 服务，让它们读到新 `NEXUS_METRICS_TOKEN`
   （过滤器在进程启动时读取 env，运行中不会热更新）；
4. 验证：Prometheus `up{job="nexus-*"}=1`，`/actuator/prometheus` 带新 token 为 200；
5. **期间的抓取空窗是已知代价**：旧 token 被 Prometheus 先换掉、服务未重启完，
   或服务先换、Prometheus 未换，都会出现短暂 401。指标是 pull 型，不会丢业务数据；
   若要求零空窗，需要「同时接受新旧两个 token」的改造（未实现，立项另行评估）。
6. 轮换后 15 分钟内留意 `up==0` 与任何 `FinalityOutboxUnreadable` 类误报。

## 四、排查树

```
metrics 抓取失败
├─ 503? → 该 Pod env 里没有 NEXUS_METRICS_TOKEN：查 Secret key 是否存在、envFrom/secretKeyRef 渲染
├─ 401? → 直接 curl 对照：
│    curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" http://<pod>:<port>/actuator/prometheus
│    ├─ 200 ⇒ 应用侧 OK，问题在 Prometheus 侧 token 文件/Secret 没挂对或内容不同
│    └─ 401 ⇒ 应用侧 token 与请求不符（或 Prometheus 根本没发 Authorization）
└─ 200 但没序列? → 不是鉴权问题；查 endpoint 暴露/micrometer 依赖
```

常用定位命令：

```bash
# 应用侧（不打印 token 本体）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:19585/actuator/health          # 应 200
curl -s -D - -o /dev/null http://127.0.0.1:19585/actuator/prometheus | head -5            # 应 401 + WWW-Authenticate

# Prometheus 侧
#   query: up{job=~"nexus-.*"}          -> 全 1
#   targets API 的 lastError            -> 空；出错时常见 "server returned HTTP status 401"
```

## 五、边界

- 本 runbook 基于本机真实进程演练（PLAN-076，2026-11-05）：同内核单机 +
  真实 Prometheus 3.15.1，**不是物理多机/真实集群验收**；
- 未覆盖：K8s Operator 实际 Secret 挂载验收、双 token 并行、自动轮换；
- 不改 `/actuator/health`、不改业务 RPC 的 JWT 语义。
