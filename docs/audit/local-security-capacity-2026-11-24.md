# 本机真实 HTTP、安全与容量画像（2026-11-24）

## 范围与不可外推边界

本批只覆盖 Windows 本机 Docker 隔离环境：`nexus-gateway`、fake chain RPC、mock connector、隔离 H2、临时随机凭据。它不是生产环境、真实 L1、真实资金、物理多机、生产 HA、真实互联网 DDoS 或生产 SLA 证明。

压测没有修改 300 次/分钟/API key 的现有限流规则。过载档出现 429 是测量结果；任何 5xx 或其他未预期状态都会使演练失败。

## 1. 真实 HTTP 攻击电池

执行 `scripts/drill/api_attack_drill.py`，完整复跑通过：

- 有效 NXC2 签名基线支付返回 201。
- 16 个并发、相同 `request_id` 的请求收敛到同一个 payment ID。
- 完全相同的签名 + nonce 重放返回 401 / 业务码 40106。
- SQLi、XSS、CRLF 文本不逃逸 API 契约，不返回 5xx 或实现栈信息。
- 畸形 JSON 返回 400。
- 200 KB `description` 在首次实测中触发数据库 `VARCHAR(512)` 异常与 HTTP 500；修复为进入 service/数据库前拒绝超过 512 字符，回归先红后绿，真实 HTTP 复跑返回 400。
- 同族审查扩展至全部七个持久化文本字段：`currency`、`description`、`notify_url`、`metadata`、`request_id`、`payer_address`、`payee_address` 均按实体列长校验并返回准确 400 消息；512 字符描述仍可正常创建。
- 最终 320 个有界并发请求：268 个 201、52 个 429、0 个 5xx；61 秒后新支付恢复 201。
- 修复 `compose run` 清理缺口后再次全量复跑，容器与项目网络均无残留。

原始日志：`F:/Agent/opencode/workspace/evidence/api-attack-all-boundaries.log`。

## 2. 真实浏览器 XSS

`nexus-explorer/frontend/e2e/xss.spec.ts` 使用真实 BFF、动态空闲端口和本机 Chrome，避免误连其他进程。后端返回的 `<img ... onerror=...>` 载荷在两个资金表区域均只作为文本出现：

- 文本节点数量为 2；`img[src="x"]` 元素数量为 0。
- 页面加载与 network idle 后，`window.__xssExecuted` 仍为 `false`。
- 用例 1/1 通过；前端完整 `npm run check` 通过，单测 223/223。

原始日志：`F:/Agent/opencode/workspace/evidence/browser-xss-isolated.log`、`frontend-check-after-xss.log`。

## 3. 多 key 单级容量画像

每档使用独立 k6 `constant-arrival-rate` 进程、40 个临时 API key、逐请求随机轮换；档位间等待 61 秒。每档原始 summary 独立保存，P50/P95/P99 不混入其他档位或爬升/降载请求。

| 目标 RPS | 时长 | 请求数 | 2xx/3xx | 429 | 5xx | P50 ms | P95 ms | P99 ms | 最大 ms |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 30s | 301 | 301 | 0 | 0 | 14.48 | 21.15 | 25.94 | 631.66 |
| 50 | 30s | 1501 | 1501 | 0 | 0 | 8.88 | 14.75 | 17.60 | 694.46 |
| 100 | 30s | 3000 | 3000 | 0 | 0 | 6.12 | 12.21 | 17.42 | 670.64 |
| 200 | 30s | 6001 | 6001 | 0 | 0 | 5.15 | 7.41 | 13.98 | 657.79 |
| 250 | 30s | 7501 | 7501 | 0 | 0 | 6.89 | 11.57 | 25.18 | 1677.19 |
| 300 | 60s | 17899 | 12346 | 5553 | 0 | 7.88 | 49.42 | 1018.69 | 2725.53 |

五档 30 秒样本的业务成功率均为 100%，没有观察到服务饱和；这不证明更高负载或持续负载也能保持同样结果。250 RPS 档已有约 1.68 秒最大尾峰，不能只引用低 P99。

300 RPS × 60 秒档出现 31.02% 429，业务成功率 68.98%，P99 约 1.02 秒；k6 因宽松错误率阈值返回 99，这是过载画像数据，不是被忽略的产品失败。该档 5xx 为 0，等待 61 秒后签名支付恢复 201。40 key 的限流总量与随机分配是拐点的重要组成部分，不能把 300 RPS 解释为网关或生产的绝对容量上限。

原始数据：`F:/Agent/opencode/workspace/evidence/capacity-68016/` 与 `capacity-300rps-60s/`。

### 测量工具校准记录

首次 10 RPS 实际完成 301/301 请求，但解析器误用了另一种 summary 包装格式而错误报 0；随后修正扁平字段解析并显式启用 `p(99)`。第二轮 `Rate` 的 `value` 字段又需与 Counter 的 `rate` 区分，最终表已从每份原始 summary 重算并断言五档业务成功率为 1。所有原始文件保留，未把校准轮混进正式容量结论。

## 4. 后续真实链验收

`docs/drill/testnet-small-value-acceptance.md` 仅定义 testnet / mainnet 小额验收的硬门槛、样本矩阵、取证字段、对账不变量和停止条件。未发起真实交易、未支出资金。小额真实交易只补真实资金链证据，不自动关闭本文件开头列出的其他生产边界。
