# SLO / SLI 指标清单（阈值待定）

- **本文给什么**：NexusChain 上**哪些服务等级指标今天真的能测**、用什么 PromQL 测、指标从哪个端点来、以及**哪些指标根本不存在**（想建 SLO 必须先补埋点）。
- **本文不给什么**：**不给阈值**。目标值（99.9%？p95 < 200ms？）是业务/合同决策，取决于客户是谁、SLA 怎么签、成本能承受多少。本文的阈值列全部留空并标注 `待定（业务决策）`。
- **与告警的关系**：`deploy/k8s/50-prometheus-rules.yaml` 的 24 条告警是**症状触发**（阈值型、单点型）；SLO 是**周期达标率**。两者不互相替代——告警告诉你「现在出事了」，SLO 告诉你「这个季度有没有守住承诺」。

> **证据口径**：本文引用的每个指标名都在 `origin/master` 上核对过注册点（见「出处」列）。
> 核过 ≠ 有数据：**注册了但没人喂** 的指标同样恒为 0，第 2 节单独列。

---

## 一、先看结论：现在的可测性

| 层面 | 能不能测 | 依据 |
|---|---|---|
| HTTP 请求（可用性 / 延迟 / 状态码分布） | ✅ 能 | Spring Boot Actuator 自动注册 `http_server_requests_seconds_*` |
| JVM（堆 / GC / 线程） | ✅ 能 | Micrometer 默认 JVM binder |
| 容器资源（CPU / 内存 / 重启） | ✅ 能 | kube-state-metrics / cAdvisor |
| 链（块高 / 最终性 / 投票 / 持久化） | ✅ 能 | `nexuschain_block_height`、`nexus_finality_*`、`nexus_block_*` |
| 安全（重放拦截 / 密码验证 / 3DS / KEK） | ✅ 能 | `SecurityMetricsRecorder` 9 个 meter 已全部接线（#584 补齐 encrypt/decrypt 两件） |
| **支付业务漏斗（下单 / 成功 / 失败 / 退款 / Webhook）** | ✅ 能 | `PaymentMetrics` 7 个 meter 已由 #577 全接线（见 §2.1）；下单指标导出名是 `nexus_orders_total`，不是 `nexus_orders_created_total` |
| 结算确认耗时 / 对账差错率 / 桥接金额 / 风控触发 / MPC 签名耗时 | ❌ 不能 | 全仓无对应指标注册 |

**一句话**：**基础设施、链与支付漏斗的 SLO 今天就能立**（阈值仍待业务决策）；**结算 / 对账 / 桥接 / 风控 / MPC 签名仍立不起来**——这些指标全仓未注册，分子分母都没有。

---

## 二、曾经必须先修、现已修复的事实（保留作审计记录）

### 2.1 `PaymentMetrics` 7 个指标曾零调用点，已由 #577 全接线

`nexus-gateway/src/main/java/org/nexus/gateway/observability/PaymentMetrics.java` 的构造器用 `MeterRegistry` 注册了 7 个 meter：

| 指标（Prometheus 实际导出名） | 接线点（#577） |
|---|---|
| `nexus_orders_total` ⚠ 不是 `nexus_orders_created_total` | `OrderServiceImpl` → `orderCreated()` |
| `nexus_payments_confirmed_total` | `PaymentServiceImpl.recordPaymentConfirmed` → `paymentConfirmed()` |
| `nexus_payments_failed_total` | `PaymentServiceImpl.recordPaymentFailed` → `paymentFailed()`（仅 FAILED 终态） |
| `nexus_refunds_issued_total` | `PaymentServiceImpl` 退款分支 → `refundIssued()`（仅 `RefundStatus.COMPLETED`） |
| `nexus_webhooks_delivered_total` | `WebhookDeliveryService` → `webhookDelivered()` |
| `nexus_webhooks_failed_total` | `WebhookDeliveryService` → `webhookFailed()` |
| `nexus_payment_latency_seconds`（Timer） | `PaymentServiceImpl.recordPaymentConfirmed` → `paymentLatencySince(order.getCreatedAt())` |

修复前的复现（#576 时两条命令都返回空；#577 后不再为空，保留作审计记录）：

```bash
# ① 除自身文件外，全仓有没有引用 PaymentMetrics 这个类型？
git grep -n "PaymentMetrics" -- '*.java' | grep -v "observability/PaymentMetrics.java"
#   → #576 时：只有 SecurityMetricsRecorder.java:15 的一行 javadoc 注释，没有注入点
#   → #577 后：命中 OrderServiceImpl / PaymentServiceImpl / WebhookDeliveryService 及两个测试

# ② 有没有任何地方调用它的方法？
git grep -n "orderCreated()\|paymentConfirmed()\|paymentFailed()\|refundIssued()\|webhookDelivered()\|webhookFailed()" -- '*.java'
#   → #576 时：只命中 PaymentMetrics.java 自身的方法定义
#   → #577 后：命中上表 7 个接线点
```

**修复前的后果**：因为 `PaymentMetrics` 是 `@Component` 且构造器注入 `MeterRegistry`，这些 meter 在启动时会被注册，所以 `/actuator/prometheus` 里能看到它们、值恒为 0——不是"面板没数据"，是"面板永远显示 0"。**#577 接线后这一条不再成立**；`@Autowired(required = false)` setter 注入 + 判空跳过，保证指标类缺席时业务路径不受影响。

`deploy/grafana/dashboards/nexus-gateway.json` panel 13–16 的业务面板引用这批名字，#577 已把首条 expr 从不存在的 `nexus_orders_created_total` 改为 `nexus_orders_total`（OpenMetrics 把 `_created` 保留给创建时间戳序列，故 `nexus.orders.created` 这个 counter 实际导出为 `nexus_orders_total`，见 `PaymentMetricsTest` 抓取断言）；其余 expr 保持。**接线后面板不再恒为 0**，但阈值仍未定（见第六节）。

**这不是新问题，而且已有先例可循**：`ThreeDsAuthCompletedObserver` 的 javadoc 记录了同一缺陷——`nexus_3ds_auth_total` "在 `SecurityMetricsRecorder` 里注册后从来没人喂过，此前恒为 0"，后来通过加观察者修好了。`PaymentMetrics` 走的正是同一条老路，最终由 #577 以完全相同的方式补齐。

### 2.2 安全指标 9 个里曾有 2 个是死的（#584 已补齐）

`SecurityMetricsRecorder`（`nexus-gateway/src/main/java/org/nexus/gateway/security/audit/SecurityMetricsRecorder.java`）注册 9 个 meter，接线情况：

| 指标 | 调用点 | 状态 |
|---|---|---|
| `nexus_replay_interceptions_total` | `RequestSignatureInterceptor.java:232` | ✅ |
| `nexus_3ds_auth_total` | `ThreeDsAuthCompletedObserver.java:70` | ✅ |
| `nexus_3ds_auth_latency_seconds` | `ThreeDsAuthCompletedObserver.java:120` | ✅ |
| `nexus_password_validations_total` | `PaymentPasswordService.java:199` | ✅ |
| `nexus_password_locks_total` | `PaymentPasswordService.java:209` | ✅ |
| `nexus_kek_version` | `KeyRotationScheduler.java:189,209` | ✅ |
| `nexus_key_rotation_progress` | `KeyRotationScheduler.java:164` | ✅ |
| `nexus_encryption_operations_total` | 加解密工具类出口（#584） | ✅ |
| `nexus_encryption_latency_seconds` | 加解密工具类出口（#584） | ✅ |

`recordEncryptionOperation()` 与 `getEncryptionLatencyTimer()` 在 #576 时全仓无调用点（`git grep "\.recordEncryptionOperation("` 只命中 `SecurityAuditServiceTest` 里**另一个类**的同名方法）；#584 已在加解密出口补上调用点。该段保留作审计记录。

---

## 三、今天就能立的 SLO（指标真实存在且已被喂数据）

**阈值列全部留空——填它需要业务决策，不是技术决策。**

### 3.1 服务可用性（HTTP 层）

| 项 | 内容 |
|---|---|
| SLI | 非 5xx 响应占比 |
| PromQL | `1 - ( sum(rate(http_server_requests_seconds_count{job=~".*nexus-gateway.*", status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{job=~".*nexus-gateway.*"}[5m])), 1) )` |
| 采集位置 | 各服务 `/actuator/prometheus`（需 `Authorization: Bearer $NEXUS_METRICS_TOKEN`） |
| 已用于 | `deploy/grafana/dashboards/nexus-gateway.json`（错误率面板） |
| 阈值 | `待定（业务决策）` |
| 局限 | **只反映网关自己的 HTTP 层**。一次「返回 200 但支付没成」不算失败——要覆盖这类业务失败，须改用 §3.8 的支付漏斗 SLI |

### 3.2 API 延迟

| 项 | 内容 |
|---|---|
| SLI | p95 / p99 请求耗时 |
| PromQL | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job=~".*nexus-gateway.*"}[5m])) by (le))` |
| 采集位置 | 同上 |
| 阈值 | `待定（业务决策）` |
| 局限 | 直方图桶边界由 `management.metrics.distribution.slo` 决定（默认 `50ms,100ms,250ms,500ms,1s,2s,5s`，见 `deploy/monitoring/micrometer-config.yaml:69`）。**桶外插值是估算**，p99 超过最大桶（5s）时读数不可信 |

### 3.3 链出块

| 项 | 内容 |
|---|---|
| SLI | 块高在观察窗口内是否增长 |
| PromQL | `changes(nexuschain_block_height[15m])` |
| 采集位置 | nexus-core `:19585/actuator/prometheus`（`CoreMetricsConfig` 注册，lazy 读 `stateDB.getBestBlock().nHeight`） |
| 阈值 | `待定（业务决策）`；**dev/sandbox 单节点不产块是预期**，只在 prod 多节点 + `ENABLE_MINING=true` 下有意义 |
| 出处 | `docs/runbooks/alerts.md:19,46-56` |

### 3.4 最终性滞后

| 项 | 内容 |
|---|---|
| SLI | 最终性落后链头几个 epoch |
| PromQL | `nexus_finality_lag`（gauge）/ `nexus_finality_progress_percent` |
| 采集位置 | nexus-core `:19585/actuator/prometheus`（`org.nexus.metrics.FinalityMetrics`） |
| 阈值 | `待定（业务决策）`——**但健康基线不是 0**：健康 3 验证人拓扑下 `finalityLag` 基线**恒为 2**（轮转 + 视图切换使投票稳定落后 2 个 epoch）。按 `lag >= 1` 设阈值会永久误报。详见 `alerts.md:213-219` 的 PLAN-047 重标定 |
| 局限 | 非 pos 模式无此序列（指标缺失，不是 0）；死锁时 `latest_voted_epoch` 是粘滞水位，`lag` 会无界增长 |

### 3.5 最终性投票质量

| 项 | 内容 |
|---|---|
| SLI | 异常投票拒绝速率 |
| PromQL | `rate(nexus_finality_vote_rejected_total[10m])` / `rate(nexus_finality_finalize_rejected_total[15m])` |
| 采集位置 | nexus-core `:19585/actuator/prometheus` |
| 阈值 | `待定（业务决策）`；**健康值是 0**，非 0 即异常（伪造/畸形/超限票） |

### 3.6 区块持久化健康

| 项 | 内容 |
|---|---|
| SLI | 写块失败率 / outbox 积压 |
| PromQL | `rate(nexus_block_writer_write_failures_total[10m])`、`nexus_block_outbox_database_pending`、`nexus_block_outbox_readable` |
| 采集位置 | nexus-core `:19585/actuator/prometheus`（`org.nexus.metrics.BlockPersistenceMetrics`） |
| 阈值 | `待定（业务决策）`；`readable == 0` 时 `database_pending` 是 **NaN 不是 0**，别当"没有积压"读 |

### 3.7 安全：重放与口令

| 项 | 内容 |
|---|---|
| SLI | 重放拦截次数、密码验证失败/锁定次数 |
| PromQL | `rate(nexus_replay_interceptions_total[5m])`、`rate(nexus_password_locks_total[10m])` |
| 采集位置 | nexus-gateway `:8080/actuator/prometheus` |
| 阈值 | `待定（业务决策）`；**这不是"越低越好"的可用性指标**——重放拦截持续 > 0 意味着有人在打你的签名协议，属安全事件而非性能退化 |

### 3.8 支付漏斗（#577 接线后可测）

| 项 | 内容 |
|---|---|
| SLI | 支付成功率、下单吞吐、Webhook 投递成功率、支付端到端延迟 |
| PromQL | 支付成功率：`sum(rate(nexus_payments_confirmed_total[5m])) / clamp_min(sum(rate(nexus_payments_confirmed_total[5m])) + sum(rate(nexus_payments_failed_total[5m])), 1)`；下单吞吐：`sum(rate(nexus_orders_total[5m]))` ⚠ 不是 `nexus_orders_created_total`；延迟（与 panel 13–16 一致）：`sum(rate(nexus_payment_latency_seconds_sum[5m])) / clamp_min(sum(rate(nexus_payment_latency_seconds_count[5m])), 1)`，峰值 `max(nexus_payment_latency_seconds_max)`。⚠ `PaymentMetrics` 的 Timer 未开 `publishPercentileHistogram()`，**没有 `_bucket` 序列，不能用 `histogram_quantile`** |
| 采集位置 | nexus-gateway `:8080/actuator/prometheus` |
| 已用于 | `deploy/grafana/dashboards/nexus-gateway.json` panel 13–16 |
| 阈值 | `待定（业务决策）` |
| 局限 | `paymentFailed()` 只在 FAILED 终态计数，`refundIssued()` 只在 `RefundStatus.COMPLETED` 计数；`paymentLatencySince()` 在 `createdAt` 为 null 或时间在未来时**跳过样本**，绝不记 0 ⇒ 低流量期延迟样本可能不足；且 Timer 未发布直方图桶，无法给出服务端分位数 |

---

## 四、立不起来的 SLO（指标不存在 / 未注册）

要立这些 SLO，**先补埋点**。**支付漏斗 6 项与加密 2 项已由 #577 / #584 修复并移入 §3.8，不再属本节**；下表只保留仍无对应指标注册的部分。

| 想要的 SLO | 缺什么指标 | 建议落点（现有类） |
|---|---|---|
| 结算确认耗时 | 无 `nexus_chain_block_confirmation_seconds`（全仓未注册） | `ChainSettlementConfirmationController` 路径 |
| 对账差错率 | 无指标 | `DiscrepancyResolutionController` 路径 |
| 桥接金额 / 延迟 | `nexus_bridge_*` **全仓未注册** | `nexus-bridge` 侧新增 MeterRegistry |
| 风控触发率 | `nexus_risk_*` **全仓未注册** | 风控引擎出口 |
| MPC 签名耗时 / 成功率 | mpc-engine（Rust）只暴露容器指标 | mpc-engine 侧新增 Prometheus exporter |

> **注意 `nexus_bridge_*` / `nexus_risk_*` 的位置**：`deploy/monitoring/grafana-dashboards/` 下的 5 个面板引用这些名字，但该目录已整体标注**废弃**（`deploy/monitoring/grafana-dashboards/README.md` 开头即写「这批 dashboard 是在"文档约定指标名"假想下编写的，而这些指标名从未在代码中注册」）。**不要**拿那批面板当"指标已经有了"的证据，也不要再挂进 Grafana。

---

## 五、采集位置对照表

| 服务 | 端口 | 抓取路径 | 鉴权 |
|---|---|---|---|
| nexus-gateway | 8080 | `/actuator/prometheus` | Bearer（`NEXUS_METRICS_TOKEN`） |
| nexus-signing-service | 8082 | `/actuator/prometheus` | Bearer |
| nexus-wallet-service | 8083 | `/actuator/prometheus` | Bearer |
| nexus-bridge | 8084 | `/actuator/prometheus` | Bearer |
| nexus-api-gateway | 8085 | `/actuator/prometheus` | Bearer |
| nexus-core | 19585 | `/actuator/prometheus` | Bearer |
| mpc-engine | — | 无应用指标（只有容器/K8s 指标） | — |

- `/actuator/health` **不需要** token（探针用），`/actuator/prometheus` **必须**带 Bearer：未配 token → **503**，token 错 → **401**。两种都是 fail-closed 的正常行为，**不是服务故障**。详见 [`docs/runbooks/metrics-auth.md`](../runbooks/metrics-auth.md)。
- 面板里的 `job=~".*nexus-gateway.*"` 是**故意用正则**的：兼容 ServiceMonitor 形态与静态抓取形态两种 job 命名。
- 指标描述与桶配置：`deploy/monitoring/micrometer-config.yaml`。
- 告警规则单一真源：`deploy/k8s/50-prometheus-rules.yaml`（24 条，见 `alerts.md`）。

---

## 六、SLO 台账模板（填法）

阈值由业务 owner 填；**填之前先确认「分子分母都真实存在」**（第三节可测、第四节不可测）。支付漏斗在 #577 接线后已归入第三节，不再属第四节。

| SLO | SLI（PromQL） | 目标 | 测量窗口 | 错误预算 | owner | 数据可用？ |
|---|---|---|---|---|---|---|
| 网关可用性 | §3.1 | 待定 | 30d | 待定 | | ✅ |
| 网关延迟 p95 | §3.2 | 待定 | 30d | 待定 | | ✅ |
| 出块连续性 | §3.3 | 待定 | 30d | 待定 | | ✅ |
| 最终性滞后 | §3.4 | 待定 | 30d | 待定 | | ✅ |
| 支付成功率 | §3.8 | 待定 | 30d | 待定 | | ✅ |
| 结算时效 | 需先补 §4 埋点 | — | — | — | | ❌ |

**「错误预算」为什么必须和阈值一起定**：只写"99.9%"而不写"这个月已经烧掉多少、烧完了怎么办"，SLO 就只是一句口号。烧完之后的动作（停止发版？降级非核心功能？）同样属业务决策。

---

## 七、已知局限

1. **本文的 PromQL 一条都没在真实 Prometheus 上跑过**——本机无集群。语法按 `deploy/grafana/dashboards/` 里已入库的表达式对齐，但**没有执行证据**。
2. **`nexus_payment_latency_seconds_max` 已有抓取断言覆盖**：`PaymentMetricsTest` 用 `registry.scrape()` 逐条断言 `nexus_payment_latency_seconds_count` / `_sum` / `_max` 存在（面板的「最慢一次」格依赖 `_max`）。但 Timer 未开 `publishPercentileHistogram()`，**不导出 `_bucket`**，所以不要写 `histogram_quantile` 类查询。
3. **没有 SLO 计算管线**：仓库里没有 recording rules / SLO 计算 Job，第三节的表达式需要人肉在 Grafana 里看。要做成"自动算达标率"，需新增 recording rules 与一个周期任务——目前不存在。
4. **`deploy/grafana/dashboards/` 与 `deploy/monitoring/grafana-dashboards/` 两套面板并存**，前者有效、后者废弃。挂错目录会得到一片空白，且不会报错。
