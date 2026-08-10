/**
 * NexusChain k6 压测 — 支付创建
 * ============================================================================
 * 目标：P99 < 500ms @ 1000 RPS
 * 端点：POST /api/v1/payments
 * 认证：API Key + HMAC-SHA256 签名
 *
 * 运行：
 *   k6 run -e API_KEY=xxx -e SIGNING_SECRET=yyy \
 *          -e BASE_URL_GATEWAY=http://localhost:8080 \
 *          perf/k6/payment-create.js
 *
 * 阶段（ramp-up → 满载 → ramp-down）：
 *   30s → 200 VU | 1m → 500 VU | 2m → 1000 VU | 30s → 0
 *   注：k6 用 VU + iteration 控制吞吐；如需精确 RPS，加 --rps 1000
 *
 * 阈值：
 *   - http_req_duration p(99) < 500ms
 *   - http_req_failed rate < 1%
 *   - checks rate > 99%
 */

import http from "k6/http";
import { check, sleep, group } from "k6";
import { Rate, Trend } from "k6/metrics";

import { buildAuthHeaders, assertCredentials } from "./utils/auth.js";
import { paymentCreateBody, thinkTime, extractPaymentId } from "./utils/data.js";

// ---------------------------------------------------------------------------
// 配置（环境变量）
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL_GATEWAY || "http://localhost:8080";
const ENDPOINT = "/api/v1/payments";
const TIMEOUT_MS = parseInt(__ENV.TIMEOUT_MS || "10000", 10);

// 自定义指标：业务成功率（HTTP 2xx + 响应含 id）
const bizSuccessRate = new Rate("biz_success_rate");
const createLatency = new Trend("payment_create_latency", true);

// ---------------------------------------------------------------------------
// k6 options
// ---------------------------------------------------------------------------
export const options = {
  // 标签：用于结果聚合区分场景
  tags: { scenario: "payment-create", service: "nexus-gateway" },

  // 阶段化负载（如需精确 RPS，命令行加 --rps 1000）
  stages: [
    { duration: "30s", target: 200 },
    { duration: "1m", target: 500 },
    { duration: "2m", target: 1000 },
    { duration: "30s", target: 0 },
  ],

  // 阈值：P99 < 500ms，错误率 < 1%，检查通过率 > 99%
  thresholds: {
    http_req_duration: ["p(99)<500"],
    http_req_failed: ["rate<0.01"],
    checks: ["rate>0.99"],
    biz_success_rate: ["rate>0.99"],
    payment_create_latency: ["p(99)<500"],
  },

  // 复用连接，降低 TLS/TCP 握手开销（与生产连接池行为一致）
  noConnectionReuse: false,
  insecureSkipTLSVerify: __ENV.TLS_SKIP_VERIFY === "true",

  // 60s 内每个 VU 至少 1 次迭代，否则视为 VU 跟不上目标 RPS
  vus: 1, // 默认值，被 stages 覆盖
};

// ---------------------------------------------------------------------------
// setup：一次性校验凭据 + 预热
// ---------------------------------------------------------------------------
export function setup() {
  assertCredentials();
  // 可选：setup 中做一次健康探测，避免压测一个挂掉的服务
  const healthResp = http.get(`${BASE_URL}/actuator/health`, {
    timeout: TIMEOUT_MS,
    tags: { phase: "setup" },
  });
  if (healthResp.status !== 200) {
    console.warn(
      `setup: gateway health check returned ${healthResp.status}; 压测仍将继续。`
    );
  }
  return { baseUrl: BASE_URL };
}

// ---------------------------------------------------------------------------
// 主迭代：创建支付
// ---------------------------------------------------------------------------
export default function (data) {
  const baseUrl = data.baseUrl;
  const body = paymentCreateBody();
  const bodyStr = JSON.stringify(body);
  const headers = buildAuthHeaders("POST", ENDPOINT, bodyStr);
  const url = `${baseUrl}${ENDPOINT}`;

  group("create_payment", function () {
    const resp = http.post(url, bodyStr, {
      headers: headers,
      timeout: TIMEOUT_MS,
      tags: { endpoint: "payments_create" },
    });

    createLatency.add(resp.timings.duration);

    const ok = check(
      resp,
      {
        "status is 201": (r) => r.status === 201,
        "has payment id": (r) => extractPaymentId(r.body) !== null,
        "id starts with pay_": (r) => {
          const id = extractPaymentId(r.body);
          return id !== null && id.startsWith("pay_");
        },
        "response time < 500ms": (r) => r.timings.duration < 500,
      },
      { endpoint: "payments_create" }
    );

    bizSuccessRate.add(ok && resp.status === 201);

    if (!ok) {
      // 限速日志：仅 1% 采样，避免日志淹没
      if (Math.random() < 0.01) {
        console.warn(
          `create_payment FAIL: status=${resp.status} body=${resp.body}`
        );
      }
    }
  });

  // 思考时间 100~300ms（模拟商户系统读屏/落库）
  sleep(thinkTime(100, 300) / 1000);
}

// ---------------------------------------------------------------------------
// teardown：汇总日志
// ---------------------------------------------------------------------------
export function teardown(data) {
  console.log(`payment-create teardown: baseUrl=${data.baseUrl}`);
}